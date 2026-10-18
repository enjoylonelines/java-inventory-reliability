package com.inventory.report;

import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = OracleReportController.class)
@Profile("oracle")
@ConditionalOnProperty(name = "inventory.api.enabled", havingValue = "true")
public class OracleReportErrors {
    @ExceptionHandler({IllegalArgumentException.class, MissingServletRequestParameterException.class,
                       MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, String>> badRequest(Exception exception) {
        return ResponseEntity.badRequest().body(Map.of("code", "BAD_REQUEST"));
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Map<String, String>> notFound(Exception exception) {
        return ResponseEntity.status(404).body(Map.of("code", "NOT_FOUND"));
    }
}
