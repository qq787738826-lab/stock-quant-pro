package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

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
        CONTROLLED_NO_RETRY,
        /** One no-response resend per call, bounded again across the run. */
        CONTROLLED_NETWORK_RECOVERY
    }

    enum ErrorKind {
        PERMISSION_DENIED,
        RATE_LIMITED,
        TIMEOUT,
        NETWORK_ERROR,
        API_ERROR,
        STRUCTURE_CHANGED
    }

    /** Strictly non-secret transport evidence retained with a gateway error. */
    record GatewayDiagnostic(
            int httpStatus,
            Integer providerCode,
            String providerMessageCategory,
            String endpoint,
            List<String> requestParameterNames,
            String providerHost,
            String providerPath,
            String requestContentType,
            String responseContentType,
            boolean responseJsonValid
    ) {
        public GatewayDiagnostic {
            if (httpStatus < 100 || httpStatus > 599
                    || providerCode != null
                    && (providerCode < -999_999 || providerCode > 999_999)
                    || providerMessageCategory == null
                    || !providerMessageCategory.matches("[A-Z][A-Z0-9_]{2,63}")
                    || endpoint == null
                    || !endpoint.matches("[a-z][a-z0-9_]{0,63}")
                    || providerHost == null
                    || !providerHost.toLowerCase(Locale.ROOT)
                    .equals("api.tushare.pro")
                    || providerPath == null || providerPath.isBlank()
                    || !"application/json".equals(requestContentType)
                    || responseContentType == null
                    || responseContentType.length() > 128
                    || responseContentType.matches(".*[\\x00-\\x1F\\x7F].*")) {
                throw new IllegalArgumentException(
                        "invalid Tushare gateway diagnostic");
            }
            requestParameterNames = List.copyOf(requestParameterNames);
            if (requestParameterNames.isEmpty()
                    || requestParameterNames.stream().anyMatch(name ->
                    name == null
                            || !name.matches("[a-z][a-z0-9_]{0,63}"))) {
                throw new IllegalArgumentException(
                        "invalid Tushare gateway diagnostic");
            }
        }
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
        private final GatewayDiagnostic diagnostic;

        public GatewayException(
                ErrorKind kind,
                String safeCode,
                String safeMessage,
                int providerCallCount,
                int rateLimitRetryCount,
                Throwable cause
        ) {
            this(kind, safeCode, safeMessage, providerCallCount,
                    rateLimitRetryCount, cause, null);
        }

        public GatewayException(
                ErrorKind kind,
                String safeCode,
                String safeMessage,
                int providerCallCount,
                int rateLimitRetryCount,
                Throwable cause,
                GatewayDiagnostic diagnostic
        ) {
            super(safeMessage, cause);
            this.kind = kind;
            this.safeCode = safeCode;
            this.providerCallCount = providerCallCount;
            this.rateLimitRetryCount = rateLimitRetryCount;
            this.diagnostic = diagnostic;
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

        public GatewayDiagnostic diagnostic() {
            return diagnostic;
        }
    }
}
