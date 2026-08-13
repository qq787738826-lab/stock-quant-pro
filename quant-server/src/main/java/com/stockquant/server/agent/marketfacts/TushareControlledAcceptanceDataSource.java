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

/** Single-purpose, non-pooling DataSource for trusted one-shot processes. */
public final class TushareControlledAcceptanceDataSource
        implements DataSource, AutoCloseable {
    private final String jdbcUrl;
    private final String databaseUser;
    private char[] password;
    private volatile boolean closed;
    private PrintWriter logWriter;
    private int loginTimeout;

    public TushareControlledAcceptanceDataSource(
            int port,
            SslMode sslMode,
            char[] password
    ) {
        if (port <= 0 || port > 65_535) {
            throw new IllegalArgumentException(
                    "TUSHARE_CONTROLLED_ACCEPTANCE_DATABASE_PORT_INVALID");
        }
        this.databaseUser =
                TushareDedicatedResearchPersistenceGuard.REQUIRED_USER;
        this.jdbcUrl = "jdbc:postgresql://127.0.0.1:" + port + '/'
                + TushareDedicatedResearchPersistenceGuard.REQUIRED_DATABASE
                + "?currentSchema="
                + TushareDedicatedResearchPersistenceGuard.REQUIRED_SCHEMA
                + "&sslmode=" + Objects.requireNonNull(sslMode, "sslMode").wireValue;
        Objects.requireNonNull(password, "password");
        if (password.length < 8) {
            throw new IllegalArgumentException(
                    "TUSHARE_CONTROLLED_ACCEPTANCE_DATABASE_SECRET_INVALID");
        }
        this.password = password.clone();
    }

    @Override
    public Connection getConnection() throws SQLException {
        requireOpen();
        char[] local = password.clone();
        String passwordText = new String(local);
        Arrays.fill(local, '\0');
        Properties properties = new Properties();
        properties.setProperty("user", databaseUser);
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
                "TUSHARE_CONTROLLED_ACCEPTANCE_EXPLICIT_CREDENTIALS_FORBIDDEN");
    }

    @Override
    public PrintWriter getLogWriter() {
        return logWriter;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        this.logWriter = out;
    }

    @Override
    public void setLoginTimeout(int seconds) {
        if (seconds < 0) {
            throw new IllegalArgumentException("seconds");
        }
        loginTimeout = seconds;
        DriverManager.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() {
        return loginTimeout;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException(
                "TUSHARE_CONTROLLED_ACCEPTANCE_PARENT_LOGGER_UNAVAILABLE");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface != null && iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("TUSHARE_CONTROLLED_ACCEPTANCE_UNWRAP_REJECTED");
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
        logWriter = null;
    }

    public boolean closed() {
        return closed;
    }

    private void requireOpen() throws SQLException {
        if (closed || password == null) {
            throw new SQLException(
                    "TUSHARE_CONTROLLED_ACCEPTANCE_DATASOURCE_CLOSED");
        }
    }

    @Override
    public String toString() {
        return "TushareControlledAcceptanceDataSource[REDACTED]";
    }

    public enum SslMode {
        REQUIRE("require"),
        DISABLE_LOCAL_ONLY("disable");

        private final String wireValue;

        SslMode(String wireValue) {
            this.wireValue = wireValue;
        }
    }
}
