package com.stockquant.server.agent.marketfacts;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.CharArrayReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * Manual whitelist implementation of the dedicated database preparation.
 * It never constructs Provider, Spring Boot, web, scheduler or acceptance
 * components and it never scans the controlled-acceptance V14 location.
 */
final class TushareControlledAcceptanceDatabasePreparationService {
    static final List<String> EXPECTED_MAIN_MIGRATIONS = List.of(
            "1", "2", "3", "4", "5", "6", "7",
            "8", "9", "10", "11", "12", "13");
    private final Clock clock;
    private final SecureRandom secureRandom;

    TushareControlledAcceptanceDatabasePreparationService(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.secureRandom = new SecureRandom();
    }

    PreparationReport validateOnly(
            TushareControlledAcceptanceDatabasePreparationPlan plan
    ) {
        Objects.requireNonNull(plan, "plan");
        if (plan.databaseExecutionAllowed()) {
            throw new IllegalArgumentException(
                    "TUSHARE_DATABASE_PREPARATION_EXECUTION_EXPECTED");
        }
        Instant now = clock.instant();
        return new PreparationReport(
                "DATABASE_PREPARATION_CANDIDATE",
                plan.mode(), plan.expectedCommit(), plan.databasePort(),
                plan.administratorUser(), "NOT_CONNECTED",
                List.of(), false, false, false, false,
                now, now, Phase.NON_SECRET_PLAN_VALIDATED,
                "PREPARATION_ONLY_NO_DATABASE_MUTATION");
    }

    PreparationReport prepare(
            TushareControlledAcceptanceDatabasePreparationPlan plan,
            char[] administratorPassword,
            DedicatedPasswordSupplier dedicatedPasswordSupplier,
            BootstrapSecretRegistrar bootstrapSecretRegistrar
    ) {
        Objects.requireNonNull(plan, "plan");
        if (!plan.databaseExecutionAllowed()) {
            throw new IllegalArgumentException(
                    "TUSHARE_DATABASE_PREPARATION_MODE_NOT_AUTHORIZED");
        }
        requireSecret(administratorPassword);
        Objects.requireNonNull(dedicatedPasswordSupplier,
                "dedicatedPasswordSupplier");
        Objects.requireNonNull(bootstrapSecretRegistrar,
                "bootstrapSecretRegistrar");
        // This service takes ownership of the supplied copies. The caller
        // also clears them defensively, but the administrator copy is erased
        // here before the dedicated-user migration phase starts.
        char[] adminSecret = administratorPassword;
        char[] bootstrapSecret = bootstrapSecret();
        char[] dedicatedSecret = null;
        bootstrapSecretRegistrar.register(bootstrapSecret);
        Instant startedAt = clock.instant();
        Phase phase = Phase.NON_SECRET_PLAN_VALIDATED;
        boolean mutated = false;
        try {
            phase = Phase.ADMIN_PREFLIGHT;
            try (var adminSource = dataSource(
                    plan.databasePort(),
                    TushareControlledAcceptanceDatabasePreparationPlan.ADMIN_DATABASE,
                    plan.administratorUser(), null, adminSecret);
                 Connection admin = adminSource.getConnection()) {
                requireAdministratorTarget(admin, plan);
                requireFreshTarget(admin);
                phase = Phase.ROLE_CREATED;
                createDedicatedRole(admin, bootstrapSecret);
                mutated = true;
                phase = Phase.DATABASE_CREATED;
                createDedicatedDatabase(admin);
            }

            phase = Phase.DEDICATED_SCHEMA_CREATED;
            try (var adminTargetSource = dataSource(
                    plan.databasePort(),
                    TushareControlledAcceptanceDatabasePreparationPlan.DATABASE,
                    plan.administratorUser(), null, adminSecret);
                 Connection adminTarget = adminTargetSource.getConnection()) {
                hardenTargetForDedicatedBootstrap(adminTarget);
                try (var bootstrapSource = dataSource(
                        plan.databasePort(),
                        TushareControlledAcceptanceDatabasePreparationPlan.DATABASE,
                        TushareControlledAcceptanceDatabasePreparationPlan.USER,
                        null, bootstrapSecret);
                     Connection bootstrapConnection =
                             bootstrapSource.getConnection()) {
                    createDedicatedSchemaAndExtension(bootstrapConnection);
                }
                phase = Phase.PERMISSIONS_HARDENED;
            }
            Arrays.fill(adminSecret, '\0');

            phase = Phase.DEDICATED_SECRET_CONFIGURED;
            dedicatedSecret = Objects.requireNonNull(
                    dedicatedPasswordSupplier.read(), "dedicatedPassword");
            requireSecret(dedicatedSecret);
            try (var bootstrapSource = dataSource(
                    plan.databasePort(),
                    TushareControlledAcceptanceDatabasePreparationPlan.DATABASE,
                    TushareControlledAcceptanceDatabasePreparationPlan.USER,
                    TushareControlledAcceptanceDatabasePreparationPlan.SCHEMA,
                    bootstrapSecret);
                 Connection bootstrapConnection = bootstrapSource.getConnection()) {
                rotateDedicatedPassword(bootstrapConnection, dedicatedSecret);
            }
            Arrays.fill(bootstrapSecret, '\0');

            phase = Phase.MAIN_MIGRATIONS_APPLIED;
            try (var dedicatedSource = dataSource(
                    plan.databasePort(),
                    TushareControlledAcceptanceDatabasePreparationPlan.DATABASE,
                    TushareControlledAcceptanceDatabasePreparationPlan.USER,
                    TushareControlledAcceptanceDatabasePreparationPlan.SCHEMA,
                    dedicatedSecret)) {
                migrateMainOnly(dedicatedSource);
                phase = Phase.READBACK_VERIFIED;
                Verification verification = verify(dedicatedSource);
                Instant completedAt = clock.instant();
                return new PreparationReport(
                        "DATABASE_PREPARATION_CANDIDATE",
                        plan.mode(), plan.expectedCommit(), plan.databasePort(),
                        plan.administratorUser(), verification.postgresVersion(),
                        verification.mainMigrations(),
                        verification.governanceHistoryPresent(),
                        verification.governanceObjectsPresent(),
                        verification.publicBusinessObjectsPresent(),
                        verification.factOrAcceptanceRowsPresent(),
                        startedAt, completedAt, Phase.READBACK_VERIFIED,
                        plan.formalExecution()
                                ? "FORMAL_DATABASE_PREPARED_REQUIRES_NEW_FREEZE"
                                : "TEMPORARY_POSTGRES_TEST_ONLY");
            }
        } catch (DatabasePreparationException error) {
            throw error;
        } catch (Throwable error) {
            throw new DatabasePreparationException(
                    phase, mutated,
                    safeReason(error, phase), error);
        } finally {
            Arrays.fill(adminSecret, '\0');
            Arrays.fill(bootstrapSecret, '\0');
            if (dedicatedSecret != null) {
                Arrays.fill(dedicatedSecret, '\0');
            }
        }
    }

    @FunctionalInterface
    interface DedicatedPasswordSupplier {
        char[] read();
    }

    @FunctionalInterface
    interface BootstrapSecretRegistrar {
        void register(char[] secret);
    }

    static Flyway mainFlyway(DataSource dataSource) {
        MainFlywayContract contract = MainFlywayContract.frozen();
        Flyway flyway = Flyway.configure()
                .dataSource(Objects.requireNonNull(dataSource, "dataSource"))
                .schemas(TushareControlledAcceptanceDatabasePreparationPlan.SCHEMA)
                .defaultSchema(TushareControlledAcceptanceDatabasePreparationPlan.SCHEMA)
                .table(contract.historyTable())
                .locations(contract.location())
                .target(MigrationVersion.fromVersion(contract.targetVersion()))
                .baselineOnMigrate(contract.baselineOnMigrate())
                .outOfOrder(contract.outOfOrder())
                .validateOnMigrate(true)
                .cleanDisabled(contract.cleanDisabled())
                .load();
        if (flyway.getConfiguration().isBaselineOnMigrate()
                || !flyway.getConfiguration().isCleanDisabled()
                || flyway.getConfiguration().isOutOfOrder()
                || !List.of(TushareControlledAcceptanceDatabasePreparationPlan.MAIN_LOCATION)
                .equals(Arrays.stream(flyway.getConfiguration().getLocations())
                        .map(Object::toString).toList())) {
            throw new IllegalStateException(
                    "TUSHARE_DATABASE_PREPARATION_FLYWAY_CONFIGURATION_INVALID");
        }
        return flyway;
    }

    record MainFlywayContract(
            String location,
            String historyTable,
            String targetVersion,
            boolean baselineOnMigrate,
            boolean cleanDisabled,
            boolean outOfOrder,
            boolean repairExposed
    ) {
        MainFlywayContract {
            if (!TushareControlledAcceptanceDatabasePreparationPlan.MAIN_LOCATION
                    .equals(location)
                    || !TushareControlledAcceptanceDatabasePreparationPlan.MAIN_HISTORY
                    .equals(historyTable)
                    || !"13".equals(targetVersion)
                    || baselineOnMigrate || !cleanDisabled || outOfOrder
                    || repairExposed) {
                throw new IllegalArgumentException(
                        "TUSHARE_DATABASE_PREPARATION_FLYWAY_CONTRACT_INVALID");
            }
        }

        static MainFlywayContract frozen() {
            return new MainFlywayContract(
                    TushareControlledAcceptanceDatabasePreparationPlan.MAIN_LOCATION,
                    TushareControlledAcceptanceDatabasePreparationPlan.MAIN_HISTORY,
                    "13", false, true, false, false);
        }
    }

    private static void migrateMainOnly(DataSource dataSource) {
        Flyway flyway = mainFlyway(dataSource);
        Logger flywayLogger = null;
        Level previousLevel = null;
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            flywayLogger = context.getLogger("org.flywaydb");
            previousLevel = flywayLogger.getLevel();
            flywayLogger.setLevel(Level.OFF);
        }
        try {
            flyway.migrate();
            List<String> applied = Arrays.stream(flyway.info().applied())
                    .map(MigrationInfo::getVersion)
                    .filter(Objects::nonNull)
                    .map(MigrationVersion::getVersion)
                    .toList();
            if (!EXPECTED_MAIN_MIGRATIONS.equals(applied)
                    || flyway.info().pending().length != 0) {
                throw new IllegalStateException(
                        "TUSHARE_DATABASE_PREPARATION_MAIN_HISTORY_INVALID");
            }
        } finally {
            if (flywayLogger != null) {
                flywayLogger.setLevel(previousLevel);
            }
        }
    }

    private static void requireAdministratorTarget(
            Connection connection,
            TushareControlledAcceptanceDatabasePreparationPlan plan
    ) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT current_database(), current_user, rolsuper
                  FROM pg_roles
                 WHERE rolname = current_user
                """)) {
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw blocked(Phase.ADMIN_PREFLIGHT, false,
                            "TUSHARE_DATABASE_PREPARATION_ADMIN_IDENTITY_MISSING");
                }
                if (!TushareControlledAcceptanceDatabasePreparationPlan.ADMIN_DATABASE
                        .equals(rows.getString(1))) {
                    throw blocked(Phase.ADMIN_PREFLIGHT, false,
                            "TUSHARE_DATABASE_PREPARATION_ADMIN_DATABASE_INVALID");
                }
                if (!plan.administratorUser().equals(rows.getString(2))) {
                    throw blocked(Phase.ADMIN_PREFLIGHT, false,
                            "TUSHARE_DATABASE_PREPARATION_ADMIN_USER_INVALID");
                }
                String expectedPrefix = "jdbc:postgresql://127.0.0.1:"
                        + plan.databasePort() + "/postgres";
                if (connection.getMetaData().getURL() == null
                        || !connection.getMetaData().getURL()
                        .startsWith(expectedPrefix)) {
                    throw blocked(Phase.ADMIN_PREFLIGHT, false,
                            "TUSHARE_DATABASE_PREPARATION_ADMIN_HOST_INVALID");
                }
                if (!rows.getBoolean(3)) {
                    throw blocked(Phase.ADMIN_PREFLIGHT, false,
                            "TUSHARE_DATABASE_PREPARATION_ADMIN_PRIVILEGE_INVALID");
                }
            }
        }
    }

    private static void requireFreshTarget(Connection connection)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                 SELECT EXISTS (SELECT 1 FROM pg_database WHERE datname = ?),
                        EXISTS (SELECT 1 FROM pg_roles WHERE rolname = ?),
                        EXISTS (
                          SELECT 1 FROM pg_database
                           WHERE datname NOT IN ('postgres', 'template0', 'template1'))
                """)) {
            query.setString(1,
                    TushareControlledAcceptanceDatabasePreparationPlan.DATABASE);
            query.setString(2,
                    TushareControlledAcceptanceDatabasePreparationPlan.USER);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next() || rows.getBoolean(1) || rows.getBoolean(2)) {
                    throw blocked(Phase.ADMIN_PREFLIGHT, false,
                            "TUSHARE_DATABASE_PREPARATION_TARGET_ALREADY_EXISTS");
                }
                if (rows.getBoolean(3)) {
                    throw blocked(Phase.ADMIN_PREFLIGHT, false,
                            "TUSHARE_DATABASE_PREPARATION_DEDICATED_INSTANCE_REQUIRED");
                }
            }
        }
    }

    private static void createDedicatedRole(
            Connection connection,
            char[] dedicatedPassword
    ) throws SQLException {
        try (PreparedStatement config = connection.prepareStatement(
                "SELECT set_config('stockquant.dbprep_password', ?, false)");
             CharArrayReader secretReader = new CharArrayReader(dedicatedPassword)) {
            config.setCharacterStream(1, secretReader, dedicatedPassword.length);
            try (ResultSet ignored = config.executeQuery()) {
                if (!ignored.next()) {
                    throw new SQLException(
                            "TUSHARE_DATABASE_PREPARATION_SECRET_BIND_FAILED");
                }
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    DO $stockquant_dbprep$
                    BEGIN
                      EXECUTE format(
                        'CREATE ROLE stock_quant_research WITH LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 4',
                        current_setting('stockquant.dbprep_password'));
                    END
                    $stockquant_dbprep$
                    """);
            statement.execute("RESET stockquant.dbprep_password");
        }
    }

    private static void createDedicatedDatabase(Connection connection)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE DATABASE stock_quant_research
                    WITH OWNER stock_quant_research
                    TEMPLATE template0 ENCODING 'UTF8'
                    """);
        }
    }

    private static void rotateDedicatedPassword(
            Connection connection,
            char[] dedicatedPassword
    ) throws SQLException {
        try (PreparedStatement config = connection.prepareStatement(
                "SELECT set_config('stockquant.dbprep_final_password', ?, false)");
             CharArrayReader secretReader = new CharArrayReader(dedicatedPassword)) {
            config.setCharacterStream(1, secretReader, dedicatedPassword.length);
            try (ResultSet ignored = config.executeQuery()) {
                if (!ignored.next()) {
                    throw new SQLException(
                            "TUSHARE_DATABASE_PREPARATION_SECRET_BIND_FAILED");
                }
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    DO $stockquant_dbprep$
                    BEGIN
                      EXECUTE format(
                        'ALTER ROLE stock_quant_research PASSWORD %L',
                        current_setting('stockquant.dbprep_final_password'));
                    END
                    $stockquant_dbprep$
                    """);
            statement.execute("RESET stockquant.dbprep_final_password");
        }
    }

    private static void hardenTargetForDedicatedBootstrap(Connection connection)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("REVOKE ALL ON DATABASE stock_quant_research FROM PUBLIC");
            statement.execute("GRANT CONNECT ON DATABASE stock_quant_research TO stock_quant_research");
            statement.execute("ALTER SCHEMA public OWNER TO CURRENT_USER");
            statement.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
            statement.execute("REVOKE ALL ON SCHEMA public FROM stock_quant_research");
        }
    }

    private static void createDedicatedSchemaAndExtension(Connection connection)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA tushare_research");
            statement.execute("REVOKE ALL ON SCHEMA tushare_research FROM PUBLIC");
            statement.execute("GRANT USAGE, CREATE ON SCHEMA tushare_research TO stock_quant_research");
            statement.execute("CREATE EXTENSION btree_gist WITH SCHEMA tushare_research");
            statement.execute("ALTER ROLE stock_quant_research IN DATABASE stock_quant_research SET search_path TO tushare_research");
        }
    }

    private static Verification verify(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            String database = scalar(statement, "SELECT current_database()");
            String user = scalar(statement, "SELECT current_user");
            String schema = scalar(statement, "SELECT current_schema()");
            String searchPath = scalar(statement,
                    "SELECT current_setting('search_path')");
            String postgresVersion = scalar(statement,
                    "SELECT current_setting('server_version')");
            if (!TushareControlledAcceptanceDatabasePreparationPlan.DATABASE.equals(database)
                    || !TushareControlledAcceptanceDatabasePreparationPlan.USER.equals(user)
                    || !TushareControlledAcceptanceDatabasePreparationPlan.SCHEMA.equals(schema)
                    || !TushareControlledAcceptanceDatabasePreparationPlan.SEARCH_PATH
                    .equals(searchPath)) {
                throw new IllegalStateException(
                        "TUSHARE_DATABASE_PREPARATION_DATABASE_IDENTITY_INVALID");
            }
            List<String> migrations = strings(statement, """
                    SELECT version
                      FROM tushare_research.flyway_schema_history
                     WHERE success
                     ORDER BY installed_rank
                    """);
            int failed = integer(statement, """
                    SELECT count(*)
                      FROM tushare_research.flyway_schema_history
                     WHERE NOT success
                    """);
            int ranks = integer(statement, """
                    SELECT count(*)
                      FROM tushare_research.flyway_schema_history
                     WHERE installed_rank < 1 OR version IS NULL OR type <> 'SQL'
                    """);
            boolean governanceHistory = bool(statement, """
                    SELECT to_regclass('tushare_research.flyway_controlled_acceptance_history') IS NOT NULL
                    """);
            boolean governanceObjects = bool(statement, """
                    SELECT to_regclass('tushare_research.tushare_controlled_acceptance_execution') IS NOT NULL
                        OR to_regclass('tushare_research.tushare_controlled_acceptance_transition') IS NOT NULL
                    """);
            boolean publicObjects = bool(statement, """
                    SELECT EXISTS (
                      SELECT 1 FROM pg_class c
                      JOIN pg_namespace n ON n.oid = c.relnamespace
                      WHERE n.nspname = 'public'
                        AND c.relkind IN ('r','p','v','m','S','f'))
                    OR EXISTS (
                      SELECT 1 FROM pg_proc p
                      JOIN pg_namespace n ON n.oid = p.pronamespace
                      WHERE n.nspname = 'public')
                    """);
            boolean factRows = bool(statement, """
                    SELECT EXISTS (SELECT 1 FROM tushare_research.pit_market_fact_batches)
                        OR EXISTS (SELECT 1 FROM tushare_research.pit_market_fact_observations)
                    """);
            boolean roleInvalid = bool(statement, """
                    SELECT NOT (rolcanlogin AND NOT rolsuper AND NOT rolcreatedb
                         AND NOT rolcreaterole AND NOT rolinherit
                         AND NOT rolreplication AND NOT rolbypassrls
                         AND rolconnlimit = 4)
                      FROM pg_roles WHERE rolname = current_user
                    """);
            boolean publicSchemaCreate = bool(statement,
                    "SELECT has_schema_privilege(current_user, 'public', 'CREATE')");
            boolean targetConnect = bool(statement,
                    "SELECT has_database_privilege(current_user, current_database(), 'CONNECT')");
            boolean publicDatabasePrivileges = bool(statement, """
                    SELECT EXISTS (
                      SELECT 1
                        FROM pg_database d,
                             LATERAL aclexplode(COALESCE(
                               d.datacl, acldefault('d', d.datdba))) acl
                       WHERE d.datname = current_database()
                         AND acl.grantee = 0
                         AND acl.privilege_type IN ('CONNECT','CREATE','TEMPORARY'))
                    """);
            String schemaOwner = scalar(statement, """
                    SELECT pg_get_userbyid(nspowner)
                      FROM pg_namespace WHERE nspname = 'tushare_research'
                    """);
            String databaseOwner = scalar(statement, """
                    SELECT pg_get_userbyid(datdba)
                      FROM pg_database WHERE datname = current_database()
                    """);
            boolean relationOwnerMismatch = bool(statement, """
                    SELECT EXISTS (
                      SELECT 1 FROM pg_class c
                      JOIN pg_namespace n ON n.oid = c.relnamespace
                      WHERE n.nspname = 'tushare_research'
                        AND pg_get_userbyid(c.relowner) <> current_user
                        AND NOT EXISTS (
                          SELECT 1 FROM pg_depend d
                           WHERE d.classid = 'pg_class'::regclass
                             AND d.objid = c.oid
                             AND d.refclassid = 'pg_extension'::regclass
                             AND d.deptype = 'e'))
                    """);
            boolean procedureOwnerMismatch = bool(statement, """
                    SELECT EXISTS (
                      SELECT 1 FROM pg_proc p
                      JOIN pg_namespace n ON n.oid = p.pronamespace
                      WHERE n.nspname = 'tushare_research'
                        AND pg_get_userbyid(p.proowner) <> current_user
                        AND NOT EXISTS (
                          SELECT 1 FROM pg_depend d
                           WHERE d.classid = 'pg_proc'::regclass
                             AND d.objid = p.oid
                             AND d.refclassid = 'pg_extension'::regclass
                             AND d.deptype = 'e'))
                    """);
            String extensionSchema = scalar(statement, """
                    SELECT n.nspname
                      FROM pg_extension e
                      JOIN pg_namespace n ON n.oid = e.extnamespace
                     WHERE e.extname = 'btree_gist'
                    """);
            if (!TushareControlledAcceptanceDatabasePreparationPlan.USER
                    .equals(databaseOwner)) {
                throw new IllegalStateException(
                        "TUSHARE_DATABASE_PREPARATION_DATABASE_OWNER_INVALID");
            }
            if (!TushareControlledAcceptanceDatabasePreparationPlan.USER
                    .equals(schemaOwner)) {
                throw new IllegalStateException(
                        "TUSHARE_DATABASE_PREPARATION_SCHEMA_OWNER_INVALID");
            }
            if (relationOwnerMismatch) {
                throw new IllegalStateException(
                        "TUSHARE_DATABASE_PREPARATION_RELATION_OWNER_INVALID");
            }
            if (procedureOwnerMismatch) {
                throw new IllegalStateException(
                        "TUSHARE_DATABASE_PREPARATION_PROCEDURE_OWNER_INVALID");
            }
            if (!EXPECTED_MAIN_MIGRATIONS.equals(migrations)
                    || failed != 0 || ranks != 0 || governanceHistory
                    || governanceObjects || publicObjects || factRows
                    || roleInvalid || publicSchemaCreate || !targetConnect
                    || publicDatabasePrivileges
                    || !TushareControlledAcceptanceDatabasePreparationPlan.SCHEMA
                    .equals(extensionSchema)) {
                throw new IllegalStateException(
                        "TUSHARE_DATABASE_PREPARATION_READBACK_INVALID");
            }
            return new Verification(postgresVersion, migrations,
                    governanceHistory, governanceObjects, publicObjects, factRows);
        }
    }

    private static TushareControlledAcceptanceDatabasePreparationDataSource dataSource(
            int port,
            String database,
            String user,
            String schema,
            char[] password
    ) {
        return new TushareControlledAcceptanceDatabasePreparationDataSource(
                port, database, user, schema, password);
    }

    private static String scalar(Statement statement, String sql)
            throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) {
                throw new SQLException("TUSHARE_DATABASE_PREPARATION_RESULT_MISSING");
            }
            return result.getString(1);
        }
    }

    private static int integer(Statement statement, String sql)
            throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) {
                throw new SQLException("TUSHARE_DATABASE_PREPARATION_RESULT_MISSING");
            }
            return result.getInt(1);
        }
    }

    private static boolean bool(Statement statement, String sql)
            throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) {
                throw new SQLException("TUSHARE_DATABASE_PREPARATION_RESULT_MISSING");
            }
            return result.getBoolean(1);
        }
    }

    private static List<String> strings(Statement statement, String sql)
            throws SQLException {
        List<String> values = new ArrayList<>();
        try (ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                values.add(result.getString(1));
            }
        }
        return List.copyOf(values);
    }

    private static void requireSecret(char[] value) {
        if (value == null || value.length < 8) {
            throw new IllegalArgumentException(
                    "TUSHARE_DATABASE_PREPARATION_SECRET_INVALID");
        }
    }

    private char[] bootstrapSecret() {
        byte[] entropy = new byte[32];
        secureRandom.nextBytes(entropy);
        try {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(entropy).toCharArray();
        } finally {
            Arrays.fill(entropy, (byte) 0);
        }
    }

    private static DatabasePreparationException blocked(
            Phase phase,
            boolean mutated,
            String code
    ) {
        return new DatabasePreparationException(phase, mutated, code, null);
    }

    private static String safeReason(Throwable error, Phase phase) {
        if (error != null && error.getMessage() != null
                && error.getMessage().matches("TUSHARE_[A-Z0-9_]+")) {
            return error.getMessage();
        }
        return "TUSHARE_DATABASE_PREPARATION_" + phase.name() + "_FAILED";
    }

    enum Phase {
        NON_SECRET_PLAN_VALIDATED,
        ADMIN_PREFLIGHT,
        ROLE_CREATED,
        DATABASE_CREATED,
        DEDICATED_SCHEMA_CREATED,
        PERMISSIONS_HARDENED,
        DEDICATED_SECRET_CONFIGURED,
        MAIN_MIGRATIONS_APPLIED,
        READBACK_VERIFIED
    }

    record Verification(
            String postgresVersion,
            List<String> mainMigrations,
            boolean governanceHistoryPresent,
            boolean governanceObjectsPresent,
            boolean publicBusinessObjectsPresent,
            boolean factOrAcceptanceRowsPresent
    ) {
        Verification {
            postgresVersion = Objects.requireNonNull(postgresVersion, "postgresVersion");
            mainMigrations = List.copyOf(mainMigrations);
        }
    }

    record PreparationReport(
            String status,
            TushareControlledAcceptanceDatabasePreparationPlan.Mode mode,
            String gitCommit,
            int databasePort,
            String administratorUser,
            String postgresVersion,
            List<String> mainMigrations,
            boolean governanceHistoryPresent,
            boolean governanceObjectsPresent,
            boolean publicBusinessObjectsPresent,
            boolean factOrAcceptanceRowsPresent,
            Instant startedAt,
            Instant completedAt,
            Phase completedPhase,
            String conclusion
    ) {
        PreparationReport {
            status = requiredSafe(status);
            mode = Objects.requireNonNull(mode, "mode");
            gitCommit = TushareControlledAcceptanceExecution.commit(gitCommit);
            administratorUser = requiredSafe(administratorUser);
            postgresVersion = requiredSafe(postgresVersion);
            mainMigrations = List.copyOf(mainMigrations);
            startedAt = Objects.requireNonNull(startedAt, "startedAt");
            completedAt = Objects.requireNonNull(completedAt, "completedAt");
            completedPhase = Objects.requireNonNull(completedPhase, "completedPhase");
            conclusion = requiredSafe(conclusion);
        }

        String render(boolean auditClean) {
            return String.join(System.lineSeparator(),
                    "DATABASE_PREPARATION_STATUS=" + status,
                    "DATABASE_PREPARATION_MODE=" + mode,
                    "GIT_COMMIT=" + gitCommit,
                    "DATABASE_HOST=127.0.0.1",
                    "DATABASE_PORT=" + databasePort,
                    "DATABASE_NAME=stock_quant_research",
                    "DATABASE_USER=stock_quant_research",
                    "DATABASE_ADMIN_USER=" + administratorUser,
                    "DATABASE_SCHEMA=tushare_research",
                    "DATABASE_SEARCH_PATH=tushare_research",
                    "POSTGRESQL_VERSION=" + postgresVersion,
                    "MAIN_FLYWAY_HISTORY=" + String.join(",", mainMigrations),
                    "MAIN_FLYWAY_VERSION=" + (mainMigrations.isEmpty()
                            ? "NOT_RUN" : mainMigrations.get(mainMigrations.size() - 1)),
                    "GOVERNANCE_HISTORY_PRESENT=" + governanceHistoryPresent,
                    "GOVERNANCE_OBJECTS_PRESENT=" + governanceObjectsPresent,
                    "PUBLIC_PRIVILEGES_REVOKED=" + (mainMigrations.isEmpty()
                            ? "NOT_VERIFIED" : "true"),
                    "PUBLIC_BUSINESS_OBJECTS_PRESENT=" + publicBusinessObjectsPresent,
                    "DEDICATED_ROLE_PRIVILEGES=" + (mainMigrations.isEmpty()
                            ? "NOT_VERIFIED"
                            : "LOGIN,NOSUPERUSER,NOCREATEDB,NOCREATEROLE,"
                            + "NOINHERIT,NOREPLICATION,NOBYPASSRLS,"
                            + "CONNECTION_LIMIT_4"),
                    "FACT_OR_ACCEPTANCE_ROWS_PRESENT=" + factOrAcceptanceRowsPresent,
                    "STARTED_AT=" + startedAt,
                    "COMPLETED_AT=" + completedAt,
                    "COMPLETED_PHASE=" + completedPhase,
                    "OUTPUT_AUDIT_CLEAN=" + auditClean,
                    "CONCLUSION=" + conclusion);
        }

        private static String requiredSafe(String value) {
            if (value == null || value.isBlank()
                    || value.toLowerCase(java.util.Locale.ROOT).contains("password")
                    || value.toLowerCase(java.util.Locale.ROOT).contains("token")
                    || value.toLowerCase(java.util.Locale.ROOT).contains("jdbc:")) {
                throw new IllegalArgumentException(
                        "TUSHARE_DATABASE_PREPARATION_REPORT_INVALID");
            }
            return value;
        }
    }

    static final class DatabasePreparationException extends RuntimeException {
        private final Phase phase;
        private final boolean targetMutated;
        private final String safeCode;

        DatabasePreparationException(
                Phase phase,
                boolean targetMutated,
                String safeCode,
                Throwable cause
        ) {
            super(safeCode, cause);
            this.phase = Objects.requireNonNull(phase, "phase");
            this.targetMutated = targetMutated;
            this.safeCode = requiredCode(safeCode);
        }

        Phase phase() {
            return phase;
        }

        boolean targetMutated() {
            return targetMutated;
        }

        String safeCode() {
            return safeCode;
        }

        private static String requiredCode(String value) {
            if (value == null || !value.matches("TUSHARE_[A-Z0-9_]+")) {
                throw new IllegalArgumentException(
                        "TUSHARE_DATABASE_PREPARATION_ERROR_CODE_INVALID");
            }
            return value;
        }
    }
}
