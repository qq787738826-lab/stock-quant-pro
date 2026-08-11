package com.stockquant.server.agent.marketfacts;

import java.util.Arrays;
import java.util.Objects;

/**
 * Explicit source of the fixed secrets allowed by local research automation.
 *
 * <p>Implementations must never use command-line arguments, environment
 * variables, system properties or plaintext files as a secret source.</p>
 */
public interface SecretProvider extends AutoCloseable {

    SecretValue read(SecretTarget target);

    default SecretValue readResearchDatabasePassword() {
        return read(SecretTarget.RESEARCH_DATABASE_PASSWORD);
    }

    default SecretValue readTushareToken() {
        return read(SecretTarget.TUSHARE_TOKEN);
    }

    default SecretValue readOpenAiApiKey() {
        return read(SecretTarget.OPENAI_API_KEY);
    }

    @Override
    default void close() {
        // Providers own no long-lived plaintext by default.
    }

    enum SecretTarget {
        RESEARCH_DATABASE_PASSWORD("StockQuant/ResearchDbPassword"),
        TUSHARE_TOKEN("StockQuant/TushareToken"),
        OPENAI_API_KEY("StockQuant/OpenAiApiKey");

        private final String credentialTarget;

        SecretTarget(String credentialTarget) {
            this.credentialTarget = credentialTarget;
        }

        public String credentialTarget() {
            return credentialTarget;
        }

        static SecretTarget requireCredentialTarget(String value) {
            for (SecretTarget target : values()) {
                if (target.credentialTarget.equals(value)) {
                    return target;
                }
            }
            throw new IllegalArgumentException(
                    "STOCK_QUANT_SECRET_TARGET_NOT_ALLOWED");
        }
    }

    /** Clearable secret value that never renders its plaintext. */
    final class SecretValue implements AutoCloseable {
        private static final int MAXIMUM_SECRET_CHARACTERS = 1_280;
        private char[] value;

        public SecretValue(char[] value) {
            Objects.requireNonNull(value, "value");
            if (value.length < 8
                    || value.length > MAXIMUM_SECRET_CHARACTERS) {
                Arrays.fill(value, '\0');
                throw new IllegalArgumentException(
                        "STOCK_QUANT_SECRET_VALUE_INVALID");
            }
            this.value = value.clone();
            Arrays.fill(value, '\0');
        }

        public char[] copy() {
            if (value == null) {
                throw new IllegalStateException(
                        "STOCK_QUANT_SECRET_VALUE_CLEARED");
            }
            return value.clone();
        }

        boolean cleared() {
            return value == null;
        }

        @Override
        public void close() {
            if (value != null) {
                Arrays.fill(value, '\0');
                value = null;
            }
        }

        @Override
        public String toString() {
            return "SecretValue[REDACTED]";
        }
    }
}
