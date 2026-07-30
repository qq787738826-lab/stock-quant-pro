package com.stockquant.server.agent.marketfacts;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Verifies that the current data source targets a strict random F1C schema.
 *
 * <p>The check runs before provider access and again immediately before
 * capture. It never authorizes {@code public} or a search-path fallback.</p>
 */
@Component
public final class TushareReducedResearchPersistenceGuard {

    public static final String SCHEMA_PREFIX =
            "f1c_tushare_research_";
    private static final Pattern SCHEMA_PATTERN = Pattern.compile(
            "^f1c_tushare_research_[0-9a-f]{32}$");
    private static final List<String> REQUIRED_MIGRATIONS = List.of(
            "1", "2", "3", "4", "5", "6", "7",
            "8", "9", "10", "11", "12", "13");

    private final SchemaInspector inspector;

    @Autowired
    public TushareReducedResearchPersistenceGuard(
            JdbcTemplate jdbcTemplate
    ) {
        Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.inspector = () -> jdbcTemplate.execute(
                (ConnectionCallback<SchemaState>) this::inspect);
    }

    TushareReducedResearchPersistenceGuard(
            SchemaInspector inspector
    ) {
        this.inspector = Objects.requireNonNull(
                inspector, "inspector");
    }

    public Verification verify() {
        SchemaState state = Objects.requireNonNull(
                inspector.inspect(), "schemaState");
        String schema = state.currentSchema();
        String searchPath = state.searchPath();
        if ("public".equalsIgnoreCase(schema)
                || containsPublic(searchPath)) {
            throw blocked(
                    "TUSHARE_REDUCED_RUNTIME_PUBLIC_SCHEMA_FORBIDDEN");
        }
        if (schema == null
                || !SCHEMA_PATTERN.matcher(schema).matches()
                || !strictSearchPath(searchPath, schema)) {
            throw blocked(
                    "TUSHARE_REDUCED_RUNTIME_ISOLATED_SCHEMA_REQUIRED");
        }
        if (!state.appliedMigrations().equals(REQUIRED_MIGRATIONS)) {
            throw blocked(
                    "TUSHARE_REDUCED_RUNTIME_SCHEMA_VERSION_INVALID");
        }
        return new Verification(
                schema,
                searchPath,
                state.appliedMigrations(),
                "VERIFIED",
                false);
    }

    public void verifyUnchanged(
            Verification before,
            Verification after
    ) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        if (!before.equals(after)) {
            throw blocked(
                    "TUSHARE_REDUCED_RUNTIME_ISOLATED_SCHEMA_REQUIRED");
        }
    }

    private SchemaState inspect(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            String schema = scalar(
                    statement, "SELECT current_schema()");
            String searchPath = scalar(
                    statement,
                    "SELECT current_setting('search_path')");
            List<String> migrations = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery("""
                    SELECT version
                    FROM flyway_schema_history
                    WHERE success
                    ORDER BY installed_rank
                    """)) {
                while (rows.next()) {
                    migrations.add(rows.getString(1));
                }
            }
            return new SchemaState(
                    schema, searchPath, List.copyOf(migrations));
        }
    }

    private static String scalar(
            Statement statement,
            String sql
    ) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) {
                throw new SQLException("missing schema guard result");
            }
            return result.getString(1);
        }
    }

    private static boolean containsPublic(String searchPath) {
        if (searchPath == null) {
            return false;
        }
        return java.util.Arrays.stream(searchPath.split(","))
                .map(TushareReducedResearchPersistenceGuard::normalizePath)
                .anyMatch(value -> "public".equalsIgnoreCase(value));
    }

    private static boolean strictSearchPath(
            String searchPath,
            String schema
    ) {
        if (searchPath == null) {
            return false;
        }
        List<String> values = java.util.Arrays.stream(
                        searchPath.split(","))
                .map(TushareReducedResearchPersistenceGuard::normalizePath)
                .filter(value -> !value.isBlank())
                .toList();
        return values.equals(List.of(schema));
    }

    private static String normalizePath(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() >= 2
                && normalized.startsWith("\"")
                && normalized.endsWith("\"")) {
            normalized = normalized.substring(
                    1, normalized.length() - 1);
        }
        return normalized.replace("\"\"", "\"");
    }

    private static GuardException blocked(String safeCode) {
        return new GuardException(safeCode);
    }

    public record Verification(
            String currentSchema,
            String searchPath,
            List<String> appliedMigrations,
            String isolatedSchemaGuardQualification,
            boolean normalBusinessDatabaseAllowed
    ) {
        public Verification {
            appliedMigrations = List.copyOf(appliedMigrations);
            if (currentSchema == null || currentSchema.isBlank()
                    || searchPath == null || searchPath.isBlank()
                    || !"VERIFIED".equals(
                    isolatedSchemaGuardQualification)
                    || normalBusinessDatabaseAllowed) {
                throw new IllegalArgumentException(
                        "invalid F1C schema verification");
            }
        }
    }

    record SchemaState(
            String currentSchema,
            String searchPath,
            List<String> appliedMigrations
    ) {
        SchemaState {
            appliedMigrations = List.copyOf(appliedMigrations);
        }
    }

    @FunctionalInterface
    interface SchemaInspector {
        SchemaState inspect();
    }

    public static final class GuardException
            extends RuntimeException {
        private final String safeCode;

        GuardException(String safeCode) {
            super(safeCode);
            this.safeCode = safeCode;
        }

        public String safeCode() {
            return safeCode;
        }
    }
}
