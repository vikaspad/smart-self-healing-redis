package com.example.smartcall.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Optional per-request options.  These settings control how the service
 * calls the underlying external API.  Values are intentionally simple to
 * support demonstration use cases.  You can extend this class with
 * additional fields such as headers or query parameters if needed.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Options {

    /**
     * Maximum amount of time (milliseconds) to wait for a response from the
     * external API.  If null or non‑positive, the service uses a default
     * timeout.
     */
    @PositiveOrZero
    private Integer timeout;

    /**
     * Number of times to retry the call if it fails due to connection
     * problems or server errors.  Zero indicates no retry.
     */
    @Min(0)
    private Integer retries;
}