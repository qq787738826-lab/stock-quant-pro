package com.stockquant.server.agent.marketfacts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TushareCredentialHealthProbeTest {

    @Test
    void acceptsStableTransportSafeCredentialWithoutExposingFingerprint() {
        char[] first = "0123456789abcdef0123456789abcdef".toCharArray();
        char[] second = first.clone();

        var inspection = TushareCredentialHealthProbe.inspect(first, second);

        assertEquals(32, inspection.length());
        assertEquals("VALID_TRANSPORT_FORMAT", inspection.format());
        assertTrue(inspection.fingerprintStable());
        assertFalse(inspection.toString().contains(
                "0123456789abcdef0123456789abcdef"));
    }

    @Test
    void distinguishesLengthWhitespaceCharactersAndFingerprintChanges() {
        assertEquals("INVALID_LENGTH",
                TushareCredentialHealthProbe.inspect(
                        "too-short".toCharArray(),
                        "too-short".toCharArray()).format());
        assertEquals("INVALID_WHITESPACE",
                TushareCredentialHealthProbe.inspect(
                        "0123456789abcdef 0123456789abcdef".toCharArray(),
                        "0123456789abcdef 0123456789abcdef".toCharArray())
                        .format());
        assertEquals("INVALID_CHARACTERS",
                TushareCredentialHealthProbe.inspect(
                        "0123456789abcdef-0123456789abcdef".toCharArray(),
                        "0123456789abcdef-0123456789abcdef".toCharArray())
                        .format());
        assertFalse(TushareCredentialHealthProbe.inspect(
                "0123456789abcdef0123456789abcdef".toCharArray(),
                "fedcba9876543210fedcba9876543210".toCharArray())
                .fingerprintStable());
    }
}
