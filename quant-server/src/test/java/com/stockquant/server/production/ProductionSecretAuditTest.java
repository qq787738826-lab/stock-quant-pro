package com.stockquant.server.production;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionSecretAuditTest {
    @Test
    void installsBeforeSecretRegistrationAndClearsInput() {
        char[] secret = "LOCAL_TEST_SECRET".toCharArray();
        try (var audit = ProductionSecretAudit.install()) {
            audit.registerAndClear(secret);
        }
        assertTrue(Arrays.equals(new char[secret.length], secret));
        assertEquals("M6_DATABASE_FAILURE",
                ProductionSecretAudit.safeCode(
                        new IllegalStateException("M6_DATABASE_FAILURE")));
        assertEquals("M6_INTERNAL_FAILURE",
                ProductionSecretAudit.safeCode(
                        new IllegalStateException("unsafe detail")));
    }
}
