package com.stockquant.server.agent.marketfacts;

import org.flywaydb.core.api.logging.Log;
import org.flywaydb.core.api.logging.LogCreator;

/** Deliberately discards Flyway connection metadata during audited runs. */
public final class SilentFlywayLogCreator implements LogCreator {
    private static final Log SILENT = new Log() {
        @Override
        public boolean isDebugEnabled() {
            return false;
        }

        @Override
        public void debug(String message) {
        }

        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message) {
        }

        @Override
        public void error(String message) {
        }

        @Override
        public void error(String message, Exception error) {
        }

        @Override
        public void notice(String message) {
        }
    };

    @Override
    public Log createLogger(Class<?> type) {
        return SILENT;
    }
}
