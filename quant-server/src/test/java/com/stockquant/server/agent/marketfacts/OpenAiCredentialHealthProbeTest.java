package com.stockquant.server.agent.marketfacts;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCredentialHealthProbeTest {

    @Test
    void fixedCredentialCanBeReadAndValidatedWithoutNetworkAccess() {
        AtomicInteger reads = new AtomicInteger();
        char[] returned = "sk-fake-openai-probe-key-value".toCharArray();
        SecretProvider provider = target -> {
            assertEquals(SecretProvider.SecretTarget.OPENAI_API_KEY, target);
            reads.incrementAndGet();
            return new SecretProvider.SecretValue(returned);
        };

        int exit = OpenAiCredentialHealthProbe.run(provider);

        assertEquals(OpenAiCredentialHealthProbe.EXIT_SUCCESS, exit);
        assertEquals(1, reads.get());
        assertTrue(Arrays.equals(new char[returned.length], returned));
    }

    @Test
    void missingCredentialFailsClosedWithoutFallbackOrNetwork() {
        SecretProvider missing = target -> {
            throw new IllegalStateException(
                    "STOCK_QUANT_CREDENTIAL_NOT_FOUND");
        };

        assertEquals(OpenAiCredentialHealthProbe.EXIT_REJECTED,
                OpenAiCredentialHealthProbe.run(missing));
    }
}
