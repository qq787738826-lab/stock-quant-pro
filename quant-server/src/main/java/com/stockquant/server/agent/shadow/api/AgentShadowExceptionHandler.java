package com.stockquant.server.agent.shadow.api;

import com.stockquant.server.api.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AgentShadowController.class)
public class AgentShadowExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(
            AgentShadowExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiResponse<Void>> badRequest(
            IllegalArgumentException error
    ) {
        return ResponseEntity.badRequest().body(
                ApiResponse.fail(error.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ApiResponse<Void>> conflict(
            IllegalStateException error
    ) {
        log.warn("shadow request rejected: {}", error.getMessage());
        return ResponseEntity.status(409).body(
                ApiResponse.fail(error.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Void>> validation(
            MethodArgumentNotValidException error
    ) {
        String message = error.getBindingResult()
                .getAllErrors().get(0).getDefaultMessage();
        return ResponseEntity.badRequest().body(
                ApiResponse.fail(message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiResponse<Void>> unreadable(
            HttpMessageNotReadableException error
    ) {
        return ResponseEntity.badRequest().body(
                ApiResponse.fail("invalid shadow request JSON"));
    }
}
