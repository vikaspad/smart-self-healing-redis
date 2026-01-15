package com.example.smartcall.worker;

import com.example.smartcall.service.OpenAIService;
import com.example.smartcall.service.RuleInferenceService;
import com.example.smartcall.service.RuleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Background worker that consumes failure events from a Redis stream and
 * generates new healing rules.  This is a prototype—real integration with
 * a language model is left as a TODO.  The worker runs in its own thread
 * and gracefully stops when the application context closes.
 */
@Component
public class AiWorker implements InitializingBean, DisposableBean, Runnable {
    private static final Logger log = LoggerFactory.getLogger(AiWorker.class);

    private final RedisTemplate<String, Object> redisTemplate;
    private final RuleService ruleService;
    private final OpenAIService openAIService;
    private final RuleInferenceService ruleInferenceService;
    private Thread workerThread;
    private volatile boolean running = true;
    private final String failureStream;

    public AiWorker(RedisTemplate<String, Object> redisTemplate,
                    RuleService ruleService,
                    OpenAIService openAIService,
                    RuleInferenceService ruleInferenceService,
                    @org.springframework.beans.factory.annotation.Value("${selfheal.failure-stream}") String failureStream) {
        this.redisTemplate = redisTemplate;
        this.ruleService = ruleService;
        this.openAIService = openAIService;
        this.ruleInferenceService = ruleInferenceService;
        this.failureStream = failureStream;
    }

    @Override
    public void afterPropertiesSet() {
        // Start the background thread once all dependencies are ready
        workerThread = new Thread(this, "ai-worker-thread");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    @Override
    public void destroy() {
        // Signal the worker to stop and wait for it to finish
        running = false;
        if (workerThread != null) {
            try {
                workerThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void run() {
        String stream = failureStream;
        if (stream == null || stream.isEmpty()) {
            log.warn("No failure stream configured; AI worker will not run.");
            return;
        }
        // Set up a consumer group so that multiple workers can share the same
        // stream and process events exactly once.  Using a random consumer
        // name ensures that each application instance has its own consumer.
        StreamOperations<String, Object, Object> ops = redisTemplate.opsForStream();
        String consumerGroup = "ai-worker-group";
        String consumerName = "worker-" + java.util.UUID.randomUUID();
        try {
            ops.createGroup(stream, ReadOffset.latest(), consumerGroup);
        } catch (Exception e) {
            // The group may already exist; ignore the exception
        }
        log.info("AI worker started; consuming failure events from stream {} as consumer {}", stream, consumerName);
        // Create a Consumer object for this instance.  The Consumer class
        // encapsulates the group and name for reading from a stream.
        Consumer consumer = Consumer.from(consumerGroup, consumerName);
        while (running) {
            try {
                // Read a single record from the stream for this consumer group.  We
                // block for up to 5 seconds waiting for new messages.  The
                // StreamReadOptions builder configures the blocking timeout and
                // maximum number of records to return.
                List<MapRecord<String, Object, Object>> records = ops.read(
                        consumer,
                        StreamReadOptions.empty()
                                .block(Duration.ofSeconds(5))
                                .count(1),
                        StreamOffset.create(stream, ReadOffset.lastConsumed()));
                if (records != null) {
                    for (MapRecord<String, Object, Object> record : records) {
                        try {
                            processEvent(record.getValue());
                        } catch (Exception ex) {
                            log.error("Error processing failure event", ex);
                        }
                        // acknowledge the record so it won't be delivered again
                        ops.acknowledge(stream, consumerGroup, record.getId());
                    }
                }
            } catch (Exception e) {
                log.error("Error reading from failure stream", e);
                // Sleep briefly before retrying to avoid busy loop on repeated errors
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        log.info("AI worker shutting down.");
    }

    /**
     * Placeholder for processing a single failure event and creating a healing
     * rule.  You should extract the error message, call an LLM to analyse it,
     * then write rules using {@link RuleService#saveEndpointRule(String, String)}
     * and {@link RuleService#saveFieldMapping(String, String, String)}.
     *
     * @param event the map received from the failure stream
     */
    private void processEvent(Map<Object, Object> event) {
        // Extract the event fields from the stream payload.  Missing values are
        // converted to empty strings to avoid null pointer exceptions.
        String target = event.getOrDefault("target", "").toString();
        // RuleService publishes the payload under "payloadJson" (stringified JSON)
        String payloadJson = event.getOrDefault("payloadJson", "").toString();
        String error = event.getOrDefault("error", "").toString();
        log.info("Processing failure for target {} with error: {}", target, error);
        // Try OpenAI first (if configured). If it returns nothing, fall back to deterministic parsing.
        Map<String, Object> suggestions = openAIService.proposeRules(target, payloadJson, error);
        if (suggestions == null || suggestions.isEmpty()) {
            log.info("Fall back to RuleInference Service");
            suggestions = ruleInferenceService.proposeRules(target, payloadJson, error);
        }
        if (suggestions == null || suggestions.isEmpty()) {
            log.info("No healing suggestions returned for target {}", target);
            return;
        }
        // Save endpoint rewrite rule if present
        Object endpointObj = suggestions.get("endpoint");
        if (endpointObj instanceof String endpoint && !endpoint.isBlank()) {
            ruleService.saveEndpointRule(target, endpoint);
            log.info("Saved endpoint rewrite for target {}: {}", target, endpoint);
        }
        // Save field mappings if present
        Object mappingsObj = suggestions.get("fieldMappings");
        if (mappingsObj instanceof Map<?, ?> mappings) {
            for (Map.Entry<?, ?> entry : mappings.entrySet()) {
                String oldName = entry.getKey() == null ? null : entry.getKey().toString();
                String newName = entry.getValue() == null ? null : entry.getValue().toString();
                if (oldName != null && newName != null) {
                    ruleService.saveFieldMapping(target, oldName, newName);
                    log.info("Saved field mapping for target {}: {} -> {}", target, oldName, newName);
                }
            }
        }
    }
}