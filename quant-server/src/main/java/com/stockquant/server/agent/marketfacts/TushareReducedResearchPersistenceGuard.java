package com.stockquant.server.agent.marketfacts;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Verifies the dedicated database identity and strict random F1C schema.
 *
 * <p>Preflight verification blocks provider access when the target is unsafe.
 * Transactional verification additionally proves that the actual write
 * connection is bound to the active Spring transaction.</p>
 */
@Component
public final class TushareReducedResearchPersistenceGuard {

    public static final String SCHEMA_PREFIX =
            "f1c_tushare_research_";
    public static final String DATABASE_PURPOSE =
            "F1C_ISOLATED_RESEARCH";
    static final String REQUIRED_DATABASE = "stock_quant_test";
    static final String REQUIRED_DATABASE_USER = "stock_quant_test";
    private static final Pattern SAFE_JDBC_URL = Pattern.compile(
            "^jdbc:postgresql://127\\.0\\.0\\.1:(5432|55432)"
                    + "/stock_quant_test(?:\\?[^#\\s]*)?$");
    private static final Pattern SCHEMA_PATTERN = Pattern.compile(
            "^f1c_tushare_research_[0-9a-f]{32}$");
    private static final List<String> REQUIRED_MIGRATIONS = List.of(
            "1", "2", "3", "4", "5", "6", "7",
            "8", "9", "10", "11", "12", "13");

    private final SchemaInspector inspector;
    private final DatabaseIdentityPolicy databaseIdentityPolicy;

    @Autowired
    public TushareReducedResearchPersistenceGuard(
            JdbcTemplate jdbcTemplate,
            @Value("${stockquant.market-facts.tushare.f1c-isolated-database-purpose:UNSPECIFIED}")
            String databasePurpose
    ) {
        Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        DataSource dataSource = Objects.requireNonNull(
                jdbcTemplate.getDataSource(), "dataSource");
        this.databaseIdentityPolicy =
                new DatabaseIdentityPolicy(databasePurpose);
        this.inspector = () -> jdbcTemplate.execute(
                (ConnectionCallback<SchemaState>) connection ->
                        inspect(connection, dataSource));
    }

    TushareReducedResearchPersistenceGuard(
            SchemaInspector inspector
    ) {
        this(inspector, new DatabaseIdentityPolicy(DATABASE_PURPOSE));
    }

    TushareReducedResearchPersistenceGuard(
            SchemaInspector inspector,
            DatabaseIdentityPolicy databaseIdentityPolicy
    ) {
        this.inspector = Objects.requireNonNull(
                inspector, "inspector");
        this.databaseIdentityPolicy = Objects.requireNonNull(
                databaseIdentityPolicy, "databaseIdentityPolicy");
    }

    public Verification verify() {
        return verify(false);
    }

    public Verification verifyTransactional() {
        return verify(true);
    }

    private Verification verify(boolean requireTransactionalConnection) {
        SchemaState state = Objects.requireNonNull(
                inspector.inspect(), "schemaState");
        validateDatabaseIdentity(state);
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
        if (requireTransactionalConnection
                && !state.transactionBound()) {
            throw blocked(
                    "TUSHARE_REDUCED_RUNTIME_TRANSACTION_CONNECTION_REQUIRED");
        }
        return new Verification(
                state.currentDatabase(),
                state.currentUser(),
                state.jdbcUrl(),
                databaseIdentityPolicy.databasePurpose(),
                schema,
                searchPath,
                state.appliedMigrations(),
                state.backendPid(),
                state.transactionBound(),
                "VERIFIED",
                "VERIFIED");
    }

    public void verifyUnchanged(
            Verification before,
            Verification after
    ) {
        verifySameTarget(before, after);
    }

    public void verifySameTarget(
            Verification before,
            Verification after
    ) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        if (!before.targetIdentity().equals(after.targetIdentity())) {
            throw blocked(
                    "TUSHARE_REDUCED_RUNTIME_ISOLATED_SCHEMA_REQUIRED");
        }
    }

    public void verifySameTransactionalConnection(
            Verification before,
            Verification after
    ) {
        verifySameTarget(before, after);
        if (!before.transactionBound()
                || !after.transactionBound()
                || before.backendPid() != after.backendPid()) {
            throw blocked(
                    "TUSHARE_REDUCED_RUNTIME_TRANSACTION_CONNECTION_REQUIRED");
        }
    }

    private void validateDatabaseIdentity(SchemaState state) {
        if (!databaseIdentityPolicy.purposeValid()) {
            throw blocked(
                    "TUSHARE_REDUCED_RUNTIME_DATABASE_IDENTITY_INVALID");
        }
        if (!REQUIRED_DATABASE.equals(state.currentDatabase())) {
            throw blocked(
                    "TUSHARE_REDUCED_RUNTIME_NORMAL_DATABASE_FORBIDDEN");
        }
        if (!REQUIRED_DATABASE_USER.equals(state.currentUser())
                || state.jdbcUrl() == null
                || !SAFE_JDBC_URL.matcher(state.jdbcUrl()).matches()) {
            throw blocked(
                    "TUSHARE_REDUCED_RUNTIME_DATABASE_IDENTITY_INVALID");
        }
    }

    private SchemaState inspect(
            Connection connection,
            DataSource dataSource
    ) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            String database = scalar(
                    statement, "SELECT current_database()");
            String user = scalar(
                    statement, "SELECT current_user");
            String schema = scalar(
                    statement, "SELECT current_schema()");
            String searchPath = scalar(
                    statement,
                    "SELECT current_setting('search_path')");
            int backendPid = integer(
                    statement, "SELECT pg_backend_pid()");
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
            boolean transactionBound =
                    TransactionSynchronizationManager
                            .isActualTransactionActive()
                            && TransactionSynchronizationManager
                            .hasResource(dataSource);
            return new SchemaState(
                    database,
                    user,
                    connection.getMetaData().getURL(),
                    schema,
                    searchPath,
                    List.copyOf(migrations),
                    backendPid,
                    transactionBound);
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

    private static int integer(
            Statement statement,
            String sql
    ) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) {
                throw new SQLException("missing schema guard result");
            }
            return result.getInt(1);
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
            String currentDatabase,
            String currentUser,
            String jdbcUrl,
            String databasePurpose,
            String currentSchema,
            String searchPath,
            List<String> appliedMigrations,
            int backendPid,
            boolean transactionBound,
            String databaseIdentityQualification,
            String isolatedSchemaGuardQualification
    ) {
        public Verification {
            appliedMigrations = List.copyOf(appliedMigrations);
            if (currentDatabase == null || currentDatabase.isBlank()
                    || currentUser == null || currentUser.isBlank()
                    || jdbcUrl == null || jdbcUrl.isBlank()
                    || databasePurpose == null || databasePurpose.isBlank()
                    || currentSchema == null || currentSchema.isBlank()
                    || searchPath == null || searchPath.isBlank()
                    || backendPid <= 0
                    || !"VERIFIED".equals(
                    databaseIdentityQualification)
                    || !"VERIFIED".equals(
                    isolatedSchemaGuardQualification)) {
                throw new IllegalArgumentException(
                        "invalid F1C schema verification");
            }
        }

        public boolean normalBusinessDatabaseAllowed() {
            return !(DATABASE_PURPOSE.equals(databasePurpose)
                    && REQUIRED_DATABASE.equals(currentDatabase)
                    && REQUIRED_DATABASE_USER.equals(currentUser)
                    && SAFE_JDBC_URL.matcher(jdbcUrl).matches());
        }

        TargetIdentity targetIdentity() {
            return new TargetIdentity(
                    currentDatabase,
                    currentUser,
                    jdbcUrl,
                    databasePurpose,
                    currentSchema,
                    searchPath,
                    appliedMigrations,
                    databaseIdentityQualification,
                    isolatedSchemaGuardQualification);
        }
    }

    record SchemaState(
            String currentDatabase,
            String currentUser,
            String jdbcUrl,
            String currentSchema,
            String searchPath,
            List<String> appliedMigrations,
            int backendPid,
            boolean transactionBound
    ) {
        SchemaState {
            appliedMigrations = List.copyOf(appliedMigrations);
        }

        SchemaState(
                String currentSchema,
                String searchPath,
                List<String> appliedMigrations
        ) {
            this(
                    REQUIRED_DATABASE,
                    REQUIRED_DATABASE_USER,
                    "jdbc:postgresql://127.0.0.1:55432/"
                            + REQUIRED_DATABASE,
                    currentSchema,
                    searchPath,
                    appliedMigrations,
                    10_001,
                    false);
        }
    }

    record DatabaseIdentityPolicy(String databasePurpose) {
        DatabaseIdentityPolicy {
            databasePurpose = databasePurpose == null
                    ? "" : databasePurpose.trim();
        }

        boolean purposeValid() {
            return DATABASE_PURPOSE.equals(databasePurpose);
        }
    }

    private record TargetIdentity(
            String currentDatabase,
            String currentUser,
            String jdbcUrl,
            String databasePurpose,
            String currentSchema,
            String searchPath,
            List<String> appliedMigrations,
            String databaseIdentityQualification,
            String isolatedSchemaGuardQualification
    ) {
        private TargetIdentity {
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
