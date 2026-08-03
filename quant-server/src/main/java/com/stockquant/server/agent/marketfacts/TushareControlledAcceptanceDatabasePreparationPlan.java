package com.stockquant.server.agent.marketfacts;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict non-secret plan for the one-shot dedicated database preparation. */
record TushareControlledAcceptanceDatabasePreparationPlan(
        Mode mode,
        String expectedCommit,
        int databasePort,
        String administratorUser,
        String userApprovalReference,
        ExecutionScope executionScope
) {
    static final String HOST = "127.0.0.1";
    static final String ADMIN_DATABASE = "postgres";
    static final String DATABASE = "stock_quant_research";
    static final String USER = "stock_quant_research";
    static final String SCHEMA = "tushare_research";
    static final String SEARCH_PATH = SCHEMA;
    static final String MAIN_HISTORY = "flyway_schema_history";
    static final String GOVERNANCE_HISTORY =
            "flyway_controlled_acceptance_history";
    static final String MAIN_LOCATION = "classpath:db/migration";
    static final int MAIN_VERSION = 13;
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");
    private static final Set<String> ALLOWED_ARGUMENTS = Set.of(
            "mode", "expected-commit", "database-port", "admin-user",
            "user-approval-reference");

    TushareControlledAcceptanceDatabasePreparationPlan {
        mode = Objects.requireNonNull(mode, "mode");
        expectedCommit = required(expectedCommit, "expectedCommit");
        administratorUser = required(administratorUser, "administratorUser");
        userApprovalReference = userApprovalReference == null
                ? "" : userApprovalReference.trim();
        executionScope = Objects.requireNonNull(executionScope, "executionScope");
        if (!COMMIT.matcher(expectedCommit).matches()
                || databasePort <= 0 || databasePort > 65_535
                || !IDENTIFIER.matcher(administratorUser).matches()
                || USER.equals(administratorUser)
                || mode == Mode.CONTROLLED_DATABASE_PREPARATION
                && userApprovalReference.isBlank()
                || executionScope == ExecutionScope.TEMPORARY_POSTGRES_TEST
                && mode != Mode.PREPARATION_ONLY) {
            throw invalid("TUSHARE_DATABASE_PREPARATION_PLAN_INVALID");
        }
    }

    static TushareControlledAcceptanceDatabasePreparationPlan parse(
            String[] arguments
    ) {
        Map<String, String> values = new LinkedHashMap<>();
        if (arguments == null) {
            throw invalid("TUSHARE_DATABASE_PREPARATION_ARGUMENTS_INVALID");
        }
        for (String argument : arguments) {
            if (argument == null || !argument.startsWith("--")) {
                throw invalid("TUSHARE_DATABASE_PREPARATION_ARGUMENTS_INVALID");
            }
            int separator = argument.indexOf('=');
            if (separator <= 2 || separator == argument.length() - 1) {
                throw invalid("TUSHARE_DATABASE_PREPARATION_ARGUMENTS_INVALID");
            }
            String key = argument.substring(2, separator);
            String value = argument.substring(separator + 1);
            if (!ALLOWED_ARGUMENTS.contains(key)
                    || values.putIfAbsent(key, value) != null
                    || secretLike(key)) {
                throw invalid("TUSHARE_DATABASE_PREPARATION_ARGUMENTS_INVALID");
            }
        }
        if (!values.keySet().containsAll(Set.of(
                "expected-commit", "database-port", "admin-user"))) {
            throw invalid("TUSHARE_DATABASE_PREPARATION_ARGUMENTS_INVALID");
        }
        Mode mode = values.containsKey("mode")
                ? parseMode(values.get("mode")) : Mode.PREPARATION_ONLY;
        if (mode == Mode.CONTROLLED_DATABASE_PREPARATION
                && !values.containsKey("mode")) {
            throw invalid("TUSHARE_DATABASE_PREPARATION_FORMAL_MODE_EXPLICIT_REQUIRED");
        }
        try {
            return new TushareControlledAcceptanceDatabasePreparationPlan(
                    mode,
                    values.get("expected-commit"),
                    Integer.parseInt(values.get("database-port")),
                    values.get("admin-user"),
                    values.get("user-approval-reference"),
                    ExecutionScope.COMMAND_LINE);
        } catch (RuntimeException error) {
            if (error instanceof IllegalArgumentException
                    && error.getMessage() != null
                    && error.getMessage().startsWith("TUSHARE_")) {
                throw error;
            }
            throw invalid("TUSHARE_DATABASE_PREPARATION_ARGUMENTS_INVALID");
        }
    }

    static TushareControlledAcceptanceDatabasePreparationPlan temporaryTest(
            String expectedCommit,
            int port,
            String administratorUser
    ) {
        return new TushareControlledAcceptanceDatabasePreparationPlan(
                Mode.PREPARATION_ONLY, expectedCommit, port,
                administratorUser, "TEMPORARY_POSTGRES_TEST",
                ExecutionScope.TEMPORARY_POSTGRES_TEST);
    }

    boolean databaseExecutionAllowed() {
        return mode == Mode.CONTROLLED_DATABASE_PREPARATION
                || executionScope == ExecutionScope.TEMPORARY_POSTGRES_TEST;
    }

    boolean formalExecution() {
        return mode == Mode.CONTROLLED_DATABASE_PREPARATION;
    }

    private static Mode parseMode(String value) {
        try {
            return Mode.valueOf(value);
        } catch (RuntimeException error) {
            throw invalid("TUSHARE_DATABASE_PREPARATION_MODE_INVALID");
        }
    }

    private static boolean secretLike(String value) {
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("password") || lower.contains("token")
                || lower.contains("secret") || lower.contains("jdbc");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid("TUSHARE_DATABASE_PREPARATION_"
                    + field.toUpperCase(java.util.Locale.ROOT) + "_INVALID");
        }
        return value.trim();
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }

    enum Mode {
        PREPARATION_ONLY,
        CONTROLLED_DATABASE_PREPARATION
    }

    enum ExecutionScope {
        COMMAND_LINE,
        TEMPORARY_POSTGRES_TEST
    }
}
