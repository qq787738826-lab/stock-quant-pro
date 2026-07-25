package com.stockquant.server.agent.announcement;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnnouncementFoundationMigrationTest {

    private static final String MIGRATION =
            "db/migration/V10__announcement_observation_foundation.sql";

    @Test
    void createsOnlyApprovedAppendOnlyResearchAnnouncementModel() throws Exception {
        String sql;
        try (InputStream stream = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(MIGRATION)) {
            assertNotNull(stream, "V10 migration must exist");
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        String normalized = sql.toLowerCase(Locale.ROOT);
        assertEquals(2, normalized.split("create table ", -1).length - 1);
        for (String table : List.of(
                "announcement_capture_batches",
                "announcement_observations")) {
            assertTrue(normalized.contains("create table " + table));
            assertTrue(normalized.contains("before update or delete on " + table));
            assertTrue(normalized.contains("before truncate on " + table));
        }
        for (String required : List.of(
                "akshare_cninfo_research_v1",
                "akshare_cninfo_provider_v1",
                "date_only",
                "assurance_level = 'research'",
                "not formal_eligible",
                "not pit_verified",
                "not revision_relationship_guaranteed",
                "first_observed_at = known_at",
                "known_at <= recorded_at",
                "jsonb_typeof(provider_metadata_json) = 'object'",
                "jsonb_typeof(raw_payload_json) = 'object'",
                "cninfo[.]com[.]cn",
                "raw_payload_json ->> '公告链接' = source_url",
                "idx_announcement_capture_batches_coverage",
                "idx_announcement_observations_as_of",
                "reported_publish_date")) {
            assertTrue(normalized.contains(required), required);
        }
        for (String forbidden : List.of(
                "drop table",
                "delete from",
                "update daily_bars",
                "alter table daily_bars",
                "alter table securities",
                "insert into daily_bars",
                "flyway_schema_history")) {
            assertFalse(normalized.contains(forbidden), forbidden);
        }
    }
}
