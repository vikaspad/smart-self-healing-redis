package com.example.smartcall.service;

import com.example.smartcall.model.SmartCallRequest;
import com.example.smartcall.model.SmartCallResponse;
import com.example.smartcall.model.HealingPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SmartCallService:
 * ---------------
 * This is the coordinator/orchestrator for your "self-healing call" workflow.
 * Responsibilities:
 * 1) Fetch healing rules from Redis (via RuleService)
 * - endpoint rewrite rules (e.g., v1/orders -> v2/createOrder)
 * - field mapping rules (e.g., custName -> name, custAge -> age)
 * 2) Apply those rules to the request before calling the upstream API
 * 3) Call the upstream API (using RestTemplate)
 * 4) If upstream fails, push a failure event to Redis Stream for async AI learning/audit
 * 5) Also attempt synchronous inference (RuleInferenceService) and persist learned rules
 * Notes:
 * - This service is stateless: the "memory" lives in Redis.
 * - Each handleSmartCall can do multiple retries to converge to a valid request.
 */
@Service
public class SmartCallService {

    // Logger used for visibility/troubleshooting in the service logs
    private static final Logger log = LoggerFactory.getLogger(SmartCallService.class);

    // RuleService: your Redis-backed storage for endpoint rules + field mappings + failure streams
    private final RuleService ruleService;

    // RuleInferenceService: synchronous inference engine that tries to derive HealingPlan from error text/body
    private final RuleInferenceService ruleInferenceService;

    // RestTemplate: HTTP client used to call the external upstream API
    private final RestTemplate restTemplate;

    // baseUrl: base location of the upstream system (e.g., http://localhost:8081 or https://api.vendor.com)
    private final String baseUrl;

    /**
     * Constructor injection:
     * - Spring injects RuleService, RuleInferenceService, RestTemplate beans.
     * - Spring injects external-api.base-url from application.yml into baseUrl.
     */
    public SmartCallService(RuleService ruleService,
                            RuleInferenceService ruleInferenceService,
                            RestTemplate restTemplate,
                            @Value("${external-api.base-url}") String baseUrl) {

        // Save dependencies for later usage inside handleSmartCall()
        this.ruleService = ruleService;
        this.ruleInferenceService = ruleInferenceService;
        this.restTemplate = restTemplate;

        // Normalize + remove trailing slash so URL join logic is consistent.
        // Example:
        //  input:  "https://localhost:8081/"
        //  output: "http://localhost:8081" (downgraded if localhost HTTPS) then trailing slash removed
        this.baseUrl = normalizeLocalHttps(baseUrl, "external-api.base-url").replaceAll("/$", "");
    }

    /**
     * Main entry point:
     * Takes SmartCallRequest { target, payload }, attempts to:
     * - rewrite target -> endpoint if rule exists
     * - map payload fields if rules exist
     * - call upstream
     * - on failure, infer & store rules, then retry (bounded loop)
     */
    public SmartCallResponse handleSmartCall(SmartCallRequest request) {

        // "target" is what caller asks to invoke (e.g., "v1/orders")
        String target = request.getTarget();

        // Normalize null payload:
        // - if payload is null, replace with empty map to avoid NullPointerExceptions
        // - if not null, use it as-is
        Map<String, Object> originalPayload =
                request.getPayload() == null ? Collections.emptyMap() : request.getPayload();

        // Bounded convergence loop:
        // We try multiple attempts because each attempt can learn new rules.
        // Example scenario:
        //   Attempt 1 -> error says "v1 deprecated, use v2/createOrder with fields {name,age}"
        //   We learn endpoint + mappings -> retry
        //   Attempt 2 -> maybe error reveals one more field mapping -> learn -> retry
        final int maxHealIterations = 3;

        // baseTarget stays constant across iterations: it’s the original target key used to look up/store rules
        // (important: we store rules under baseTarget so all future calls to v1/orders benefit)
        final String baseTarget = target;

        // currentPayload evolves per attempt as we apply newly learned mappings
        Map<String, Object> currentPayload = originalPayload;

        // Flag: did we apply any healing rules at least once in this request lifecycle?
        boolean anyHealingApplied = false;

        // Flag: did we learn anything new (plan saved) during retries?
        boolean learnedAnything = false;

        // Retry loop: each iteration attempts a call, on failure tries to infer rules and retry
        for (int attempt = 0; attempt < maxHealIterations; attempt++) {

            // 1) Resolve endpoint rule:
            // - If Redis has endpoint rule for baseTarget, use it
            // - Else, default to baseTarget itself (meaning no endpoint rewrite)
            String endpoint = ruleService.getEndpointRule(baseTarget).orElse(baseTarget);

            // Normalize localhost HTTPS to HTTP (dev convenience)
            endpoint = normalizeLocalHttps(endpoint, "endpoint-rule[" + baseTarget + "]");

            // If endpoint is relative (not starting with http), strip leading slashes.
            // This prevents baseUrl + "//v2/createOrder" cases.
            String endpointClean = endpoint;
            if (!endpointClean.startsWith("http")) {
                endpointClean = endpointClean.replaceFirst("^/+", "");
            }

            // 2) Apply field mappings stored for this target.
            // - This reads Redis mappings for baseTarget and rewrites payload keys accordingly.
            // - Returns a new map (or same map if no mappings).
            Map<String, Object> healedPayload = applyFieldMappings(baseTarget, currentPayload);

            // Determine whether healing happened this round:
            // - endpointHealed: rule changed endpoint (endpoint != baseTarget)
            // - fieldsHealed: payload keys changed compared to currentPayload
            boolean endpointHealed = !endpoint.equals(baseTarget);
            boolean fieldsHealed = !healedPayload.equals(currentPayload);
            boolean healedThisRound = endpointHealed || fieldsHealed;

            // Track if healing was applied in ANY round for final response metadata
            anyHealingApplied = anyHealingApplied || healedThisRound;

            // 3) Build the final URL:
            // - If endpointClean is already absolute (http...), use it as-is
            // - Else join it with baseUrl (baseUrl has no trailing slash)
            String url = endpointClean.startsWith("http")
                    ? endpointClean
                    : baseUrl + "/" + endpointClean;

            // Log current attempt details for troubleshooting/audit
            log.info("SmartCall attempt={} target='{}' url='{}' healedEndpoint={} healedFields={}",
                    attempt + 1, baseTarget, url, endpointHealed, fieldsHealed);

            // Prepare request headers:
            // - We are POSTing JSON
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // Wrap payload + headers in HttpEntity which RestTemplate needs
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(healedPayload, headers);

            try {
                // Execute the upstream call:
                // - POST to URL
                // - send the healed payload
                // - expect a generic Object response
                ResponseEntity<Object> result = restTemplate.postForEntity(url, entity, Object.class);

                // Success path: build SmartCallResponse with status=success
                // healed=true if we applied any healing rule in the attempts
                return SmartCallResponse.builder()
                        .status("success")
                        .healed(anyHealingApplied)
                        .appliedRuleId(anyHealingApplied ? target : null)
                        .data(result.getBody())
                        .message(null)
                        .build();

            } catch (HttpStatusCodeException ex) {
                // This block handles HTTP 4xx/5xx responses returned by upstream.
                // HttpStatusCodeException gives us both status code and response body.
                String body = ex.getResponseBodyAsString();

                // Always push failure event for async learning/audit.
                // This ensures even if synchronous inference fails, the failure is recorded.
                ruleService.pushFailureEvent(baseTarget, healedPayload, body);

                // 4) Synchronous inference:
                // Try to infer a HealingPlan from the payload we sent + the upstream error body.
                // Typical outcomes:
                //  - plan.endpoint = "/v2/createOrder"
                //  - plan.fieldMappings = { "custName": "name", "custAge": "age" }
                HealingPlan plan = ruleInferenceService.inferPlan(healedPayload, body);

                // If we cannot infer anything, stop retrying and return error response immediately.
                if (plan == null || plan.isEmpty()) {
                    return SmartCallResponse.builder()
                            .status("error")
                            .healed(anyHealingApplied)
                            .appliedRuleId(anyHealingApplied ? target : null)
                            .data(null)
                            .message("Upstream API error: " + ex.getStatusCode().value())
                            .build();
                }

                // 5) Persist endpoint + all field mappings together (atomic).
                // This is important so we don’t store endpoint without mappings or vice versa if a failure happens mid-write.
                ruleService.saveHealingPlanAtomic(baseTarget, plan);
                learnedAnything = true;

                // 6) Update in-memory state for the next iteration.
                // IMPORTANT:
                // - You already fetch endpoint from Redis at the top of the loop on the next attempt,
                //   so you don't need to mutate `endpoint` here.
                // - If you want immediate endpoint usage in the SAME loop iteration, you'd set endpointClean here,
                //   but the code structure retries next loop iteration anyway.

                // NOTE: This if-block is currently empty.
                // You can either remove it OR implement "immediate endpoint update" logic here.
                if (plan.getEndpoint() != null && !plan.getEndpoint().isBlank()) {
                    // Example (optional):
                    // endpointClean = plan.getEndpoint();
                    // currentEndpoint = plan.getEndpoint();
                    // But since next iteration reads Redis rule again, it's not strictly required.
                }

                // Apply newly learned field mappings immediately to produce the next attempt's payload.
                // This reduces the time-to-heal because we don't wait for another Redis read to see the new mapping.
                if (plan.getFieldMappings() != null && !plan.getFieldMappings().isEmpty()) {
                    currentPayload = applyMappingsToPayload(healedPayload, plan.getFieldMappings());
                } else {
                    // If no field mappings were learned, just carry forward healedPayload as-is
                    currentPayload = healedPayload;
                }

            } catch (Exception ex) {
                // This block handles unexpected runtime issues:
                // - network failures not mapped to HttpStatusCodeException
                // - JSON conversion issues
                // - any other thrown exception
                ruleService.pushFailureEvent(baseTarget, healedPayload, ex.getMessage());

                // Return an error response to the caller
                return SmartCallResponse.builder()
                        .status("error")
                        .healed(anyHealingApplied)
                        .appliedRuleId(anyHealingApplied ? target : null)
                        .data(null)
                        .message("Unexpected error: " + ex.getMessage())
                        .build();
            }
        }

        // If we exit the loop, we exhausted all attempts without success.
        // We return a final error response.
        // If we learned something along the way, we mention healing attempts were made.
        return SmartCallResponse.builder()
                .status("error")
                .healed(anyHealingApplied)
                .appliedRuleId(anyHealingApplied ? target : null)
                .data(null)
                .message(learnedAnything
                        ? "Upstream API error: 400 (healing attempted, max retries reached)"
                        : "Upstream API error: 400")
                .build();
    }

    /**
     * normalizeLocalHttps(...)
     * -----------------------
     * Purpose:
     * - In local dev, people often set base URL as https://localhost:xxxx by habit,
     * but their local server (Express/Spring mock) is running plain HTTP.
     * - That mismatch causes SSL handshake errors.
     * <p>
     * Behavior:
     * - If url begins with https://localhost or https://127.0.0.1, convert to http://...
     * - Trim and remove trailing slash for consistent joining.
     * <p>
     * Parameters:
     * - url:    input URL string from config or rules
     * - source: label used only for logging so you know which config/rule produced the URL
     */
    private static String normalizeLocalHttps(String url, String source) {
        // If caller passes null, return empty string (prevents NPE in callers)
        if (url == null) return "";

        // Trim whitespace and remove trailing slash to keep formatting consistent
        String trimmed = url.trim().replaceAll("/$", "");

        /**
         * Safety mechanism for local development environments:
         * - Automatically downgrades https://localhost or https://127.0.0.1 to http://
         * - Avoids SSL/TLS handshake errors when local servers don't have certificates
         * - Logs a warning so the developer knows this was changed
         */
        if (trimmed.startsWith("https://localhost") || trimmed.startsWith("https://127.0.0.1")) {
            // Replace scheme from "https" -> "http" by rebuilding the string
            String fixed = "http://" + trimmed.substring("https://".length());

            // Warn in logs because we're changing a URL that the user configured/stored
            log.warn("{} is HTTPS for localhost. Downgrading to HTTP: '{}' -> '{}'", source, trimmed, fixed);
            return fixed;
        }

        // If not localhost HTTPS, return as-is (trimmed)
        return trimmed;
    }

    /**
     * applyFieldMappings(...)
     * ----------------------
     * Purpose:
     * - Applies all stored field mappings for a given target.
     * Example:
     * - Redis mappings: { "custName" -> "name", "custAge" -> "age" }
     * - Input payload:  { "custName": "vikas", "custAge": 10 }
     * - Output payload: { "name": "vikas", "age": 10 }
     * Notes:
     * - If payload is null -> return empty map
     * - If no mappings exist -> return original payload unchanged
     * - Uses LinkedHashMap to preserve insertion order (nice for debugging/logging)
     */
    private Map<String, Object> applyFieldMappings(String target, Map<String, Object> payload) {

        // null payload -> empty map
        if (payload == null) return Collections.emptyMap();

        // Read mappings from Redis for this target
        Map<String, String> mappings = ruleService.getFieldMappings(target);

        // No mappings -> return payload unchanged (fast path)
        if (mappings == null || mappings.isEmpty()) return payload;

        // Create output map initialized with payload values
        Map<String, Object> out = new LinkedHashMap<>(payload);

        // Iterate each mapping: fromKey -> toKey
        for (Map.Entry<String, String> m : mappings.entrySet()) {
            String from = m.getKey();
            String to = m.getValue();

            // Skip invalid mapping entries to prevent unexpected behavior
            if (from == null || to == null || from.isBlank() || to.isBlank()) continue;

            // If the payload contains the "from" key, rename it to "to"
            if (out.containsKey(from)) {
                // Remove old key and capture value
                Object val = out.remove(from);

                // Put same value under new key
                out.put(to, val);
            }
        }

        // Return the rewritten payload
        return out;
    }

    /**
     * applyMappingsToPayload(...)
     * --------------------------
     * Purpose:
     * - Applies a mapping set (usually learned from the *current error*) immediately.
     * - This avoids requiring a Redis round-trip to see new mapping changes in the next retry.
     * Duplicate handling rule:
     * - If payload contains "from" and does NOT contain "to": rename (move value).
     * - If payload contains "from" and ALSO contains "to": keep "to", remove "from".
     * (prevents duplicate/conflicting fields).
     */
    private Map<String, Object> applyMappingsToPayload(Map<String, Object> payload,
                                                       Map<String, String> mappings) {

        // Defensive: null payload -> empty map
        if (payload == null) return Collections.emptyMap();

        // No mappings -> return payload unchanged
        if (mappings == null || mappings.isEmpty()) return payload;

        // Copy payload so we don't mutate the caller's map
        Map<String, Object> out = new LinkedHashMap<>(payload);

        // Apply each mapping
        for (Map.Entry<String, String> e : mappings.entrySet()) {
            String from = e.getKey();
            String to = e.getValue();

            // Skip invalid mapping entries
            if (from == null || to == null || from.isBlank() || to.isBlank()) continue;

            // Only do anything if "from" exists in payload
            if (out.containsKey(from)) {
                if (!out.containsKey(to)) {
                    // Case 1: "to" doesn't exist -> rename/move value
                    Object val = out.remove(from);
                    out.put(to, val);
                } else {
                    // Case 2: "to" already exists -> drop "from" to avoid duplicates
                    out.remove(from);
                }
            }
        }

        // Return rewritten payload
        return out;
    }
}
