package com.stockquant.server.production;

import com.stockquant.server.QuantServerApplication;
import com.stockquant.server.agent.marketfacts.CompositeSecretProvider;
import com.stockquant.server.agent.marketfacts.SecretProvider;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceBuildProof;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceDataSource;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchPersistenceGuard;
import org.flywaydb.core.Flyway;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Map;

/**
 * Fixed local production entry point. It reads one allow-listed database
 * credential, migrates through Flyway, then starts the API and scheduler.
 */
public final class StockQuantResearchProductionRunner {
    private static final int DATABASE_PORT = 38_432;

    private StockQuantResearchProductionRunner() {
    }

    public static void main(String[] args) {
        if (args.length != 0) {
            fail("M6_ARGUMENTS_FORBIDDEN");
        }
        TushareControlledAcceptanceDataSource dataSource = null;
        ProductionSecretAudit audit = null;
        try {
            audit = ProductionSecretAudit.install();
            var proof = TushareControlledAcceptanceBuildProof
                    .loadCurrentExecutorArtifact();
            if (!proof.m6ProductionEligible()
                    || !TushareControlledAcceptanceBuildProof
                    .M6_RUNNER_START_CLASS.equals(proof.runnerStartClass())) {
                throw new IllegalStateException("M6_BUILD_PROOF_NOT_ELIGIBLE");
            }
            boolean migrationApplied;
            try (SecretProvider provider = CompositeSecretProvider.formalLocal(
                    CompositeSecretProvider.Mode.WINDOWS_CREDENTIAL_MANAGER);
                 SecretProvider.SecretValue secret =
                         provider.readResearchDatabasePassword()) {
                char[] password = secret.copy();
                try {
                    dataSource = new TushareControlledAcceptanceDataSource(
                            DATABASE_PORT,
                            TushareControlledAcceptanceDataSource.SslMode
                                    .DISABLE_LOCAL_ONLY,
                            password);
                } finally {
                    audit.registerAndClear(password);
                }
            }
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            int before = schemaVersion(jdbc);
            if (before != 15 && before != 16) {
                throw new IllegalStateException(
                        "M6_DATABASE_CONTROLLED_START_VERSION_INVALID");
            }
            verifyDedicated(jdbc);
            Flyway.configure().dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .load().migrate();
            verifyDedicated(jdbc);
            int after = schemaVersion(jdbc);
            if (after != 16 || before > after) {
                throw new IllegalStateException("M6_DATABASE_VERSION_INVALID");
            }
            migrationApplied = before < after;
            ProductionRuntimeState.install(new ProductionRuntimeState.Snapshot(
                    proof.gitCommit(), proof.actualArtifactSha256(),
                    Instant.now(), DATABASE_PORT, after, migrationApplied,
                    true));
            TushareControlledAcceptanceDataSource owned = dataSource;
            ConfigurableApplicationContext context =
                    new SpringApplicationBuilder(QuantServerApplication.class)
                    .initializers(applicationContext -> {
                        if (!(applicationContext
                                instanceof GenericApplicationContext generic)) {
                            throw new IllegalStateException(
                                    "M6_APPLICATION_CONTEXT_INVALID");
                        }
                        generic.getEnvironment().getPropertySources().addFirst(
                                new MapPropertySource("m6FixedRuntime",
                                        fixedProperties()));
                        generic.registerBean("dataSource",
                                javax.sql.DataSource.class, () -> owned,
                                definition -> definition.setPrimary(true));
                    })
                    .run();
            ProductionSecretAudit finalAudit = audit;
            context.addApplicationListener(event -> {
                if (event instanceof org.springframework.context.event
                        .ContextClosedEvent) {
                    owned.close();
                    finalAudit.close();
                    ProductionRuntimeState.clear();
                }
            });
            dataSource = null;
            audit = null;
        } catch (Throwable error) {
            if (dataSource != null) {
                dataSource.close();
            }
            if (audit != null) {
                audit.close();
            }
            ProductionRuntimeState.clear();
            fail(ProductionSecretAudit.safeCode(error));
        }
    }

    private static Map<String, Object> fixedProperties() {
        return Map.ofEntries(
                Map.entry("server.address", "127.0.0.1"),
                Map.entry("server.port", "8080"),
                Map.entry("server.shutdown", "graceful"),
                Map.entry("spring.lifecycle.timeout-per-shutdown-phase",
                        "15s"),
                Map.entry("spring.flyway.enabled", "false"),
                Map.entry("quant.jobs.enabled", "false"),
                Map.entry("stockquant.production.enabled", "true"),
                Map.entry("stockquant.shadow-research.scheduler.enabled",
                        "true"),
                Map.entry("stockquant.agent-team.enabled", "false"),
                Map.entry("stockquant.agent-team.shadow.enabled", "false"),
                Map.entry("stockquant.agent-team.shadow.scheduler-enabled",
                        "false"),
                Map.entry("stockquant.announcement.akshare.enabled", "false"),
                Map.entry("stockquant.market-facts.ifind.enabled", "false"),
                Map.entry("stockquant.market-facts.tushare.mode", "DISABLED"),
                Map.entry("stockquant.market-facts.tushare.token", ""),
                Map.entry("stockquant.market-facts.tushare."
                                + "f1e-dedicated-database-purpose",
                        TushareDedicatedResearchPersistenceGuard
                                .DATABASE_PURPOSE));
    }

    private static void verifyDedicated(JdbcTemplate jdbc) {
        new TushareDedicatedResearchPersistenceGuard(jdbc,
                TushareDedicatedResearchPersistenceGuard.DATABASE_PURPOSE)
                .verifyBeforeProvider();
    }

    static int schemaVersion(JdbcTemplate jdbc) {
        Integer value = jdbc.queryForObject("""
                SELECT COALESCE(max(version::integer), 0)
                  FROM tushare_research.flyway_schema_history
                 WHERE success
                """, Integer.class);
        return value == null ? 0 : value;
    }

    private static void fail(String reason) {
        System.err.println("M6_RESEARCH_PRODUCTION_STATUS=FAILED");
        System.err.println("M6_RESEARCH_PRODUCTION_REASON=" + reason);
        System.exit(20);
    }
}
