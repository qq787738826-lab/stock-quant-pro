package com.stockquant.server.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentPythonSmokeEnvironmentTest {

    @Test
    void acceptsExactIpv4LoopbackWithUnprivilegedPort() {
        assertEquals("http://127.0.0.1:18001",
                AgentPythonSmokeEnvironment.validate("http://127.0.0.1:18001"));
    }

    @Test
    void rejectsLocalhostWildcardRemoteAndDomainHosts() {
        assertRejected("http://localhost:18001");
        assertRejected("http://0.0.0.0:18001");
        assertRejected("http://192.0.2.1:18001");
        assertRejected("http://example.invalid:18001");
        assertRejected("http://[::1]:18001");
    }

    @Test
    void rejectsHttpsMissingAndPrivilegedPorts() {
        assertRejected("https://127.0.0.1:18001");
        assertRejected("http://127.0.0.1");
        assertRejected("http://127.0.0.1:1023");
    }

    @Test
    void rejectsPathQueryFragmentAndTrailingSlash() {
        assertRejected("http://127.0.0.1:18001/path");
        assertRejected("http://127.0.0.1:18001?debug=true");
        assertRejected("http://127.0.0.1:18001#fragment");
        assertRejected("http://127.0.0.1:18001/");
    }

    @Test
    void rejectsUserInformationAndEmptyValuesWithoutLeakingCredentials() {
        String value = "http://user:secret-value@127.0.0.1:18001";
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> AgentPythonSmokeEnvironment.validate(value));
        assertFalse(error.getMessage().contains("secret-value"));
        assertRejected(null);
        assertRejected("  ");
    }

    private static void assertRejected(String value) {
        assertThrows(IllegalStateException.class, () -> AgentPythonSmokeEnvironment.validate(value));
    }
}
