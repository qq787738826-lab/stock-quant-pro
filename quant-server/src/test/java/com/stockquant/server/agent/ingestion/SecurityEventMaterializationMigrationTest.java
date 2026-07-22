package com.stockquant.server.agent.ingestion;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityEventMaterializationMigrationTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
    private static final Path V8 = MIGRATIONS.resolve(
            "V8__security_event_materialization_foundation.sql");
    private static final Path LEGACY_EVENT_REPOSITORY = Path.of(
            "src/main/java/com/stockquant/server/agent/temporal/SecurityStatusEventRepository.java");

    @Test
    void v8ContainsOnlyFrozenTestDemoEventMaterializationFoundation() throws IOException {
        String sql = Files.readString(V8).toLowerCase(Locale.ROOT);
        assertEquals(4, count(sql, "create table "));
        for (String table : new String[]{
                "security_identity_registry", "source_security_identity_mappings",
                "security_status_normalization_results", "security_status_event_lineage"}) {
            assertTrue(sql.contains("create table " + table));
        }
        assertTrue(sql.contains("manifest_contract_version varchar(80) not null"));
        assertTrue(sql.contains("event lineage requires the frozen v1 materialization rules"));
        assertTrue(sql.contains("normalization requires the frozen v1 materialization contracts"));
        assertTrue(sql.contains("ingestion_manifest_v2_security_event"));
        assertTrue(sql.contains("security_status_raw_test_v1"));
        assertTrue(sql.contains("security_status_event_v1"));
        assertTrue(sql.contains("deferrable initially deferred"));
        assertTrue(sql.contains("event lineage requires an open test/demo manifest v2 run"));
        assertTrue(sql.contains("normalization result requires an open attached manifest v2"));
        assertTrue(sql.contains("on delete restrict"));
        assertTrue(sql.contains("before truncate"));
        assertFalse(sql.contains("create extension"));
        assertTrue(sql.contains("before insert on security_status_history"));
        assertTrue(sql.contains("v8 resolved event must wait for stage 2d-2b-2 history projector"));
        assertTrue(sql.contains("and event_logical_key is null"));
        assertTrue(sql.contains("current chain-head state"));
        assertTrue(sql.contains("lineage_row.raw_record_id <> raw_row.id"));
        assertFalse(sql.contains("trading_calendar_revisions("));
        assertFalse(sql.contains("market_universe_snapshot"));
    }

    @Test
    void v1ThroughV7MigrationBytesRemainFrozen() throws Exception {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("V1__init.sql", "ce63d03bfa32ac2ba94ac5cbdcc965020b783abfb1b06d3f148776b8417f9fc6");
        expected.put("V2__market_data_center.sql", "e2ea6e039c2688ad65a776ee6b1e1140e126c24f6968bf6cd50624a03c5239da");
        expected.put("V3__selection_validation.sql", "db330ed855b4cc5ff3ae66436b6c992f16edbe9646d6ca19d40be4931105d484");
        expected.put("V4__simulated_trading.sql", "75f1f46ed2f3a0a2e74853b30356c84c6a16dc0d13cba6fa7db40d20cc541631");
        expected.put("V5__agent_team.sql", "571c75de267081422922b93334a5a172c9102f66b435810cf9b6a4af7a337fb1");
        expected.put("V6__temporal_market_foundation.sql", "1bddfbff8130281127ec4e19f1504673cf0c1dce964b4c8316b2c70107d798ac");
        expected.put("V7__market_data_ingestion_foundation.sql", "036a0a2f45214ae7fa03ae5d51ccddc7f3323b339eedde8bd26b6164fd1c43d4");
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            assertEquals(entry.getValue(), sha256(Files.readAllBytes(MIGRATIONS.resolve(entry.getKey()))),
                    entry.getKey());
        }
    }

    @Test
    void legacyEventIdempotencyLookupCannotReturnResolvedV8Event() throws IOException {
        String source = Files.readString(LEGACY_EVENT_REPOSITORY)
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        assertTrue(source.contains("and event_logical_key is null"));
    }

    private static String sha256(byte[] value) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static int count(String value, String needle) {
        int total = 0;
        for (int index = value.indexOf(needle); index >= 0;
             index = value.indexOf(needle, index + needle.length())) total++;
        return total;
    }
}
