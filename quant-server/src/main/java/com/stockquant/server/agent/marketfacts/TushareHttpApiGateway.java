package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.ErrorKind;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.GatewayDiagnostic;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.GatewayException;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.QueryMode;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.QueryResult;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.Table;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** HTTPS JSON client with bounded rate-limit retries and no response logging. */
@Component
public final class TushareHttpApiGateway
        implements TushareApiGateway, F1cRateLimitedGateway {

    private static final Set<String> ALLOWED_ENDPOINTS = Set.of(
            "stock_basic", "trade_cal", "daily", "adj_factor", "dividend");
    private static final String JSON_CONTENT_TYPE = "application/json";

    private final ObjectMapper objectMapper;
    private final TushareMarketFactProperties properties;
    private final TushareTokenRateLimiter rateLimiter;
    private final URI baseUri;
    private final HttpExchangeStrategy httpExchangeStrategy;
    private final RetryWaitStrategy retryWaitStrategy;

    @Autowired
    public TushareHttpApiGateway(
            ObjectMapper objectMapper,
            TushareMarketFactProperties properties,
            TushareTokenRateLimiter rateLimiter
    ) {
        this(
                objectMapper,
                properties,
                rateLimiter,
                properties.validatedBaseUri(),
                new JdkHttpExchangeStrategy(
                        properties.getConnectTimeout()),
                duration -> Thread.sleep(
                        duration.toMillis(),
                        duration.minusMillis(
                                duration.toMillis()).getNano()));
    }

    @Override
    public F1cRateLimitedGatewayContract f1cRateLimitContract() {
        return F1cRateLimitedGatewayContract.from(
                rateLimiter.policy(), rateLimiter);
    }

    TushareHttpApiGateway(
            ObjectMapper objectMapper,
            TushareMarketFactProperties properties,
            TushareTokenRateLimiter rateLimiter,
            URI baseUri,
            HttpExchangeStrategy httpExchangeStrategy,
            RetryWaitStrategy retryWaitStrategy
    ) {
        this.objectMapper = Objects.requireNonNull(
                objectMapper, "objectMapper");
        this.properties = Objects.requireNonNull(
                properties, "properties");
        this.rateLimiter = Objects.requireNonNull(
                rateLimiter, "rateLimiter");
        this.baseUri = Objects.requireNonNull(baseUri, "baseUri");
        this.httpExchangeStrategy = Objects.requireNonNull(
                httpExchangeStrategy, "httpExchangeStrategy");
        this.retryWaitStrategy = Objects.requireNonNull(
                retryWaitStrategy, "retryWaitStrategy");
        properties.validateFrozenContract();
    }

    @Override
    public QueryResult query(
            String endpoint,
            ObjectNode parameters,
            List<String> fields,
            Duration timeout,
            QueryMode mode,
            TushareManualBoundedSession session
    ) {
        validateRequest(
                endpoint, parameters, fields, timeout, mode, session);
        properties.requireManualBoundedToken();
        int maximumRetries = mode != QueryMode.NORMAL
                || !session.automaticRetryAllowed()
                ? 0 : properties.getMaximumRateLimitRetries();
        int calls = 0;
        int retries = 0;
        boolean networkRecoveryUsed = false;
        HttpExchangeStrategy exchangeStrategy = httpExchangeStrategy;
        while (true) {
            session.authorizeAndReserve(endpoint, parameters);
            try {
                rateLimiter.acquire(endpoint);
            } catch (TushareTokenRateLimiter.QuotaException error) {
                throw failure(
                        ErrorKind.RATE_LIMITED,
                        error.safeCode(),
                        "Tushare application quota is exhausted",
                        error);
            }
            calls++;
            try {
                ResponseEnvelope response = execute(
                        endpoint, parameters, fields, timeout,
                        mode, exchangeStrategy);
                return new QueryResult(
                        parse(response), calls, retries);
            } catch (GatewayException error) {
                if (eligibleNetworkRecovery(error, mode,
                        networkRecoveryUsed)
                        && session.networkRecoveryAvailable()) {
                    try {
                        awaitNetworkRecovery();
                    } catch (GatewayException waitFailure) {
                        throw withCounts(waitFailure, calls, retries);
                    }
                    if (!session.reserveNetworkRecovery()) {
                        throw withCounts(error, calls, retries);
                    }
                    exchangeStrategy = exchangeStrategy.freshConnection();
                    retries++;
                    networkRecoveryUsed = true;
                    continue;
                }
                if (error.kind() != ErrorKind.RATE_LIMITED
                        || retries >= maximumRetries) {
                    throw withCounts(error, calls, retries);
                }
                retries++;
                awaitRetry();
            }
        }
    }

    private ResponseEnvelope execute(
            String endpoint,
            ObjectNode parameters,
            List<String> fields,
            Duration timeout,
            QueryMode mode,
            HttpExchangeStrategy exchangeStrategy
    ) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("api_name", endpoint);
        body.put("token", properties.requireManualBoundedToken());
        body.set("params", parameters.deepCopy());
        body.put("fields", String.join(",", fields));
        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException error) {
            throw failure(
                    ErrorKind.API_ERROR,
                    "TUSHARE_REQUEST_JSON_INVALID",
                    "Tushare request could not be serialized",
                    error);
        }
        List<String> parameterNames = new ArrayList<>();
        parameters.fieldNames().forEachRemaining(parameterNames::add);
        parameterNames.sort(String::compareTo);
        HttpExchangeResult exchange;
        long startedNanos = System.nanoTime();
        Duration effectiveTimeout = effectiveTimeout(endpoint, timeout, mode);
        try {
            exchange = exchangeStrategy.post(
                    baseUri,
                    requestBody,
                    effectiveTimeout);
        } catch (HttpConnectTimeoutException error) {
            throw noResponseTimeout("CONNECT_TIMEOUT",
                    "TUSHARE_CONNECT_TIMEOUT_NO_RESPONSE", endpoint,
                    parameterNames, properties.getConnectTimeout(),
                    startedNanos, error);
        } catch (HttpTimeoutException error) {
            throw noResponseTimeout("REQUEST_TIMEOUT",
                    "TUSHARE_REQUEST_TIMEOUT_NO_RESPONSE", endpoint,
                    parameterNames, effectiveTimeout, startedNanos, error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw failure(
                    ErrorKind.NETWORK_ERROR,
                    "TUSHARE_REQUEST_INTERRUPTED",
                    "Tushare request was interrupted",
                    error);
        } catch (IOException error) {
            throw failure(
                    ErrorKind.NETWORK_ERROR,
                    "TUSHARE_NETWORK_ERROR",
                    "Tushare network request failed",
                    error);
        }
        GatewayDiagnostic transport = diagnostic(
                exchange, endpoint, parameterNames, null,
                "NOT_PARSED", false);
        if (exchange.statusCode() == 429) {
            throw failure(
                    ErrorKind.RATE_LIMITED,
                    "TUSHARE_HTTP_429",
                    "Tushare rate limit reached",
                    null,
                    transport);
        }
        if (exchange.statusCode() < 200
                || exchange.statusCode() >= 300) {
            String safeCode = switch (exchange.statusCode()) {
                case 401 -> "TUSHARE_HTTP_UNAUTHORIZED_401";
                case 403 -> "TUSHARE_HTTP_FORBIDDEN_403";
                default -> "TUSHARE_HTTP_STATUS_" + exchange.statusCode();
            };
            throw failure(
                    ErrorKind.NETWORK_ERROR,
                    safeCode,
                    "Tushare HTTP request failed with status "
                            + exchange.statusCode(),
                    null,
                    transport);
        }
        JsonNode response;
        try {
            response = objectMapper.readTree(exchange.body());
        } catch (JsonProcessingException error) {
            throw failure(
                    ErrorKind.STRUCTURE_CHANGED,
                    "TUSHARE_RESPONSE_JSON_INVALID",
                    "Tushare response JSON is invalid",
                    error,
                    transport);
        }
        if (response == null) {
            throw failure(
                    ErrorKind.STRUCTURE_CHANGED,
                    "TUSHARE_NULL_RESPONSE",
                    "Tushare returned an empty response envelope",
                    null,
                    transport);
        }
        return new ResponseEnvelope(response, diagnostic(
                exchange, endpoint, parameterNames, null,
                "NOT_APPLICABLE", true));
    }

    private Table parse(ResponseEnvelope envelope) {
        JsonNode response = envelope.body();
        JsonNode codeNode = response.get("code");
        if (codeNode == null || !codeNode.canConvertToInt()) {
            throw failure(
                    ErrorKind.STRUCTURE_CHANGED,
                    "TUSHARE_CODE_MISSING",
                    "Tushare response code is missing",
                    null);
        }
        int code = codeNode.intValue();
        if (code != 0) {
            String message = safeProviderMessage(
                    response.path("msg").asText("provider error"));
            String category = providerMessageCategory(message);
            GatewayDiagnostic diagnostic = withProvider(
                    envelope.diagnostic(), code, category);
            if (code == 429 || "RATE_LIMITED".equals(category)) {
                throw failure(
                        ErrorKind.RATE_LIMITED,
                        "TUSHARE_API_RATE_LIMITED",
                        message,
                        null,
                        diagnostic);
            }
            if (code == 2002) {
                throw failure(
                        ErrorKind.PERMISSION_DENIED,
                        "TUSHARE_PERMISSION_DENIED",
                        message,
                        null,
                        withProvider(envelope.diagnostic(), code,
                                "PERMISSION_DENIED"));
            }
            if (code == 40101) {
                String safeCode = switch (category) {
                    case "INVALID_CREDENTIAL" ->
                            "TUSHARE_CREDENTIAL_REJECTED_40101";
                    case "PERMISSION_DENIED" ->
                            "TUSHARE_PERMISSION_DENIED_40101";
                    case "ACCOUNT_RESTRICTED" ->
                            "TUSHARE_ACCOUNT_RESTRICTED_40101";
                    default -> "TUSHARE_API_ERROR_40101";
                };
                ErrorKind kind = "PERMISSION_DENIED".equals(category)
                        || "ACCOUNT_RESTRICTED".equals(category)
                        ? ErrorKind.PERMISSION_DENIED : ErrorKind.API_ERROR;
                throw failure(kind, safeCode, message, null, diagnostic);
            }
            throw failure(
                    ErrorKind.API_ERROR,
                    "TUSHARE_API_ERROR_" + code,
                    message,
                    null,
                    diagnostic);
        }

        JsonNode data = response.get("data");
        if (data == null || data.isNull()) {
            return new Table(List.of(), List.of());
        }
        JsonNode fieldNodes = data.get("fields");
        JsonNode itemNodes = data.get("items");
        if (fieldNodes == null || !fieldNodes.isArray()
                || itemNodes == null || !itemNodes.isArray()) {
            throw failure(
                    ErrorKind.STRUCTURE_CHANGED,
                    "TUSHARE_DATA_STRUCTURE_CHANGED",
                    "Tushare data fields/items structure changed",
                    null);
        }
        List<String> fields = new ArrayList<>();
        for (JsonNode field : fieldNodes) {
            if (!field.isTextual()
                    || !field.asText().matches(
                    "[a-z][a-z0-9_]{0,63}")) {
                throw failure(
                        ErrorKind.STRUCTURE_CHANGED,
                        "TUSHARE_FIELD_STRUCTURE_CHANGED",
                        "Tushare response field structure changed",
                        null);
            }
            fields.add(field.asText());
        }
        if (fields.size() != fields.stream().distinct().count()) {
            throw failure(
                    ErrorKind.STRUCTURE_CHANGED,
                    "TUSHARE_DUPLICATE_FIELDS",
                    "Tushare response contains duplicate fields",
                    null);
        }
        List<List<JsonNode>> rows = new ArrayList<>();
        for (JsonNode item : itemNodes) {
            if (!item.isArray() || item.size() != fields.size()) {
                throw failure(
                        ErrorKind.STRUCTURE_CHANGED,
                        "TUSHARE_ROW_STRUCTURE_CHANGED",
                        "Tushare response row structure changed",
                        null);
            }
            List<JsonNode> row = new ArrayList<>();
            item.forEach(value -> row.add(value.deepCopy()));
            rows.add(List.copyOf(row));
        }
        return new Table(fields, rows);
    }

    private void awaitRetry() {
        try {
            retryWaitStrategy.await(properties.getRetryBackoff());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw failure(
                    ErrorKind.RATE_LIMITED,
                    "TUSHARE_RETRY_INTERRUPTED",
                    "Tushare rate-limit retry was interrupted",
                    error);
        }
    }

    private void awaitNetworkRecovery() {
        try {
            retryWaitStrategy.await(properties.getRetryBackoff());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw failure(
                    ErrorKind.NETWORK_ERROR,
                    "TUSHARE_NETWORK_RECOVERY_INTERRUPTED",
                    "Tushare network recovery wait was interrupted",
                    error);
        }
    }

    private static boolean eligibleNetworkRecovery(
            GatewayException error,
            QueryMode mode,
            boolean recoveryUsed
    ) {
        boolean exactNoResponseFailure = error.kind()
                == ErrorKind.NETWORK_ERROR
                && "TUSHARE_NETWORK_ERROR".equals(error.safeCode())
                && error.diagnostic() == null
                || error.kind() == ErrorKind.TIMEOUT
                && Set.of("TUSHARE_CONNECT_TIMEOUT_NO_RESPONSE",
                "TUSHARE_REQUEST_TIMEOUT_NO_RESPONSE")
                .contains(error.safeCode())
                && error.noResponseDiagnostic() != null;
        return mode == QueryMode.CONTROLLED_NETWORK_RECOVERY
                && !recoveryUsed
                && exactNoResponseFailure;
    }

    private Duration effectiveTimeout(
            String endpoint,
            Duration requested,
            QueryMode mode
    ) {
        Duration ceiling = mode == QueryMode.CONTROLLED_NETWORK_RECOVERY
                && Set.of("daily", "adj_factor").contains(endpoint)
                ? properties.getMainboardReadTimeout()
                : properties.getReadTimeout();
        return requested.compareTo(ceiling) <= 0 ? requested : ceiling;
    }

    private static GatewayException noResponseTimeout(
            String stage,
            String code,
            String endpoint,
            List<String> parameterNames,
            Duration configuredTimeout,
            long startedNanos,
            HttpTimeoutException cause
    ) {
        long elapsedMillis = Math.max(0L,
                Duration.ofNanos(System.nanoTime() - startedNanos)
                        .toMillis());
        var evidence = new TushareApiGateway.NoResponseDiagnostic(
                stage, endpoint, parameterNames,
                configuredTimeout.toMillis(), elapsedMillis,
                false, false, false);
        return new GatewayException(ErrorKind.TIMEOUT, code,
                "Tushare transport timed out without an HTTP response",
                0, 0, cause, null, evidence);
    }

    private String safeProviderMessage(String message) {
        String safe = message == null || message.isBlank()
                ? "Tushare provider error" : message;
        if (properties.tokenPresent()) {
            safe = safe.replace(
                    properties.getToken(), "[REDACTED_TOKEN]");
        }
        safe = safe.replaceAll(
                "(?i)(token|authorization|cookie|session|password"
                        + "|username|account|mobile|phone)"
                        + "\\s*[:=]\\s*[^\\s,;]+",
                "$1=[REDACTED]");
        return safe.length() > 256 ? safe.substring(0, 256) : safe;
    }

    private static String providerMessageCategory(String message) {
        String normalized = message == null
                ? "" : message.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("rate limit")
                || normalized.contains("频次限制")
                || normalized.contains("每分钟最多访问")) {
            return "RATE_LIMITED";
        }
        if (normalized.contains("invalid token")
                || normalized.contains("token invalid")
                || normalized.contains("token验证失败")
                || normalized.contains("token认证失败")
                || normalized.contains("token无效")
                || normalized.contains("token失效")
                || normalized.contains("token错误")) {
            return "INVALID_CREDENTIAL";
        }
        if (normalized.contains("permission denied")
                || normalized.contains("access denied")
                || normalized.contains("没有访问")
                || normalized.contains("无权限")
                || normalized.contains("权限不足")
                || normalized.contains("积分不足")) {
            return "PERMISSION_DENIED";
        }
        if (normalized.contains("account disabled")
                || normalized.contains("account suspended")
                || normalized.contains("账号禁用")
                || normalized.contains("账户禁用")
                || normalized.contains("账号冻结")
                || normalized.contains("账户冻结")) {
            return "ACCOUNT_RESTRICTED";
        }
        return "OTHER_PROVIDER_ERROR";
    }

    private GatewayDiagnostic diagnostic(
            HttpExchangeResult exchange,
            String endpoint,
            List<String> parameterNames,
            Integer providerCode,
            String category,
            boolean jsonValid
    ) {
        String path = baseUri.getRawPath();
        if (path == null || path.isBlank()) {
            path = "/";
        }
        return new GatewayDiagnostic(
                exchange.statusCode(), providerCode, category, endpoint,
                parameterNames, baseUri.getHost(), path, JSON_CONTENT_TYPE,
                exchange.contentType(), jsonValid);
    }

    private static GatewayDiagnostic withProvider(
            GatewayDiagnostic diagnostic,
            int providerCode,
            String category
    ) {
        return new GatewayDiagnostic(
                diagnostic.httpStatus(), providerCode, category,
                diagnostic.endpoint(), diagnostic.requestParameterNames(),
                diagnostic.providerHost(), diagnostic.providerPath(),
                diagnostic.requestContentType(),
                diagnostic.responseContentType(),
                diagnostic.responseJsonValid());
    }

    private static void validateRequest(
            String endpoint,
            ObjectNode parameters,
            List<String> fields,
            Duration timeout,
            QueryMode mode,
            TushareManualBoundedSession session
    ) {
        if (!ALLOWED_ENDPOINTS.contains(endpoint)) {
            throw new IllegalArgumentException(
                    "unsupported Tushare endpoint");
        }
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(fields, "fields");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(session, "session");
        if (fields.isEmpty()
                || fields.stream().anyMatch(field ->
                field == null
                        || !field.matches("[a-z][a-z0-9_]{0,63}"))) {
            throw new IllegalArgumentException(
                    "invalid Tushare fields");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException(
                    "invalid Tushare timeout");
        }
    }

    private static GatewayException withCounts(
            GatewayException error,
            int calls,
            int retries
    ) {
        return new GatewayException(
                error.kind(),
                error.safeCode(),
                error.getMessage(),
                calls,
                retries,
                error.getCause(),
                error.diagnostic(),
                error.noResponseDiagnostic());
    }

    private static GatewayException failure(
            ErrorKind kind,
            String code,
            String message,
            Throwable cause
    ) {
        return new GatewayException(
                kind, code, message, 0, 0, cause);
    }

    private static GatewayException failure(
            ErrorKind kind,
            String code,
            String message,
            Throwable cause,
            GatewayDiagnostic diagnostic
    ) {
        return new GatewayException(
                kind, code, message, 0, 0, cause, diagnostic);
    }

    record HttpExchangeResult(
            int statusCode,
            String contentType,
            String body
    ) {
        HttpExchangeResult(int statusCode, String body) {
            this(statusCode, JSON_CONTENT_TYPE, body);
        }

        HttpExchangeResult {
            if (statusCode < 100 || statusCode > 599
                    || contentType == null || contentType.length() > 128
                    || contentType.matches(".*[\\x00-\\x1F\\x7F].*")
                    || body == null) {
                throw new IllegalArgumentException(
                        "invalid Tushare HTTP exchange result");
            }
        }
    }

    private record ResponseEnvelope(
            JsonNode body,
            GatewayDiagnostic diagnostic
    ) {
    }

    @FunctionalInterface
    interface HttpExchangeStrategy {
        HttpExchangeResult post(
                URI uri,
                String body,
                Duration timeout
        ) throws IOException, InterruptedException;

        /** Returns a strategy backed by a newly constructed connection. */
        default HttpExchangeStrategy freshConnection() {
            return this;
        }
    }

    private static final class JdkHttpExchangeStrategy
            implements HttpExchangeStrategy {
        private final Duration connectTimeout;
        private final HttpClient httpClient;

        private JdkHttpExchangeStrategy(Duration connectTimeout) {
            this.connectTimeout = Objects.requireNonNull(
                    connectTimeout, "connectTimeout");
            this.httpClient = HttpClient.newBuilder()
                    .connectTimeout(connectTimeout)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
        }

        @Override
        public HttpExchangeResult post(
                URI uri,
                String body,
                Duration timeout
        ) throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(
                            StandardCharsets.UTF_8));
            return new HttpExchangeResult(
                    response.statusCode(),
                    response.headers().firstValue("Content-Type")
                            .orElse("MISSING"),
                    response.body());
        }

        @Override
        public HttpExchangeStrategy freshConnection() {
            return new JdkHttpExchangeStrategy(connectTimeout);
        }
    }

    @FunctionalInterface
    interface RetryWaitStrategy {
        void await(Duration duration) throws InterruptedException;
    }
}
