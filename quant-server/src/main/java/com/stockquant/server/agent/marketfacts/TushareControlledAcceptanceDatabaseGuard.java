package com.stockquant.server.agent.marketfacts;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;

/**
 * Explicit governance-migration and runtime guard for F1F controlled acceptance.
 *
 * <p>This type is deliberately not a Spring bean. The future dedicated F1F-B2
 * process must call {@link #migrateGovernance(DataSource, String)} before it
 * constructs the executor. Normal application Flyway never includes the
 * controlled-acceptance location.</p>
 */
public final class TushareControlledAcceptanceDatabaseGuard {
    static final String GOVERNANCE_LOCATION = "classpath:db/controlled-acceptance";
    static final String GOVERNANCE_HISTORY_TABLE =
            "flyway_controlled_acceptance_history";
    static final List<String> GOVERNANCE_MIGRATIONS = List.of("13", "14");

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
            String databasePurpose
    ) {
        Objects.requireNonNull(dataSource, "dataSource");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TushareDedicatedResearchPersistenceGuard base =
                new TushareDedicatedResearchPersistenceGuard(jdbc, databasePurpose);
        TushareDedicatedResearchPersistenceGuard.Verification before =
                base.verifyBeforeProvider();
        requireGovernanceStateBeforeMigration(jdbc);

        Flyway.configure()
                .dataSource(dataSource)
                .schemas(TushareDedicatedResearchPersistenceGuard.REQUIRED_SCHEMA)
                .defaultSchema(TushareDedicatedResearchPersistenceGuard.REQUIRED_SCHEMA)
                .table(GOVERNANCE_HISTORY_TABLE)
                .locations(GOVERNANCE_LOCATION)
                .target(MigrationVersion.fromVersion("14"))
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("13"))
                .baselineDescription("verified dedicated V1-V13 base")
                .outOfOrder(false)
                .validateOnMigrate(true)
                .cleanDisabled(true)
                .load()
                .migrate();

        ControlledVerification after =
                new TushareControlledAcceptanceDatabaseGuard(jdbc, base)
                        .verifyBeforeProvider();
        base.verifySameTarget(before, after.baseVerification());
        return after;
    }

    public ControlledVerification verifyBeforeProvider() {
        TushareDedicatedResearchPersistenceGuard.Verification base =
                baseGuard.verifyBeforeProvider();
        List<String> versions;
        int failed;
        try {
            versions = jdbc.queryForList("""
                    SELECT version
                      FROM tushare_research.flyway_controlled_acceptance_history
                     WHERE success
                     ORDER BY installed_rank
                    """, String.class);
            Integer failedValue = jdbc.queryForObject("""
                    SELECT count(*)
                      FROM tushare_research.flyway_controlled_acceptance_history
                     WHERE NOT success
                    """, Integer.class);
            failed = failedValue == null ? -1 : failedValue;
        } catch (RuntimeException error) {
            throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_GOVERNANCE_MIGRATION_REQUIRED");
        }
        if (!GOVERNANCE_MIGRATIONS.equals(versions) || failed != 0
                || !requiredObjectsPresent()) {
            throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_GOVERNANCE_SCHEMA_INVALID");
        }
        return new ControlledVerification(
                base, versions, GOVERNANCE_HISTORY_TABLE, 14);
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

    private static void requireGovernanceStateBeforeMigration(JdbcTemplate jdbc) {
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
        }
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
