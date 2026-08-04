package com.stockquant.server.agent.marketfacts;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Explicit governance-migration and runtime guard for F1F controlled acceptance.
 *
 * <p>This type is deliberately not a Spring bean. The future dedicated F1F-B2
 * process must call {@link #migrateGovernance(DataSource, String,
 * TushareControlledAcceptanceAuthorization,
 * TushareControlledAcceptanceBuildProof.VerifiedBuildProof)} before it
 * constructs the executor. Normal application Flyway never includes the
 * controlled-acceptance location.</p>
 */
public final class TushareControlledAcceptanceDatabaseGuard {
    static final String GOVERNANCE_LOCATION = "classpath:db/controlled-acceptance";
    static final String GOVERNANCE_HISTORY_TABLE =
            "flyway_controlled_acceptance_history";
    static final List<String> GOVERNANCE_MIGRATIONS = List.of("13", "14");
    static final List<GovernanceHistoryEntry> GOVERNANCE_HISTORY = List.of(
            new GovernanceHistoryEntry("13", "BASELINE",
                    "explicit verified dedicated V1-V13 base "
                            + TushareControlledAcceptanceExecution.RULE_VERSION),
            new GovernanceHistoryEntry("14", "SQL",
                    "V14__tushare_controlled_acceptance_execution.sql"));

    private final JdbcTemplate jdbc;
    private final TushareDedicatedResearchPersistenceGuard baseGuard;

    public TushareControlledAcceptanceDatabaseGuard(
            JdbcTemplate jdbc,
            TushareDedicatedResearchPersistenceGuard baseGuard
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.baseGuard = Objects.requireNonNull(baseGuard, "baseGuard");
    }

    /**
     * The only production migration entry for governance V14. Target identity
     * and the exact V1-V13 base are verified before Flyway can issue DDL.
     */
    public static ControlledVerification migrateGovernance(
            DataSource dataSource,
            String databasePurpose,
            TushareControlledAcceptanceAuthorization authorization,
            TushareControlledAcceptanceBuildProof.VerifiedBuildProof buildProof
    ) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(authorization, "authorization").validateFrozen();
        Objects.requireNonNull(buildProof, "buildProof").validate();
        boolean proofEligible = (buildProof.governanceEligible()
                && authorization.userApproval()
                == TushareControlledAcceptanceAuthorization.UserApproval.CONFIRMED)
                || (buildProof.e2eDryRunEligible()
                && authorization.userApproval()
                == TushareControlledAcceptanceAuthorization.UserApproval.E2E_DRY_RUN);
        if (!proofEligible
                || !authorization.codeBaselineCommit().equals(buildProof.gitCommit())
                || !authorization.artifactSha256().equals(
                buildProof.actualArtifactSha256())) {
            throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_BUILD_PROOF_INVALID");
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TushareDedicatedResearchPersistenceGuard base =
                new TushareDedicatedResearchPersistenceGuard(jdbc, databasePurpose);
        PreMigrationVerification before = performGuardedGovernanceInitialization(
                () -> {
                    var baseVerification = base.verifyBeforeProvider();
                    requireExactMainHistory(jdbc);
                    return new PreMigrationVerification(
                            baseVerification,
                            requireGovernanceStateBeforeMigration(jdbc));
                },
                ignored -> flywayOperations(dataSource));

        ControlledVerification after =
                new TushareControlledAcceptanceDatabaseGuard(jdbc, base)
                        .verifyBeforeProvider();
        base.verifySameTarget(before.baseVerification(), after.baseVerification());
        return after;
    }

    private static GovernanceOperations flywayOperations(DataSource dataSource) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .schemas(TushareDedicatedResearchPersistenceGuard.REQUIRED_SCHEMA)
                .defaultSchema(TushareDedicatedResearchPersistenceGuard.REQUIRED_SCHEMA)
                .table(GOVERNANCE_HISTORY_TABLE)
                .locations(GOVERNANCE_LOCATION)
                .target(MigrationVersion.fromVersion("14"))
                .baselineOnMigrate(false)
                .baselineVersion(MigrationVersion.fromVersion("13"))
                .baselineDescription("explicit verified dedicated V1-V13 base "
                        + TushareControlledAcceptanceExecution.RULE_VERSION)
                .outOfOrder(false)
                .validateOnMigrate(true)
                .cleanDisabled(true)
                .load();
        if (flyway.getConfiguration().isBaselineOnMigrate()) {
            throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_AUTOMATIC_BASELINE_FORBIDDEN");
        }
        return new GovernanceOperations() {
            @Override
            public void baseline() {
                flyway.baseline();
            }

            @Override
            public void migrate() {
                flyway.migrate();
            }
        };
    }

    public ControlledVerification verifyBeforeProvider() {
        TushareDedicatedResearchPersistenceGuard.Verification base =
                baseGuard.verifyBeforeProvider();
        requireExactMainHistory(jdbc);
        List<GovernanceHistoryEntry> history;
        int failed;
        try {
            history = governanceHistory(jdbc);
            Integer failedValue = jdbc.queryForObject("""
                    SELECT count(*)
                      FROM tushare_research.flyway_controlled_acceptance_history
                     WHERE NOT success
                    """, Integer.class);
            failed = failedValue == null ? -1 : failedValue;
        } catch (RuntimeException error) {
            throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_GOVERNANCE_MIGRATION_REQUIRED");
        }
        if (!GOVERNANCE_HISTORY.equals(history) || failed != 0
                || !requiredObjectsPresent()) {
            throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_GOVERNANCE_SCHEMA_INVALID");
        }
        return new ControlledVerification(
                base, history.stream().map(GovernanceHistoryEntry::version).toList(),
                GOVERNANCE_HISTORY_TABLE, 14);
    }

    private boolean requiredObjectsPresent() {
        Boolean present = jdbc.queryForObject("""
                SELECT to_regclass(
                         'tushare_research.tushare_controlled_acceptance_execution') IS NOT NULL
                   AND to_regclass(
                         'tushare_research.tushare_controlled_acceptance_transition') IS NOT NULL
                   AND EXISTS (
                         SELECT 1 FROM pg_trigger
                          WHERE tgrelid =
                            'tushare_research.tushare_controlled_acceptance_execution'::regclass
                            AND tgname = 'trg_tca_transition_guard'
                            AND NOT tgisinternal)
                   AND EXISTS (
                         SELECT 1 FROM pg_trigger
                          WHERE tgrelid =
                            'tushare_research.tushare_controlled_acceptance_transition'::regclass
                            AND tgname = 'trg_tca_transition_immutable'
                            AND NOT tgisinternal)
                """, Boolean.class);
        return Boolean.TRUE.equals(present);
    }

    private static GovernanceState requireGovernanceStateBeforeMigration(JdbcTemplate jdbc) {
        Boolean historyExists = jdbc.queryForObject("""
                SELECT to_regclass(
                  'tushare_research.flyway_controlled_acceptance_history') IS NOT NULL
                """, Boolean.class);
        if (!Boolean.TRUE.equals(historyExists)) {
            Boolean acceptanceObjectsExist = jdbc.queryForObject("""
                    SELECT to_regclass(
                             'tushare_research.tushare_controlled_acceptance_execution') IS NOT NULL
                        OR to_regclass(
                             'tushare_research.tushare_controlled_acceptance_transition') IS NOT NULL
                    """, Boolean.class);
            if (Boolean.TRUE.equals(acceptanceObjectsExist)) {
                throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_UNTRACKED_SCHEMA_OBJECTS");
            }
            return GovernanceState.ABSENT;
        }
        List<GovernanceHistoryEntry> history = governanceHistory(jdbc);
        Integer failed = jdbc.queryForObject("""
                SELECT count(*)
                  FROM tushare_research.flyway_controlled_acceptance_history
                 WHERE NOT success
                """, Integer.class);
        if (failed == null || failed != 0
                || !history.equals(GOVERNANCE_HISTORY.subList(0, 1))
                && !history.equals(GOVERNANCE_HISTORY)) {
            throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_GOVERNANCE_HISTORY_INVALID");
        }
        return history.equals(GOVERNANCE_HISTORY)
                ? GovernanceState.COMPLETE : GovernanceState.BASELINED;
    }

    private static void requireExactMainHistory(JdbcTemplate jdbc) {
        try {
            Integer failed = jdbc.queryForObject("""
                    SELECT count(*)
                      FROM tushare_research.flyway_schema_history
                     WHERE NOT success
                    """, Integer.class);
            if (failed == null || failed != 0) {
                throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_MAIN_HISTORY_INVALID");
            }
        } catch (IllegalStateException error) {
            throw error;
        } catch (RuntimeException error) {
            throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_MAIN_HISTORY_INVALID");
        }
    }

    private static List<GovernanceHistoryEntry> governanceHistory(JdbcTemplate jdbc) {
        return jdbc.query("""
                SELECT version, type, script
                  FROM tushare_research.flyway_controlled_acceptance_history
                 WHERE success
                 ORDER BY installed_rank
                """, (row, ignored) -> new GovernanceHistoryEntry(
                row.getString("version"), row.getString("type"),
                row.getString("script")));
    }

    enum GovernanceState {
        ABSENT,
        BASELINED,
        COMPLETE
    }

    record GovernanceHistoryEntry(String version, String type, String script) {
        GovernanceHistoryEntry {
            version = Objects.requireNonNull(version, "version");
            type = Objects.requireNonNull(type, "type");
            script = Objects.requireNonNull(script, "script");
        }
    }

    static PreMigrationVerification performGuardedGovernanceInitialization(
            Supplier<PreMigrationVerification> verifier,
            Function<PreMigrationVerification, GovernanceOperations> operationFactory
    ) {
        PreMigrationVerification verified = Objects.requireNonNull(
                Objects.requireNonNull(verifier, "verifier").get(), "verified");
        GovernanceOperations operations = Objects.requireNonNull(
                Objects.requireNonNull(operationFactory, "operationFactory")
                        .apply(verified), "operations");
        if (verified.governanceState() == GovernanceState.ABSENT) {
            operations.baseline();
        }
        operations.migrate();
        return verified;
    }

    record PreMigrationVerification(
            TushareDedicatedResearchPersistenceGuard.Verification baseVerification,
            GovernanceState governanceState
    ) {
        PreMigrationVerification {
            Objects.requireNonNull(baseVerification, "baseVerification");
            Objects.requireNonNull(governanceState, "governanceState");
        }
    }

    interface GovernanceOperations {
        void baseline();

        void migrate();
    }

    public record ControlledVerification(
            TushareDedicatedResearchPersistenceGuard.Verification baseVerification,
            List<String> governanceMigrations,
            String governanceHistoryTable,
            int controlledSchemaVersion
    ) {
        public ControlledVerification {
            baseVerification = Objects.requireNonNull(
                    baseVerification, "baseVerification");
            governanceMigrations = List.copyOf(Objects.requireNonNull(
                    governanceMigrations, "governanceMigrations"));
            governanceHistoryTable = Objects.requireNonNull(
                    governanceHistoryTable, "governanceHistoryTable");
            if (!TushareDedicatedResearchPersistenceGuard.REQUIRED_MIGRATIONS.equals(
                    baseVerification.appliedMigrations())
                    || !GOVERNANCE_MIGRATIONS.equals(governanceMigrations)
                    || !GOVERNANCE_HISTORY_TABLE.equals(governanceHistoryTable)
                    || controlledSchemaVersion != 14) {
                throw new IllegalArgumentException(
                        "TUSHARE_CONTROLLED_ACCEPTANCE_DATABASE_VERIFICATION_INVALID");
            }
        }
    }

    private static IllegalStateException blocked(String code) {
        return new IllegalStateException(code);
    }
}
