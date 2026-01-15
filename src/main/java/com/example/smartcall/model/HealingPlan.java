package com.example.smartcall.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * HealingPlan represents what the AI decided needs to be fixed.
 * It can contain:
 * 1) A new API endpoint (v1 -> v2)
 * 2) One or more field name corrections (custName -> name)
 * This object is stored in Redis and reused for future API calls.
 */
public class HealingPlan {

    /**
     * The new endpoint to use.
     * Example: "/v2/createOrder"
     * If null, it means endpoint does not need to change.
     */
    private String endpoint;

    /**
     * A map of:
     * wrongFieldName -> correctFieldName
     * <p>
     * Example:
     * "custName" -> "name"
     * "custAge"  -> "age"
     */
    private Map<String, String> fieldMappings = new LinkedHashMap<>();

    /**
     * Empty constructor.
     * Required by Jackson (JSON library) when loading from Redis.
     */
    public HealingPlan() {
    }

    /**
     * Constructor used when AI builds a new healing plan.
     *
     * @param endpoint      the corrected endpoint (may be null)
     * @param fieldMappings field name mappings
     */
    public HealingPlan(String endpoint, Map<String, String> fieldMappings) {
        this.endpoint = endpoint;

        // Copy mappings instead of using original reference
        if (fieldMappings != null) {
            this.fieldMappings.putAll(fieldMappings);
        }
    }

    /**
     * Returns the corrected endpoint.
     */
    public String getEndpoint() {
        return endpoint;
    }

    /**
     * Sets the new endpoint.
     */
    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    /**
     * Returns all field mappings.
     * If none exist, return an empty map instead of null.
     * <p>
     * This prevents NullPointerException.
     */
    public Map<String, String> getFieldMappings() {
        return fieldMappings == null
                ? Collections.emptyMap()
                : fieldMappings;
    }

    /**
     * Replace all field mappings.
     */
    public void setFieldMappings(Map<String, String> fieldMappings) {
        this.fieldMappings = new LinkedHashMap<>();
        if (fieldMappings != null) {
            this.fieldMappings.putAll(fieldMappings);
        }
    }

    /**
     * Tells whether this plan actually does anything.
     * <p>
     * If there is no new endpoint
     * AND no field mappings,
     * then this plan is useless.
     */
    public boolean isEmpty() {
        return (endpoint == null || endpoint.isBlank())
                && (fieldMappings == null || fieldMappings.isEmpty());
    }

    /**
     * Used for logging.
     * Example output:
     * HealingPlan{endpoint='/v2/createOrder', fieldMappings={custName=name}}
     */
    @Override
    public String toString() {
        return "HealingPlan{endpoint='" + endpoint + "', fieldMappings=" + fieldMappings + "}";
    }

    /**
     * Used when comparing two healing plans.
     * Needed by Redis cache and collections.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HealingPlan)) return false;

        HealingPlan that = (HealingPlan) o;

        return Objects.equals(endpoint, that.endpoint)
                && Objects.equals(getFieldMappings(), that.getFieldMappings());
    }

    /**
     * Required whenever equals() is overridden.
     * Used internally by HashMap, Redis, etc.
     */
    @Override
    public int hashCode() {
        return Objects.hash(endpoint, getFieldMappings());
    }
}