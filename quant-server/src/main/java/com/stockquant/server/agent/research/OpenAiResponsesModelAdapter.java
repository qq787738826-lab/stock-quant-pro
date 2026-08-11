package com.stockquant.server.agent.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.research.AgentResearchModels.ClaimType;
import com.stockquant.server.agent.research.AgentResearchModels.CriticIssueCode;
import com.stockquant.server.agent.research.AgentResearchModels.ModelUsage;
import com.stockquant.server.agent.research.AgentResearchModels.ToolCode;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * First provider adapter for M3. It uses OpenAI Responses Structured Outputs,
 * a pinned model snapshot, a fixed endpoint, and no provider fallback.
 */
public final class OpenAiResponsesModelAdapter implements ModelAdapter {
    static final URI RESPONSES_URI = URI.create(
            "https://api.openai.com/v1/responses");
    static final String ADAPTER_VERSION = "OPENAI_RESPONSES_ADAPTER_V1";
    static final int MAXIMUM_MODEL_CALLS = 13;
    static final int MAXIMUM_OUTPUT_TOKENS = 1_200;
    public static final BigDecimal M3_HARD_COST_LIMIT_USD =
            new BigDecimal("0.10");
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private static final BigDecimal MILLION = new BigDecimal("1000000");
    private static final BigDecimal INPUT_PER_MILLION =
            new BigDecimal("0.25");
    private static final BigDecimal CACHED_INPUT_PER_MILLION =
            new BigDecimal("0.025");
    private static final BigDecimal OUTPUT_PER_MILLION =
            new BigDecimal("2.00");

    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules();
    private final char[] apiKey;
    private final Duration timeout;
    private final Transport transport;
    private final BigDecimal hardCostLimitUsd;
    private BigDecimal accountedCostUsd = BigDecimal.ZERO;
    private int attemptedCallCount;
    private int networkCallCount;
    private int completedCallCount;
    private int inputTokenCount;
    private int outputTokenCount;
    private boolean terminated;
    private boolean closed;

    public OpenAiResponsesModelAdapter(char[] apiKey, Duration timeout) {
        this(apiKey, timeout, new JdkTransport(timeout),
                M3_HARD_COST_LIMIT_USD);
    }

    OpenAiResponsesModelAdapter(
            char[] apiKey,
            Duration timeout,
            Transport transport
    ) {
        this(apiKey, timeout, transport, M3_HARD_COST_LIMIT_USD);
    }

    OpenAiResponsesModelAdapter(
            char[] apiKey,
            Duration timeout,
            Transport transport,
            BigDecimal hardCostLimitUsd
    ) {
        Objects.requireNonNull(apiKey, "apiKey");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.hardCostLimitUsd = Objects.requireNonNull(hardCostLimitUsd,
                "hardCostLimitUsd");
        if (!isStructurallyValidApiKey(apiKey)
                || timeout.compareTo(Duration.ofSeconds(5)) < 0
                || timeout.compareTo(Duration.ofMinutes(2)) > 0
                || hardCostLimitUsd.signum() <= 0
                || hardCostLimitUsd.compareTo(M3_HARD_COST_LIMIT_USD) > 0) {
            throw AgentResearchModels.invalid(
                    "M3_OPENAI_ADAPTER_CONFIGURATION_INVALID");
        }
        this.apiKey = apiKey.clone();
    }

    @Override
    public Descriptor descriptor() {
        return new Descriptor("OPENAI", AgentResearchModels.REAL_MODEL,
                ADAPTER_VERSION, false);
    }

    @Override
    public synchronized ModelResponse complete(ModelRequest request) {
        Objects.requireNonNull(request, "request");
        if (closed) {
            throw AgentResearchModels.invalid("M3_OPENAI_ADAPTER_CLOSED");
        }
        if (terminated) {
            throw AgentResearchModels.invalid("M3_OPENAI_ADAPTER_TERMINATED");
        }
        if (attemptedCallCount >= MAXIMUM_MODEL_CALLS) {
            terminated = true;
            throw AgentResearchModels.invalid(
                    "M3_OPENAI_MODEL_CALL_BUDGET_EXHAUSTED");
        }
        String requestBody = requestBody(request);
        int requestBytes = requestBody.getBytes(StandardCharsets.UTF_8).length;
        BigDecimal reservation = maximumCallCost(requestBytes);
        if (accountedCostUsd.add(reservation)
                .compareTo(hardCostLimitUsd) > 0) {
            terminated = true;
            throw AgentResearchModels.invalid(
                    "M3_OPENAI_COST_BUDGET_PRECALL_REJECTED");
        }
        attemptedCallCount++;
        networkCallCount++;
        TransportResponse response;
        try {
            response = transport.post(RESPONSES_URI, apiKey, requestBody,
                    timeout);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            accountUnknownCall(reservation);
            throw new IllegalStateException("M3_OPENAI_REQUEST_INTERRUPTED");
        } catch (IOException exception) {
            accountUnknownCall(reservation);
            throw new IllegalStateException("M3_OPENAI_TRANSPORT_FAILED");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            accountUnknownCall(reservation);
            throw new IllegalStateException("M3_OPENAI_HTTP_STATUS_"
                    + response.statusCode());
        }
        if (response.body().getBytes(StandardCharsets.UTF_8).length
                > MAX_RESPONSE_BYTES
                || response.contentType() == null
                || !response.contentType().toLowerCase(Locale.ROOT)
                .startsWith("application/json")) {
            accountUnknownCall(reservation);
            throw new IllegalStateException("M3_OPENAI_RESPONSE_REJECTED");
        }
        ModelResponse parsed;
        try {
            parsed = parseResponse(response.body());
        } catch (RuntimeException error) {
            accountUnknownCall(reservation);
            throw error;
        }
        ModelUsage usage = parsed.usage();
        if (usage.inputTokens() > requestBytes
                || usage.outputTokens() > MAXIMUM_OUTPUT_TOKENS
                || accountedCostUsd.add(usage.estimatedCostUsd())
                .compareTo(hardCostLimitUsd) > 0) {
            accountUnknownCall(reservation);
            throw new IllegalStateException(
                    "M3_OPENAI_USAGE_BUDGET_INVALID");
        }
        accountedCostUsd = accountedCostUsd.add(usage.estimatedCostUsd());
        completedCallCount++;
        inputTokenCount += usage.inputTokens();
        outputTokenCount += usage.outputTokens();
        return parsed;
    }

    @Override
    public synchronized void close() {
        Arrays.fill(apiKey, '\0');
        terminated = true;
        closed = true;
    }

    public synchronized Telemetry telemetry() {
        return new Telemetry(attemptedCallCount, networkCallCount,
                completedCallCount, inputTokenCount, outputTokenCount,
                accountedCostUsd, hardCostLimitUsd, terminated, closed);
    }

    private String requestBody(ModelRequest request) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", AgentResearchModels.REAL_MODEL);
        root.put("store", false);
        root.put("max_output_tokens", MAXIMUM_OUTPUT_TOKENS);
        root.putObject("reasoning").put("effort", "minimal");
        root.put("instructions", request.systemPrompt());
        ArrayNode input = root.putArray("input");
        ObjectNode message = input.addObject();
        message.put("role", "user");
        ArrayNode content = message.putArray("content");
        ObjectNode inputText = content.addObject();
        inputText.put("type", "input_text");
        ObjectNode payload = mapper.createObjectNode();
        payload.put("protocolVersion",
                AgentResearchModels.MODEL_PROTOCOL_VERSION);
        payload.put("callId", request.callId());
        payload.put("agentRole", request.agentRole().name());
        payload.put("phase", request.phase());
        payload.put("promptVersion", request.promptVersion());
        payload.put("untrustedObjective", request.untrustedObjective());
        payload.set("allowedTools", mapper.valueToTree(request.allowedTools()));
        payload.set("evidence", mapper.valueToTree(request.evidence()));
        payload.set("priorFindingSummaries", mapper.valueToTree(
                request.priorFindingSummaries()));
        payload.put("revision", request.revision());
        payload.put("confidenceCap", request.confidenceCap());
        payload.put("inputFingerprint", request.inputFingerprint());
        inputText.put("text", AgentResearchCanonical.json(payload));
        ObjectNode format = root.putObject("text").putObject("format");
        format.put("type", "json_schema");
        format.put("name", "agent_research_response");
        format.put("strict", true);
        format.set("schema", schema());
        try {
            return mapper.writeValueAsString(root);
        } catch (IOException exception) {
            throw new IllegalStateException("M3_OPENAI_REQUEST_JSON_FAILED",
                    exception);
        }
    }

    private ObjectNode schema() {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        ArrayNode required = root.putArray("required");
        List.of("requestedTools", "claims", "summary", "issueCodes",
                "reworkRequested").forEach(required::add);
        ObjectNode properties = root.putObject("properties");
        enumArray(properties.putObject("requestedTools"), ToolCode.values());
        ObjectNode claims = properties.putObject("claims");
        claims.put("type", "array");
        claims.put("maxItems", 8);
        ObjectNode claim = claims.putObject("items");
        claim.put("type", "object");
        claim.put("additionalProperties", false);
        ArrayNode claimRequired = claim.putArray("required");
        List.of("claimType", "statement", "evidenceIds", "confidence")
                .forEach(claimRequired::add);
        ObjectNode claimProperties = claim.putObject("properties");
        enumValue(claimProperties.putObject("claimType"),
                ClaimType.values());
        claimProperties.putObject("statement").put("type", "string")
                .put("maxLength", 600);
        ObjectNode evidenceIds = claimProperties.putObject("evidenceIds");
        evidenceIds.put("type", "array");
        evidenceIds.put("uniqueItems", true);
        evidenceIds.putObject("items").put("type", "string");
        ObjectNode confidence = claimProperties.putObject("confidence");
        confidence.put("type", "number");
        confidence.put("minimum", 0);
        confidence.put("maximum", 1);
        properties.putObject("summary").put("type", "string")
                .put("maxLength", 800);
        enumArray(properties.putObject("issueCodes"),
                CriticIssueCode.values());
        properties.putObject("reworkRequested").put("type", "boolean");
        return root;
    }

    private static void enumArray(ObjectNode node, Enum<?>[] values) {
        node.put("type", "array");
        node.put("uniqueItems", true);
        ObjectNode items = node.putObject("items");
        enumValue(items, values);
    }

    private static void enumValue(ObjectNode node, Enum<?>[] values) {
        node.put("type", "string");
        ArrayNode allowed = node.putArray("enum");
        for (Enum<?> value : values) {
            allowed.add(value.name());
        }
    }

    private ModelResponse parseResponse(String body) {
        try {
            JsonNode root = mapper.readTree(body);
            if (!AgentResearchModels.REAL_MODEL.equals(root.path("model")
                    .asText())) {
                throw new IllegalStateException(
                        "M3_OPENAI_MODEL_SNAPSHOT_MISMATCH");
            }
            String outputText = outputText(root);
            JsonNode structured = mapper.readTree(outputText);
            requireExactFields(structured, SetNames.RESPONSE_FIELDS);
            List<ToolCode> tools = enums(structured.path("requestedTools"),
                    ToolCode.class);
            List<ModelClaim> claims = new ArrayList<>();
            JsonNode claimNodes = structured.path("claims");
            if (!claimNodes.isArray()) {
                throw parseFailure();
            }
            for (JsonNode node : claimNodes) {
                requireExactFields(node, SetNames.CLAIM_FIELDS);
                List<String> ids = new ArrayList<>();
                if (!node.path("evidenceIds").isArray()) {
                    throw parseFailure();
                }
                node.path("evidenceIds").forEach(value -> ids.add(
                        requiredText(value)));
                claims.add(new ModelClaim(
                        ClaimType.valueOf(requiredText(node.path(
                                "claimType"))),
                        requiredText(node.path("statement")), ids,
                        node.path("confidence").decimalValue()));
            }
            List<CriticIssueCode> issues = enums(
                    structured.path("issueCodes"), CriticIssueCode.class);
            ModelUsage usage = usage(root.path("usage"));
            return new ModelResponse(tools, claims,
                    requiredText(structured.path("summary")), issues,
                    structured.path("reworkRequested").asBoolean(), usage);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (RuntimeException | IOException exception) {
            throw new IllegalStateException("M3_OPENAI_RESPONSE_PARSE_FAILED",
                    exception);
        }
    }

    private static String outputText(JsonNode root) {
        JsonNode output = root.path("output");
        if (!output.isArray()) {
            throw parseFailure();
        }
        for (JsonNode item : output) {
            JsonNode content = item.path("content");
            if (!content.isArray()) {
                continue;
            }
            for (JsonNode value : content) {
                if ("output_text".equals(value.path("type").asText())
                        && value.path("text").isTextual()) {
                    return value.path("text").asText();
                }
            }
        }
        throw parseFailure();
    }

    private static <E extends Enum<E>> List<E> enums(
            JsonNode values,
            Class<E> type
    ) {
        if (!values.isArray()) {
            throw parseFailure();
        }
        List<E> result = new ArrayList<>();
        values.forEach(value -> result.add(Enum.valueOf(type,
                requiredText(value))));
        return List.copyOf(result);
    }

    private static ModelUsage usage(JsonNode usage) {
        if (!usage.isObject()) {
            throw parseFailure();
        }
        int input = nonNegativeInt(usage.path("input_tokens"));
        int output = nonNegativeInt(usage.path("output_tokens"));
        int cached = 0;
        JsonNode details = usage.path("input_tokens_details");
        if (details.isObject() && details.has("cached_tokens")) {
            cached = nonNegativeInt(details.path("cached_tokens"));
        }
        if (cached > input) {
            throw parseFailure();
        }
        BigDecimal cost = BigDecimal.valueOf(input - cached)
                .multiply(INPUT_PER_MILLION)
                .add(BigDecimal.valueOf(cached)
                        .multiply(CACHED_INPUT_PER_MILLION))
                .add(BigDecimal.valueOf(output)
                        .multiply(OUTPUT_PER_MILLION))
                .divide(MILLION, 12, RoundingMode.HALF_EVEN);
        return new ModelUsage(input, output, cost);
    }

    private static int nonNegativeInt(JsonNode value) {
        if (!value.canConvertToInt() || value.asInt() < 0) {
            throw parseFailure();
        }
        return value.asInt();
    }

    private static String requiredText(JsonNode value) {
        if (!value.isTextual() || value.asText().isBlank()) {
            throw parseFailure();
        }
        return value.asText();
    }

    private static void requireExactFields(JsonNode node,
                                           java.util.Set<String> expected) {
        if (!node.isObject()) {
            throw parseFailure();
        }
        java.util.Set<String> actual = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw parseFailure();
        }
    }

    public static boolean isStructurallyValidApiKey(char[] value) {
        Objects.requireNonNull(value, "value");
        if (value.length < 20 || value.length > 512) {
            return false;
        }
        for (char character : value) {
            if (Character.isWhitespace(character)
                    || Character.isISOControl(character)) {
                return false;
            }
        }
        return true;
    }

    private static BigDecimal maximumCallCost(int requestBytes) {
        if (requestBytes <= 0) {
            throw AgentResearchModels.invalid("M3_OPENAI_REQUEST_SIZE_INVALID");
        }
        return BigDecimal.valueOf(requestBytes)
                .multiply(INPUT_PER_MILLION)
                .add(BigDecimal.valueOf(MAXIMUM_OUTPUT_TOKENS)
                        .multiply(OUTPUT_PER_MILLION))
                .divide(MILLION, 12, RoundingMode.UP);
    }

    private void accountUnknownCall(BigDecimal reservation) {
        accountedCostUsd = accountedCostUsd.add(reservation);
        terminated = true;
    }

    private static IllegalStateException parseFailure() {
        return new IllegalStateException("M3_OPENAI_RESPONSE_PARSE_FAILED");
    }

    interface Transport {
        TransportResponse post(
                URI uri,
                char[] apiKey,
                String body,
                Duration timeout
        ) throws IOException, InterruptedException;
    }

    record TransportResponse(int statusCode, String contentType, String body) {
        TransportResponse {
            Objects.requireNonNull(body, "body");
        }
    }

    public record Telemetry(
            int attemptedCallCount,
            int networkCallCount,
            int completedCallCount,
            int inputTokenCount,
            int outputTokenCount,
            BigDecimal accountedCostUsd,
            BigDecimal hardCostLimitUsd,
            boolean terminated,
            boolean closed
    ) {
        public Telemetry {
            Objects.requireNonNull(accountedCostUsd, "accountedCostUsd");
            Objects.requireNonNull(hardCostLimitUsd, "hardCostLimitUsd");
            if (attemptedCallCount < 0 || networkCallCount < 0
                    || completedCallCount < 0 || inputTokenCount < 0
                    || outputTokenCount < 0
                    || completedCallCount > networkCallCount
                    || networkCallCount > attemptedCallCount
                    || attemptedCallCount > MAXIMUM_MODEL_CALLS
                    || accountedCostUsd.signum() < 0
                    || accountedCostUsd.compareTo(hardCostLimitUsd) > 0) {
                throw AgentResearchModels.invalid(
                        "M3_OPENAI_TELEMETRY_INVALID");
            }
        }
    }

    private static final class JdkTransport implements Transport {
        private final HttpClient client;

        private JdkTransport(Duration timeout) {
            this.client = HttpClient.newBuilder().connectTimeout(timeout)
                    .followRedirects(HttpClient.Redirect.NEVER).build();
        }

        @Override
        public TransportResponse post(
                URI uri,
                char[] apiKey,
                String body,
                Duration timeout
        ) throws IOException, InterruptedException {
            String secret = new String(apiKey);
            try {
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(timeout)
                        .header("Authorization", "Bearer " + secret)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body,
                                StandardCharsets.UTF_8)).build();
                HttpResponse<String> response = client.send(request,
                        HttpResponse.BodyHandlers.ofString(
                                StandardCharsets.UTF_8));
                return new TransportResponse(response.statusCode(),
                        response.headers().firstValue("Content-Type")
                                .orElse(""), response.body());
            } finally {
                secret = null;
            }
        }
    }

    private static final class SetNames {
        private static final java.util.Set<String> RESPONSE_FIELDS =
                java.util.Set.of("requestedTools", "claims", "summary",
                        "issueCodes", "reworkRequested");
        private static final java.util.Set<String> CLAIM_FIELDS =
                java.util.Set.of("claimType", "statement", "evidenceIds",
                        "confidence");

        private SetNames() {
        }
    }
}
