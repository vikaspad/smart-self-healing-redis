package com.example.smartcall.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Incoming request for the smart call endpoint.  A client specifies a
 * target which identifies the external API to call, a payload
 * containing the body to send, and optional 'options' controlling
 * timeout and retry behaviour.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SmartCallRequest {

    /**
     * Logical name of the external integration.  For example "createOrder"
     * might correspond to the clients order creation endpoint.  Rules in
     * Redis are keyed off this value.
     */
    @NotBlank
    private String target;

    /**
     * JSON payload to send to the external API.  It is represented as a map
     * so that field names can be adjusted by healing rules.  Jackson will
     * automatically convert nested objects and arrays.
     */
    @NotNull
    private Map<String, Object> payload;

    /**
     * Optional settings controlling the underlying HTTP call.  If null,
     * sensible defaults are used.
     */
    @Valid
    private Options options;
}