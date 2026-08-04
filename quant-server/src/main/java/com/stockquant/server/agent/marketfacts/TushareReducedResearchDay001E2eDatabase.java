package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceBuildProof.VerifiedBuildProof;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.Objects;

/** Main V1-V13 bootstrap reachable only from a packaged Day 001 E2E proof. */
final class TushareReducedResearchDay001E2eDatabase {
    private TushareReducedResearchDay001E2eDatabase() {
    }

    static void initialize(
            DataSource dataSource,
            TushareReducedResearchDay001Authorization authorization,
            VerifiedBuildProof proof
    ) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(authorization, "authorization");
        Objects.requireNonNull(proof, "proof").validate();
        if (!authorization.e2eDryRun() || !proof.e2eDryRunEligible()) {
            throw blocked("TUSHARE_REDUCED_RESEARCH_E2E_BUILD_REQUIRED");
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Target target = jdbc.queryForObject("""
                SELECT current_database(), current_user,
                       current_schema(), current_setting('search_path'),
                       COALESCE((SELECT pg_get_userbyid(nspowner)
                                   FROM pg_namespace
                                  WHERE nspname='tushare_research'), ''),
                       (SELECT count(*) FROM pg_class relation
                          JOIN pg_namespace namespace
                            ON namespace.oid=relation.relnamespace
                         WHERE namespace.nspname='tushare_research'),
                       to_regclass('tushare_research.flyway_schema_history')
                         IS NOT NULL,
                       to_regclass(
                         'tushare_research.tushare_controlled_acceptance_execution')
                         IS NOT NULL,
                       to_regclass(
                         'tushare_research.flyway_controlled_acceptance_history')
                         IS NOT NULL
                """, (rs, row) -> new Target(
                rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getInt(6),
                rs.getBoolean(7), rs.getBoolean(8), rs.getBoolean(9)));
        if (target == null || !target.exactIdentity()
                || target.governanceTableExists()
                || target.governanceHistoryExists()) {
            throw blocked("TUSHARE_REDUCED_RESEARCH_E2E_DATABASE_INVALID");
        }
        if (!target.mainHistoryExists()) {
            if (target.schemaObjectCount() != 0) {
                throw blocked("TUSHARE_REDUCED_RESEARCH_E2E_FRESH_DATABASE_REQUIRED");
            }
            Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(TushareDedicatedResearchPersistenceGuard.REQUIRED_SCHEMA)
                    .defaultSchema(
                            TushareDedicatedResearchPersistenceGuard.REQUIRED_SCHEMA)
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(false)
                    .cleanDisabled(true)
                    .load()
                    .migrate();
        }
        new TushareDedicatedResearchPersistenceGuard(
                jdbc, TushareDedicatedResearchPersistenceGuard.DATABASE_PURPOSE)
                .verifyBeforeProvider();
        Boolean governanceAbsent = jdbc.queryForObject("""
                SELECT to_regclass(
                         'tushare_research.tushare_controlled_acceptance_execution')
                         IS NULL
                   AND to_regclass(
                         'tushare_research.flyway_controlled_acceptance_history')
                         IS NULL
                """, Boolean.class);
        if (!Boolean.TRUE.equals(governanceAbsent)) {
            throw blocked("TUSHARE_REDUCED_RESEARCH_GOVERNANCE_TABLE_FORBIDDEN");
        }
    }

    private record Target(
            String database,
            String user,
            String schema,
            String searchPath,
            String schemaOwner,
            int schemaObjectCount,
            boolean mainHistoryExists,
            boolean governanceTableExists,
            boolean governanceHistoryExists
    ) {
        boolean exactIdentity() {
            return TushareDedicatedResearchPersistenceGuard.REQUIRED_DATABASE
                    .equals(database)
                    && TushareDedicatedResearchPersistenceGuard.REQUIRED_USER
                    .equals(user)
                    && TushareDedicatedResearchPersistenceGuard.REQUIRED_SCHEMA
                    .equals(schema)
                    && TushareDedicatedResearchPersistenceGuard.REQUIRED_SCHEMA
                    .equals(searchPath)
                    && TushareDedicatedResearchPersistenceGuard.REQUIRED_USER
                    .equals(schemaOwner);
        }
    }

    private static IllegalStateException blocked(String code) {
        return new IllegalStateException(code);
    }
}
