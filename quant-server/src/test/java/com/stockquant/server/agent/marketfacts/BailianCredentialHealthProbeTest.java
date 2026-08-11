package com.stockquant.server.agent.marketfacts;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BailianCredentialHealthProbeTest {

    @Test
    void fixedCredentialCanBeReadAndValidatedWithoutNetworkAccess() {
        AtomicInteger reads = new AtomicInteger();
        char[] returned = "sk-fake-bailian-probe-key-value".toCharArray();
        SecretProvider provider = target -> {
            assertEquals(SecretProvider.SecretTarget.BAILIAN_API_KEY, target);
            reads.incrementAndGet();
            return new SecretProvider.SecretValue(returned);
        };

        int exit = BailianCredentialHealthProbe.run(provider);

        assertEquals(BailianCredentialHealthProbe.EXIT_SUCCESS, exit);
        assertEquals(1, reads.get());
        assertTrue(Arrays.equals(new char[returned.length], returned));
    }

    @Test
    void missingCredentialFailsClosedWithoutFallbackOrNetwork() {
        SecretProvider missing = target -> {
            throw new IllegalStateException(
                    "STOCK_QUANT_CREDENTIAL_NOT_FOUND");
        };

        assertEquals(BailianCredentialHealthProbe.EXIT_REJECTED,
                BailianCredentialHealthProbe.run(missing));
    }
}
