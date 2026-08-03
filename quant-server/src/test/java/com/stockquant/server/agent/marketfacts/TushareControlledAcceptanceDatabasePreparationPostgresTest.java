package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceDatabasePreparationService.DatabasePreparationException;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceDatabasePreparationService.PreparationReport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(
        named = "F1F_B2_DBPREP_POSTGRES_PORT", matches = "[0-9]+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TushareControlledAcceptanceDatabasePreparationPostgresTest {
    private static final String COMMIT = "c".repeat(40);
    private int port;
    private char[] dedicatedPassword;
    private TushareControlledAcceptanceDatabasePreparationDataSource dedicated;
    private TushareControlledAcceptanceDatabasePreparationDataSource administrator;
    private PreparationReport report;

    @BeforeAll
    void prepareFreshDedicatedDatabase() {
        port = Integer.parseInt(System.getenv("F1F_B2_DBPREP_POSTGRES_PORT"));
        char[] adminPassword = randomSecret();
        dedicatedPassword = randomSecret();
        char[] dedicatedForService = dedicatedPassword.clone();
        var plan = TushareControlledAcceptanceDatabasePreparationPlan.temporaryTest(
                COMMIT, port, "postgres");
        try {
            report = new TushareControlledAcceptanceDatabasePreparationService(
                    Clock.systemUTC()).prepare(
                    plan,
                    adminPassword,
                    () -> {
                        assertArrayEquals(new char[adminPassword.length],
                                adminPassword,
                                "administrator secret must be cleared before "
                                        + "the dedicated secret is requested");
                        return dedicatedForService;
                    },
                    ignored -> { });
        } finally {
            java.util.Arrays.fill(adminPassword, '\0');
        }
        dedicated = new TushareControlledAcceptanceDatabasePreparationDataSource(
                port, "stock_quant_research", "stock_quant_research",
                "tushare_research", dedicatedPassword);
        char[] adminReadbackPassword = randomSecret();
        try {
            administrator =
                    new TushareControlledAcceptanceDatabasePreparationDataSource(
                            port, "stock_quant_research", "postgres", null,
                            adminReadbackPassword);
        } finally {
            java.util.Arrays.fill(adminReadbackPassword, '\0');
        }
    }

    @AfterAll
    void closeSources() {
        if (dedicated != null) {
            dedicated.close();
        }
        if (administrator != null) {
            administrator.close();
        }
        if (dedicatedPassword != null) {
            java.util.Arrays.fill(dedicatedPassword, '\0');
        }
    }

    @Test
    @Order(1)
    void preparationReturnsOnlyCandidateForTemporaryPostgres() {
        assertEquals("DATABASE_PREPARATION_CANDIDATE", report.status());
        assertEquals("TEMPORARY_POSTGRES_TEST_ONLY", report.conclusion());
        assertEquals(13, report.mainMigrations().size());
    }

    @Test
    @Order(2)
    void createsDedicatedNonSuperuserRoleWithFrozenFlags() throws Exception {
        try (Connection connection = administrator.getConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("""
                      SELECT rolcanlogin, rolsuper, rolcreatedb, rolcreaterole,
                             rolinherit, rolreplication, rolbypassrls, rolconnlimit
                        FROM pg_roles WHERE rolname = 'stock_quant_research'
                     """)) {
            assertTrue(row.next());
            assertTrue(row.getBoolean(1));
            for (int column = 2; column <= 7; column++) {
                assertFalse(row.getBoolean(column));
            }
            assertEquals(4, row.getInt(8));
        }
    }

    @Test
    @Order(3)
    void createsExactDatabaseUserAndSchemaIdentity() throws Exception {
        try (Connection connection = dedicated.getConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("""
                     SELECT current_database(), current_user, current_schema(),
                            current_setting('search_path')
                     """)) {
            assertTrue(row.next());
            assertEquals("stock_quant_research", row.getString(1));
            assertEquals("stock_quant_research", row.getString(2));
            assertEquals("tushare_research", row.getString(3));
            assertEquals("tushare_research", row.getString(4));
        }
    }

    @Test
    @Order(4)
    void dedicatedRoleOwnsResearchSchema() throws Exception {
        assertEquals("stock_quant_research", scalar(administrator, """
                SELECT pg_get_userbyid(nspowner)
                  FROM pg_namespace WHERE nspname = 'tushare_research'
                """));
        assertEquals("tushare_research", scalar(administrator, """
                SELECT n.nspname FROM pg_extension e
                JOIN pg_namespace n ON n.oid = e.extnamespace
                WHERE e.extname = 'btree_gist'
                """));
    }

    @Test
    @Order(5)
    void publicCreateAndDatabasePublicPrivilegesAreRevoked() throws Exception {
        assertEquals("false", scalar(dedicated,
                "SELECT has_schema_privilege(current_user, 'public', 'CREATE')::text"));
        assertEquals("0", scalar(administrator, """
                SELECT count(*)::text
                  FROM pg_database d,
                       LATERAL aclexplode(COALESCE(
                         d.datacl, acldefault('d', d.datdba))) acl
                 WHERE d.datname = 'stock_quant_research'
                   AND acl.grantee = 0
                   AND acl.privilege_type IN ('CONNECT','CREATE','TEMPORARY')
                """));
    }

    @Test
    @Order(6)
    void appliesExactMainV1ThroughV13WithoutBaseline() throws Exception {
        assertEquals(TushareControlledAcceptanceDatabasePreparationService
                        .EXPECTED_MAIN_MIGRATIONS,
                strings(dedicated, """
                        SELECT version FROM tushare_research.flyway_schema_history
                         WHERE success ORDER BY installed_rank
                        """));
        assertEquals("0", scalar(dedicated, """
                SELECT count(*)::text
                  FROM tushare_research.flyway_schema_history
                 WHERE type = 'BASELINE' OR NOT success
                """));
    }

    @Test
    @Order(7)
    void mainHistoryHasNoMissingDuplicateOrFutureVersions() throws Exception {
        assertEquals("13", scalar(dedicated, """
                SELECT count(DISTINCT version)::text
                  FROM tushare_research.flyway_schema_history
                 WHERE success AND version::integer BETWEEN 1 AND 13
                """));
        assertEquals("13", scalar(dedicated, """
                SELECT max(version::integer)::text
                  FROM tushare_research.flyway_schema_history WHERE success
                """));
    }

    @Test
    @Order(8)
    void governanceHistoryWasNotCreated() throws Exception {
        assertEquals("false", scalar(dedicated, """
                SELECT (to_regclass(
                  'tushare_research.flyway_controlled_acceptance_history') IS NOT NULL)::text
                """));
    }

    @Test
    @Order(9)
    void governanceV14ObjectsWereNotCreated() throws Exception {
        assertEquals("false", scalar(dedicated, """
                SELECT (to_regclass(
                  'tushare_research.tushare_controlled_acceptance_execution') IS NOT NULL
                  OR to_regclass(
                  'tushare_research.tushare_controlled_acceptance_transition') IS NOT NULL)::text
                """));
    }

    @Test
    @Order(10)
    void noMarketFactsOrAcceptanceRowsExist() throws Exception {
        assertEquals("0", scalar(dedicated, """
                SELECT ((SELECT count(*) FROM tushare_research.pit_market_fact_batches)
                      + (SELECT count(*) FROM tushare_research.pit_market_fact_observations))::text
                """));
    }

    @Test
    @Order(11)
    void mainMigrationsCreatedNoBusinessObjectsInPublic() throws Exception {
        assertEquals("0", scalar(administrator, """
                SELECT count(*)::text FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public'
                  AND c.relkind IN ('r','p','v','m','S','f')
                """));
    }

    @Test
    @Order(12)
    void databaseAndRoleReuseAreFailClosedWithoutFurtherMutation() throws Exception {
        int before = Integer.parseInt(scalar(administrator, """
                SELECT count(*)::text FROM tushare_research.flyway_schema_history
                """));
        char[] secret = randomSecret();
        try {
            var failure = assertThrows(DatabasePreparationException.class,
                    () -> new TushareControlledAcceptanceDatabasePreparationService(
                            Clock.systemUTC()).prepare(
                            TushareControlledAcceptanceDatabasePreparationPlan
                                    .temporaryTest(COMMIT, port, "postgres"),
                            secret, secret::clone, ignored -> { }));
            assertFalse(failure.targetMutated());
            assertEquals("TUSHARE_DATABASE_PREPARATION_TARGET_ALREADY_EXISTS",
                    failure.safeCode());
        } finally {
            java.util.Arrays.fill(secret, '\0');
        }
        assertEquals(Integer.toString(before), scalar(administrator, """
                SELECT count(*)::text FROM tushare_research.flyway_schema_history
                """));
    }

    @Test
    @Order(13)
    void wrongNamesAndMissingPortFailBeforeAnyDatabaseDdl() throws Exception {
        int databasesBefore = Integer.parseInt(scalar(administrator,
                "SELECT count(*)::text FROM pg_database"));
        assertThrows(IllegalArgumentException.class,
                () -> TushareControlledAcceptanceDatabasePreparationPlan.parse(
                        new String[]{"--expected-commit=" + COMMIT,
                                "--admin-user=postgres"}));
        assertThrows(IllegalArgumentException.class,
                () -> TushareControlledAcceptanceDatabasePreparationPlan.parse(
                        new String[]{"--expected-commit=" + COMMIT,
                                "--database-port=" + port, "--admin-user=postgres",
                                "--schema=public"}));
        assertEquals(Integer.toString(databasesBefore), scalar(administrator,
                "SELECT count(*)::text FROM pg_database"));
    }

    @Test
    @Order(14)
    void postgresVersionIsSixteenAndLocalhostOnly() throws Exception {
        assertTrue(report.postgresVersion().startsWith("16."));
        try (Connection connection = dedicated.getConnection()) {
            assertTrue(connection.getMetaData().getURL().startsWith(
                    "jdbc:postgresql://127.0.0.1:" + port
                            + "/stock_quant_research"));
        }
    }

    @Test
    @Order(15)
    void preparationDidNotCreateAcceptanceIdentityOrProviderEvidence() throws Exception {
        assertFalse(report.governanceHistoryPresent());
        assertFalse(report.governanceObjectsPresent());
        assertFalse(report.factOrAcceptanceRowsPresent());
        assertFalse(report.publicBusinessObjectsPresent());
    }

    private static char[] randomSecret() {
        return ("f1f-dbprep-" + UUID.randomUUID()).toCharArray();
    }

    private static String scalar(
            TushareControlledAcceptanceDatabasePreparationDataSource source,
            String sql
    ) throws Exception {
        try (Connection connection = source.getConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            assertTrue(row.next());
            return row.getString(1);
        }
    }

    private static List<String> strings(
            TushareControlledAcceptanceDatabasePreparationDataSource source,
            String sql
    ) throws Exception {
        try (Connection connection = source.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            List<String> result = new java.util.ArrayList<>();
            while (rows.next()) {
                result.add(rows.getString(1));
            }
            return List.copyOf(result);
        }
    }
}
