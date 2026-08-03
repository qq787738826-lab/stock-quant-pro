package com.stockquant.server.agent.marketfacts;

import java.io.Console;
import java.util.Arrays;
import java.util.Objects;

/**
 * Interactive-only secret input for the dedicated controlled runner.
 *
 * <p>The production implementation deliberately does not inspect command-line
 * arguments, environment variables, system properties or configuration
 * files. Values remain in clearable character arrays and are never rendered
 * by {@link #toString()}.</p>
 */
interface TushareControlledAcceptanceSecretChannel {

    SecretValue readDatabasePassword();

    SecretValue readTushareToken();

    default SecretValue readAdministratorDatabasePassword() {
        throw new IllegalStateException(
                "TUSHARE_DATABASE_PREPARATION_ADMIN_SECRET_UNAVAILABLE");
    }

    default SecretValue readDedicatedDatabasePassword() {
        throw new IllegalStateException(
                "TUSHARE_DATABASE_PREPARATION_DEDICATED_SECRET_UNAVAILABLE");
    }

    static TushareControlledAcceptanceSecretChannel consoleOnly() {
        Console console = System.console();
        if (console == null) {
            throw new IllegalStateException(
                    "TUSHARE_CONTROLLED_ACCEPTANCE_SECURE_CONSOLE_REQUIRED");
        }
        return new ConsoleSecretChannel(console);
    }

    final class SecretValue implements AutoCloseable {
        private char[] value;

        SecretValue(char[] value) {
            Objects.requireNonNull(value, "value");
            if (value.length < 8) {
                Arrays.fill(value, '\0');
                throw new IllegalArgumentException(
                        "TUSHARE_CONTROLLED_ACCEPTANCE_SECRET_INVALID");
            }
            this.value = value.clone();
            Arrays.fill(value, '\0');
        }

        char[] copy() {
            if (value == null) {
                throw new IllegalStateException(
                        "TUSHARE_CONTROLLED_ACCEPTANCE_SECRET_CLEARED");
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

    final class ConsoleSecretChannel
            implements TushareControlledAcceptanceSecretChannel {
        private final Console console;

        private ConsoleSecretChannel(Console console) {
            this.console = Objects.requireNonNull(console, "console");
        }

        @Override
        public SecretValue readDatabasePassword() {
            return read("Dedicated research database password: ");
        }

        @Override
        public SecretValue readTushareToken() {
            return read("Tushare token: ");
        }

        @Override
        public SecretValue readAdministratorDatabasePassword() {
            return read("Local PostgreSQL administrator password: ");
        }

        @Override
        public SecretValue readDedicatedDatabasePassword() {
            return read("New dedicated research database password: ");
        }

        private SecretValue read(String prompt) {
            char[] value = console.readPassword("%s", prompt);
            if (value == null) {
                throw new IllegalStateException(
                        "TUSHARE_CONTROLLED_ACCEPTANCE_SECRET_INPUT_ABORTED");
            }
            return new SecretValue(value);
        }
    }
}
