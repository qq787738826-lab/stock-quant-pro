package com.stockquant.server.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentPostgresTestEnvironmentTest {

    private static final String PASSWORD = "unit-test-secret-value";

    @Test
    void acceptsOnlyExactDedicatedDatabaseIdentity() {
        AgentPostgresTestEnvironment.Credentials credentials = AgentPostgresTestEnvironment.validate(
                AgentPostgresTestEnvironment.REQUIRED_URL,
                AgentPostgresTestEnvironment.REQUIRED_USERNAME,
                PASSWORD
        );

        assertEquals(AgentPostgresTestEnvironment.REQUIRED_URL, credentials.url());
        assertEquals(AgentPostgresTestEnvironment.REQUIRED_USERNAME, credentials.username());
        assertEquals(PASSWORD, credentials.password());
    }

    @Test
    void rejectsPostgresDatabase() {
        assertRejected("jdbc:postgresql://127.0.0.1:5432/postgres", "stock_quant_test", PASSWORD);
    }

    @Test
    void rejectsDevelopmentDatabase() {
        assertRejected("jdbc:postgresql://127.0.0.1:5432/stock_quant", "stock_quant_test", PASSWORD);
    }

    @Test
    void rejectsRemoteHost() {
        assertRejected("jdbc:postgresql://192.0.2.10:5432/stock_quant_test", "stock_quant_test", PASSWORD);
    }

    @Test
    void rejectsUnexpectedUsername() {
        assertRejected(AgentPostgresTestEnvironment.REQUIRED_URL, "postgres", PASSWORD);
    }

    @Test
    void rejectsBlankPassword() {
        assertRejected(AgentPostgresTestEnvironment.REQUIRED_URL, "stock_quant_test", "  ");
    }

    @Test
    void rejectsExtraPathPortAndQueryParameters() {
        assertRejected("jdbc:postgresql://127.0.0.1:5433/stock_quant_test", "stock_quant_test", PASSWORD);
        assertRejected("jdbc:postgresql://127.0.0.1:5432/stock_quant_test/extra", "stock_quant_test", PASSWORD);
        assertRejected("jdbc:postgresql://127.0.0.1:5432/stock_quant_test?ssl=true", "stock_quant_test", PASSWORD);
    }

    @Test
    void neverIncludesPasswordInValidationErrors() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> AgentPostgresTestEnvironment.validate("invalid", "invalid", PASSWORD));

        assertFalse(error.getMessage().contains(PASSWORD));
    }

    private static void assertRejected(String url, String username, String password) {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> AgentPostgresTestEnvironment.validate(url, username, password));
        assertFalse(error.getMessage().contains(PASSWORD));
    }
}
