package com.stockquant.server.agent.marketfacts;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Properties;
import java.util.logging.Logger;

/** Non-pooling localhost-only DataSource used solely by database preparation. */
final class TushareControlledAcceptanceDatabasePreparationDataSource
        implements DataSource, AutoCloseable {
    private final String jdbcUrl;
    private final String user;
    private char[] password;
    private volatile boolean closed;

    TushareControlledAcceptanceDatabasePreparationDataSource(
            int port,
            String database,
            String user,
            String schema,
            char[] password
    ) {
        if (port <= 0 || port > 65_535) {
            throw new IllegalArgumentException(
                    "TUSHARE_DATABASE_PREPARATION_PORT_INVALID");
        }
        this.user = requireIdentifier(user);
        String databaseName = requireIdentifier(database);
        String schemaName = schema == null ? "" : requireIdentifier(schema);
        Objects.requireNonNull(password, "password");
        if (password.length < 8) {
            throw new IllegalArgumentException(
                    "TUSHARE_DATABASE_PREPARATION_SECRET_INVALID");
        }
        this.password = password.clone();
        this.jdbcUrl = "jdbc:postgresql://127.0.0.1:" + port + '/'
                + databaseName + "?sslmode=disable"
                + (schemaName.isEmpty() ? "" : "&currentSchema=" + schemaName);
    }

    @Override
    public Connection getConnection() throws SQLException {
        requireOpen();
        char[] local = password.clone();
        String passwordText = new String(local);
        Arrays.fill(local, '\0');
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", passwordText);
        try {
            return DriverManager.getConnection(jdbcUrl, properties);
        } finally {
            properties.remove("password");
        }
    }

    @Override
    public Connection getConnection(String username, String suppliedPassword)
            throws SQLException {
        throw new SQLFeatureNotSupportedException(
                "TUSHARE_DATABASE_PREPARATION_EXPLICIT_CREDENTIALS_FORBIDDEN");
    }

    @Override
    public PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        if (out != null) {
            throw new IllegalArgumentException(
                    "TUSHARE_DATABASE_PREPARATION_LOG_WRITER_FORBIDDEN");
        }
    }

    @Override
    public void setLoginTimeout(int seconds) {
        if (seconds < 0 || seconds > 30) {
            throw new IllegalArgumentException(
                    "TUSHARE_DATABASE_PREPARATION_LOGIN_TIMEOUT_INVALID");
        }
        DriverManager.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() {
        return DriverManager.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException(
                "TUSHARE_DATABASE_PREPARATION_PARENT_LOGGER_UNAVAILABLE");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface != null && iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("TUSHARE_DATABASE_PREPARATION_UNWRAP_REJECTED");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface != null && iface.isInstance(this);
    }

    @Override
    public void close() {
        closed = true;
        if (password != null) {
            Arrays.fill(password, '\0');
            password = null;
        }
    }

    boolean closed() {
        return closed;
    }

    private void requireOpen() throws SQLException {
        if (closed || password == null) {
            throw new SQLException("TUSHARE_DATABASE_PREPARATION_DATASOURCE_CLOSED");
        }
    }

    private static String requireIdentifier(String value) {
        if (value == null || !value.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) {
            throw new IllegalArgumentException(
                    "TUSHARE_DATABASE_PREPARATION_IDENTIFIER_INVALID");
        }
        return value;
    }

    @Override
    public String toString() {
        return "TushareControlledAcceptanceDatabasePreparationDataSource[REDACTED]";
    }
}
