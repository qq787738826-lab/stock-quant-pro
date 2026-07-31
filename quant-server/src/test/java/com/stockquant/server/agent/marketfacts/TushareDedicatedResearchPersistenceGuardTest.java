package com.stockquant.server.agent.marketfacts;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TushareDedicatedResearchPersistenceGuardTest {

    private static final List<String> V1_TO_V13 =
            TushareDedicatedResearchPersistenceGuard.REQUIRED_MIGRATIONS;

    @Test
    void acceptsOnlyDedicatedLocalResearchIdentity() {
        var verification = guard(validState(false))
                .verifyBeforeProvider();

        assertEquals("stock_quant_research",
                verification.currentDatabase());
        assertEquals("stock_quant_research",
                verification.currentUser());
        assertEquals("tushare_research",
                verification.currentSchema());
        assertEquals("tushare_research",
                verification.searchPath());
        assertEquals(V1_TO_V13,
                verification.appliedMigrations());
        assertFalse(verification.transactionBound());
        assertFalse(verification.normalBusinessDatabaseAllowed());
    }

    @Test
    void rejectsNormalAndGenericTestDatabasesEvenWithSameSchema() {
        assertCode(
                state("stock_quant", "stock_quant_research",
                        localUrl("stock_quant"), false, V1_TO_V13),
                "TUSHARE_DEDICATED_RESEARCH_NORMAL_DATABASE_FORBIDDEN");
        assertCode(
                state("stock_quant_test", "stock_quant_research",
                        localUrl("stock_quant_test"), false, V1_TO_V13),
                "TUSHARE_DEDICATED_RESEARCH_NORMAL_DATABASE_FORBIDDEN");
    }

    @Test
    void rejectsRemoteWrongUserWrongPurposeAndWrongSchema() {
        assertCode(
                state("stock_quant_research", "stock_quant_research",
                        "jdbc:postgresql://192.0.2.10:55433/"
                                + "stock_quant_research",
                        false, V1_TO_V13),
                "TUSHARE_DEDICATED_RESEARCH_DATABASE_IDENTITY_INVALID");
        assertCode(
                state("stock_quant_research", "postgres",
                        localUrl("stock_quant_research"),
                        false, V1_TO_V13),
                "TUSHARE_DEDICATED_RESEARCH_DATABASE_IDENTITY_INVALID");
        var wrongPurpose = new TushareDedicatedResearchPersistenceGuard(
                () -> validState(false),
                new TushareDedicatedResearchPersistenceGuard
                        .DatabaseIdentityPolicy("NORMAL_BUSINESS"));
        assertEquals(
                "TUSHARE_DEDICATED_RESEARCH_DATABASE_IDENTITY_INVALID",
                assertThrows(
                        TushareDedicatedResearchPersistenceGuard
                                .GuardException.class,
                        wrongPurpose::verifyBeforeProvider).safeCode());
        assertCode(
                new TushareDedicatedResearchPersistenceGuard.SchemaState(
                        "stock_quant_research",
                        "stock_quant_research",
                        localUrl("stock_quant_research"),
                        "public",
                        "public",
                        V1_TO_V13,
                        10_001,
                        false),
                "TUSHARE_DEDICATED_RESEARCH_PUBLIC_SCHEMA_FORBIDDEN");
    }

    @Test
    void rejectsSearchPathFallbackAndIncompleteMigrations() {
        assertCode(
                new TushareDedicatedResearchPersistenceGuard.SchemaState(
                        "stock_quant_research",
                        "stock_quant_research",
                        localUrl("stock_quant_research"),
                        "tushare_research",
                        "tushare_research, public",
                        V1_TO_V13,
                        10_001,
                        false),
                "TUSHARE_DEDICATED_RESEARCH_SEARCH_PATH_INVALID");
        assertCode(
                new TushareDedicatedResearchPersistenceGuard.SchemaState(
                        "stock_quant_research",
                        "stock_quant_research",
                        localUrl("stock_quant_research"),
                        "tushare_research",
                        "tushare_research, pg_catalog",
                        V1_TO_V13,
                        10_001,
                        false),
                "TUSHARE_DEDICATED_RESEARCH_SEARCH_PATH_INVALID");
        assertCode(
                state(
                        "stock_quant_research",
                        "stock_quant_research",
                        localUrl("stock_quant_research"),
                        false,
                        V1_TO_V13.subList(0, 12)),
                "TUSHARE_DEDICATED_RESEARCH_SCHEMA_VERSION_INVALID");
    }

    @Test
    void transactionGuardRequiresBoundConnectionAndStablePid() {
        assertEquals(
                "TUSHARE_DEDICATED_RESEARCH_TRANSACTION_REQUIRED",
                assertThrows(
                        TushareDedicatedResearchPersistenceGuard
                                .GuardException.class,
                        () -> guard(validState(false))
                                .verifyTransactional()).safeCode());
        var before = guard(validState(true)).verifyTransactional();
        assertTrue(before.transactionBound());
        var after = new TushareDedicatedResearchPersistenceGuard
                .Verification(
                before.currentDatabase(),
                before.currentUser(),
                before.jdbcUrl(),
                before.databasePurpose(),
                before.currentSchema(),
                before.searchPath(),
                before.appliedMigrations(),
                before.backendPid() + 1,
                true,
                before.databaseIdentityQualification(),
                before.schemaQualification());
        assertEquals(
                "TUSHARE_DEDICATED_RESEARCH_BACKEND_CHANGED",
                assertThrows(
                        TushareDedicatedResearchPersistenceGuard
                                .GuardException.class,
                        () -> guard(validState(true))
                                .verifySameTransactionalConnection(
                                        before, after)).safeCode());
    }

    private static void assertCode(
            TushareDedicatedResearchPersistenceGuard.SchemaState state,
            String expected
    ) {
        assertEquals(
                expected,
                assertThrows(
                        TushareDedicatedResearchPersistenceGuard
                                .GuardException.class,
                        () -> guard(state).verifyBeforeProvider())
                        .safeCode());
    }

    private static TushareDedicatedResearchPersistenceGuard guard(
            TushareDedicatedResearchPersistenceGuard.SchemaState state
    ) {
        return new TushareDedicatedResearchPersistenceGuard(() -> state);
    }

    private static TushareDedicatedResearchPersistenceGuard.SchemaState
    validState(boolean transactionBound) {
        return state(
                "stock_quant_research",
                "stock_quant_research",
                localUrl("stock_quant_research"),
                transactionBound,
                V1_TO_V13);
    }

    private static TushareDedicatedResearchPersistenceGuard.SchemaState
    state(
            String database,
            String user,
            String url,
            boolean transactionBound,
            List<String> migrations
    ) {
        return new TushareDedicatedResearchPersistenceGuard.SchemaState(
                database,
                user,
                url,
                "tushare_research",
                "tushare_research",
                migrations,
                10_001,
                transactionBound);
    }

    private static String localUrl(String database) {
        return "jdbc:postgresql://127.0.0.1:55433/" + database
                + "?currentSchema=tushare_research";
    }
}
