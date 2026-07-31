package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.ErrorKind;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.GatewayException;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.QueryMode;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.QueryResult;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.Table;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
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
                        HttpClient.newBuilder()
                                .connectTimeout(
                                        properties.getConnectTimeout())
                                .followRedirects(
                                        HttpClient.Redirect.NEVER)
                                .build()),
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
        int maximumRetries = mode == QueryMode.CONTROLLED_NO_RETRY
                || !session.automaticRetryAllowed()
                ? 0 : properties.getMaximumRateLimitRetries();
        int calls = 0;
        int retries = 0;
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
                JsonNode response = execute(
                        endpoint, parameters, fields, timeout);
                return new QueryResult(
                        parse(response), calls, retries);
            } catch (GatewayException error) {
                if (error.kind() != ErrorKind.RATE_LIMITED
                        || retries >= maximumRetries) {
                    throw withCounts(error, calls, retries);
                }
                retries++;
                awaitRetry();
            }
        }
    }

    private JsonNode execute(
            String endpoint,
            ObjectNode parameters,
            List<String> fields,
            Duration timeout
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
        HttpExchangeResult exchange;
        try {
            Duration effectiveTimeout = timeout.compareTo(
                    properties.getReadTimeout()) <= 0
                    ? timeout : properties.getReadTimeout();
            exchange = httpExchangeStrategy.post(
                    baseUri,
                    requestBody,
                    effectiveTimeout);
        } catch (HttpTimeoutException error) {
            throw failure(
                    ErrorKind.TIMEOUT,
                    "TUSHARE_TIMEOUT",
                    "Tushare request timed out",
                    error);
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
        if (exchange.statusCode() == 429) {
            throw failure(
                    ErrorKind.RATE_LIMITED,
                    "TUSHARE_HTTP_429",
                    "Tushare rate limit reached",
                    null);
        }
        if (exchange.statusCode() < 200
                || exchange.statusCode() >= 300) {
            throw failure(
                    ErrorKind.NETWORK_ERROR,
                    "TUSHARE_HTTP_ERROR",
                    "Tushare HTTP request failed with status "
                            + exchange.statusCode(),
                    null);
        }
        JsonNode response;
        try {
            response = objectMapper.readTree(exchange.body());
        } catch (JsonProcessingException error) {
            throw failure(
                    ErrorKind.STRUCTURE_CHANGED,
                    "TUSHARE_RESPONSE_JSON_INVALID",
                    "Tushare response JSON is invalid",
                    error);
        }
        if (response == null) {
            throw failure(
                    ErrorKind.STRUCTURE_CHANGED,
                    "TUSHARE_NULL_RESPONSE",
                    "Tushare returned an empty response envelope",
                    null);
        }
        return response;
    }

    private Table parse(JsonNode response) {
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
            if (code == 429 || isRateLimitMessage(message)) {
                throw failure(
                        ErrorKind.RATE_LIMITED,
                        "TUSHARE_API_RATE_LIMITED",
                        message,
                        null);
            }
            if (code == 2002) {
                throw failure(
                        ErrorKind.PERMISSION_DENIED,
                        "TUSHARE_PERMISSION_DENIED",
                        message,
                        null);
            }
            throw failure(
                    ErrorKind.API_ERROR,
                    "TUSHARE_API_ERROR_" + code,
                    message,
                    null);
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

    private static boolean isRateLimitMessage(String message) {
        String normalized = message == null
                ? "" : message.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("每分钟最多访问")
                || normalized.contains("频次限制")
                || normalized.contains("rate limit");
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
                error.getCause());
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

    record HttpExchangeResult(int statusCode, String body) {
        HttpExchangeResult {
            if (statusCode < 100 || statusCode > 599 || body == null) {
                throw new IllegalArgumentException(
                        "invalid Tushare HTTP exchange result");
            }
        }
    }

    @FunctionalInterface
    interface HttpExchangeStrategy {
        HttpExchangeResult post(
                URI uri,
                String body,
                Duration timeout
        ) throws IOException, InterruptedException;
    }

    private record JdkHttpExchangeStrategy(
            HttpClient httpClient
    ) implements HttpExchangeStrategy {
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
                    response.statusCode(), response.body());
        }
    }

    @FunctionalInterface
    interface RetryWaitStrategy {
        void await(Duration duration) throws InterruptedException;
    }
}
