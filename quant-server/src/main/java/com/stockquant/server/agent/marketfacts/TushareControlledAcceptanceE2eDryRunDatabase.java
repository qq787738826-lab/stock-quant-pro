package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceBuildProof.VerifiedBuildProof;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.Objects;

/** Fresh-database bootstrap that is reachable only from an E2E dry-run build. */
final class TushareControlledAcceptanceE2eDryRunDatabase {
    private TushareControlledAcceptanceE2eDryRunDatabase() {
    }

    static void initialize(
            DataSource dataSource,
            TushareControlledAcceptanceAuthorization authorization,
            VerifiedBuildProof proof
    ) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(authorization, "authorization");
        Objects.requireNonNull(proof, "proof").validate();
        if (!proof.e2eDryRunEligible()
                || authorization.userApproval()
                != TushareControlledAcceptanceAuthorization.UserApproval.E2E_DRY_RUN) {
            throw blocked("TUSHARE_E2E_DRY_RUN_BUILD_REQUIRED");
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        FreshTarget target = jdbc.queryForObject("""
                SELECT current_database(), current_user,
                       to_regnamespace('tushare_research') IS NOT NULL,
                       to_regclass('tushare_research.flyway_schema_history') IS NOT NULL,
                       to_regclass(
                         'tushare_research.tushare_controlled_acceptance_execution')
                         IS NOT NULL
                """, (rs, row) -> new FreshTarget(
                rs.getString(1), rs.getString(2), rs.getBoolean(3),
                rs.getBoolean(4), rs.getBoolean(5)));
        if (target == null
                || !TushareDedicatedResearchPersistenceGuard.REQUIRED_DATABASE
                .equals(target.database())
                || !TushareDedicatedResearchPersistenceGuard.REQUIRED_USER
                .equals(target.user())
                || target.schemaExists() || target.mainHistoryExists()
                || target.governanceTableExists()) {
            throw blocked("TUSHARE_E2E_DRY_RUN_FRESH_DATABASE_REQUIRED");
        }
        Flyway.configure()
                .dataSource(dataSource)
                .schemas(TushareDedicatedResearchPersistenceGuard.REQUIRED_SCHEMA)
                .defaultSchema(TushareDedicatedResearchPersistenceGuard.REQUIRED_SCHEMA)
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                .load()
                .migrate();
        TushareControlledAcceptanceDatabaseGuard.migrateGovernance(
                dataSource,
                TushareDedicatedResearchPersistenceGuard.DATABASE_PURPOSE,
                authorization,
                proof);
    }

    private record FreshTarget(
            String database,
            String user,
            boolean schemaExists,
            boolean mainHistoryExists,
            boolean governanceTableExists
    ) {
    }

    private static IllegalStateException blocked(String code) {
        return new IllegalStateException(code);
    }
}
