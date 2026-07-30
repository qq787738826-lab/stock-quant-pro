package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.List;

/** Narrow transport seam used by the provider-neutral Tushare adapter. */
public interface TushareApiGateway {

    QueryResult query(
            String endpoint,
            ObjectNode parameters,
            List<String> fields,
            Duration timeout,
            QueryMode mode,
            TushareManualBoundedSession session
    );

    enum QueryMode {
        NORMAL,
        CONTROLLED_NO_RETRY
    }

    enum ErrorKind {
        PERMISSION_DENIED,
        RATE_LIMITED,
        TIMEOUT,
        NETWORK_ERROR,
        API_ERROR,
        STRUCTURE_CHANGED
    }

    record Table(
            List<String> fields,
            List<List<JsonNode>> rows
    ) {
        public Table {
            fields = List.copyOf(fields);
            rows = rows.stream()
                    .map(List::copyOf)
                    .toList();
        }
    }

    record QueryResult(
            Table table,
            int providerCallCount,
            int rateLimitRetryCount
    ) {
        public QueryResult {
            if (table == null
                    || providerCallCount <= 0
                    || rateLimitRetryCount < 0
                    || rateLimitRetryCount >= providerCallCount) {
                throw new IllegalArgumentException(
                        "invalid Tushare query result");
            }
        }
    }

    final class GatewayException extends RuntimeException {
        private final ErrorKind kind;
        private final String safeCode;
        private final int providerCallCount;
        private final int rateLimitRetryCount;

        public GatewayException(
                ErrorKind kind,
                String safeCode,
                String safeMessage,
                int providerCallCount,
                int rateLimitRetryCount,
                Throwable cause
        ) {
            super(safeMessage, cause);
            this.kind = kind;
            this.safeCode = safeCode;
            this.providerCallCount = providerCallCount;
            this.rateLimitRetryCount = rateLimitRetryCount;
        }

        public ErrorKind kind() {
            return kind;
        }

        public String safeCode() {
            return safeCode;
        }

        public int providerCallCount() {
            return providerCallCount;
        }

        public int rateLimitRetryCount() {
            return rateLimitRetryCount;
        }
    }
}
