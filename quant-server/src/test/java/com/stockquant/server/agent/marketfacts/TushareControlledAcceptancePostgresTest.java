package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceAuthorization.ControlledEndpoint;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.ExecutionSource;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.ExecutionStatus;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.Reservation;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "F1F_B1_POSTGRES_JDBC_URL", matches = ".+")
class TushareControlledAcceptancePostgresTest {
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(System.getenv("F1F_B1_POSTGRES_JDBC_URL"));
        source.setUser(System.getenv("F1F_B1_POSTGRES_USER"));
        dataSource = source;
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE SCHEMA tushare_research AUTHORIZATION stock_quant_research");
        Flyway.configure().dataSource(dataSource)
                .schemas("tushare_research").defaultSchema("tushare_research")
                .locations("classpath:db/migration", "classpath:db/controlled-acceptance")
                .load().migrate();
    }

    @AfterAll
    static void clean() {
        if (jdbc != null) {
            jdbc.execute("DROP SCHEMA tushare_research CASCADE");
        }
    }

    @Test
    void migrationReservationConcurrencyRecoveryAndStateConstraintAreDurable() throws Exception {
        var repository = repository();
        Reservation reservation = reservation("F1FB1_PG_0001");
        var executor = Executors.newFixedThreadPool(2);
        try {
            var results = executor.invokeAll(java.util.List.<Callable<Boolean>>of(
                    () -> reserve(repository, reservation),
                    () -> reserve(repository, reservation)));
            assertEquals(1, results.stream().filter(value -> {
                try { return value.get(); } catch (Exception e) { return false; }
            }).count());
        } finally {
            executor.shutdownNow();
        }
        assertEquals(ExecutionStatus.RESERVED,
                repository.find("F1FB1_PG_0001").orElseThrow().status());
        repository.markRunning("F1FB1_PG_0001");
        assertEquals(1, repository.recoverIncompleteExecutions());
        assertEquals(ExecutionStatus.INTERRUPTED,
                repository.find("F1FB1_PG_0001").orElseThrow().status());
        assertThrows(Exception.class, () -> jdbc.update("""
                UPDATE tushare_controlled_acceptance_execution
                   SET status='RESERVED', row_version=row_version+1
                 WHERE acceptance_id='F1FB1_PG_0001'
                """));
        assertThrows(IllegalStateException.class, () -> repository.reserve(reservation));
        assertEquals(java.util.List.of(ExecutionStatus.AUTHORIZED,
                        ExecutionStatus.RESERVED, ExecutionStatus.RUNNING,
                        ExecutionStatus.INTERRUPTED),
                repository.history("F1FB1_PG_0001").stream()
                        .map(TushareControlledAcceptanceExecution.Transition::to).toList());
        assertEquals(java.util.List.of("1","2","3","4","5","6","7","8","9",
                        "10","11","12","13","14"),
                jdbc.queryForList("SELECT version FROM flyway_schema_history "
                        + "WHERE success ORDER BY installed_rank", String.class));
    }

    private static boolean reserve(
            TushareControlledAcceptanceExecutionRepository repository,
            Reservation reservation
    ) {
        try {
            repository.reserve(reservation);
            return true;
        } catch (IllegalStateException expected) {
            return false;
        }
    }

    private static TushareControlledAcceptanceExecutionRepository repository() {
        return new TushareControlledAcceptanceExecutionRepository(
                jdbc, new ObjectMapper().findAndRegisterModules(),
                new DataSourceTransactionManager(dataSource),
                Clock.fixed(Instant.parse("2026-08-01T01:00:00Z"), ZoneOffset.UTC));
    }

    private static Reservation reservation(String id) {
        return new Reservation(id, "b".repeat(64), ExecutionSource.TEST,
                TushareMarketFactProvider.PROVIDER_CODE, "600000.SH",
                LocalDate.of(2025, 1, 2), Set.of(ControlledEndpoint.DAILY,
                ControlledEndpoint.ADJ_FACTOR, ControlledEndpoint.TRADE_CAL),
                "f68d84403ebb82babe92a1cb0f78d845ed39547a", "a".repeat(64),
                "stock_quant_research", "stock_quant_research", "tushare_research",
                14, Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-08-01T02:00:00Z"));
    }
}
