package com.example.smartcall.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OpenAIService
 * PURPOSE:
 * This class talks to OpenAI (Chat Completions API) and asks:
 * "Given a failed API call, what should we change to fix it?"
 * INPUTS to AI:
 * - target (which integration we are calling)
 * - payloadJson (what we sent)
 * - error (what upstream API complained about)
 * OUTPUT from AI:
 * A JSON object like:
 * {
 * "endpoint": "/v2/createOrder",
 * "fieldMappings": {
 * "custId": "customer_id",
 * "qty": "quantity"
 * }
 * }
 * If no healing is needed, AI should return:
 * {}
 * OUTCOME:
 * This service returns a Map<String,Object> representing those rules.
 */
@Service
public class OpenAIService {

    /**
     * RestClient is Spring's modern HTTP client.
     * We use it to make the HTTPS call to OpenAI.
     */
    private final RestClient restClient;

    /**
     * JsonMapper converts JSON <-> Java objects.
     * Here it is used to parse the AI response JSON into a Map.
     */
    private final JsonMapper jsonMapper;

    /**
     * Model name (example: gpt-4o-mini).
     * Comes from application.yml or environment config.
     */
    private final String model;

    /**
     * Regex pattern to grab the first JSON object from a response.
     * Useful because sometimes the model wraps JSON in text or code blocks.
     */
    private static final Pattern FIRST_JSON_OBJECT =
            Pattern.compile("\\{.*}\\s*", Pattern.DOTALL);

    /**
     * Logger to print useful information in console logs.
     */
    private static final Logger log = LoggerFactory.getLogger(OpenAIService.class);

    /**
     * Constructor: Spring calls this automatically and injects dependencies.
     *
     * @param builder    RestClient.Builder provided by Spring
     * @param apiKey     OpenAI API key from config: openai.api-key
     * @param model      Model from config: openai.model (defaults to gpt-4o-mini)
     * @param jsonMapper Json mapper bean from configuration
     *                   OUTCOME:
     *                   Builds a RestClient that knows:
     *                   - OpenAI base URL
     *                   - Authorization header (Bearer API key)
     *                   - Content-Type JSON
     */
    public OpenAIService(RestClient.Builder builder,
                         @Value("${openai.api-key}") String apiKey,
                         @Value("${openai.model:gpt-4o-mini}") String model,
                         JsonMapper jsonMapper) {

        this.model = model;
        this.jsonMapper = jsonMapper;

        // Build a reusable HTTP client configured for OpenAI
        this.restClient = builder
                .baseUrl("https://api.openai.com/v1") // all calls go to this base
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey) // auth
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE) // request body is JSON
                .build();
    }

    /**
     * proposeRules()
     * PURPOSE:
     * Called when an upstream API fails. This method asks OpenAI:
     * "What should we change to fix the endpoint and/or payload fields?"
     * STEPS:
     * 1) Build "messages" list (system + user prompt)
     * 2) Create ChatCompletionRequest
     * 3) POST it to OpenAI /chat/completions
     * 4) Read assistant response text
     * 5) Extract JSON from response text
     * 6) Convert JSON into Map<String, Object>
     * OUTCOME:
     * Returns a Map with:
     * - endpoint (optional)
     * - fieldMappings (optional)
     * Or emptyMap() if nothing is returned / error happened.
     */
    public Map<String, Object> proposeRules(String target, String payloadJson, String error) {

        // This list will hold chat messages to send to OpenAI.
        // The order matters: system first, then user.
        List<Message> messages = new ArrayList<>();

        log.info("AI making call for suggestions");

        // SYSTEM MESSAGE:
        // Defines strict behavior: return JSON only with specific fields.
        messages.add(new Message(
                "system",
                "You are an assistant that generates healing rules for failing API calls. " +
                        "Given the target integration name, the original payload and the error message, " +
                        "you must analyse the error and decide whether an endpoint rewrite or field renames are required. " +
                        "Respond with a JSON object containing an optional 'endpoint' field (string) and an optional " +
                        "'fieldMappings' object mapping original field names to new field names. If no changes are needed, return {}."
        ));

        // USER MESSAGE:
        // Gives the failure context (target + payload + error)
        StringBuilder userContent = new StringBuilder();
        userContent.append("Target: ").append(target).append("\n");
        userContent.append("Payload: ").append(payloadJson).append("\n");
        userContent.append("Error: ").append(error).append("\n");
        userContent.append("\nAnalyse the error and propose endpoint and field mapping updates.");

        messages.add(new Message("user", userContent.toString()));

        // Create the final request object that matches OpenAI chat API structure
        ChatCompletionRequest request = new ChatCompletionRequest(model, messages);

        try {
            /**
             * THIS IS THE ACTUAL AI CALL.
             *
             * We are calling:
             * POST https://api.openai.com/v1/chat/completions
             *
             * with body = request
             *
             * and mapping the JSON response into ChatCompletionResponse record.
             */
            ChatCompletionResponse response = restClient.post()
                    .uri("/chat/completions")
                    .body(request)
                    .retrieve()
                    .body(ChatCompletionResponse.class);

            // Validate response structure before using it
            if (response != null && response.choices() != null && !response.choices().isEmpty()) {

                // Extract assistant message content (the model's text output)
                String content = response.choices().get(0).message().content();
                log.info("AI Response: " + content);

                if (content != null && !content.isBlank()) {

                    // Sometimes AI wraps JSON in markdown ```json ... ```
                    // This method tries to isolate just the JSON part.
                    String json = extractJson(content);

                    if (json != null && !json.isBlank()) {

                        // Parse JSON string into a Map.
                        // Example result:
                        // {endpoint=/v2/createOrder, fieldMappings={custName=name, custAge=age}}
                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = jsonMapper.readValue(json, Map.class);

                        return map; // return healing rules to caller
                    }
                }
            }

        } catch (RestClientException | JacksonException e) {
            // RestClientException => HTTP call failed (network/auth/timeout/429/etc.)
            // JacksonException => response JSON couldn't be parsed
            // NOTE: In real systems you'd log this and maybe retry
            log.warn("OpenAI call failed or response parsing failed: {}", e.getMessage());
        }

        // If anything goes wrong, return empty rule set (meaning "no healing")
        return Collections.emptyMap();
    }

    /**
     * extractJson()
     * PURPOSE:
     * The AI might respond with:
     * - raw JSON
     * - JSON inside markdown code blocks
     * - extra explanation text + JSON
     * This method tries to find and return ONLY the JSON object.
     * OUTCOME:
     * Returns a JSON string like "{...}" or null if none found.
     */
    private String extractJson(String content) {
        if (content == null) return null;

        String c = content.trim();

        // Remove markdown fences if present:
        // ```json
        // { ... }
        // ```
        c = c.replaceAll("^```(?:json)?\\s*", "");
        c = c.replaceAll("```\\s*$", "");
        c = c.trim();

        // If already looks like pure JSON object, return it
        if (c.startsWith("{") && c.endsWith("}")) return c;

        // Try to locate the first '{' and last '}' and extract the substring
        int first = c.indexOf('{');
        int last = c.lastIndexOf('}');
        if (first >= 0 && last > first) {
            return c.substring(first, last + 1).trim();
        }

        // Last fallback: regex match for JSON-like blob
        Matcher m = FIRST_JSON_OBJECT.matcher(c);
        if (m.find()) return m.group().trim();

        return null;
    }

    /**
     * ChatCompletionRequest
     * PURPOSE:
     * Represents the request body sent to OpenAI.
     * Must include:
     * - model
     * - messages list
     */
    public record ChatCompletionRequest(String model, List<Message> messages) {
    }

    /**
     * Message
     * PURPOSE:
     * One chat message object sent to OpenAI.
     * role can be:
     * - "system" (instructions)
     * - "user" (input context)
     * - "assistant" (model response)
     */
    public record Message(String role, String content) {
    }

    /**
     * ChatCompletionResponse
     * PURPOSE:
     * Represents only the part of the OpenAI response we care about.
     * The real response has more fields, but we only need "choices".
     */
    public record ChatCompletionResponse(List<Choice> choices) {
    }

    /**
     * Choice
     * PURPOSE:
     * OpenAI may return multiple choices (multiple answers).
     * We usually take the first one.
     */
    public record Choice(Message message) {
    }
}