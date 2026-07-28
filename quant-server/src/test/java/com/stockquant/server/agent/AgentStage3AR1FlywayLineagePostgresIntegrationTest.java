package com.stockquant.server.agent;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.ValidateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@EnabledIfEnvironmentVariable(
        named = "STOCK_QUANT_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(
        named = "STOCK_QUANT_TEST_DB_USERNAME", matches = ".+")
@EnabledIfEnvironmentVariable(
        named = "STOCK_QUANT_TEST_DB_PASSWORD", matches = ".+")
class AgentStage3AR1FlywayLineagePostgresIntegrationTest {

    private static final String PREFIX = "stage_3ar1_lineage_";
    private static final String FRESH_SCHEMA = schema("fresh");
    private static final String LEGACY_SCHEMA = schema("legacy");
    private static final String GUARDED_SCHEMA = schema("guarded");
    private static final String FRESH_V13_SCHEMA = schema("freshv13");
    private static final String LEGACY_V13_SCHEMA = schema("legacyv13");
    private static final String MIGRATIONS = "classpath:db/migration";
    private static final int APPLIED_V6_CHECKSUM = -981595186;
    private static final int PUBLIC_V12_CHECKSUM = -178798261;

    @Test
    void validatesPublicReadOnlyAndConvergesFreshAndAppliedV6Lineages()
            throws Exception {
        var credentials = credentials();
        PublicBaseline before;
        try (Connection connection =
                     publicReadOnlyConnection(credentials);
             Statement statement = connection.createStatement()) {
            assertEquals("stock_quant_test",
                    scalarText(statement, "SELECT current_database()"));
            assertEquals("stock_quant_test",
                    scalarText(statement, "SELECT current_user"));
            before = publicBaseline(statement);
            assertCurrentPublicLineage(statement);
        }

        try {
            createSchema(credentials, FRESH_SCHEMA);
            createSchema(credentials, LEGACY_SCHEMA);
            createSchema(credentials, GUARDED_SCHEMA);
            createSchema(credentials, FRESH_V13_SCHEMA);
            createSchema(credentials, LEGACY_V13_SCHEMA);

            migrate(credentials, FRESH_SCHEMA, "12");
            assertEquals("12", latestVersion(credentials, FRESH_SCHEMA));

            migrate(credentials, LEGACY_SCHEMA, "6");
            assertEquals("6", latestVersion(credentials, LEGACY_SCHEMA));
            try (Connection connection = controlConnection(credentials);
                 Statement statement = connection.createStatement()) {
                assertAppliedV6IsolatedLineage(
                        statement, LEGACY_SCHEMA);
            }

            ValidateResult legacyValidation = validate(
                    credentials, LEGACY_SCHEMA, "6");
            assertTrue(legacyValidation.validationSuccessful,
                    legacyValidation.getAllErrorMessages());
            migrate(credentials, LEGACY_SCHEMA, "12");
            assertEquals("12", latestVersion(credentials, LEGACY_SCHEMA));

            try (Connection connection = controlConnection(credentials);
                 Statement statement = connection.createStatement()) {
                assertStructureEquals(
                        schemaFingerprint(statement, FRESH_SCHEMA),
                        schemaFingerprint(statement, LEGACY_SCHEMA),
                        "fresh and applied-V6 lineages must converge");
                assertEquals(
                        migrationHistory(statement, FRESH_SCHEMA),
                        migrationHistory(statement, LEGACY_SCHEMA),
                        "fresh and applied-V6 migration sets must converge");
                assertStructureEquals(
                        before.schemaStructure(),
                        schemaFingerprint(statement, FRESH_SCHEMA),
                        "fresh lineage must converge with current public V12");
                assertEquals(
                        before.flywayHistory(),
                        migrationHistory(statement, FRESH_SCHEMA),
                        "fresh lineage must have the current public "
                                + "V1-V12 migration set");
                assertV12Hardening(statement, FRESH_SCHEMA);
                assertV12Hardening(statement, LEGACY_SCHEMA);
            }

            migrate(credentials, GUARDED_SCHEMA, "11");
            insertPopulatedLegacyCalendarNavigation(
                    credentials, GUARDED_SCHEMA);
            assertThrows(FlywayException.class,
                    () -> migrate(credentials, GUARDED_SCHEMA, "12"));
            try (Connection connection = controlConnection(credentials);
                 Statement statement = connection.createStatement()) {
                assertEquals(1, scalar(statement, """
                        SELECT count(*)
                        FROM %s.trading_calendar_revisions
                        WHERE previous_open_date IS NOT NULL
                          AND next_open_date IS NOT NULL
                        """.formatted(quote(GUARDED_SCHEMA))));
                assertEquals(2, scalar(statement, """
                        SELECT count(*)
                        FROM information_schema.columns
                        WHERE table_schema='%s'
                          AND table_name='trading_calendar_revisions'
                          AND column_name IN (
                            'previous_open_date', 'next_open_date')
                        """.formatted(GUARDED_SCHEMA)));
                assertEquals("11",
                        latestVersion(statement, GUARDED_SCHEMA));
            }

            ValidateResult publicValidation = validate(
                    credentials, "public", "12");
            assertTrue(publicValidation.validationSuccessful,
                    publicValidation.getAllErrorMessages());

            migrate(credentials, FRESH_V13_SCHEMA, null);
            assertEquals("13",
                    latestVersion(credentials, FRESH_V13_SCHEMA));
            migrate(credentials, LEGACY_V13_SCHEMA, "6");
            assertEquals("6",
                    latestVersion(credentials, LEGACY_V13_SCHEMA));
            migrate(credentials, LEGACY_V13_SCHEMA, null);
            assertEquals("13",
                    latestVersion(credentials, LEGACY_V13_SCHEMA));
            assertTrue(validate(
                    credentials, FRESH_V13_SCHEMA, null)
                    .validationSuccessful);
            assertTrue(validate(
                    credentials, LEGACY_V13_SCHEMA, null)
                    .validationSuccessful);
            try (Connection connection = controlConnection(credentials);
                 Statement statement = connection.createStatement()) {
                assertStructureEquals(
                        schemaFingerprint(statement, FRESH_V13_SCHEMA),
                        schemaFingerprint(statement, LEGACY_V13_SCHEMA),
                        "fresh and applied-V6 V13 lineages must converge");
                assertEquals(
                        migrationHistory(statement, FRESH_V13_SCHEMA),
                        migrationHistory(statement, LEGACY_V13_SCHEMA),
                        "fresh and applied-V6 V13 histories must converge");
            }
        } finally {
            dropSchema(credentials, LEGACY_V13_SCHEMA);
            dropSchema(credentials, FRESH_V13_SCHEMA);
            dropSchema(credentials, GUARDED_SCHEMA);
            dropSchema(credentials, LEGACY_SCHEMA);
            dropSchema(credentials, FRESH_SCHEMA);
        }

        try (Connection connection =
                     publicReadOnlyConnection(credentials);
             Statement statement = connection.createStatement()) {
            assertEquals(before, publicBaseline(statement),
                    "public must remain byte-for-byte and structure unchanged");
            for (String schema : List.of(
                    FRESH_SCHEMA, LEGACY_SCHEMA, GUARDED_SCHEMA,
                    FRESH_V13_SCHEMA, LEGACY_V13_SCHEMA)) {
                assertEquals(0, scalar(statement, """
                        SELECT count(*)
                        FROM information_schema.schemata
                        WHERE schema_name='%s'
                        """.formatted(schema)));
            }
        }
    }

    private static void assertCurrentPublicLineage(
            Statement statement
    ) throws SQLException {
        assertEquals(
                List.of("1", "2", "3", "4", "5", "6",
                        "7", "8", "9", "10", "11", "12"),
                strings(statement, """
                        SELECT version
                        FROM public.flyway_schema_history
                        WHERE success
                        ORDER BY installed_rank
                        """));
        assertEquals(0, scalar(statement, """
                SELECT count(*)
                FROM public.flyway_schema_history
                WHERE NOT success
                """));
        assertEquals(0, scalar(statement, """
                SELECT count(*) - count(DISTINCT version)
                FROM public.flyway_schema_history
                WHERE version IS NOT NULL
                """));
        assertPublicMigration(
                statement, "6", 6,
                "temporal market foundation",
                "V6__temporal_market_foundation.sql",
                APPLIED_V6_CHECKSUM);
        assertPublicMigration(
                statement, "12", 12,
                "temporal market foundation hardening",
                "V12__temporal_market_foundation_hardening.sql",
                PUBLIC_V12_CHECKSUM);
        assertV12Hardening(statement, "public");
    }

    private static void assertPublicMigration(
            Statement statement,
            String version,
            int installedRank,
            String description,
            String script,
            int checksum
    ) throws SQLException {
        try (ResultSet row = statement.executeQuery("""
                SELECT installed_rank, version, description, type, script,
                       checksum, installed_on, success
                FROM public.flyway_schema_history
                WHERE version='%s'
                """.formatted(version))) {
            assertTrue(row.next(),
                    "public migration history must exist: " + version);
            assertEquals(installedRank,
                    row.getInt("installed_rank"));
            assertEquals(version, row.getString("version"));
            assertEquals(description, row.getString("description"));
            assertEquals("SQL", row.getString("type"));
            assertEquals(script, row.getString("script"));
            assertEquals(checksum, row.getInt("checksum"));
            assertNotNull(row.getTimestamp("installed_on"));
            assertTrue(row.getBoolean("success"));
            assertFalse(row.next(),
                    "public must contain one migration row: "
                            + version);
        }
    }

    private static void assertAppliedV6IsolatedLineage(
            Statement statement,
            String schema
    ) throws SQLException {
        assertEquals(
                List.of("1", "2", "3", "4", "5", "6"),
                migrationVersions(statement, schema));
        assertEquals(APPLIED_V6_CHECKSUM, scalar(statement, """
                SELECT checksum
                FROM %s.flyway_schema_history
                WHERE version='6' AND success
                """.formatted(quote(schema))));
        assertEquals(2, scalar(statement, """
                SELECT count(*)
                FROM information_schema.columns
                WHERE table_schema='%s'
                  AND table_name='trading_calendar_revisions'
                  AND column_name IN (
                    'previous_open_date', 'next_open_date')
                """.formatted(schema)));
        assertEquals(0, scalar(statement, """
                SELECT count(*)
                FROM %s.trading_calendar_revisions
                WHERE previous_open_date IS NOT NULL
                   OR next_open_date IS NOT NULL
                """.formatted(quote(schema))),
                "legacy navigation columns must be empty before "
                        + "forward removal");
        assertEquals(1, scalar(statement, """
                SELECT count(*)
                FROM pg_proc function_record
                JOIN pg_namespace schema_record
                  ON schema_record.oid=function_record.pronamespace
                WHERE schema_record.nspname='%s'
                  AND function_record.proname =
                    'reject_security_status_event_update'
                """.formatted(schema)));
        assertEquals(0, scalar(statement, """
                SELECT count(*)
                FROM pg_proc function_record
                JOIN pg_namespace schema_record
                  ON schema_record.oid=function_record.pronamespace
                WHERE schema_record.nspname='%s'
                  AND function_record.proname IN (
                    'reject_temporal_immutable_mutation',
                    'allow_only_temporal_knowledge_close')
                """.formatted(schema)));
    }

    private static void assertV12Hardening(
            Statement statement,
            String schema
    ) throws SQLException {
        assertEquals(0, scalar(statement, """
                SELECT count(*)
                FROM information_schema.columns
                WHERE table_schema='%s'
                  AND table_name='trading_calendar_revisions'
                  AND column_name IN (
                    'previous_open_date', 'next_open_date')
                """.formatted(schema)));
        assertEquals(8, scalar(statement, """
                SELECT count(*)
                FROM pg_trigger trigger_record
                JOIN pg_class table_record
                  ON table_record.oid=trigger_record.tgrelid
                JOIN pg_namespace schema_record
                  ON schema_record.oid=table_record.relnamespace
                WHERE schema_record.nspname='%s'
                  AND NOT trigger_record.tgisinternal
                  AND trigger_record.tgname IN (
                    'trg_market_dataset_versions_immutable_rows',
                    'trg_market_dataset_versions_no_truncate',
                    'trg_security_status_events_immutable_rows',
                    'trg_security_status_events_no_truncate',
                    'trg_security_status_history_guard_rows',
                    'trg_security_status_history_no_truncate',
                    'trg_trading_calendar_revisions_guard_rows',
                    'trg_trading_calendar_revisions_no_truncate')
                """.formatted(schema)));
        assertEquals(2, scalar(statement, """
                SELECT count(*)
                FROM pg_proc function_record
                JOIN pg_namespace schema_record
                  ON schema_record.oid=function_record.pronamespace
                WHERE schema_record.nspname='%s'
                  AND function_record.proname IN (
                    'reject_temporal_immutable_mutation',
                    'allow_only_temporal_knowledge_close')
                """.formatted(schema)));
        assertEquals(0, scalar(statement, """
                SELECT count(*)
                FROM pg_proc function_record
                JOIN pg_namespace schema_record
                  ON schema_record.oid=function_record.pronamespace
                WHERE schema_record.nspname='%s'
                  AND function_record.proname =
                    'reject_security_status_event_update'
                """.formatted(schema)));
    }

    private static void insertPopulatedLegacyCalendarNavigation(
            AgentPostgresTestEnvironment.Credentials credentials,
            String schema
    ) throws SQLException {
        try (Connection connection = schemaConnection(credentials, schema);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO market_data_dataset_versions (
                        dataset_type, source, source_version,
                        connector_version, range_start, range_end,
                        fetched_at, recorded_at, payload_hash,
                        trust_level, metadata
                    ) VALUES (
                        'TRADING_CALENDAR', 'LINEAGE_TEST',
                        'legacy-v6', 'lineage-test-v1',
                        DATE '2026-07-01', DATE '2026-07-31',
                        TIMESTAMPTZ '2026-07-27 15:30:00+08',
                        TIMESTAMPTZ '2026-07-27 15:31:00+08',
                        repeat('a', 64), 'OBSERVED', '{}'::jsonb
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO trading_calendar_revisions (
                        dataset_version_id, exchange, trade_date,
                        is_open, session_type, session_open_at,
                        session_close_at, previous_open_date,
                        next_open_date, known_from, source,
                        source_version, source_record_id,
                        source_revision, trust_level, payload_hash,
                        recorded_at
                    ) VALUES (
                        1, 'SSE', DATE '2026-07-24',
                        TRUE, 'REGULAR',
                        TIMESTAMPTZ '2026-07-24 09:30:00+08',
                        TIMESTAMPTZ '2026-07-24 15:00:00+08',
                        DATE '2026-07-23', DATE '2026-07-27',
                        TIMESTAMPTZ '2026-07-24 15:01:00+08',
                        'LINEAGE_TEST', 'legacy-v6',
                        'SSE:2026-07-24', '1', 'OBSERVED',
                        repeat('b', 64),
                        TIMESTAMPTZ '2026-07-24 15:02:00+08'
                    )
                    """);
        }
    }

    private static void migrate(
            AgentPostgresTestEnvironment.Credentials credentials,
            String schema,
            String target
    ) {
        requireSafeSchema(schema);
        var configuration = Flyway.configure()
                .dataSource(schemaUrl(credentials, schema),
                        credentials.username(), credentials.password())
                .locations(MIGRATIONS)
                .defaultSchema(schema)
                .schemas(schema)
                .createSchemas(false);
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    private static ValidateResult validate(
            AgentPostgresTestEnvironment.Credentials credentials,
            String schema,
            String target
    ) {
        if (!"public".equals(schema)) {
            requireSafeSchema(schema);
        }
        var configuration = Flyway.configure()
                .dataSource(schemaUrl(credentials, schema),
                        credentials.username(), credentials.password())
                .locations(MIGRATIONS)
                .defaultSchema(schema)
                .schemas(schema)
                .createSchemas(false);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load().validateWithResult();
    }

    private static void createSchema(
            AgentPostgresTestEnvironment.Credentials credentials,
            String schema
    ) throws SQLException {
        requireSafeSchema(schema);
        try (Connection connection = controlConnection(credentials);
             Statement statement = connection.createStatement()) {
            assertEquals(0, scalar(statement, """
                    SELECT count(*) FROM information_schema.schemata
                    WHERE schema_name='%s'
                    """.formatted(schema)));
            statement.execute("CREATE SCHEMA " + quote(schema));
        }
    }

    private static void dropSchema(
            AgentPostgresTestEnvironment.Credentials credentials,
            String schema
    ) throws SQLException {
        requireSafeSchema(schema);
        try (Connection connection = controlConnection(credentials);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS "
                    + quote(schema) + " CASCADE");
        }
    }

    private static AgentPostgresTestEnvironment.Credentials credentials() {
        return AgentPostgresTestEnvironment.validate(
                System.getenv("STOCK_QUANT_TEST_DB_URL"),
                System.getenv("STOCK_QUANT_TEST_DB_USERNAME"),
                System.getenv("STOCK_QUANT_TEST_DB_PASSWORD"));
    }

    private static Connection controlConnection(
            AgentPostgresTestEnvironment.Credentials credentials
    ) throws SQLException {
        return DriverManager.getConnection(
                credentials.url(), credentials.username(),
                credentials.password());
    }

    private static Connection publicReadOnlyConnection(
            AgentPostgresTestEnvironment.Credentials credentials
    ) throws SQLException {
        Connection connection = controlConnection(credentials);
        connection.setReadOnly(true);
        return connection;
    }

    private static Connection schemaConnection(
            AgentPostgresTestEnvironment.Credentials credentials,
            String schema
    ) throws SQLException {
        return DriverManager.getConnection(
                schemaUrl(credentials, schema),
                credentials.username(), credentials.password());
    }

    private static String schemaUrl(
            AgentPostgresTestEnvironment.Credentials credentials,
            String schema
    ) {
        if ("public".equals(schema)) {
            return credentials.url();
        }
        String separator = credentials.url().contains("?") ? "&" : "?";
        return credentials.url() + separator + "currentSchema=" + schema;
    }

    private static String latestVersion(
            AgentPostgresTestEnvironment.Credentials credentials,
            String schema
    ) throws SQLException {
        try (Connection connection = controlConnection(credentials);
             Statement statement = connection.createStatement()) {
            return latestVersion(statement, schema);
        }
    }

    private static String latestVersion(
            Statement statement,
            String schema
    ) throws SQLException {
        return scalarText(statement, """
                SELECT version
                FROM %s.flyway_schema_history
                WHERE success
                ORDER BY installed_rank DESC
                LIMIT 1
                """.formatted(quote(schema)));
    }

    private static List<String> migrationHistory(
            Statement statement,
            String schema
    ) throws SQLException {
        return strings(statement, """
                SELECT installed_rank || ':' || coalesce(version, '')
                       || ':' || description || ':' || type || ':'
                       || script || ':' || coalesce(checksum::text, '')
                       || ':' || success
                FROM %s.flyway_schema_history
                ORDER BY installed_rank
                """.formatted(quote(schema)));
    }

    private static List<String> migrationVersions(
            Statement statement,
            String schema
    ) throws SQLException {
        return strings(statement, """
                SELECT version
                FROM %s.flyway_schema_history
                WHERE success
                ORDER BY installed_rank
                """.formatted(quote(schema)));
    }

    private static List<String> schemaFingerprint(
            Statement statement,
            String schema
    ) throws SQLException {
        List<String> values = new ArrayList<>();
        values.addAll(strings(statement, """
                SELECT 'RELATION|' || c.relkind::text || '|' || c.relname
                FROM pg_class c
                JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='%s'
                  AND c.relkind IN ('r','p','v','m','S')
                  AND NOT EXISTS (
                    SELECT 1
                    FROM pg_depend d
                    WHERE d.classid='pg_class'::regclass
                      AND d.objid=c.oid
                      AND d.deptype='e')
                ORDER BY c.relkind, c.relname
                """.formatted(schema)));
        values.addAll(strings(statement, """
                SELECT 'COLUMN|' || c.relname || '|' || a.attnum || '|'
                       || a.attname || '|' || format_type(a.atttypid, a.atttypmod)
                       || '|' || a.attnotnull || '|'
                       || coalesce(pg_get_expr(def.adbin, def.adrelid), '')
                FROM pg_attribute a
                JOIN pg_class c ON c.oid=a.attrelid
                JOIN pg_namespace n ON n.oid=c.relnamespace
                LEFT JOIN pg_attrdef def
                  ON def.adrelid=a.attrelid AND def.adnum=a.attnum
                WHERE n.nspname='%s'
                  AND c.relkind IN ('r','p','v','m')
                  AND a.attnum > 0
                  AND NOT a.attisdropped
                  AND NOT EXISTS (
                    SELECT 1
                    FROM pg_depend d
                    WHERE d.classid='pg_class'::regclass
                      AND d.objid=c.oid
                      AND d.deptype='e')
                ORDER BY c.relname, a.attnum
                """.formatted(schema)));
        values.addAll(strings(statement, """
                SELECT 'CONSTRAINT|' || table_record.relname || '|'
                       || constraint_record.conname || '|'
                       || constraint_record.contype::text || '|'
                       || pg_get_constraintdef(constraint_record.oid, true)
                FROM pg_constraint constraint_record
                JOIN pg_class table_record
                  ON table_record.oid=constraint_record.conrelid
                JOIN pg_namespace schema_record
                  ON schema_record.oid=table_record.relnamespace
                WHERE schema_record.nspname='%s'
                ORDER BY table_record.relname, constraint_record.conname
                """.formatted(schema)));
        values.addAll(strings(statement, """
                SELECT 'INDEX|' || table_record.relname || '|'
                       || index_record.relname || '|'
                       || pg_get_indexdef(index_record.oid)
                FROM pg_index index_link
                JOIN pg_class table_record
                  ON table_record.oid=index_link.indrelid
                JOIN pg_class index_record
                  ON index_record.oid=index_link.indexrelid
                JOIN pg_namespace schema_record
                  ON schema_record.oid=table_record.relnamespace
                WHERE schema_record.nspname='%s'
                ORDER BY table_record.relname, index_record.relname
                """.formatted(schema)));
        values.addAll(strings(statement, """
                SELECT 'TRIGGER|' || table_record.relname || '|'
                       || trigger_record.tgname || '|'
                       || pg_get_triggerdef(trigger_record.oid, true)
                FROM pg_trigger trigger_record
                JOIN pg_class table_record
                  ON table_record.oid=trigger_record.tgrelid
                JOIN pg_namespace schema_record
                  ON schema_record.oid=table_record.relnamespace
                WHERE schema_record.nspname='%s'
                  AND NOT trigger_record.tgisinternal
                ORDER BY table_record.relname, trigger_record.tgname
                """.formatted(schema)));
        values.addAll(strings(statement, """
                SELECT 'FUNCTION|' || function_record.proname || '|'
                       || pg_get_function_identity_arguments(function_record.oid)
                       || '|' || pg_get_functiondef(function_record.oid)
                FROM pg_proc function_record
                JOIN pg_namespace schema_record
                  ON schema_record.oid=function_record.pronamespace
                WHERE schema_record.nspname='%s'
                  AND NOT EXISTS (
                    SELECT 1
                    FROM pg_depend dependency
                    WHERE dependency.classid='pg_proc'::regclass
                      AND dependency.objid=function_record.oid
                      AND dependency.deptype='e')
                ORDER BY function_record.proname,
                         pg_get_function_identity_arguments(function_record.oid)
                """.formatted(schema)));
        values.addAll(strings(statement, """
                SELECT 'SEQUENCE|' || sequence_record.relname || '|'
                       || sequence_metadata.seqstart || '|'
                       || sequence_metadata.seqincrement || '|'
                       || sequence_metadata.seqmin || '|'
                       || sequence_metadata.seqmax || '|'
                       || sequence_metadata.seqcache || '|'
                       || sequence_metadata.seqcycle
                FROM pg_class sequence_record
                JOIN pg_namespace schema_record
                  ON schema_record.oid=sequence_record.relnamespace
                JOIN pg_sequence sequence_metadata
                  ON sequence_metadata.seqrelid=sequence_record.oid
                WHERE schema_record.nspname='%s'
                ORDER BY sequence_record.relname
                """.formatted(schema)));
        return values.stream()
                .map(value -> normalizeSchema(value, schema))
                .toList();
    }

    private static PublicBaseline publicBaseline(Statement statement)
            throws SQLException {
        Map<String, String> rows = new LinkedHashMap<>();
        for (String table : strings(statement, """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema='public'
                  AND table_type='BASE TABLE'
                ORDER BY table_name
                """)) {
            rows.put(table, scalarText(statement, """
                    SELECT count(*) || ':' || md5(coalesce(
                        string_agg(row_value, E'\\n' ORDER BY row_value), ''))
                    FROM (
                        SELECT to_jsonb(t)::text row_value
                        FROM public.%s t
                    ) facts
                    """.formatted(quote(table))));
        }
        return new PublicBaseline(
                Map.copyOf(rows),
                schemaFingerprint(statement, "public"),
                migrationHistory(statement, "public"));
    }

    private static List<String> strings(
            Statement statement,
            String sql
    ) throws SQLException {
        List<String> values = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return List.copyOf(values);
    }

    private static int scalar(
            Statement statement,
            String sql
    ) throws SQLException {
        try (ResultSet row = statement.executeQuery(sql)) {
            assertTrue(row.next(), "scalar query returned no row");
            return row.getInt(1);
        }
    }

    private static String scalarText(
            Statement statement,
            String sql
    ) throws SQLException {
        try (ResultSet row = statement.executeQuery(sql)) {
            assertTrue(row.next(), "scalar query returned no row");
            return row.getString(1);
        }
    }

    private static String normalizeSchema(
            String value,
            String schema
    ) {
        String normalized = value
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("\"" + schema + "\".", "<schema>.")
                .replace(schema + ".", "<schema>.")
                .replace("'" + schema + "'", "'<schema>'");
        if ("public".equals(schema)) {
            normalized = normalized.replaceAll(
                    "nextval\\('([a-zA-Z0-9_]+)'::regclass\\)",
                    "nextval('<schema>.$1'::regclass)");
            normalized = normalized.replaceAll(
                    "REFERENCES ([a-zA-Z0-9_]+)\\(",
                    "REFERENCES <schema>.$1(");
            if (normalized.startsWith("TRIGGER|")) {
                normalized = normalized.replaceFirst(
                        " ON ([a-zA-Z0-9_]+)(?=\\s)",
                        " ON <schema>.$1");
            }
            normalized = normalized.replaceAll(
                    "EXECUTE FUNCTION ([a-zA-Z0-9_]+)\\(",
                    "EXECUTE FUNCTION <schema>.$1(");
        }
        return normalized;
    }

    private static void assertStructureEquals(
            List<String> expected,
            List<String> actual,
            String message
    ) {
        if (expected.equals(actual)) {
            return;
        }
        List<String> missing = expected.stream()
                .filter(value -> !actual.contains(value))
                .limit(10)
                .toList();
        List<String> unexpected = actual.stream()
                .filter(value -> !expected.contains(value))
                .limit(10)
                .toList();
        fail(message + "; expectedSize=" + expected.size()
                + ", actualSize=" + actual.size()
                + ", missing=" + missing
                + ", unexpected=" + unexpected);
    }

    private static String schema(String role) {
        return PREFIX + role + "_"
                + UUID.randomUUID().toString().replace("-", "");
    }

    private static void requireSafeSchema(String schema) {
        if (!schema.matches(
                "^stage_3ar1_lineage_(fresh|legacy|guarded|freshv13|legacyv13)"
                        + "_[0-9a-f]{32}$")) {
            throw new IllegalStateException(
                    "unsafe 3A-R1 temporary schema name");
        }
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private record PublicBaseline(
            Map<String, String> tableRows,
            List<String> schemaStructure,
            List<String> flywayHistory
    ) {
    }
}
