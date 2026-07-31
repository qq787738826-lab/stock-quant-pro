package com.stockquant.server.agent.marketfacts;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TushareReducedResearchPersistenceGuardTest {

    private static final String SCHEMA =
            "f1c_tushare_research_"
                    + "00000000000000000000000000000001";
    private static final List<String> V1_TO_V13 = List.of(
            "1", "2", "3", "4", "5", "6", "7",
            "8", "9", "10", "11", "12", "13");

    @Test
    void acceptsOnlyStrictF1cSchemaWithV1ToV13() {
        TushareReducedResearchPersistenceGuard guard = guard(
                SCHEMA, SCHEMA, V1_TO_V13);

        var verification = assertDoesNotThrow(guard::verify);

        assertEquals(SCHEMA, verification.currentSchema());
        assertEquals(V1_TO_V13, verification.appliedMigrations());
        assertEquals("VERIFIED",
                verification.isolatedSchemaGuardQualification());
        assertFalse(verification.normalBusinessDatabaseAllowed());
    }

    @Test
    void transactionalVerificationRequiresBoundDedicatedConnection() {
        TushareReducedResearchPersistenceGuard guard =
                new TushareReducedResearchPersistenceGuard(
                        () -> identityState(
                                "stock_quant_test",
                                "stock_quant_test",
                                safeUrl(),
                                SCHEMA,
                                SCHEMA,
                                V1_TO_V13,
                                12_345,
                                true));

        var verification = assertDoesNotThrow(
                guard::verifyTransactional);

        assertEquals("stock_quant_test",
                verification.currentDatabase());
        assertEquals("stock_quant_test",
                verification.currentUser());
        assertEquals(12_345, verification.backendPid());
        assertTrue(verification.transactionBound());
        assertFalse(verification.normalBusinessDatabaseAllowed());
    }

    @Test
    void rejectsNormalDatabaseEvenWithSafeSchema() {
        assertGuardCode(
                "TUSHARE_REDUCED_RUNTIME_NORMAL_DATABASE_FORBIDDEN",
                identityGuard(
                        "stock_quant",
                        "stock_quant_test",
                        "jdbc:postgresql://127.0.0.1:55432/stock_quant",
                        TushareReducedResearchPersistenceGuard
                                .DATABASE_PURPOSE));
    }

    @Test
    void rejectsWrongDatabaseUserMissingPurposeAndUnsafeUrl() {
        assertGuardCode(
                "TUSHARE_REDUCED_RUNTIME_DATABASE_IDENTITY_INVALID",
                identityGuard(
                        "stock_quant_test",
                        "application_user",
                        safeUrl(),
                        TushareReducedResearchPersistenceGuard
                                .DATABASE_PURPOSE));
        assertGuardCode(
                "TUSHARE_REDUCED_RUNTIME_DATABASE_IDENTITY_INVALID",
                identityGuard(
                        "stock_quant_test",
                        "stock_quant_test",
                        safeUrl(),
                        "UNSPECIFIED"));
        assertGuardCode(
                "TUSHARE_REDUCED_RUNTIME_DATABASE_IDENTITY_INVALID",
                identityGuard(
                        "stock_quant_test",
                        "stock_quant_test",
                        "jdbc:postgresql://db.internal:5432/"
                                + "stock_quant_test",
                        TushareReducedResearchPersistenceGuard
                                .DATABASE_PURPOSE));
    }

    @Test
    void transactionEndMustUseSameBackendPid() {
        var before = transactionalVerification(12_345);
        var after = transactionalVerification(54_321);
        TushareReducedResearchPersistenceGuard guard = guard(
                SCHEMA, SCHEMA, V1_TO_V13);

        TushareReducedResearchPersistenceGuard.GuardException error =
                assertThrows(
                        TushareReducedResearchPersistenceGuard
                                .GuardException.class,
                        () -> guard.verifySameTransactionalConnection(
                                before, after));

        assertEquals(
                "TUSHARE_REDUCED_RUNTIME_TRANSACTION_CONNECTION_REQUIRED",
                error.safeCode());
    }

    @Test
    void rejectsPublicBeforeAnyRuntimeWork() {
        assertGuardCode(
                "TUSHARE_REDUCED_RUNTIME_PUBLIC_SCHEMA_FORBIDDEN",
                guard("public", "public", V1_TO_V13));
        assertGuardCode(
                "TUSHARE_REDUCED_RUNTIME_PUBLIC_SCHEMA_FORBIDDEN",
                guard(SCHEMA, SCHEMA + ", public", V1_TO_V13));
    }

    @Test
    void rejectsNonF1cOrSearchPathFallbackSchema() {
        assertGuardCode(
                "TUSHARE_REDUCED_RUNTIME_ISOLATED_SCHEMA_REQUIRED",
                guard(
                        "agent_it_tushare_"
                                + "00000000000000000000000000000001",
                        "agent_it_tushare_"
                                + "00000000000000000000000000000001",
                        V1_TO_V13));
        assertGuardCode(
                "TUSHARE_REDUCED_RUNTIME_ISOLATED_SCHEMA_REQUIRED",
                guard(SCHEMA, SCHEMA + ", pg_catalog", V1_TO_V13));
    }

    @Test
    void rejectsIncompleteMigrationLineage() {
        assertGuardCode(
                "TUSHARE_REDUCED_RUNTIME_SCHEMA_VERSION_INVALID",
                guard(SCHEMA, SCHEMA, V1_TO_V13.subList(0, 12)));
    }

    @Test
    void rejectsTargetChangeBetweenProviderAndCapture() {
        AtomicReference<TushareReducedResearchPersistenceGuard.SchemaState>
                state = new AtomicReference<>(state(
                SCHEMA, SCHEMA, V1_TO_V13));
        TushareReducedResearchPersistenceGuard guard =
                new TushareReducedResearchPersistenceGuard(state::get);
        var before = guard.verify();
        state.set(state(
                "f1c_tushare_research_"
                        + "00000000000000000000000000000002",
                "f1c_tushare_research_"
                        + "00000000000000000000000000000002",
                V1_TO_V13));
        var after = guard.verify();

        TushareReducedResearchPersistenceGuard.GuardException error =
                assertThrows(
                        TushareReducedResearchPersistenceGuard
                                .GuardException.class,
                        () -> guard.verifyUnchanged(before, after));
        assertEquals(
                "TUSHARE_REDUCED_RUNTIME_ISOLATED_SCHEMA_REQUIRED",
                error.safeCode());
    }

    private static TushareReducedResearchPersistenceGuard guard(
            String schema,
            String searchPath,
            List<String> migrations
    ) {
        return new TushareReducedResearchPersistenceGuard(
                () -> state(schema, searchPath, migrations));
    }

    private static TushareReducedResearchPersistenceGuard identityGuard(
            String database,
            String user,
            String url,
            String purpose
    ) {
        return new TushareReducedResearchPersistenceGuard(
                () -> identityState(
                        database,
                        user,
                        url,
                        SCHEMA,
                        SCHEMA,
                        V1_TO_V13,
                        12_345,
                        false),
                new TushareReducedResearchPersistenceGuard
                        .DatabaseIdentityPolicy(purpose));
    }

    private static TushareReducedResearchPersistenceGuard.SchemaState state(
            String schema,
            String searchPath,
            List<String> migrations
    ) {
        return new TushareReducedResearchPersistenceGuard.SchemaState(
                schema, searchPath, migrations);
    }

    private static TushareReducedResearchPersistenceGuard.SchemaState
    identityState(
            String database,
            String user,
            String url,
            String schema,
            String searchPath,
            List<String> migrations,
            int backendPid,
            boolean transactionBound
    ) {
        return new TushareReducedResearchPersistenceGuard.SchemaState(
                database,
                user,
                url,
                schema,
                searchPath,
                migrations,
                backendPid,
                transactionBound);
    }

    private static TushareReducedResearchPersistenceGuard.Verification
    transactionalVerification(int backendPid) {
        return new TushareReducedResearchPersistenceGuard.Verification(
                "stock_quant_test",
                "stock_quant_test",
                safeUrl(),
                TushareReducedResearchPersistenceGuard.DATABASE_PURPOSE,
                SCHEMA,
                SCHEMA,
                V1_TO_V13,
                backendPid,
                true,
                "VERIFIED",
                "VERIFIED");
    }

    private static String safeUrl() {
        return "jdbc:postgresql://127.0.0.1:55432/stock_quant_test";
    }

    private static void assertGuardCode(
            String expected,
            TushareReducedResearchPersistenceGuard guard
    ) {
        TushareReducedResearchPersistenceGuard.GuardException error =
                assertThrows(
                        TushareReducedResearchPersistenceGuard
                                .GuardException.class,
                        guard::verify);
        assertEquals(expected, error.safeCode());
    }
}
