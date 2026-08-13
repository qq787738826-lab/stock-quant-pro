package com.stockquant.server.production;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Locale;

/** Installs the production log policy before the first credential read. */
final class ProductionSecretAudit implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(
            ProductionSecretAudit.class);
    private boolean active;

    private ProductionSecretAudit() {
    }

    static ProductionSecretAudit install() {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) ->
                LOG.error("M6_UNCAUGHT_FAILURE reason={}", safeCode(error)));
        ProductionSecretAudit audit = new ProductionSecretAudit();
        audit.active = true;
        return audit;
    }

    void registerAndClear(char[] secret) {
        if (!active || secret == null || secret.length < 8) {
            if (secret != null) {
                Arrays.fill(secret, '\0');
            }
            throw new IllegalStateException("M6_SECRET_AUDIT_REGISTRATION_INVALID");
        }
        Arrays.fill(secret, '\0');
    }

    static String safeCode(Throwable error) {
        String message = error == null ? null : error.getMessage();
        if (message != null
                && message.toUpperCase(Locale.ROOT)
                .matches("[A-Z][A-Z0-9_]{3,127}")) {
            return message.toUpperCase(Locale.ROOT);
        }
        return "M6_INTERNAL_FAILURE";
    }

    @Override
    public void close() {
        active = false;
    }
}
