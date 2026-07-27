package com.stockquant.server.agent.temporal;

import org.flywaydb.core.api.resource.LoadableResource;
import org.flywaydb.core.internal.resolver.ChecksumCalculator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemporalMarketFoundationMigrationTest {

    private static final String V6 = "db/migration/V6__temporal_market_foundation.sql";
    private static final String V12 =
            "db/migration/V12__temporal_market_foundation_hardening.sql";

    @Test
    void v6MatchesTheAppliedTemporalFoundationLineage() throws IOException {
        String sql = resource(V6);
        String normalized = sql.toLowerCase(Locale.ROOT);
        assertEquals(4, normalized.split("create table ", -1).length - 1,
                "V6 must create exactly the four approved temporal tables");

        for (String table : List.of(
                "market_data_dataset_versions",
                "security_status_events",
                "security_status_history",
                "trading_calendar_revisions"
        )) {
            assertTrue(normalized.contains("create table " + table), table);
        }
        assertTrue(normalized.contains("create extension if not exists btree_gist"));
        assertTrue(normalized.contains("ex_security_status_history_bitemporal_overlap"));
        assertTrue(normalized.contains("daterange(valid_from, valid_to, '[)')"));
        assertTrue(normalized.contains("tstzrange(known_from, known_to, '[)')"));
        assertTrue(normalized.contains("ex_trading_calendar_revisions_knowledge_overlap"));
        assertTrue(normalized.contains("where known_to is null"));
        assertTrue(normalized.contains("backfilled_inferred"));
        assertTrue(normalized.contains("jsonb not null default '{}'::jsonb"));
        assertTrue(normalized.contains("previous_open_date date"));
        assertTrue(normalized.contains("next_open_date date"));
        assertTrue(normalized.contains("reject_security_status_event_update"));
        assertTrue(normalized.contains("before update on security_status_events"));
        assertFalse(normalized.contains("reject_temporal_immutable_mutation"));
        assertFalse(normalized.contains("allow_only_temporal_knowledge_close"));
        assertFalse(normalized.contains("before truncate"));

        for (String forbiddenTable : List.of(
                "security_universe_snapshot",
                "security_universe_snapshot_member",
                "daily_bar_revision",
                "corporate_action_revision",
                "adjustment_factor_snapshot",
                "market_regime_evaluation_case"
        )) {
            assertFalse(normalized.contains("create table " + forbiddenTable), forbiddenTable);
        }
        for (String destructive : List.of("drop table", "delete from", "alter table securities",
                "alter table daily_bars", "insert into securities", "insert into daily_bars")) {
            assertFalse(normalized.contains(destructive), destructive);
        }
        assertFalse(normalized.contains("references securities"));
        assertFalse(normalized.contains("timestamp without time zone"));
    }

    @Test
    void v6FlywayChecksumMatchesTheExistingPublicHistory() throws IOException {
        assertEquals(-981595186,
                ChecksumCalculator.calculate(new StringResource(
                        V6, resource(V6))));
    }

    @Test
    void v12CarriesOnlyTheForwardTemporalHardeningDelta() throws IOException {
        String normalized = resource(V12).toLowerCase(Locale.ROOT);
        assertFalse(normalized.contains("create table "));
        assertFalse(normalized.contains("insert into "));
        assertFalse(normalized.contains("delete from "));
        assertFalse(normalized.contains("drop table "));
        assertTrue(normalized.contains("reject_temporal_immutable_mutation"));
        assertTrue(normalized.contains("allow_only_temporal_knowledge_close"));
        assertTrue(normalized.contains("previous_open_date is not null"));
        assertTrue(normalized.contains("next_open_date is not null"));
        assertTrue(normalized.contains("drop column previous_open_date"));
        assertTrue(normalized.contains("drop column next_open_date"));
        for (String table : List.of(
                "market_data_dataset_versions", "security_status_events",
                "security_status_history", "trading_calendar_revisions")) {
            assertTrue(normalized.contains("before truncate on " + table));
        }
        assertTrue(normalized.contains("before update or delete on security_status_events"));
        assertTrue(normalized.contains("before update or delete on security_status_history"));
        assertTrue(normalized.contains("before update or delete on trading_calendar_revisions"));
    }

    private String resource(String path) throws IOException {
        try (InputStream stream = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(path)) {
            assertNotNull(stream, path + " must exist");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static final class StringResource extends LoadableResource {
        private final String path;
        private final String content;

        private StringResource(String path, String content) {
            this.path = path;
            this.content = content;
        }

        @Override
        public Reader read() {
            return new StringReader(content);
        }

        @Override
        public String getAbsolutePath() {
            return path;
        }

        @Override
        public String getAbsolutePathOnDisk() {
            return path;
        }

        @Override
        public String getFilename() {
            return path.substring(path.lastIndexOf('/') + 1);
        }

        @Override
        public String getRelativePath() {
            return path;
        }
    }
}
