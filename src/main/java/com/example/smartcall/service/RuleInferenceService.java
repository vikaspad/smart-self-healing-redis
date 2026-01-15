package com.example.smartcall.service;

import org.springframework.stereotype.Service;
import com.example.smartcall.model.HealingPlan;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RuleInferenceService
 *
 * PURPOSE:
 * This is a deterministic (non-AI) rule generator for self-healing.
 *
 * WHY IT EXISTS:
 * 1) Some environments do NOT have OpenAI API keys.
 * 2) Even if AI exists, its response can be inconsistent.
 *
 * WHAT IT DOES:
 * - Looks at error messages and tries to infer:
 *    a) endpoint rewrite: v1 -> v2
 *    b) required fields: fields { name, age }
 *    c) payload field mapping: custAge -> age
 *
 * OUTCOME:
 * Produces a HealingPlan that SmartCall can apply immediately.
 */
@Service
public class RuleInferenceService {

    /**
     * Regex #1: Detects "Use /v2/something"
     * Example error: "v1 deprecated. Use /v2/createOrder ..."
     *
     * Group(1) captures "/v2/createOrder"
     */
    private static final Pattern USE_ENDPOINT =
            Pattern.compile("(?i)\\buse\\s+([/A-Za-z0-9._:-]+)\\b");

    /**
     * Regex #2: Detects expected fields written like:
     * "fields { name, age }"
     *
     * Group(1) captures: " name, age "
     */
    private static final Pattern FIELDS_BRACES =
            Pattern.compile("(?i)fields?\\s*\\{([^}]*)}");

    /**
     * Regex #3: Detects errors written like:
     * "expects: name, age"
     * or "expect name,age"
     *
     * Group(1) captures list: "name, age"
     */
    private static final Pattern EXPECTS =
            Pattern.compile("(?i)expects?\\s*:?\\s*([A-Za-z0-9_,\\s\\-]+)");

    /**
     * Regex #4: Detects errors that mention one missing field at a time:
     * - {"error":"Missing or invalid field: name"}
     * - "Missing required field: age"
     *
     * Group(1) captures: "name" or "age"
     */
    private static final Pattern MISSING_FIELD = Pattern.compile(
            "(?i)\\bmissing(?:\\s+or\\s+invalid)?(?:\\s+required)?\\s+field\\s*:?\\s*([A-Za-z0-9_]+)"
    );

    /**
     * ObjectMapper parses JSON into Java objects.
     * Here it's used to parse the request payload JSON into Map form.
     */
    private final ObjectMapper objectMapper;

    /**
     * Constructor injection: Spring gives us ObjectMapper automatically.
     */
    public RuleInferenceService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * inferPlan()
     *
     * PURPOSE:
     * Create a "compound" healing plan that can include:
     * - endpoint rewrite
     * - many field mappings
     *
     * OUTCOME:
     * Returns HealingPlan (may be empty if nothing is inferred).
     */
    public HealingPlan inferPlan(Map<String, Object> payload, String error) {

        // If error is null, treat it as empty string to avoid null checks everywhere.
        String err = error == null ? "" : error;

        // Step 1: Try to extract "Use /v2/..." suggestion.
        String endpoint = inferEndpoint(err);

        // Step 2: Try to detect required/expected field names.
        Set<String> expectedFields = inferExpectedFields(err);

        // Step 3: Try to map payload fields to expected fields.
        Map<String, String> fieldMappings = inferFieldMappings(payload, expectedFields);

        // Build the final HealingPlan
        HealingPlan plan = new HealingPlan();

        // Only set endpoint if it looks valid
        if (endpoint != null && !endpoint.isBlank()) {
            plan.setEndpoint(endpoint.trim());
        }

        // Only set mappings if we inferred something meaningful
        if (fieldMappings != null && !fieldMappings.isEmpty()) {
            plan.setFieldMappings(fieldMappings);
        }

        // If nothing was found, return a clean empty plan.
        return plan.isEmpty() ? new HealingPlan() : plan;
    }

    /**
     * proposeRules()
     *
     * PURPOSE:
     * This method provides the same output format as OpenAIService.proposeRules()
     * so SmartCallService can use it interchangeably.
     *
     * OUTCOME:
     * Returns Map like:
     * { "endpoint": "/v2/createOrder", "fieldMappings": {...} }
     */
    public Map<String, Object> proposeRules(String target, String payloadJson, String error) {

        // LinkedHashMap preserves insertion order (nice for logs/debugging)
        Map<String, Object> out = new LinkedHashMap<>();

        // Convert payload JSON -> Map<String,Object>
        Map<String, Object> payload = parsePayload(payloadJson);

        // Infer healing plan from error + payload
        HealingPlan plan = inferPlan(payload, error);

        // If nothing inferred, return empty map
        if (plan == null || plan.isEmpty()) return out;

        // Convert HealingPlan -> Map format
        if (plan.getEndpoint() != null && !plan.getEndpoint().isBlank()) {
            out.put("endpoint", plan.getEndpoint());
        }
        if (!plan.getFieldMappings().isEmpty()) {
            out.put("fieldMappings", plan.getFieldMappings());
        }

        return out;
    }

    /**
     * parsePayload()
     *
     * PURPOSE:
     * Convert JSON string into a Map.
     *
     * IMPORTANT:
     * This is forgiving. If JSON is bad, it returns empty map.
     * This prevents the healing pipeline from crashing.
     */
    private Map<String, Object> parsePayload(String payloadJson) {
        return safeParsePayload(payloadJson);
    }

    /**
     * inferEndpoint()
     *
     * PURPOSE:
     * Search the error message for endpoint hints like:
     * "Use /v2/createOrder"
     *
     * OUTCOME:
     * Returns the extracted endpoint, or null if none found.
     */
    private String inferEndpoint(String error) {
        if (error == null || error.isBlank()) return null;

        Matcher m = USE_ENDPOINT.matcher(error);
        if (m.find()) {
            String candidate = m.group(1);

            // Normalize: if it's a relative path but missing leading "/",
            // add it so it becomes "/v2/..."
            if (!candidate.startsWith("http") && !candidate.startsWith("/")) {
                candidate = "/" + candidate;
            }
            return candidate;
        }
        return null;
    }

    /**
     * inferExpectedFields()
     *
     * PURPOSE:
     * Extract expected field names from an error string.
     *
     * OUTCOME:
     * Returns Set of fields like {"name","age"}.
     */
    private Set<String> inferExpectedFields(String error) {
        if (error == null) return Collections.emptySet();

        // Use LinkedHashSet so field order is preserved.
        Set<String> collected = new LinkedHashSet<>();

        // Best case: "fields { name, age }"
        Matcher m = FIELDS_BRACES.matcher(error);
        if (m.find()) {
            collected.addAll(splitFieldList(m.group(1)));
            return collected;
        }

        // Next: "expects name, age"
        Matcher m2 = EXPECTS.matcher(error);
        if (m2.find()) {
            collected.addAll(splitFieldList(m2.group(1)));
            return collected;
        }

        // Next: multiple occurrences of "Missing field: X"
        Matcher m3 = MISSING_FIELD.matcher(error);
        while (m3.find()) {
            String f = m3.group(1);
            if (f != null && !f.isBlank()) collected.add(f.trim());
        }

        return collected;
    }

    /**
     * splitFieldList()
     *
     * PURPOSE:
     * Converts raw "name, age" into {"name","age"}.
     */
    private Set<String> splitFieldList(String raw) {
        if (raw == null) return Collections.emptySet();

        Set<String> out = new LinkedHashSet<>();

        // Split by comma or newline
        for (String p : raw.split("[,\\n]")) {
            String f = p.trim();

            // Remove quotes and braces leftover from JSON-like text
            f = f.replaceAll("[\\\"'{}]", "").trim();

            if (!f.isBlank()) out.add(f);
        }
        return out;
    }

    /**
     * safeParsePayload()
     *
     * PURPOSE:
     * Parse payload JSON string into a Map.
     *
     * OUTCOME:
     * - If parsing works -> returns Map
     * - If parsing fails -> returns empty map
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> safeParsePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) return Collections.emptyMap();

        try {
            Object obj = objectMapper.readValue(payloadJson, Map.class);

            // Ensure keys are Strings (some JSON parsers give Object keys)
            if (obj instanceof Map<?, ?> m) {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (e.getKey() == null) continue;
                    out.put(String.valueOf(e.getKey()), e.getValue());
                }
                return out;
            }
        } catch (Exception ignored) {
            // Swallow errors intentionally so healing flow doesn't crash.
        }

        return Collections.emptyMap();
    }

    /**
     * inferFieldMappings()
     *
     * PURPOSE:
     * Build mapping from payload keys to expected fields.
     *
     * Example:
     * payload: {custName=Vikas, custAge=10}
     * expected: {name, age}
     *
     * returns: {custName -> name, custAge -> age}
     *
     * IMPORTANT:
     * This is best-effort. It uses a scoring method.
     */
    private Map<String, String> inferFieldMappings(Map<String, Object> payload, Set<String> expectedFields) {
        if (payload == null || payload.isEmpty()) return Collections.emptyMap();
        if (expectedFields == null || expectedFields.isEmpty()) return Collections.emptyMap();

        Set<String> payloadKeys = payload.keySet();
        Map<String, String> mappings = new LinkedHashMap<>();

        for (String expected : expectedFields) {
            if (expected == null || expected.isBlank()) continue;

            // If payload already contains correct field, no mapping needed
            if (payloadKeys.contains(expected)) continue;

            // Otherwise find best matching candidate in payload keys
            String best = null;
            int bestScore = -1;

            for (String candidate : payloadKeys) {
                int score = similarityScore(candidate, expected);
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }

            // Require minimum similarity to avoid random matches
            if (best != null && bestScore >= 2) {
                // Map old payload field -> expected field
                mappings.put(best, expected);
            }
        }

        return mappings;
    }

    /**
     * similarityScore()
     *
     * PURPOSE:
     * Gives a numeric similarity score between two field names.
     *
     * Example:
     *  custAge vs age -> strong match
     *  clientId vs customer_id -> medium match
     *
     * OUTCOME:
     * Higher score means more likely these two fields are "the same meaning".
     */
    private int similarityScore(String a, String b) {
        if (a == null || b == null) return 0;

        // Convert strings into token lists (normalized words)
        List<String> ta = normalizeTokens(a);
        List<String> tb = normalizeTokens(b);
        if (ta.isEmpty() || tb.isEmpty()) return 0;

        int score = 0;

        // Add points for token overlap
        for (String t : ta) {
            if (tb.contains(t)) score += 2;
        }

        // Bonus points if one normalized string contains the other
        String na = String.join("", ta);
        String nb = String.join("", tb);
        if (na.contains(nb) || nb.contains(na)) score += 1;

        return score;
    }

    /**
     * normalizeTokens()
     *
     * PURPOSE:
     * Convert a field name into comparable tokens.
     *
     * Example:
     *  "custAge" -> ["age"]   (cust removed as meaningless prefix)
     *  "customer_id" -> ["id"]
     *  "user-name" -> ["name"]
     */
    private List<String> normalizeTokens(String s) {
        if (s == null) return List.of();

        String x = s.trim();
        if (x.isEmpty()) return List.of();

        // Split camelCase: "custAge" -> "cust Age"
        x = x.replaceAll("([a-z])([A-Z])", "$1 $2");

        // Replace "_" and "-" with spaces
        x = x.replace('_', ' ').replace('-', ' ');

        // Lowercase everything
        x = x.toLowerCase(Locale.ROOT);

        // Remove common meaningless prefixes
        x = x.replaceAll("\\b(customer|cust|client|user|usr)\\b", " ");

        // Remove non-alphanumeric characters
        x = x.replaceAll("[^a-z0-9\\s]", " ");

        // Split into tokens
        List<String> out = new ArrayList<>();
        for (String p : x.split("\\s+")) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
