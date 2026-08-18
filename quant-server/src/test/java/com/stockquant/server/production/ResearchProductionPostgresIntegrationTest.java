package com.stockquant.server.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchProductionPostgresIntegrationTest {
    private static DriverManagerDataSource dataSource;

    @BeforeAll
    static void database() {
        String url = System.getenv("STOCK_QUANT_M5_TEST_JDBC_URL");
        String user = System.getenv("STOCK_QUANT_M5_TEST_DB_USER");
        String password = System.getenv("STOCK_QUANT_M5_TEST_DB_PASSWORD");
        if (url == null || user == null || password == null) return;
        dataSource = new DriverManagerDataSource(url, user, password);
        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration").load().migrate();
    }

    @Test
    void migratesV1ThroughV18AndCreatesSecretFreeReadOnlyBackup(
            @TempDir Path backupRoot
    ) throws Exception {
        Assumptions.assumeTrue(dataSource != null);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertEquals(18, StockQuantResearchProductionRunner
                .schemaVersion(jdbc));
        long immutableRunsBefore = count(jdbc, "shadow_research_runs");
        long immutableReportsBefore = count(jdbc,
                "agent_evaluation_reports");
        ProductionRuntimeState.install(new ProductionRuntimeState.Snapshot(
                "a".repeat(40), "b".repeat(64), Instant.now(), 38_432,
                18, true, true));
        try {
            var service = new LocalResearchBackupService(jdbc,
                    new ObjectMapper().findAndRegisterModules(), backupRoot);
            var manifest = service.create();
            assertEquals("LOCAL_BACKUP_V1", manifest.contract());
            assertEquals(18, manifest.schemaVersion());
            assertFalse(manifest.secretsIncluded());
            assertFalse(manifest.immutableShadowChanged());
            assertTrue(Files.isRegularFile(Path.of(manifest.archivePath())));
            assertTrue(manifest.archiveSha256().matches("[0-9a-f]{64}"));
            try (ZipFile archive = new ZipFile(manifest.archivePath())) {
                assertNotNull(archive.getEntry("manifest.json"));
                assertNotNull(archive.getEntry(
                        "data/shadow_research_runs.jsonl"));
                assertNotNull(archive.getEntry(
                        "data/agent_evaluation_reports.jsonl"));
                assertNotNull(archive.getEntry(
                        "data/research_selection_runs.jsonl"));
                assertNotNull(archive.getEntry(
                        "data/research_universe_snapshots.jsonl"));
                assertNotNull(archive.getEntry(
                        "data/research_universe_snapshot_observations.jsonl"));
                assertNotNull(archive.getEntry(
                        "data/research_selection_member_results.jsonl"));
            }
            assertEquals(immutableRunsBefore,
                    count(jdbc, "shadow_research_runs"));
            assertEquals(immutableReportsBefore,
                    count(jdbc, "agent_evaluation_reports"));
            try (var files = Files.list(backupRoot)) {
                assertTrue(files.noneMatch(path -> path.getFileName()
                        .toString().endsWith(".tmp")));
            }
        } finally {
            ProductionRuntimeState.clear();
        }
    }

    private static long count(JdbcTemplate jdbc, String table) {
        Long value = jdbc.queryForObject("SELECT count(*) FROM " + table,
                Long.class);
        return value == null ? 0 : value;
    }
}
