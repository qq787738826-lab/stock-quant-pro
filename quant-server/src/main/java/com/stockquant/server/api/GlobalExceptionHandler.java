package com.stockquant.server.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiResponse<Void>> handleBadRequest(IllegalArgumentException e) { return ResponseEntity.badRequest().body(ApiResponse.fail(e.getMessage())); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) { return ResponseEntity.badRequest().body(ApiResponse.fail(e.getBindingResult().getAllErrors().get(0).getDefaultMessage())); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) { return ResponseEntity.internalServerError().body(ApiResponse.fail("系统异常：" + e.getMessage())); }
}
