package com.example.smartcall.controller;

import com.example.smartcall.model.SmartCallRequest;
import com.example.smartcall.model.SmartCallResponse;
import com.example.smartcall.service.SmartCallService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing the `/api/smart-call` endpoint.  It delegates
 * processing to {@link SmartCallService} and returns a structured
 * {@link SmartCallResponse}.  Input validation is performed using
 * Jakarta Bean Validation annotations on the request DTO.
 */
@RestController
@RequestMapping("/api")
@Validated
public class SmartCallController {

    private final SmartCallService smartCallService;

    public SmartCallController(SmartCallService smartCallService) {
        this.smartCallService = smartCallService;
    }

    /**
     * Handles POST requests to `/api/smart-call`.  Validates the request and
     * delegates to the service layer.  Returns a 200 OK response even when
     * the upstream API fails; the status field inside the response conveys
     * success or error.
     */
    @PostMapping("/smart-call")
    public ResponseEntity<SmartCallResponse> smartCall(@Valid @RequestBody SmartCallRequest request) {
        SmartCallResponse response = smartCallService.handleSmartCall(request);
        return ResponseEntity.ok(response);
    }
}