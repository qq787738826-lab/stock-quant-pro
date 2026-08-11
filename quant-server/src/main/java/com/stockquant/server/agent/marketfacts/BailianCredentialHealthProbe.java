package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.CompositeSecretProvider.Mode;
import com.stockquant.server.agent.marketfacts.SecretProvider.SecretValue;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.Captured;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.SensitiveKind;
import com.stockquant.server.agent.research.OpenAiResponsesModelAdapter;

import java.util.Arrays;

/** Fixed-target, zero-network readability probe for the M3 Bailian key. */
public final class BailianCredentialHealthProbe {
    static final int EXIT_SUCCESS = 0;
    static final int EXIT_REJECTED = 20;

    private BailianCredentialHealthProbe() {
    }

    public static void main(String[] args) {
        if (args != null && args.length != 0) {
            System.err.println(
                    "STOCK_QUANT_BAILIAN_CREDENTIAL_PROBE_REASON="
                            + "M3_BAILIAN_CREDENTIAL_PROBE_ARGUMENTS_INVALID");
            System.exit(EXIT_REJECTED);
        }
        int exit;
        try (SecretProvider provider = CompositeSecretProvider.formalLocal(
                Mode.WINDOWS_CREDENTIAL_MANAGER)) {
            exit = run(provider);
        }
        System.exit(exit);
    }

    static int run(SecretProvider provider) {
        try {
            Captured<Boolean> captured = TushareControlledAcceptanceOutputAudit
                    .captureBailianCredentialOnlyProcess(registry -> {
                        try (SecretValue value = provider.readBailianApiKey()) {
                            char[] key = value.copy();
                            try {
                                registry.register(SensitiveKind.BAILIAN_API_KEY,
                                        key);
                                return OpenAiResponsesModelAdapter
                                        .isStructurallyValidApiKey(key);
                            } finally {
                                Arrays.fill(key, '\0');
                            }
                        }
                    });
            if (!Boolean.TRUE.equals(captured.value())
                    || !captured.auditResult().clean()) {
                throw new IllegalStateException(
                        "M3_BAILIAN_CREDENTIAL_FORMAT_INVALID");
            }
            System.out.println("STOCK_QUANT_BAILIAN_CREDENTIAL_READ=SUCCESS");
            System.out.println("STOCK_QUANT_BAILIAN_NETWORK_CALLS=0");
            System.out.println("STOCK_QUANT_BAILIAN_OUTPUT_AUDIT=PASSED");
            return EXIT_SUCCESS;
        } catch (Throwable error) {
            Throwable reasonSource = error instanceof
                    TushareControlledAcceptanceOutputAudit
                            .CapturedExecutionException
                    && error.getCause() != null
                    ? error.getCause() : error;
            System.err.println("STOCK_QUANT_BAILIAN_CREDENTIAL_PROBE_REASON="
                    + safeCode(reasonSource));
            System.err.println("STOCK_QUANT_BAILIAN_NETWORK_CALLS=0");
            return EXIT_REJECTED;
        }
    }

    private static String safeCode(Throwable error) {
        for (Throwable value = error; value != null;
                value = value.getCause()) {
            String message = value.getMessage();
            if (message != null
                    && message.matches("[A-Z][A-Z0-9_]{7,127}")) {
                return message;
            }
        }
        return "M3_BAILIAN_CREDENTIAL_PROBE_FAILED";
    }
}
