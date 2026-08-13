package com.stockquant.server.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.production.SystemHealthModels.BackupManifest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Local metadata/fact backup. Credential material is structurally excluded. */
@Service
@ConditionalOnProperty(prefix = "stockquant.production", name = "enabled",
        havingValue = "true")
public class LocalResearchBackupService {
    private static final DateTimeFormatter ID = DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final List<String> TABLES = List.of(
            "market_data_dataset_versions", "security_status_events",
            "security_status_history", "trading_calendar_revisions",
            "security_identity_registry", "source_security_identity_mappings",
            "daily_bar_observations",
            "pit_market_fact_batches", "pit_market_fact_observations",
            "raw_daily_bar_facts_v2", "adjustment_factor_facts_v1",
            "trading_calendar_facts_v1", "agent_tasks", "agent_runs",
            "agent_evidence", "agent_vetoes", "agent_decisions",
            "shadow_research_runs", "shadow_research_snapshots",
            "shadow_scheduler_dispatches", "shadow_paper_portfolios",
            "shadow_paper_positions", "shadow_paper_orders",
            "shadow_paper_fills", "shadow_portfolio_snapshots",
            "shadow_outcomes", "agent_evaluation_versions",
            "agent_evaluation_reports", "agent_evaluation_decisions",
            "external_api_monthly_usage_ledger",
            "research_selection_runs",
            "flyway_schema_history");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Path backupRoot;

    @Autowired
    public LocalResearchBackupService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this(jdbc, mapper, repositoryRoot().resolve(
                "quant-server/target/stock-quant-production/backups"));
    }

    LocalResearchBackupService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            Path backupRoot
    ) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.backupRoot = backupRoot.toAbsolutePath().normalize();
    }

    @Transactional(readOnly = true)
    public BackupManifest create() {
        ProductionRuntimeState.Snapshot runtime = ProductionRuntimeState
                .require();
        Instant now = Instant.now();
        String backupId = "SQBACKUP_" + ID.format(now) + "_"
                + runtime.gitCommit().substring(0, 12).toUpperCase();
        Path temporary = null;
        try {
            Files.createDirectories(backupRoot);
            Path archive = backupRoot.resolve(backupId + ".zip").normalize();
            if (!archive.getParent().equals(backupRoot)
                    || Files.exists(archive)) {
                throw new IllegalStateException("M6_BACKUP_PATH_INVALID");
            }
            Map<String, Long> rows = new LinkedHashMap<>();
            temporary = backupRoot.resolve("." + backupId + ".tmp");
            try (OutputStream output = Files.newOutputStream(temporary);
                 ZipOutputStream zip = new ZipOutputStream(
                         new BufferedOutputStream(output),
                         StandardCharsets.UTF_8)) {
                for (String table : TABLES) {
                    long count = writeTable(zip, table);
                    rows.put(table, count);
                }
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("contract", "LOCAL_BACKUP_V1");
                metadata.put("backupId", backupId);
                metadata.put("createdAt", now);
                metadata.put("gitCommit", runtime.gitCommit());
                metadata.put("schemaVersion", runtime.schemaVersion());
                metadata.put("tableRows", rows);
                metadata.put("secretsIncluded", false);
                metadata.put("realTradingIncluded", false);
                writeEntry(zip, "manifest.json",
                        mapper.writeValueAsBytes(metadata));
            }
            Files.move(temporary, archive);
            String hash = sha256(archive);
            BackupManifest manifest = new BackupManifest("LOCAL_BACKUP_V1",
                    backupId, now, runtime.gitCommit(), runtime.schemaVersion(),
                    Map.copyOf(rows), hash, archive.toString(), false, false);
            Path manifestPath = backupRoot.resolve(backupId + ".manifest.json");
            Files.writeString(manifestPath,
                    mapper.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(manifest) + "\n",
                    StandardCharsets.UTF_8);
            return manifest;
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("M6_BACKUP_WRITE_FAILED");
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The original result remains authoritative.
                }
            }
        }
    }

    private long writeTable(ZipOutputStream zip, String table)
            throws IOException {
        MessageDigest digest = digest();
        long[] count = {0};
        zip.putNextEntry(new ZipEntry("data/" + table + ".jsonl"));
        jdbc.query("SELECT * FROM " + table + " ORDER BY 1", row -> {
            try {
                byte[] value = mapper.writeValueAsBytes(row(row));
                zip.write(value);
                zip.write('\n');
                digest.update(value);
                digest.update((byte) '\n');
                count[0]++;
            } catch (IOException error) {
                throw new IllegalStateException("M6_BACKUP_WRITE_FAILED");
            }
        });
        zip.closeEntry();
        writeEntry(zip, "checksums/" + table + ".sha256",
                (HexFormat.of().formatHex(digest.digest()) + "\n")
                        .getBytes(StandardCharsets.UTF_8));
        return count[0];
    }

    private static Map<String, Object> row(ResultSet result)
            throws SQLException {
        ResultSetMetaData metadata = result.getMetaData();
        Map<String, Object> value = new LinkedHashMap<>();
        for (int index = 1; index <= metadata.getColumnCount(); index++) {
            Object cell = result.getObject(index);
            value.put(metadata.getColumnLabel(index), normalize(cell));
        }
        return value;
    }

    private static Object normalize(Object cell) throws SQLException {
        if (cell instanceof byte[] bytes) {
            return HexFormat.of().formatHex(bytes);
        }
        if (cell instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (cell instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        if (cell instanceof java.sql.Time time) {
            return time.toLocalTime();
        }
        if (cell instanceof java.sql.Array array) {
            return array.getArray();
        }
        if (cell != null && "org.postgresql.util.PGobject".equals(
                cell.getClass().getName())) {
            return cell.toString();
        }
        return cell;
    }

    private static void writeEntry(
            ZipOutputStream zip,
            String name,
            byte[] content
    ) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest = digest();
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("M6_BACKUP_HASH_UNAVAILABLE");
        }
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        for (int depth = 0; current != null && depth < 6;
             current = current.getParent(), depth++) {
            if (Files.isDirectory(current.resolve(".git"))
                    && Files.isDirectory(current.resolve("quant-server"))) {
                return current;
            }
        }
        throw new IllegalStateException("M6_REPOSITORY_ROOT_INVALID");
    }
}
