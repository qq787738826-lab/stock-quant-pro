package com.stockquant.server.agent.marketfacts;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.stockquant.server.agent.marketfacts.CompositeSecretProvider.Mode;
import com.stockquant.server.agent.marketfacts.SecretProvider.SecretTarget;
import com.stockquant.server.agent.marketfacts.SecretProvider.SecretValue;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SecretProviderTest {

    @Test
    void secretValueClearsInputCopiesAndItselfWithoutRenderingPlaintext() {
        char[] input = "fake-secret-value".toCharArray();
        SecretValue secret = new SecretValue(input);

        assertAllZero(input);
        char[] copy = secret.copy();
        assertArrayEquals("fake-secret-value".toCharArray(), copy);
        Arrays.fill(copy, '\0');
        assertEquals("SecretValue[REDACTED]", secret.toString());
        secret.close();
        assertTrue(secret.cleared());
        assertThrows(IllegalStateException.class, secret::copy);
    }

    @Test
    void invalidSecretSizeFailsClosedAndClearsTheCallerBuffer() {
        char[] tooShort = "short".toCharArray();
        char[] tooLong = new char[1_281];
        Arrays.fill(tooLong, 'x');

        assertEquals("STOCK_QUANT_SECRET_VALUE_INVALID",
                assertThrows(IllegalArgumentException.class,
                        () -> new SecretValue(tooShort)).getMessage());
        assertEquals("STOCK_QUANT_SECRET_VALUE_INVALID",
                assertThrows(IllegalArgumentException.class,
                        () -> new SecretValue(tooLong)).getMessage());
        assertAllZero(tooShort);
        assertAllZero(tooLong);
    }

    @Test
    void windowsProviderReadsOnlyBothExactTargetsAndCanReadRepeatedly() {
        List<String> targets = new ArrayList<>();
        List<char[]> returned = new ArrayList<>();
        var provider = new WindowsCredentialManagerSecretProvider(
                true, target -> {
            targets.add(target);
            char[] value = (target.endsWith("TushareToken")
                    ? "fake-token-value" : "fake-database-value").toCharArray();
            returned.add(value);
            return value;
        });

        try (SecretValue first = provider.readResearchDatabasePassword();
             SecretValue second = provider.readResearchDatabasePassword();
             SecretValue token = provider.readTushareToken()) {
            assertSecretEquals("fake-database-value", first);
            assertSecretEquals("fake-database-value", second);
            assertSecretEquals("fake-token-value", token);
        }
        assertEquals(List.of(
                "StockQuant/ResearchDbPassword",
                "StockQuant/ResearchDbPassword",
                "StockQuant/TushareToken"), targets);
        returned.forEach(SecretProviderTest::assertAllZero);
        assertFalse(provider.toString().contains("fake"));
    }

    @Test
    void wrongTargetAndMissingCredentialFailClosedWithSafeCodes() {
        assertEquals("STOCK_QUANT_SECRET_TARGET_NOT_ALLOWED",
                assertThrows(IllegalArgumentException.class, () ->
                        SecretTarget.requireCredentialTarget(
                                "StockQuant/UnrelatedCredential")).getMessage());
        var missing = new WindowsCredentialManagerSecretProvider(
                true, ignored -> {
            throw new IllegalStateException("STOCK_QUANT_CREDENTIAL_NOT_FOUND");
        });
        assertEquals("STOCK_QUANT_CREDENTIAL_NOT_FOUND",
                assertThrows(IllegalStateException.class,
                        missing::readTushareToken).getMessage());

        var unsafeFailure = new WindowsCredentialManagerSecretProvider(
                true, ignored -> {
            throw new IllegalArgumentException("fake-secret-must-not-escape");
        });
        IllegalStateException sanitized = assertThrows(
                IllegalStateException.class, unsafeFailure::readTushareToken);
        assertEquals("STOCK_QUANT_CREDENTIAL_READ_FAILED",
                sanitized.getMessage());
        assertNull(sanitized.getCause());
    }

    @Test
    void nativeCredentialLayoutCanBeReadWithoutDereferencingSecretFields() {
        try (Memory memory = new Memory(128)) {
            memory.clear();
            var credential = new WindowsCredentialManagerSecretProvider
                    .Credential(memory);
            assertDoesNotThrow(credential::read);
            assertEquals(Native.POINTER_SIZE == Long.BYTES ? 80 : 52,
                    credential.size());
            assertEquals(0, credential.credentialBlobSize);
            assertNull(credential.credentialBlob);
        }
    }

    @Test
    void nonWindowsAndCloudFormalExecutionAreRejectedBeforeSecretRead() {
        AtomicInteger reads = new AtomicInteger();
        assertEquals("STOCK_QUANT_WINDOWS_CREDENTIAL_MANAGER_REQUIRED",
                assertThrows(IllegalStateException.class, () ->
                        new WindowsCredentialManagerSecretProvider(
                                false, ignored -> {
                            reads.incrementAndGet();
                            return "never-read-secret".toCharArray();
                        })).getMessage());
        assertEquals(0, reads.get());

        assertEquals("STOCK_QUANT_FORMAL_LOCAL_RUNTIME_REQUIRED",
                assertThrows(IllegalStateException.class, () ->
                        CompositeSecretProvider.formalLocal(
                                Mode.WINDOWS_CREDENTIAL_MANAGER,
                                name -> "CI".equals(name) ? "true" : null,
                                "Windows 11")).getMessage());
    }

    @Test
    void testAndE2eProviderCannotReachRealCredentials() {
        SecretProvider forbidden =
                CompositeSecretProvider.forbiddenTestOrE2eProvider();
        assertEquals("STOCK_QUANT_REAL_CREDENTIAL_ACCESS_FORBIDDEN",
                assertThrows(IllegalStateException.class,
                        forbidden::readResearchDatabasePassword).getMessage());
        assertEquals("STOCK_QUANT_REAL_CREDENTIAL_ACCESS_FORBIDDEN",
                assertThrows(IllegalStateException.class,
                        forbidden::readTushareToken).getMessage());
    }

    @Test
    void consoleProviderIsExplicitAndClearsReaderOwnedArrays() {
        char[] database = "fake-console-database".toCharArray();
        char[] token = "fake-console-token".toCharArray();
        AtomicInteger calls = new AtomicInteger();
        ConsoleSecretProvider provider = ConsoleSecretProvider.forTest(
                (format, arguments) -> calls.getAndIncrement() == 0
                        ? database : token);

        try (SecretValue ignored = provider.readResearchDatabasePassword();
             SecretValue ignoredAgain = provider.readTushareToken()) {
            assertEquals(2, calls.get());
        }
        assertAllZero(database);
        assertAllZero(token);
        assertEquals("ConsoleSecretProvider[REDACTED]", provider.toString());
    }

    @Test
    void modeHasCredentialManagerDefaultAndNoAutoFallback() {
        assertEquals(Mode.WINDOWS_CREDENTIAL_MANAGER, Mode.parse(null));
        assertEquals(Mode.WINDOWS_CREDENTIAL_MANAGER,
                Mode.parse("WINDOWS_CREDENTIAL_MANAGER"));
        assertEquals(Mode.CONSOLE, Mode.parse("CONSOLE"));
        assertEquals("STOCK_QUANT_SECRET_MODE_INVALID",
                assertThrows(IllegalArgumentException.class,
                        () -> Mode.parse("AUTO")).getMessage());
    }

    @Test
    void scriptsNeverAcceptPlaintextSecretParametersOrFallbackSources()
            throws Exception {
        String setup = Files.readString(Path.of(
                "scripts/set-stock-quant-secrets.ps1"));
        String runner = Files.readString(Path.of(
                "src/main/java/com/stockquant/server/agent/marketfacts/"
                        + "TushareReducedResearchManualRunner.java"));
        String provider = Files.readString(Path.of(
                "src/main/java/com/stockquant/server/agent/marketfacts/"
                        + "CompositeSecretProvider.java"));

        assertFalse(setup.matches("(?s).*param\\([^)]*(Password|Token).*"));
        assertTrue(setup.contains("Read-Host")
                && setup.contains("-AsSecureString"));
        assertTrue(setup.contains("ProviderOnly"));
        assertTrue(setup.contains(
                "STOCK_QUANT_PROVIDER_CREDENTIAL_UPDATED=true"));
        assertFalse(setup.contains("ConvertFrom-SecureString"));
        assertFalse(setup.contains("SetEnvironmentVariable"));
        assertTrue(runner.contains("WINDOWS_CREDENTIAL_MANAGER"));
        assertFalse(provider.contains("System.getProperty(\"token"));
        assertFalse(provider.contains("System.getenv(\"TUSHARE_TOKEN"));
        assertFalse(provider.contains("AUTO"));
    }

    private static void assertAllZero(char[] value) {
        assertTrue(Arrays.equals(new char[value.length], value));
    }

    private static void assertSecretEquals(String expected, SecretValue secret) {
        char[] copy = secret.copy();
        try {
            assertArrayEquals(expected.toCharArray(), copy);
        } finally {
            Arrays.fill(copy, '\0');
        }
    }
}
