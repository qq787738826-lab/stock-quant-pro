package com.stockquant.server.agent.marketfacts;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Fail-closed identity guard for the dedicated local Tushare research
 * database. The accepted target is deliberately distinct from normal and
 * generic test databases.
 */
@Component
public final class TushareDedicatedResearchPersistenceGuard {

    public static final String DATABASE_PURPOSE =
            "TUSHARE_DEDICATED_LOCAL_RESEARCH";
    public static final String REQUIRED_DATABASE =
            "stock_quant_research";
    public static final String REQUIRED_USER =
            "stock_quant_research";
    public static final String REQUIRED_SCHEMA =
            "tushare_research";
    public static final List<String> REQUIRED_MIGRATIONS = List.of(
            "1", "2", "3", "4", "5", "6", "7",
            "8", "9", "10", "11", "12", "13");
    public static final List<String> M4_REQUIRED_MIGRATIONS = List.of(
            "1", "2", "3", "4", "5", "6", "7",
            "8", "9", "10", "11", "12", "13", "15");
    public static final List<String> M5_REQUIRED_MIGRATIONS = List.of(
            "1", "2", "3", "4", "5", "6", "7",
            "8", "9", "10", "11", "12", "13", "15", "16");

    private final SchemaInspector inspector;
    private final DatabaseIdentityPolicy identityPolicy;

    @Autowired
    public TushareDedicatedResearchPersistenceGuard(
            JdbcTemplate jdbcTemplate,
            @Value("${stockquant.market-facts.tushare.f1e-dedicated-database-purpose:UNSPECIFIED}")
            String databasePurpose
    ) {
        Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        DataSource dataSource = Objects.requireNonNull(
                jdbcTemplate.getDataSource(), "dataSource");
        this.identityPolicy = new DatabaseIdentityPolicy(databasePurpose);
        this.inspector = () -> jdbcTemplate.execute(
                (ConnectionCallback<SchemaState>) connection ->
                        inspect(connection, dataSource));
    }

    TushareDedicatedResearchPersistenceGuard(
            SchemaInspector inspector
    ) {
        this(inspector, new DatabaseIdentityPolicy(DATABASE_PURPOSE));
    }

    TushareDedicatedResearchPersistenceGuard(
            SchemaInspector inspector,
            DatabaseIdentityPolicy identityPolicy
    ) {
        this.inspector = Objects.requireNonNull(
                inspector, "inspector");
        this.identityPolicy = Objects.requireNonNull(
                identityPolicy, "identityPolicy");
    }

    public Verification verifyBeforeProvider() {
        return verify(false);
    }

    public Verification verifyTransactional() {
        return verify(true);
    }

    private Verification verify(boolean transactionRequired) {
        SchemaState state = Objects.requireNonNull(
                inspector.inspect(), "schemaState");
        validateDatabaseIdentity(state);
        if (!REQUIRED_SCHEMA.equals(state.currentSchema())) {
            if ("public".equalsIgnoreCase(state.currentSchema())) {
                throw blocked(
                        "TUSHARE_DEDICATED_RESEARCH_PUBLIC_SCHEMA_FORBIDDEN");
            }
            throw blocked(
                    "TUSHARE_DEDICATED_RESEARCH_SCHEMA_REQUIRED");
        }
        if (!strictSearchPath(state.searchPath())) {
            throw blocked(
                    "TUSHARE_DEDICATED_RESEARCH_SEARCH_PATH_INVALID");
        }
        if (!supportedMigrations(state.appliedMigrations())) {
            throw blocked(
                    "TUSHARE_DEDICATED_RESEARCH_SCHEMA_VERSION_INVALID");
        }
        if (transactionRequired && !state.transactionBound()) {
            throw blocked(
                    "TUSHARE_DEDICATED_RESEARCH_TRANSACTION_REQUIRED");
        }
        return new Verification(
                state.currentDatabase(),
                state.currentUser(),
                state.jdbcUrl(),
                identityPolicy.databasePurpose(),
                state.currentSchema(),
                state.searchPath(),
                state.appliedMigrations(),
                state.backendPid(),
                state.transactionBound(),
                DatabaseIdentityQualification.VERIFIED,
                SchemaQualification.VERIFIED);
    }

    public void verifySameTarget(
            Verification preProvider,
            Verification transactional
    ) {
        Objects.requireNonNull(preProvider, "preProvider");
        Objects.requireNonNull(transactional, "transactional");
        if (!preProvider.targetIdentity().equals(
                transactional.targetIdentity())) {
            throw blocked(
                    "TUSHARE_DEDICATED_RESEARCH_TARGET_CHANGED");
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
                    "TUSHARE_DEDICATED_RESEARCH_BACKEND_CHANGED");
        }
    }

    static void validateVerificationTarget(
            Verification verification,
            boolean transactionRequired
    ) {
        Objects.requireNonNull(verification, "verification");
        if (!REQUIRED_DATABASE.equals(
                verification.currentDatabase())
                || !REQUIRED_USER.equals(
                verification.currentUser())
                || !safeJdbcUrl(verification.jdbcUrl())
                || !DATABASE_PURPOSE.equals(
                verification.databasePurpose())
                || !REQUIRED_SCHEMA.equals(
                verification.currentSchema())
                || !strictSearchPath(verification.searchPath())
                || !supportedMigrations(verification.appliedMigrations())
                || verification.databaseIdentityQualification()
                != DatabaseIdentityQualification.VERIFIED
                || verification.schemaQualification()
                != SchemaQualification.VERIFIED
                || verification.backendPid() <= 0
                || transactionRequired
                && !verification.transactionBound()) {
            throw new IllegalArgumentException(
                    "TUSHARE_DEDICATED_RESEARCH_VERIFICATION_INVALID");
        }
    }

    private void validateDatabaseIdentity(SchemaState state) {
        if (!identityPolicy.purposeValid()
                || !REQUIRED_DATABASE.equals(state.currentDatabase())
                || !REQUIRED_USER.equals(state.currentUser())
                || !safeJdbcUrl(state.jdbcUrl())) {
            if ("stock_quant".equals(state.currentDatabase())
                    || "stock_quant_test".equals(
                    state.currentDatabase())) {
                throw blocked(
                        "TUSHARE_DEDICATED_RESEARCH_NORMAL_DATABASE_FORBIDDEN");
            }
            throw blocked(
                    "TUSHARE_DEDICATED_RESEARCH_DATABASE_IDENTITY_INVALID");
        }
    }

    private SchemaState inspect(
            Connection connection,
            DataSource dataSource
    ) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            String database = scalar(
                    statement, "SELECT current_database()");
            String user = scalar(statement, "SELECT current_user");
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
                    FROM tushare_research.flyway_schema_history
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
                            .hasResource(dataSource)
                    && !connection.getAutoCommit();
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

    private static boolean safeJdbcUrl(String value) {
        if (value == null || !value.startsWith("jdbc:postgresql://")) {
            return false;
        }
        try {
            URI uri = new URI(value.substring("jdbc:".length()));
            return "postgresql".equals(uri.getScheme())
                    && "127.0.0.1".equals(uri.getHost())
                    && uri.getPort() > 0
                    && uri.getPort() <= 65_535
                    && ("/" + REQUIRED_DATABASE).equals(uri.getPath())
                    && uri.getFragment() == null
                    && uri.getUserInfo() == null;
        } catch (URISyntaxException error) {
            return false;
        }
    }

    private static boolean strictSearchPath(String value) {
        if (value == null) {
            return false;
        }
        List<String> entries = java.util.Arrays.stream(
                        value.split(","))
                .map(TushareDedicatedResearchPersistenceGuard
                        ::normalizePath)
                .filter(entry -> !entry.isBlank())
                .toList();
        return entries.equals(List.of(REQUIRED_SCHEMA));
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

    private static String scalar(
            Statement statement,
            String sql
    ) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) {
                throw new SQLException("missing dedicated guard result");
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
                throw new SQLException("missing dedicated guard result");
            }
            return result.getInt(1);
        }
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
            DatabaseIdentityQualification databaseIdentityQualification,
            SchemaQualification schemaQualification
    ) {
        public Verification {
            currentDatabase = requiredText(
                    currentDatabase, "currentDatabase");
            currentUser = requiredText(currentUser, "currentUser");
            jdbcUrl = requiredText(jdbcUrl, "jdbcUrl");
            databasePurpose = requiredText(
                    databasePurpose, "databasePurpose");
            currentSchema = requiredText(
                    currentSchema, "currentSchema");
            searchPath = requiredText(searchPath, "searchPath");
            appliedMigrations = List.copyOf(Objects.requireNonNull(
                    appliedMigrations, "appliedMigrations"));
            databaseIdentityQualification = Objects.requireNonNull(
                    databaseIdentityQualification,
                    "databaseIdentityQualification");
            schemaQualification = Objects.requireNonNull(
                    schemaQualification, "schemaQualification");
            if (backendPid <= 0
                    || !REQUIRED_DATABASE.equals(currentDatabase)
                    || !REQUIRED_USER.equals(currentUser)
                    || !safeJdbcUrl(jdbcUrl)
                    || !DATABASE_PURPOSE.equals(databasePurpose)
                    || !REQUIRED_SCHEMA.equals(currentSchema)
                    || !strictSearchPath(searchPath)
                    || !supportedMigrations(appliedMigrations)
                    || databaseIdentityQualification
                    != DatabaseIdentityQualification.VERIFIED
                    || schemaQualification
                    != SchemaQualification.VERIFIED) {
                throw new IllegalArgumentException(
                        "invalid dedicated research verification");
            }
        }

        public boolean normalBusinessDatabaseAllowed() {
            return false;
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
                    schemaQualification);
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
            DatabaseIdentityQualification databaseIdentityQualification,
            SchemaQualification schemaQualification
    ) {
        private TargetIdentity {
            appliedMigrations = List.copyOf(appliedMigrations);
        }
    }

    @FunctionalInterface
    interface SchemaInspector {
        SchemaState inspect();
    }

    public enum DatabaseIdentityQualification {
        VERIFIED
    }

    public enum SchemaQualification {
        VERIFIED
    }

    public static final class GuardException extends RuntimeException {
        private final String safeCode;

        GuardException(String safeCode) {
            super(safeCode);
            this.safeCode = safeCode;
        }

        public String safeCode() {
            return safeCode;
        }
    }

    private static String requiredText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "invalid dedicated research " + field);
        }
        return value;
    }

    private static boolean supportedMigrations(List<String> migrations) {
        // V14 is intentionally absent from the main history: it remains the
        // isolated controlled-acceptance governance migration. M4 extends the
        // dedicated research history with the exact V15/V16 schemas.
        return REQUIRED_MIGRATIONS.equals(migrations)
                || M4_REQUIRED_MIGRATIONS.equals(migrations)
                || M5_REQUIRED_MIGRATIONS.equals(migrations);
    }
}
