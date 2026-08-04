package com.stockquant.server.agent.marketfacts;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/**
 * Selects exactly one explicit provider mode. It never falls back from
 * Credential Manager to Console or to any plaintext source.
 */
public final class CompositeSecretProvider implements SecretProvider {
    private static final List<String> CLOUD_MARKERS = List.of(
            "CI", "CODEX_CLOUD", "GITHUB_ACTIONS", "TF_BUILD",
            "BUILD_BUILDID", "CODEBUILD_BUILD_ID", "JENKINS_URL",
            "TEAMCITY_VERSION");

    private final SecretProvider delegate;

    private CompositeSecretProvider(SecretProvider delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public static CompositeSecretProvider formalLocal(Mode mode) {
        return formalLocal(mode, CompositeSecretProvider::environmentValue,
                System.getProperty("os.name", ""));
    }

    static CompositeSecretProvider formalLocal(
            Mode mode,
            Function<String, String> environment,
            String osName
    ) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(environment, "environment");
        boolean windows = osName != null && osName.toLowerCase(Locale.ROOT)
                .startsWith("windows");
        boolean cloud = CLOUD_MARKERS.stream().map(environment)
                .anyMatch(value -> value != null && !value.isBlank()
                        && !"false".equalsIgnoreCase(value));
        if (!windows || cloud) {
            throw new IllegalStateException(
                    "STOCK_QUANT_FORMAL_LOCAL_RUNTIME_REQUIRED");
        }
        SecretProvider selected;
        try {
            selected = switch (mode) {
                case WINDOWS_CREDENTIAL_MANAGER ->
                        new WindowsCredentialManagerSecretProvider();
                case CONSOLE -> ConsoleSecretProvider.secureConsole();
            };
        } catch (RuntimeException | LinkageError error) {
            String message = error.getMessage();
            if (message != null
                    && message.matches("[A-Z][A-Z0-9_]{7,127}")) {
                throw error;
            }
            throw new IllegalStateException(
                    "STOCK_QUANT_SECRET_PROVIDER_INITIALIZATION_FAILED");
        }
        return new CompositeSecretProvider(selected);
    }

    static CompositeSecretProvider forTest(SecretProvider provider) {
        return new CompositeSecretProvider(provider);
    }

    static SecretProvider forbiddenTestOrE2eProvider() {
        return target -> {
            throw new IllegalStateException(
                    "STOCK_QUANT_REAL_CREDENTIAL_ACCESS_FORBIDDEN");
        };
    }

    @Override
    public SecretValue read(SecretTarget target) {
        return delegate.read(target);
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public String toString() {
        return "CompositeSecretProvider[mode=EXPLICIT, value=REDACTED]";
    }

    private static String environmentValue(String name) {
        try {
            return System.getenv(name);
        } catch (SecurityException error) {
            throw new IllegalStateException(
                    "STOCK_QUANT_FORMAL_LOCAL_RUNTIME_UNVERIFIED");
        }
    }

    public enum Mode {
        WINDOWS_CREDENTIAL_MANAGER,
        CONSOLE;

        static Mode parse(String value) {
            try {
                return value == null ? WINDOWS_CREDENTIAL_MANAGER
                        : valueOf(value);
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException(
                        "STOCK_QUANT_SECRET_MODE_INVALID");
            }
        }
    }
}
