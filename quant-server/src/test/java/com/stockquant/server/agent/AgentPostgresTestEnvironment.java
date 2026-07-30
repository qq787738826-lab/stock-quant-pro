package com.stockquant.server.agent;

import org.springframework.test.context.DynamicPropertyRegistry;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AgentPostgresTestEnvironment {

    static final String REQUIRED_URL =
            "jdbc:postgresql://127.0.0.1:5432/stock_quant_test";
    static final String EPHEMERAL_LOCAL_URL =
            "jdbc:postgresql://127.0.0.1:55432/stock_quant_test";
    static final String REQUIRED_USERNAME = "stock_quant_test";
    static final int PUBLIC_V6_CHECKSUM = -981595186;
    static final int PUBLIC_V12_CHECKSUM = -178798261;

    private static final String URL_VARIABLE = "STOCK_QUANT_TEST_DB_URL";
    private static final String USERNAME_VARIABLE =
            "STOCK_QUANT_TEST_DB_USERNAME";
    private static final String PASSWORD_VARIABLE =
            "STOCK_QUANT_TEST_DB_PASSWORD";
    private static final String SCHEMA_PREFIX = "agent_it_";

    private AgentPostgresTestEnvironment() {
    }

    static IsolatedSchema registerIsolatedDataSource(
            DynamicPropertyRegistry registry,
            String scope
    ) {
        Credentials credentials = validate(
                System.getenv(URL_VARIABLE),
                System.getenv(USERNAME_VARIABLE),
                System.getenv(PASSWORD_VARIABLE)
        );
        IsolatedSchema isolated = IsolatedSchema.create(
                credentials, scope);
        registry.add("spring.datasource.url", isolated::url);
        registry.add("spring.datasource.username",
                credentials::username);
        registry.add("spring.datasource.password",
                credentials::password);
        registry.add("spring.datasource.hikari.schema",
                isolated::schema);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.default-schema",
                isolated::schema);
        registry.add("spring.flyway.schemas", isolated::schema);
        registry.add("spring.flyway.create-schemas", () -> false);
        registry.add("spring.flyway.validate-on-migrate",
                () -> true);
        registry.add("spring.flyway.baseline-on-migrate",
                () -> false);
        return isolated;
    }

    static Credentials validate(
            String url,
            String username,
            String password
    ) {
        if (!Set.of(REQUIRED_URL, EPHEMERAL_LOCAL_URL)
                .contains(url)) {
            throw new IllegalStateException(
                    "专用PostgreSQL测试库URL不符合安全要求");
        }
        if (!REQUIRED_USERNAME.equals(username)) {
            throw new IllegalStateException(
                    "专用PostgreSQL测试库用户名不符合安全要求");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "专用PostgreSQL测试库密码未设置");
        }
        return new Credentials(url, username, password);
    }

    static String isolatedSchemaName(
            String scope,
            UUID suffix
    ) {
        if (scope == null
                || !scope.matches("[a-z0-9_]{1,20}")) {
            throw new IllegalArgumentException(
                    "unsafe PostgreSQL test scope");
        }
        String schema = SCHEMA_PREFIX + scope + "_"
                + suffix.toString().replace("-", "");
        requireSafeMigrationSchema(schema);
        return schema;
    }

    static String schemaUrl(
            Credentials credentials,
            String schema
    ) {
        requireSafeMigrationSchema(schema);
        String separator = credentials.url().contains("?")
                ? "&" : "?";
        return credentials.url() + separator
                + "currentSchema=" + schema;
    }

    static void requireSafeMigrationSchema(String schema) {
        if (schema == null || "public".equalsIgnoreCase(schema)
                || !schema.matches(
                "^agent_it_[a-z0-9_]{1,20}_[0-9a-f]{32}$")) {
            throw new IllegalStateException(
                    "Flyway integration tests must use a random "
                            + "isolated schema, never public");
        }
    }

    private static void assertCurrentSchema(
            Credentials credentials,
            String schema
    ) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                schemaUrl(credentials, schema),
                credentials.username(), credentials.password());
             Statement statement = connection.createStatement()) {
            assertEquals(schema, scalarText(statement,
                    "SELECT current_schema()"));
            assertTrue(!"public".equalsIgnoreCase(
                    scalarText(statement, "SELECT current_schema()")));
        }
    }

    private static void assertPublicV12Lineage(
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
        assertEquals(PUBLIC_V6_CHECKSUM, scalar(statement, """
                SELECT checksum
                FROM public.flyway_schema_history
                WHERE version='6' AND success
                """));
        assertEquals(PUBLIC_V12_CHECKSUM, scalar(statement, """
                SELECT checksum
                FROM public.flyway_schema_history
                WHERE version='12' AND success
                """));
        assertEquals(0, scalar(statement, """
                SELECT count(*)
                FROM information_schema.columns
                WHERE table_schema='public'
                  AND table_name='trading_calendar_revisions'
                  AND column_name IN (
                    'previous_open_date', 'next_open_date')
                """));
        assertEquals(8, scalar(statement, """
                SELECT count(*)
                FROM pg_trigger trigger_record
                JOIN pg_class table_record
                  ON table_record.oid=trigger_record.tgrelid
                JOIN pg_namespace schema_record
                  ON schema_record.oid=table_record.relnamespace
                WHERE schema_record.nspname='public'
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
                """));
    }

    private static PublicBaseline publicBaseline(
            Statement statement
    ) throws SQLException {
        assertPublicV12Lineage(statement);
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
                        string_agg(row_value, E'\\n'
                                   ORDER BY row_value), ''))
                    FROM (
                        SELECT to_jsonb(fact)::text row_value
                        FROM public.%s fact
                    ) facts
                    """.formatted(quote(table))));
        }
        return new PublicBaseline(
                Map.copyOf(rows),
                schemaStructure(statement),
                strings(statement, """
                        SELECT installed_rank || ':'
                               || coalesce(version, '') || ':'
                               || description || ':' || type || ':'
                               || script || ':'
                               || coalesce(checksum::text, '') || ':'
                               || success
                        FROM public.flyway_schema_history
                        ORDER BY installed_rank
                        """));
    }

    private static PublicBaseline readPublicBaseline(
            Credentials credentials
    ) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                credentials.url(), credentials.username(),
                credentials.password())) {
            connection.setReadOnly(true);
            try (Statement statement = connection.createStatement()) {
                assertEquals("public",
                        scalarText(statement, "SELECT current_schema()"));
                return publicBaseline(statement);
            }
        }
    }

    private static List<String> schemaStructure(
            Statement statement
    ) throws SQLException {
        List<String> values = new ArrayList<>();
        values.addAll(strings(statement, """
                SELECT 'RELATION|' || relation.relkind::text || '|'
                       || relation.relname
                FROM pg_class relation
                JOIN pg_namespace schema_record
                  ON schema_record.oid=relation.relnamespace
                WHERE schema_record.nspname='public'
                  AND relation.relkind IN ('r','p','v','m','S')
                ORDER BY relation.relkind, relation.relname
                """));
        values.addAll(strings(statement, """
                SELECT 'COLUMN|' || relation.relname || '|'
                       || attribute.attnum || '|' || attribute.attname
                       || '|' || format_type(
                            attribute.atttypid, attribute.atttypmod)
                       || '|' || attribute.attnotnull || '|'
                       || coalesce(pg_get_expr(
                            default_value.adbin,
                            default_value.adrelid), '')
                FROM pg_attribute attribute
                JOIN pg_class relation
                  ON relation.oid=attribute.attrelid
                JOIN pg_namespace schema_record
                  ON schema_record.oid=relation.relnamespace
                LEFT JOIN pg_attrdef default_value
                  ON default_value.adrelid=attribute.attrelid
                 AND default_value.adnum=attribute.attnum
                WHERE schema_record.nspname='public'
                  AND relation.relkind IN ('r','p','v','m')
                  AND attribute.attnum > 0
                  AND NOT attribute.attisdropped
                ORDER BY relation.relname, attribute.attnum
                """));
        values.addAll(strings(statement, """
                SELECT 'CONSTRAINT|' || relation.relname || '|'
                       || constraint_record.conname || '|'
                       || constraint_record.contype::text || '|'
                       || pg_get_constraintdef(
                            constraint_record.oid, true)
                FROM pg_constraint constraint_record
                JOIN pg_class relation
                  ON relation.oid=constraint_record.conrelid
                JOIN pg_namespace schema_record
                  ON schema_record.oid=relation.relnamespace
                WHERE schema_record.nspname='public'
                ORDER BY relation.relname,
                         constraint_record.conname
                """));
        values.addAll(strings(statement, """
                SELECT 'INDEX|' || relation.relname || '|'
                       || index_record.relname || '|'
                       || pg_get_indexdef(index_record.oid)
                FROM pg_index index_link
                JOIN pg_class relation
                  ON relation.oid=index_link.indrelid
                JOIN pg_class index_record
                  ON index_record.oid=index_link.indexrelid
                JOIN pg_namespace schema_record
                  ON schema_record.oid=relation.relnamespace
                WHERE schema_record.nspname='public'
                ORDER BY relation.relname, index_record.relname
                """));
        values.addAll(strings(statement, """
                SELECT 'TRIGGER|' || relation.relname || '|'
                       || trigger_record.tgname || '|'
                       || pg_get_triggerdef(
                            trigger_record.oid, true)
                FROM pg_trigger trigger_record
                JOIN pg_class relation
                  ON relation.oid=trigger_record.tgrelid
                JOIN pg_namespace schema_record
                  ON schema_record.oid=relation.relnamespace
                WHERE schema_record.nspname='public'
                  AND NOT trigger_record.tgisinternal
                ORDER BY relation.relname,
                         trigger_record.tgname
                """));
        values.addAll(strings(statement, """
                SELECT 'FUNCTION|' || function_record.proname || '|'
                       || pg_get_function_identity_arguments(
                            function_record.oid)
                       || '|' || pg_get_functiondef(
                            function_record.oid)
                FROM pg_proc function_record
                JOIN pg_namespace schema_record
                  ON schema_record.oid=function_record.pronamespace
                WHERE schema_record.nspname='public'
                ORDER BY function_record.proname,
                         pg_get_function_identity_arguments(
                            function_record.oid)
                """));
        values.addAll(strings(statement, """
                SELECT 'SEQUENCE|' || sequence_record.relname || '|'
                       || metadata.seqstart || '|'
                       || metadata.seqincrement || '|'
                       || metadata.seqmin || '|'
                       || metadata.seqmax || '|'
                       || metadata.seqcache || '|'
                       || metadata.seqcycle
                FROM pg_class sequence_record
                JOIN pg_namespace schema_record
                  ON schema_record.oid=sequence_record.relnamespace
                JOIN pg_sequence metadata
                  ON metadata.seqrelid=sequence_record.oid
                WHERE schema_record.nspname='public'
                ORDER BY sequence_record.relname
                """));
        return List.copyOf(values);
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

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    record Credentials(
            String url,
            String username,
            String password
    ) {
    }

    static final class IsolatedSchema implements AutoCloseable {
        private final Credentials credentials;
        private final String schema;
        private final PublicBaseline publicBaseline;
        private final AtomicBoolean closed = new AtomicBoolean();

        private IsolatedSchema(
                Credentials credentials,
                String schema,
                PublicBaseline publicBaseline
        ) {
            this.credentials = credentials;
            this.schema = schema;
            this.publicBaseline = publicBaseline;
        }

        private static IsolatedSchema create(
                Credentials credentials,
                String scope
        ) {
            String schema = isolatedSchemaName(
                    scope, UUID.randomUUID());
            requireSafeMigrationSchema(schema);
            PublicBaseline baseline;
            try {
                baseline = readPublicBaseline(credentials);
            } catch (SQLException error) {
                throw new IllegalStateException(
                        "could not read the public PostgreSQL "
                                + "test baseline", error);
            }
            try (Connection connection = DriverManager.getConnection(
                    credentials.url(), credentials.username(),
                    credentials.password());
                 Statement statement = connection.createStatement()) {
                assertEquals("public",
                        scalarText(statement, "SELECT current_schema()"));
                assertEquals(0, scalar(statement, """
                        SELECT count(*)
                        FROM information_schema.schemata
                        WHERE schema_name='%s'
                        """.formatted(schema)));
                statement.execute("CREATE SCHEMA " + quote(schema));
                assertCurrentSchema(credentials, schema);
                return new IsolatedSchema(
                        credentials, schema, baseline);
            } catch (SQLException error) {
                throw new IllegalStateException(
                        "could not create isolated PostgreSQL "
                                + "integration-test schema", error);
            }
        }

        String schema() {
            return schema;
        }

        String url() {
            return schemaUrl(credentials, schema);
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            requireSafeMigrationSchema(schema);
            try (Connection connection = DriverManager.getConnection(
                    credentials.url(), credentials.username(),
                    credentials.password());
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA "
                        + quote(schema) + " CASCADE");
                assertEquals(0, scalar(statement, """
                        SELECT count(*)
                        FROM information_schema.schemata
                        WHERE schema_name='%s'
                        """.formatted(schema)));
            } catch (SQLException error) {
                throw new IllegalStateException(
                        "could not clean isolated PostgreSQL "
                                + "integration-test schema", error);
            }
            try {
                assertEquals(publicBaseline,
                        readPublicBaseline(credentials),
                        "public schema changed during an isolated "
                                + "PostgreSQL integration test");
            } catch (SQLException error) {
                throw new IllegalStateException(
                        "could not verify the public PostgreSQL "
                                + "test baseline", error);
            }
        }
    }

    private record PublicBaseline(
            Map<String, String> tableRows,
            List<String> schemaStructure,
            List<String> flywayHistory
    ) {
    }
}
