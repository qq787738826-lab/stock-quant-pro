package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.Objects;

/** Explicit component whitelist for the non-Spring F1F-B2 process. */
final class TushareControlledAcceptanceComponents implements AutoCloseable {
    private final TushareDedicatedResearchRuntimeComponents runtime;
    private final TushareControlledAcceptanceExecutor executor;

    private TushareControlledAcceptanceComponents(
            TushareDedicatedResearchRuntimeComponents runtime,
            TushareControlledAcceptanceExecutor executor
    ) {
        this.runtime = runtime;
        this.executor = executor;
    }

    static TushareControlledAcceptanceComponents create(
            DataSource dataSource,
            char[] token,
            Clock clock
    ) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(clock, "clock");
        return assembleSafely(dataSource, clock,
                TushareDedicatedResearchRuntimeComponents.create(
                        dataSource, token, clock));
    }

    static TushareControlledAcceptanceComponents createE2eDryRun(
            DataSource dataSource,
            Clock clock
    ) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(clock, "clock");
        return assembleSafely(dataSource, clock,
                TushareDedicatedResearchRuntimeComponents.createE2eDryRun(
                        dataSource, clock));
    }

    private static TushareControlledAcceptanceComponents assembleSafely(
            DataSource dataSource,
            Clock clock,
            TushareDedicatedResearchRuntimeComponents runtime
    ) {
        try {
            return assemble(dataSource, clock, runtime);
        } catch (RuntimeException | Error failure) {
            runtime.close();
            throw failure;
        }
    }

    private static TushareControlledAcceptanceComponents assemble(
            DataSource dataSource,
            Clock clock,
            TushareDedicatedResearchRuntimeComponents runtime
    ) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        DataSourceTransactionManager transactions =
                new DataSourceTransactionManager(dataSource);
        ObjectMapper mapper = runtime.objectMapper();
        TushareControlledAcceptanceDatabaseGuard controlledGuard =
                new TushareControlledAcceptanceDatabaseGuard(
                        jdbc,
                        new TushareDedicatedResearchPersistenceGuard(
                                jdbc,
                                TushareDedicatedResearchPersistenceGuard
                                        .DATABASE_PURPOSE));
        TushareControlledAcceptanceExecutionRepository executions =
                new TushareControlledAcceptanceExecutionRepository(
                        jdbc, mapper, transactions, clock);
        TushareControlledAcceptanceEvaluator evaluator =
                new TushareControlledAcceptanceEvaluator(mapper);
        return new TushareControlledAcceptanceComponents(
                runtime,
                new TushareControlledAcceptanceExecutor(
                        executions, controlledGuard, runtime.batchService(),
                        runtime.readbackService(), evaluator, clock));
    }

    TushareControlledAcceptanceExecutor executor() {
        return executor;
    }

    @Override
    public void close() {
        runtime.close();
    }

    @Override
    public String toString() {
        return "TushareControlledAcceptanceComponents[WHITELISTED]";
    }
}
