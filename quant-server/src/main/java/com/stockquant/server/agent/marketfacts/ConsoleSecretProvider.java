package com.stockquant.server.agent.marketfacts;

import java.io.Console;
import java.util.Objects;

/** Explicit emergency-only secure Console secret provider. */
public final class ConsoleSecretProvider implements SecretProvider {
    private final PasswordReader reader;

    private ConsoleSecretProvider(PasswordReader reader) {
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    public static ConsoleSecretProvider secureConsole() {
        Console console = System.console();
        if (console == null) {
            throw new IllegalStateException(
                    "STOCK_QUANT_SECURE_CONSOLE_REQUIRED");
        }
        return new ConsoleSecretProvider(console::readPassword);
    }

    static ConsoleSecretProvider forTest(PasswordReader reader) {
        return new ConsoleSecretProvider(reader);
    }

    @Override
    public SecretValue read(SecretTarget target) {
        Objects.requireNonNull(target, "target");
        String prompt = switch (target) {
            case RESEARCH_DATABASE_PASSWORD ->
                    "Dedicated research database password: ";
            case TUSHARE_TOKEN -> "Tushare token: ";
            case BAILIAN_API_KEY -> "Bailian API key: ";
        };
        char[] value;
        try {
            value = reader.readPassword("%s", prompt);
        } catch (RuntimeException error) {
            throw new IllegalStateException(
                    "STOCK_QUANT_SECRET_INPUT_FAILED");
        }
        if (value == null) {
            throw new IllegalStateException(
                    "STOCK_QUANT_SECRET_INPUT_ABORTED");
        }
        return new SecretValue(value);
    }

    @Override
    public String toString() {
        return "ConsoleSecretProvider[REDACTED]";
    }

    @FunctionalInterface
    interface PasswordReader {
        char[] readPassword(String format, Object... arguments);
    }
}
