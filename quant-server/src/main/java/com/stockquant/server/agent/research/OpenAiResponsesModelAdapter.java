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
 * Fixed OpenAI-compatible adapter core for M3. The original OpenAI Responses
 * profile remains available, while the Bailian profile reuses the same
 * transport, structured codec, budgets, telemetry, and secret lifecycle.
 */
public final class OpenAiResponsesModelAdapter implements ModelAdapter {
    static final URI RESPONSES_URI = URI.create(
            "https://api.openai.com/v1/responses");
    public static final URI BAILIAN_CHAT_COMPLETIONS_URI = URI.create(
            "https://dashscope.aliyuncs.com/compatible-mode/v1/"
                    + "chat/completions");
    static final String ADAPTER_VERSION = "OPENAI_RESPONSES_ADAPTER_V1";
    public static final String BAILIAN_ADAPTER_VERSION =
            "BAILIAN_OPENAI_COMPATIBLE_ADAPTER_V1";
    public static final String BAILIAN_MODEL = "qwen3.7-plus";
    static final int MAXIMUM_MODEL_CALLS = 13;
    static final int MAXIMUM_OUTPUT_TOKENS = 1_200;
    public static final int BAILIAN_MAXIMUM_OUTPUT_TOKENS = 600;
    public static final BigDecimal M3_HARD_COST_LIMIT_USD =
            new BigDecimal("0.10");
    public static final BigDecimal M3_BAILIAN_HARD_COST_LIMIT_CNY =
            new BigDecimal("5.00");
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private static final BigDecimal MILLION = new BigDecimal("1000000");
    private static final BigDecimal INPUT_PER_MILLION =
            new BigDecimal("0.25");
    private static final BigDecimal CACHED_INPUT_PER_MILLION =
            new BigDecimal("0.025");
    private static final BigDecimal OUTPUT_PER_MILLION =
            new BigDecimal("2.00");
    private static final BigDecimal BAILIAN_CONSERVATIVE_INPUT_PER_MILLION =
            new BigDecimal("20.00");
    private static final BigDecimal BAILIAN_CONSERVATIVE_OUTPUT_PER_MILLION =
            new BigDecimal("100.00");
    private static final ProviderProfile OPENAI_PROFILE = new ProviderProfile(
            "OPENAI", RESPONSES_URI, AgentResearchModels.REAL_MODEL,
            ADAPTER_VERSION, ApiShape.RESPONSES, MAXIMUM_OUTPUT_TOKENS,
            INPUT_PER_MILLION, CACHED_INPUT_PER_MILLION,
            OUTPUT_PER_MILLION, M3_HARD_COST_LIMIT_USD, "USD");
    private static final ProviderProfile BAILIAN_PROFILE = new ProviderProfile(
            "BAILIAN", BAILIAN_CHAT_COMPLETIONS_URI, BAILIAN_MODEL,
            BAILIAN_ADAPTER_VERSION, ApiShape.CHAT_COMPLETIONS,
            BAILIAN_MAXIMUM_OUTPUT_TOKENS,
            BAILIAN_CONSERVATIVE_INPUT_PER_MILLION,
            BAILIAN_CONSERVATIVE_INPUT_PER_MILLION,
            BAILIAN_CONSERVATIVE_OUTPUT_PER_MILLION,
            M3_BAILIAN_HARD_COST_LIMIT_CNY, "CNY");

    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules();
    private final char[] apiKey;
    private final Duration timeout;
    private final Transport transport;
    private final ProviderProfile profile;
    private BigDecimal accountedCost = BigDecimal.ZERO;
    private int attemptedCallCount;
    private int networkCallCount;
    private int completedCallCount;
    private int inputTokenCount;
    private int outputTokenCount;
    private boolean terminated;
    private boolean closed;

    public OpenAiResponsesModelAdapter(char[] apiKey, Duration timeout) {
        this(apiKey, timeout, new JdkTransport(timeout),
                OPENAI_PROFILE);
    }

    OpenAiResponsesModelAdapter(
            char[] apiKey,
            Duration timeout,
            Transport transport
    ) {
        this(apiKey, timeout, transport, OPENAI_PROFILE);
    }

    OpenAiResponsesModelAdapter(
            char[] apiKey,
            Duration timeout,
            Transport transport,
            BigDecimal hardCostLimitUsd
    ) {
        this(apiKey, timeout, transport, OPENAI_PROFILE.withHardCostLimit(
                hardCostLimitUsd));
    }

    public static OpenAiResponsesModelAdapter bailian(
            char[] apiKey,
            Duration timeout
    ) {
        return new OpenAiResponsesModelAdapter(apiKey, timeout,
                new JdkTransport(timeout), BAILIAN_PROFILE);
    }

    static OpenAiResponsesModelAdapter bailian(
            char[] apiKey,
            Duration timeout,
            Transport transport
    ) {
        return new OpenAiResponsesModelAdapter(apiKey, timeout, transport,
                BAILIAN_PROFILE);
    }

    static OpenAiResponsesModelAdapter bailian(
            char[] apiKey,
            Duration timeout,
            Transport transport,
            BigDecimal hardCostLimitCny
    ) {
        return new OpenAiResponsesModelAdapter(apiKey, timeout, transport,
                BAILIAN_PROFILE.withHardCostLimit(hardCostLimitCny));
    }

    private OpenAiResponsesModelAdapter(
            char[] apiKey,
            Duration timeout,
            Transport transport,
            ProviderProfile profile
    ) {
        Objects.requireNonNull(apiKey, "apiKey");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.profile = Objects.requireNonNull(profile, "profile");
        if (!isStructurallyValidApiKey(apiKey)
                || timeout.compareTo(Duration.ofSeconds(5)) < 0
                || timeout.compareTo(Duration.ofMinutes(2)) > 0) {
            throw AgentResearchModels.invalid(reason(
                    "ADAPTER_CONFIGURATION_INVALID"));
        }
        this.apiKey = apiKey.clone();
    }

    @Override
    public Descriptor descriptor() {
        return new Descriptor(profile.provider(), profile.model(),
                profile.adapterVersion(), false);
    }

    @Override
    public synchronized ModelResponse complete(ModelRequest request) {
        Objects.requireNonNull(request, "request");
        if (closed) {
            throw AgentResearchModels.invalid(reason("ADAPTER_CLOSED"));
        }
        if (terminated) {
            throw AgentResearchModels.invalid(reason("ADAPTER_TERMINATED"));
        }
        if (attemptedCallCount >= MAXIMUM_MODEL_CALLS) {
            terminated = true;
            throw AgentResearchModels.invalid(reason(
                    "MODEL_CALL_BUDGET_EXHAUSTED"));
        }
        String requestBody = requestBody(request);
        int requestBytes = requestBody.getBytes(StandardCharsets.UTF_8).length;
        BigDecimal reservation = maximumCallCost(requestBytes);
        if (accountedCost.add(reservation)
                .compareTo(profile.hardCostLimit()) > 0) {
            terminated = true;
            throw AgentResearchModels.invalid(reason(
                    "COST_BUDGET_PRECALL_REJECTED"));
        }
        attemptedCallCount++;
        networkCallCount++;
        TransportResponse response;
        try {
            response = transport.post(profile.endpoint(), apiKey, requestBody,
                    timeout);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            accountUnknownCall(reservation);
            throw new IllegalStateException(reason("REQUEST_INTERRUPTED"));
        } catch (IOException exception) {
            accountUnknownCall(reservation);
            throw new IllegalStateException(reason("TRANSPORT_FAILED"));
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            accountUnknownCall(reservation);
            throw httpFailure(response);
        }
        if (response.body().getBytes(StandardCharsets.UTF_8).length
                > MAX_RESPONSE_BYTES
                || response.contentType() == null
                || !response.contentType().toLowerCase(Locale.ROOT)
                .startsWith("application/json")) {
            accountUnknownCall(reservation);
            throw new IllegalStateException(reason("RESPONSE_REJECTED"));
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
                || usage.outputTokens() > profile.maximumOutputTokens()
                || !profile.costCurrency().equals(usage.costCurrency())
                || accountedCost.add(usage.estimatedCost())
                .compareTo(profile.hardCostLimit()) > 0) {
            accountUnknownCall(reservation);
            throw new IllegalStateException(reason(
                    "USAGE_BUDGET_INVALID"));
        }
        accountedCost = accountedCost.add(usage.estimatedCost());
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
                accountedCost, profile.hardCostLimit(),
                profile.costCurrency(), terminated, closed);
    }

    private String requestBody(ModelRequest request) {
        return profile.apiShape() == ApiShape.RESPONSES
                ? responsesRequestBody(request)
                : chatCompletionsRequestBody(request);
    }

    private String responsesRequestBody(ModelRequest request) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", profile.model());
        root.put("store", false);
        root.put("max_output_tokens", profile.maximumOutputTokens());
        root.putObject("reasoning").put("effort", "minimal");
        root.put("instructions", request.systemPrompt());
        ArrayNode input = root.putArray("input");
        ObjectNode message = input.addObject();
        message.put("role", "user");
        ArrayNode content = message.putArray("content");
        ObjectNode inputText = content.addObject();
        inputText.put("type", "input_text");
        inputText.put("text", AgentResearchCanonical.json(
                requestPayload(request)));
        ObjectNode format = root.putObject("text").putObject("format");
        format.put("type", "json_schema");
        format.put("name", "agent_research_response");
        format.put("strict", true);
        format.set("schema", schema());
        return writeRequest(root);
    }

    private String chatCompletionsRequestBody(ModelRequest request) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", profile.model());
        root.put("stream", false);
        root.put("temperature", 0);
        root.put("max_tokens", profile.maximumOutputTokens());
        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content",
                request.systemPrompt()
                        + "\nReturn only one JSON object that validates against "
                        + "this schema; do not use markdown fences: "
                        + AgentResearchCanonical.json(schema()));
        messages.addObject().put("role", "user").put("content",
                AgentResearchCanonical.json(requestPayload(request)));
        root.putObject("response_format").put("type", "json_object");
        return writeRequest(root);
    }

    private ObjectNode requestPayload(ModelRequest request) {
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
        return payload;
    }

    private String writeRequest(ObjectNode root) {
        try {
            return mapper.writeValueAsString(root);
        } catch (IOException exception) {
            throw new IllegalStateException(reason("REQUEST_JSON_FAILED"),
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
            if (root.path("error").isObject()) {
                throw providerBodyFailure(root.path("error"));
            }
            if (!profile.model().equals(root.path("model").asText())) {
                throw new IllegalStateException(reason("MODEL_MISMATCH"));
            }
            String outputText = profile.apiShape() == ApiShape.RESPONSES
                    ? responsesOutputText(root) : chatOutputText(root);
            return parseStructured(outputText, usage(root.path("usage")));
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (RuntimeException | IOException exception) {
            throw new IllegalStateException(reason("RESPONSE_PARSE_FAILED"));
        }
    }

    private ModelResponse parseStructured(
            String outputText,
            ModelUsage usage
    ) throws IOException {
        try {
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
            return new ModelResponse(tools, claims,
                    requiredText(structured.path("summary")), issues,
                    structured.path("reworkRequested").asBoolean(), usage);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw parseFailure();
        }
    }

    private String responsesOutputText(JsonNode root) {
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

    private String chatOutputText(JsonNode root) {
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.size() != 1) {
            throw parseFailure();
        }
        JsonNode choice = choices.get(0);
        if (!"stop".equals(choice.path("finish_reason").asText())) {
            throw new IllegalStateException(reason("FINISH_REASON_INVALID"));
        }
        return requiredText(choice.path("message").path("content"));
    }

    private <E extends Enum<E>> List<E> enums(
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

    private ModelUsage usage(JsonNode usage) {
        if (!usage.isObject()) {
            throw parseFailure();
        }
        String inputField = profile.apiShape() == ApiShape.RESPONSES
                ? "input_tokens" : "prompt_tokens";
        String outputField = profile.apiShape() == ApiShape.RESPONSES
                ? "output_tokens" : "completion_tokens";
        String detailsField = profile.apiShape() == ApiShape.RESPONSES
                ? "input_tokens_details" : "prompt_tokens_details";
        int input = nonNegativeInt(usage.path(inputField));
        int output = nonNegativeInt(usage.path(outputField));
        int cached = 0;
        JsonNode details = usage.path(detailsField);
        if (details.isObject() && details.has("cached_tokens")) {
            cached = nonNegativeInt(details.path("cached_tokens"));
        }
        if (cached > input) {
            throw parseFailure();
        }
        BigDecimal cost = BigDecimal.valueOf(input - cached)
                .multiply(profile.inputPerMillion())
                .add(BigDecimal.valueOf(cached)
                        .multiply(profile.cachedInputPerMillion()))
                .add(BigDecimal.valueOf(output)
                        .multiply(profile.outputPerMillion()))
                .divide(MILLION, 12, RoundingMode.HALF_EVEN);
        return new ModelUsage(input, output, cost,
                profile.costCurrency());
    }

    private int nonNegativeInt(JsonNode value) {
        if (!value.canConvertToInt() || value.asInt() < 0) {
            throw parseFailure();
        }
        return value.asInt();
    }

    private String requiredText(JsonNode value) {
        if (!value.isTextual() || value.asText().isBlank()) {
            throw parseFailure();
        }
        return value.asText();
    }

    private void requireExactFields(JsonNode node,
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

    private BigDecimal maximumCallCost(int requestBytes) {
        if (requestBytes <= 0) {
            throw AgentResearchModels.invalid(reason("REQUEST_SIZE_INVALID"));
        }
        return BigDecimal.valueOf(requestBytes)
                .multiply(profile.inputPerMillion())
                .add(BigDecimal.valueOf(profile.maximumOutputTokens())
                        .multiply(profile.outputPerMillion()))
                .divide(MILLION, 12, RoundingMode.UP);
    }

    private void accountUnknownCall(BigDecimal reservation) {
        accountedCost = accountedCost.add(reservation);
        terminated = true;
    }

    private IllegalStateException httpFailure(TransportResponse response) {
        if (profile.apiShape() == ApiShape.RESPONSES) {
            return new IllegalStateException(reason("HTTP_STATUS_"
                    + response.statusCode()));
        }
        return switch (response.statusCode()) {
            case 401 -> new IllegalStateException(reason(
                    "AUTHENTICATION_FAILED"));
            case 402 -> new IllegalStateException(reason("QUOTA_EXHAUSTED"));
            case 403 -> new IllegalStateException(reason(
                    "PERMISSION_DENIED"));
            case 429 -> new IllegalStateException(reason("RATE_LIMITED"));
            default -> response.statusCode() >= 500
                    ? new IllegalStateException(reason(
                    "SERVICE_UNAVAILABLE"))
                    : new IllegalStateException(reason("REQUEST_REJECTED"));
        };
    }

    private IllegalStateException providerBodyFailure(JsonNode error) {
        String code = error.path("code").asText("")
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_");
        String category;
        if (code.contains("API_KEY") || code.contains("APIKEY")
                || code.contains("AUTH") || code.contains("SIGNATURE")) {
            category = "AUTHENTICATION_FAILED";
        } else if (code.contains("PERMISSION") || code.contains("ACCESS")) {
            category = "PERMISSION_DENIED";
        } else if (code.contains("RATE") || code.contains("THROTTL")) {
            category = "RATE_LIMITED";
        } else if (code.contains("QUOTA") || code.contains("BALANCE")
                || code.contains("ARREAR") || code.contains("CREDIT")) {
            category = "QUOTA_EXHAUSTED";
        } else {
            category = "API_ERROR";
        }
        return new IllegalStateException(reason(category));
    }

    private String reason(String suffix) {
        return "M3_" + profile.provider() + "_" + suffix;
    }

    private IllegalStateException parseFailure() {
        return new IllegalStateException(reason("RESPONSE_PARSE_FAILED"));
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
            BigDecimal accountedCost,
            BigDecimal hardCostLimit,
            String costCurrency,
            boolean terminated,
            boolean closed
    ) {
        public Telemetry {
            Objects.requireNonNull(accountedCost, "accountedCost");
            Objects.requireNonNull(hardCostLimit, "hardCostLimit");
            Objects.requireNonNull(costCurrency, "costCurrency");
            if (attemptedCallCount < 0 || networkCallCount < 0
                    || completedCallCount < 0 || inputTokenCount < 0
                    || outputTokenCount < 0
                    || completedCallCount > networkCallCount
                    || networkCallCount > attemptedCallCount
                    || attemptedCallCount > MAXIMUM_MODEL_CALLS
                    || accountedCost.signum() < 0
                    || accountedCost.compareTo(hardCostLimit) > 0
                    || !costCurrency.matches("USD|CNY")) {
                throw AgentResearchModels.invalid(
                        "M3_LLM_TELEMETRY_INVALID");
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

    private enum ApiShape {
        RESPONSES,
        CHAT_COMPLETIONS
    }

    private record ProviderProfile(
            String provider,
            URI endpoint,
            String model,
            String adapterVersion,
            ApiShape apiShape,
            int maximumOutputTokens,
            BigDecimal inputPerMillion,
            BigDecimal cachedInputPerMillion,
            BigDecimal outputPerMillion,
            BigDecimal hardCostLimit,
            String costCurrency
    ) {
        private ProviderProfile {
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(endpoint, "endpoint");
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(adapterVersion, "adapterVersion");
            Objects.requireNonNull(apiShape, "apiShape");
            Objects.requireNonNull(inputPerMillion, "inputPerMillion");
            Objects.requireNonNull(cachedInputPerMillion,
                    "cachedInputPerMillion");
            Objects.requireNonNull(outputPerMillion, "outputPerMillion");
            Objects.requireNonNull(hardCostLimit, "hardCostLimit");
            Objects.requireNonNull(costCurrency, "costCurrency");
            if (!provider.matches("OPENAI|BAILIAN")
                    || !"https".equals(endpoint.getScheme())
                    || endpoint.getUserInfo() != null
                    || endpoint.getQuery() != null
                    || endpoint.getFragment() != null
                    || model.isBlank() || adapterVersion.isBlank()
                    || maximumOutputTokens < 1
                    || inputPerMillion.signum() < 0
                    || cachedInputPerMillion.signum() < 0
                    || outputPerMillion.signum() <= 0
                    || hardCostLimit.signum() <= 0
                    || !costCurrency.matches("USD|CNY")) {
                throw AgentResearchModels.invalid(
                        "M3_LLM_PROVIDER_PROFILE_INVALID");
            }
        }

        private ProviderProfile withHardCostLimit(BigDecimal value) {
            return new ProviderProfile(provider, endpoint, model,
                    adapterVersion, apiShape, maximumOutputTokens,
                    inputPerMillion, cachedInputPerMillion, outputPerMillion,
                    value, costCurrency);
        }
    }
}
