package com.example.smartcall.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents the response returned by the smart call endpoint.  It mirrors
 * the upstream API's response but includes additional metadata about
 * whether a healing rule was applied.  Clients can inspect the {@code healed}
 * flag and {@code appliedRuleId} to understand if the request required
 * self‑healing.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SmartCallResponse {
    /** Indicates success or error. */
    private String status;
    /** Set to true if a healing rule was applied for this request. */
    private boolean healed;
    /** Identifier of the applied rule (if any).  Can be null. */
    private String appliedRuleId;
    /** Arbitrary data returned from the upstream API. */
    private Object data;
    /** Optional message (e.g. error description). */
    private String message;
}