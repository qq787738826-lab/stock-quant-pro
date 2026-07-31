package com.stockquant.server.agent;

import org.springframework.test.context.DynamicPropertyRegistry;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F1E integration-test environment. It accepts only a newly provisioned local
 * stock_quant_research database and never an existing business/test database.
 */
final class F1eDedicatedPostgresTestEnvironment
        implements AutoCloseable {

    static final String URL_VARIABLE =
            "STOCK_QUANT_RESEARCH_TEST_DB_URL";
    static final String USER_VARIABLE =
            "STOCK_QUANT_RESEARCH_TEST_DB_USERNAME";
    static final String PASSWORD_VARIABLE =
            "STOCK_QUANT_RESEARCH_TEST_DB_PASSWORD";
    static final String DATABASE = "stock_quant_research";
    static final String USER = "stock_quant_research";
    static final String SCHEMA = "tushare_research";

    private final Credentials credentials;
    private final PublicFingerprint baseline;
    private final AtomicBoolean closed = new AtomicBoolean();

    private F1eDedicatedPostgresTestEnvironment(
            Credentials credentials,
            PublicFingerprint baseline
    ) {
        this.credentials = credentials;
        this.baseline = baseline;
    }

    static F1eDedicatedPostgresTestEnvironment register(
            DynamicPropertyRegistry registry
    ) {
        Credentials credentials = validate(
                System.getenv(URL_VARIABLE),
                System.getenv(USER_VARIABLE),
                System.getenv(PASSWORD_VARIABLE));
        PublicFingerprint baseline = publicFingerprint(credentials);
        try (Connection connection = DriverManager.getConnection(
                credentials.url(),
                credentials.username(),
                credentials.password());
             Statement statement = connection.createStatement()) {
            assertEquals(DATABASE,
                    scalar(statement, "SELECT current_database()"));
            assertEquals(USER,
                    scalar(statement, "SELECT current_user"));
            assertEquals("public",
                    scalar(statement, "SELECT current_schema()"));
            assertEquals(0, integer(statement, """
                    SELECT count(*)
                    FROM information_schema.schemata
                    WHERE schema_name='tushare_research'
                    """));
            statement.execute("CREATE SCHEMA tushare_research");
        } catch (SQLException error) {
            throw new IllegalStateException(
                    "could not create F1E dedicated schema", error);
        }
        F1eDedicatedPostgresTestEnvironment environment =
                new F1eDedicatedPostgresTestEnvironment(
                        credentials, baseline);
        registry.add("spring.datasource.url",
                environment::schemaUrl);
        registry.add("spring.datasource.username",
                credentials::username);
        registry.add("spring.datasource.password",
                credentials::password);
        registry.add("spring.datasource.hikari.schema",
                () -> SCHEMA);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.create-schemas", () -> false);
        registry.add("spring.flyway.clean-disabled", () -> true);
        registry.add("spring.flyway.validate-on-migrate", () -> true);
        registry.add("spring.flyway.baseline-on-migrate", () -> false);
        return environment;
    }

    String schemaUrl() {
        return credentials.url() + "?currentSchema=" + SCHEMA;
    }

    String baseUrl() {
        return credentials.url();
    }

    PublicFingerprint baseline() {
        return baseline;
    }

    PublicFingerprint currentPublicFingerprint() {
        return publicFingerprint(credentials);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(
                credentials.url(),
                credentials.username(),
                credentials.password());
             Statement statement = connection.createStatement()) {
            statement.execute(
                    "DROP SCHEMA tushare_research CASCADE");
            assertEquals(0, integer(statement, """
                    SELECT count(*)
                    FROM information_schema.schemata
                    WHERE schema_name='tushare_research'
                    """));
        } catch (SQLException error) {
            throw new IllegalStateException(
                    "could not remove F1E dedicated schema", error);
        }
        assertEquals(
                baseline,
                publicFingerprint(credentials),
                "public fingerprint changed in F1E dedicated test");
    }

    private static Credentials validate(
            String url,
            String username,
            String password
    ) {
        if (url == null
                || !url.startsWith("jdbc:postgresql://")
                || !USER.equals(username)
                || password == null
                || password.isBlank()) {
            throw new IllegalStateException(
                    "F1E dedicated PostgreSQL credentials are invalid");
        }
        try {
            URI uri = new URI(url.substring("jdbc:".length()));
            if (!"postgresql".equals(uri.getScheme())
                    || !"127.0.0.1".equals(uri.getHost())
                    || uri.getPort() <= 0
                    || uri.getPort() > 65_535
                    || !("/" + DATABASE).equals(uri.getPath())
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || uri.getUserInfo() != null) {
                throw new IllegalStateException(
                        "F1E dedicated PostgreSQL URL is invalid");
            }
        } catch (Exception error) {
            throw new IllegalStateException(
                    "F1E dedicated PostgreSQL URL is invalid", error);
        }
        return new Credentials(url, username, password);
    }

    private static PublicFingerprint publicFingerprint(
            Credentials credentials
    ) {
        try (Connection connection = DriverManager.getConnection(
                credentials.url(),
                credentials.username(),
                credentials.password());
             Statement statement = connection.createStatement()) {
            assertEquals("public",
                    scalar(statement, "SELECT current_schema()"));
            Map<String, String> relations = new TreeMap<>();
            for (String table : strings(statement, """
                    SELECT table_name
                    FROM information_schema.tables
                    WHERE table_schema='public'
                      AND table_type='BASE TABLE'
                    ORDER BY table_name
                    """)) {
                relations.put(table, scalar(statement, """
                        SELECT count(*)::text || ':' || md5(coalesce(
                            string_agg(row_value, E'\\n'
                                       ORDER BY row_value), ''))
                        FROM (
                            SELECT to_jsonb(value)::text row_value
                            FROM public.%s value
                        ) rows
                        """.formatted(quote(table))));
            }
            List<String> structure = strings(statement, """
                    SELECT relation.relkind::text || ':'
                           || relation.relname
                    FROM pg_class relation
                    JOIN pg_namespace namespace
                      ON namespace.oid=relation.relnamespace
                    WHERE namespace.nspname='public'
                      AND relation.relkind IN ('r','p','v','m','S')
                    ORDER BY relation.relkind, relation.relname
                    """);
            return new PublicFingerprint(
                    Map.copyOf(relations), structure);
        } catch (SQLException error) {
            throw new IllegalStateException(
                    "could not fingerprint F1E public schema", error);
        }
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

    private static String scalar(
            Statement statement,
            String sql
    ) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next(), "scalar query returned no row");
            return result.getString(1);
        }
    }

    private static int integer(
            Statement statement,
            String sql
    ) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next(), "scalar query returned no row");
            return result.getInt(1);
        }
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private record Credentials(
            String url,
            String username,
            String password
    ) {
    }

    record PublicFingerprint(
            Map<String, String> relationRows,
            List<String> structure
    ) {
        PublicFingerprint {
            relationRows = Map.copyOf(relationRows);
            structure = List.copyOf(structure);
        }
    }
}
