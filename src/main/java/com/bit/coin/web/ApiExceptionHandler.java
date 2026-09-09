package com.bit.coin.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@lombok.extern.slf4j.Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "node_unavailable"));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalidArgument(IllegalArgumentException error) {
        // Stack location is useful for diagnostics; exception messages can contain submitted secrets.
        log.debug("Invalid request: type={}, location={}", error.getClass().getSimpleName(),
                error.getStackTrace().length == 0 ? "unknown" : error.getStackTrace()[0]);
        return badRequest();
    }
    private ResponseEntity<Map<String, String>> badRequest() {
        return ResponseEntity.badRequest().body(Map.of("error", "invalid_request"));
    }
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> unreadable(org.springframework.http.converter.HttpMessageNotReadableException error) {
        Throwable cause = error;
        for (int i = 0; cause != null && i < 16; i++, cause = cause.getCause())
            if (cause instanceof ApiAccessFilter.RequestTooLarge) return tooLarge();
        return badRequest();
    }
    @ExceptionHandler(ApiAccessFilter.RequestTooLarge.class)
    ResponseEntity<Map<String, String>> tooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("error", "request_too_large"));
    }
}
