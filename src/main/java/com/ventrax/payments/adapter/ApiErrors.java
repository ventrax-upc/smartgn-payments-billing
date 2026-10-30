package com.ventrax.payments.adapter;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler({
        org.springframework.web.bind.MethodArgumentNotValidException.class,
        org.springframework.web.method.annotation.HandlerMethodValidationException.class,
        org.springframework.http.converter.HttpMessageNotReadableException.class
    })
    ResponseEntity<?> validation() {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_INPUT"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> conflict() {
        return ResponseEntity.status(409)
                .body(
                        Map.of(
                                "code",
                                "CONFLICT",
                                "message",
                                "Operation conflicts with an existing record"));
    }

    @ExceptionHandler({IllegalArgumentException.class, tools.jackson.core.JacksonException.class})
    ResponseEntity<?> invalid() {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_INPUT"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> response(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
                .body(
                        Map.of(
                                "code",
                                e.getStatusCode().toString(),
                                "message",
                                e.getReason() == null ? "Request denied" : e.getReason()));
    }
}
