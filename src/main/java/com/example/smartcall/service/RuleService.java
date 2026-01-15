package com.example.smartcall.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.stereotype.Service;
import com.example.smartcall.model.HealingPlan;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * RuleService
 *
 * PURPOSE:
 * This class is the "Redis persistence layer" of the self-healing system.
 * It stores and retrieves learned rules (endpoint rewrite + field mappings)
 * and pushes failure events into a Redis Stream so an AI worker can learn.
 *
 * OUTCOME:
 * - SmartCallService can quickly fetch rules from Redis and auto-heal.
 * - AiWorker can consume failure events and write new rules back into Redis.
 *
 * REDIS STORAGE MODEL (per target):
 *
 * 1) Endpoint mapping (String key-value):
 *    key:   selfheal:<target>:endpoint
 *    value: /v2/createOrder
 *
 * 2) Field mappings (Redis Hash):
 *    key: selfheal:<target>:field-mappings
 *    hash fields: custId -> customer_id
 *                 qty    -> quantity
 *
 * 3) Failure stream (Redis Stream):
 *    stream key: selfheal:failures   (configurable via property)
 */
@Service
public class RuleService {

    /**
     * All Redis keys start with this prefix, so it’s easy to find them.
     */
    private static final String PREFIX = "selfheal:";

    /**
     * normalizeTarget()
     *
     * PURPOSE:
     * Different code might pass target like:
     *   "v1/orders"
     *   "/v1/orders"
     *
     * We normalize it so both map to the same Redis keys.
     *
     * OUTCOME:
     * Ensures rules learned for "v1/orders" also apply to "/v1/orders".
     */
    private static String normalizeTarget(String target) {
        if (target == null) return "";
        String t = target.trim();

        // Remove leading "/" for consistency
        t = t.replaceFirst("^/+", "");

        return t;
    }

    /**
     * RedisTemplate is Spring’s main object to interact with Redis.
     * With this template we can do:
     * - opsForValue()  -> String key/value
     * - opsForHash()   -> Hash (like a Map in Redis)
     * - opsForStream() -> Streams (like a message queue)
     */
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * Stream key where failures are pushed.
     * Configurable via application.yml:
     * selfheal.failure-stream=selfheal:failures
     */
    private final String failureStream;

    /**
     * ObjectMapper is used to serialize payload Map into JSON string
     * so that Redis stream payload is readable in Redis Insight / CLI.
     */
    private final ObjectMapper objectMapper;

    /**
     * Constructor injection (Spring auto-wires these dependencies).
     */
    public RuleService(RedisTemplate<String, Object> redisTemplate,
                       ObjectMapper objectMapper,
                       @Value("${selfheal.failure-stream:selfheal:failures}") String failureStream) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.failureStream = failureStream;
    }

    /* =========================================================
       Key helpers (per target)
       ========================================================= */

    /**
     * Builds Redis key for endpoint mapping.
     * Example: target="v1/orders" -> "selfheal:v1/orders:endpoint"
     */
    private static String endpointKey(String target) {
        return PREFIX + normalizeTarget(target) + ":endpoint";
    }

    /**
     * Builds Redis key for field mappings hash.
     * Example: target="v1/orders" -> "selfheal:v1/orders:field-mappings"
     */
    private static String fieldMapKey(String target) {
        return PREFIX + normalizeTarget(target) + ":field-mappings";
    }

    /* =========================================================
       Endpoint mapping (1 per target)
       ========================================================= */

    /**
     * getEndpointRule()
     *
     * PURPOSE:
     * Read the endpoint rewrite rule from Redis.
     *
     * OUTCOME:
     * - Optional.empty() if no rule exists
     * - Optional.of("/v2/createOrder") if rule exists
     */
    public Optional<String> getEndpointRule(String target) {
        Object v = redisTemplate.opsForValue().get(endpointKey(target));
        if (v == null) return Optional.empty();

        String s = String.valueOf(v).trim();
        return s.isEmpty() ? Optional.empty() : Optional.of(s);
    }

    /**
     * saveEndpointRule()
     *
     * PURPOSE:
     * Save endpoint rewrite rule (String key/value) to Redis.
     *
     * OUTCOME:
     * Next time we call this target, we can automatically use this endpoint.
     */
    public void saveEndpointRule(String target, String endpoint) {
        if (target == null || target.isBlank()) return;
        if (endpoint == null || endpoint.isBlank()) return;

        redisTemplate.opsForValue().set(endpointKey(target), endpoint.trim());
    }

    /* =========================================================
       Field mappings (many per target)
       ========================================================= */

    /**
     * getFieldMappings()
     *
     * PURPOSE:
     * Fetch ALL field mappings for this target from Redis hash.
     *
     * OUTCOME:
     * Returns Map like:
     * { "custName" -> "name", "custAge" -> "age" }
     */
    public Map<String, String> getFieldMappings(String target) {
        if (target == null || target.isBlank()) return Collections.emptyMap();

        // Read entire hash as Map<Object,Object>
        Map<Object, Object> raw = redisTemplate.opsForHash().entries(fieldMapKey(target));
        if (raw == null || raw.isEmpty()) return Collections.emptyMap();

        // Convert keys/values safely into Strings
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<Object, Object> e : raw.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;

            String k = String.valueOf(e.getKey()).trim();
            String v = String.valueOf(e.getValue()).trim();

            if (!k.isEmpty() && !v.isEmpty()) out.put(k, v);
        }
        return out;
    }

    /**
     * saveFieldMapping()
     *
     * PURPOSE:
     * Save/Update ONE mapping in the Redis hash.
     *
     * OUTCOME:
     * Adds or updates field like:
     * HSET selfheal:v1/orders:field-mappings custAge age
     *
     * IMPORTANT:
     * This does NOT delete other mappings (it upserts one field).
     */
    public void saveFieldMapping(String target, String fromField, String toField) {
        if (target == null || target.isBlank()) return;
        if (fromField == null || fromField.isBlank()) return;
        if (toField == null || toField.isBlank()) return;

        redisTemplate.opsForHash().put(
                fieldMapKey(target),
                fromField.trim(),
                toField.trim()
        );
    }

    /**
     * saveFieldMappings()
     *
     * PURPOSE:
     * Save/Update MANY mappings at once (upsert).
     *
     * OUTCOME:
     * Adds/updates multiple fields in Redis hash.
     * Existing fields remain unless overwritten with same key.
     */
    public void saveFieldMappings(String target, Map<String, String> mappings) {
        if (target == null || target.isBlank()) return;
        if (mappings == null || mappings.isEmpty()) return;

        // Clean input (remove nulls, blanks)
        Map<String, String> clean = new HashMap<>();
        for (Map.Entry<String, String> e : mappings.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            String k = e.getKey().trim();
            String v = e.getValue().trim();
            if (!k.isEmpty() && !v.isEmpty()) clean.put(k, v);
        }

        // Write to Redis hash in one operation
        if (!clean.isEmpty()) {
            redisTemplate.opsForHash().putAll(fieldMapKey(target), clean);
        }
    }

    /* =========================================================
       Failure stream (AI worker input)
       ========================================================= */

    /**
     * pushFailureEvent()
     *
     * PURPOSE:
     * When SmartCall fails after retries/healing attempts,
     * push this event into a Redis Stream.
     *
     * AiWorker will read this stream later and call OpenAI.
     *
     * OUTCOME:
     * A new entry is added into stream selfheal:failures.
     */
    public void pushFailureEvent(String target, Map<String, Object> payload, String errorMessage) {
        Map<String, Object> body = new HashMap<>();

        // Normalize target before storing
        body.put("target", normalizeTarget(target));

        // Store error string
        body.put("error", errorMessage);

        // Add timestamp (useful for debugging and cleanup)
        body.put("ts", System.currentTimeMillis());

        // Store payload as JSON text, so you can see it easily in Redis CLI/RedisInsight
        try {
            body.put("payloadJson", objectMapper.writeValueAsString(payload == null ? Map.of() : payload));
        } catch (Exception e) {
            // Fallback: store payload using toString()
            body.put("payloadJson", String.valueOf(payload));
        }

        // Add this map as one stream record.
        // This creates something like:
        // XADD selfheal:failures * target "v1/orders" payloadJson "{...}" error "..." ts "..."
        redisTemplate.opsForStream().add(failureStream, body);

        // Optional: expire stream (commented out)
        // redisTemplate.expire(failureStream, Duration.ofDays(7));
    }

    /* =========================================================
       Compound plan persistence (endpoint + fields in one go)
       ========================================================= */

    /**
     * saveHealingPlanAtomic()
     *
     * PURPOSE:
     * Save BOTH endpoint rule + all field mappings in one atomic transaction.
     *
     * Why atomic matters:
     * You don’t want endpoint stored without mappings or vice-versa.
     *
     * Redis transaction:
     * MULTI
     *   SET endpointKey
     *   HSET fieldMapKey ...
     * EXEC
     *
     * OUTCOME:
     * A "complete learning" is persisted together.
     */
    public void saveHealingPlanAtomic(String target, HealingPlan plan) {
        if (target == null || target.isBlank()) return;
        if (plan == null || plan.isEmpty()) return;

        final String t = target;

        // Trim endpoint string if present
        final String endpoint = (plan.getEndpoint() == null ? null : plan.getEndpoint().trim());

        // Get mappings (never null because HealingPlan getter is defensive)
        final Map<String, String> mappings =
                plan.getFieldMappings() == null ? Collections.emptyMap() : plan.getFieldMappings();

        // Execute a Redis transaction using SessionCallback
        redisTemplate.execute(new SessionCallback<Object>() {
            @SuppressWarnings({"unchecked", "rawtypes"})
            @Override
            public Object execute(org.springframework.data.redis.core.RedisOperations operations) {

                // Cast operations to expected types so we can call opsForValue/opsForHash
                org.springframework.data.redis.core.RedisOperations<String, Object> ops =
                        (org.springframework.data.redis.core.RedisOperations<String, Object>) operations;

                // Begin transaction
                ops.multi();

                // Store endpoint if present
                if (endpoint != null && !endpoint.isBlank()) {
                    ops.opsForValue().set(endpointKey(t), endpoint);
                }

                // Store mappings if present
                if (mappings != null && !mappings.isEmpty()) {
                    Map<String, String> clean = new HashMap<>();

                    // Remove null/blank mapping entries
                    for (Map.Entry<String, String> e : mappings.entrySet()) {
                        if (e.getKey() == null || e.getValue() == null) continue;
                        String k = e.getKey().trim();
                        String v = e.getValue().trim();
                        if (!k.isEmpty() && !v.isEmpty()) clean.put(k, v);
                    }

                    if (!clean.isEmpty()) {
                        // PutAll into hash. Casting avoids compiler generic issues in some Spring versions.
                        ops.opsForHash().putAll(fieldMapKey(t), (Map) clean);
                    }
                }

                // Execute transaction (commit)
                return ops.exec();
            }
        });
    }

    /* =========================================================
       Optional helpers (nice for debugging / reset)
       ========================================================= */

    /**
     * deleteRulesForTarget()
     *
     * PURPOSE:
     * Delete endpoint + mappings rules for a target.
     *
     * Useful for:
     * - resetting learned behavior
     * - debugging fresh learning
     */
    public void deleteRulesForTarget(String target) {
        if (target == null || target.isBlank()) return;
        redisTemplate.delete(endpointKey(target));
        redisTemplate.delete(fieldMapKey(target));
    }
}