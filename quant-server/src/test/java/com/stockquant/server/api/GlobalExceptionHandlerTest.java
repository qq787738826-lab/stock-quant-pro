package com.stockquant.server.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GlobalExceptionHandlerTest {
    @Test
    void mapsTechnicalErrorsToBoundedUserCategories() {
        String value = GlobalExceptionHandler.userMessage(
                new IllegalStateException("M6_DATABASE_VERSION_INVALID"));
        assertEquals(true, value.startsWith("DATABASE:"));
        assertEquals(true, value.contains("no automatic retry or trade"));

        String unknown = GlobalExceptionHandler.userMessage(
                new IllegalStateException("secret detail"));
        assertFalse(unknown.contains("secret detail"));
    }
}
