package com.stockquant.server.agent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void migrationTargetCanNeverBePublicOrCallerControlled() {
        assertThrows(IllegalStateException.class,
                () -> AgentPostgresTestEnvironment
                        .requireSafeMigrationSchema("public"));
        assertThrows(IllegalStateException.class,
                () -> AgentPostgresTestEnvironment
                        .requireSafeMigrationSchema("stage_fixture"));
        String schema = AgentPostgresTestEnvironment
                .isolatedSchemaName(
                        "safety_gate",
                        UUID.fromString(
                                "00000000-0000-0000-0000-000000000001"));
        AgentPostgresTestEnvironment
                .requireSafeMigrationSchema(schema);
        assertEquals(
                "agent_it_safety_gate_"
                        + "00000000000000000000000000000001",
                schema);
        assertTrue(AgentPostgresTestEnvironment.schemaUrl(
                new AgentPostgresTestEnvironment.Credentials(
                        AgentPostgresTestEnvironment.REQUIRED_URL,
                        AgentPostgresTestEnvironment.REQUIRED_USERNAME,
                        PASSWORD),
                schema).endsWith("currentSchema=" + schema));
    }

    @Test
    void everySpringPostgresIntegrationTestDeclaresIsolation()
            throws IOException {
        Path testRoot = Path.of(
                "src/test/java/com/stockquant/server/agent");
        try (var files = Files.walk(testRoot)) {
            for (Path file : files
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList()) {
                String source = Files.readString(file);
                assertFalse(source.contains(
                                "AgentPostgresTestEnvironment"
                                        + ".registerDataSource"),
                        file + " must not bind a Spring test to public");
                if (!source.contains("@SpringBootTest")
                        || !source.contains(
                        "STOCK_QUANT_TEST_DB_URL")) {
                    continue;
                }
                boolean sharedGuard = source.contains(
                        "registerIsolatedDataSource");
                boolean explicitGuard = source.contains(
                        "currentSchema=")
                        && source.contains(
                        "spring.flyway.default-schema")
                        && source.contains("spring.flyway.schemas")
                        && source.contains(
                        "spring.flyway.create-schemas");
                assertTrue(sharedGuard || explicitGuard,
                        file + " must configure a random isolated "
                                + "schema before Flyway migrate");
            }
        }
    }

    private static void assertRejected(String url, String username, String password) {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> AgentPostgresTestEnvironment.validate(url, username, password));
        assertFalse(error.getMessage().contains(PASSWORD));
    }
}
