package com.stockquant.server.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Locale;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiResponse<Void>> handleBadRequest(
            IllegalArgumentException error
    ) {
        return ResponseEntity.badRequest().body(
                ApiResponse.fail(userMessage(error)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Void>> handleValidation(
            MethodArgumentNotValidException error
    ) {
        return ResponseEntity.badRequest().body(ApiResponse.fail(
                "DATA: request validation failed; no operation started"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception error) {
        return ResponseEntity.internalServerError().body(
                ApiResponse.fail(userMessage(error)));
    }

    static String userMessage(Throwable error) {
        String code = error == null ? null : error.getMessage();
        if (code == null || !code.toUpperCase(Locale.ROOT)
                .matches("[A-Z][A-Z0-9_]{3,127}")) {
            return "UNKNOWN: operation failed; diagnostics retained locally";
        }
        code = code.toUpperCase(Locale.ROOT);
        String category = code.contains("DATABASE") || code.contains("SQL")
                ? "DATABASE" : code.contains("BUDGET") ? "BUDGET"
                : code.contains("SCHEDULER") ? "SCHEDULER"
                : code.contains("BROKER") ? "BROKER"
                : code.contains("BUILD") || code.contains("ARTIFACT")
                ? "BUILD" : code.contains("TUSHARE")
                || code.contains("PROVIDER") ? "PROVIDER"
                : code.contains("MODEL") || code.contains("BAILIAN")
                ? "MODEL" : code.contains("DATA") || code.contains("FACT")
                ? "DATA" : "UNKNOWN";
        return category + ": " + code
                + "; today shadow may be skipped; no automatic retry or trade";
    }
}
