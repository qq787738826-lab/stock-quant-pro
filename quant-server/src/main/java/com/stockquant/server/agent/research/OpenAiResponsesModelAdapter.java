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
import java.util.Optional;

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
    private int reasoningTokenCount;
    private int totalTokenCount;
    private final List<CallTelemetry> callTelemetry = new ArrayList<>();
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

    public static OpenAiResponsesModelAdapter bailian(
            char[] apiKey,
            Duration timeout,
            BigDecimal hardCostLimitCny
    ) {
        if (hardCostLimitCny == null || hardCostLimitCny.signum() <= 0
                || hardCostLimitCny.compareTo(
                M3_BAILIAN_HARD_COST_LIMIT_CNY) > 0) {
            throw AgentResearchModels.invalid(
                    "M3_BAILIAN_COST_LIMIT_INVALID");
        }
        return new OpenAiResponsesModelAdapter(apiKey, timeout,
                new JdkTransport(timeout), BAILIAN_PROFILE.withHardCostLimit(
                hardCostLimitCny));
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
            throw failure("REQUEST_INTERRUPTED", FailureSource.TRANSPORT,
                    0, "NONE", "NOT_EVALUATED", "NONE", "NONE",
                    "NONE");
        } catch (IOException exception) {
            accountUnknownCall(reservation);
            throw failure("TRANSPORT_FAILED", FailureSource.TRANSPORT,
                    0, "NONE", "NOT_EVALUATED", "NONE", "NONE",
                    "NONE");
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
            throw failure("RESPONSE_REJECTED",
                    FailureSource.RESPONSE_ENVELOPE,
                    response.statusCode(), contentTypeCategory(
                            response.contentType()), "NOT_EVALUATED", "NONE",
                    "NONE", "NONE");
        }
        ModelResponse parsed;
        try {
            parsed = parseResponse(response.body(), request);
        } catch (ResponseFailure error) {
            accountRejectedResponse(response.body(), reservation);
            throw failure(error.reasonSuffix(), error.source(),
                    response.statusCode(), contentTypeCategory(
                            response.contentType()), "VALID_JSON",
                    error.providerCode(), error.providerCategory(),
                    error.messageCategory());
        } catch (RuntimeException error) {
            accountRejectedResponse(response.body(), reservation);
            throw failure("RESPONSE_PARSE_FAILED",
                    FailureSource.RESPONSE_PARSE, response.statusCode(),
                    contentTypeCategory(response.contentType()),
                    "INVALID_JSON", "NONE", "NONE", "NONE");
        }
        ModelUsage usage = parsed.usage();
        if (usage.inputTokens() > requestBytes) {
            accountRejectedUsage(usage, reservation);
            throw failure("INPUT_USAGE_LIMIT_EXCEEDED",
                    FailureSource.USAGE_VALIDATION, response.statusCode(),
                    contentTypeCategory(response.contentType()), "VALID_JSON",
                    "NONE", "NONE", "NONE");
        }
        if (usage.outputTokens() > profile.maximumOutputTokens()) {
            accountRejectedUsage(usage, reservation);
            throw failure("OUTPUT_USAGE_LIMIT_EXCEEDED",
                    FailureSource.USAGE_VALIDATION, response.statusCode(),
                    contentTypeCategory(response.contentType()), "VALID_JSON",
                    "NONE", "NONE", "NONE");
        }
        if (!profile.costCurrency().equals(usage.costCurrency())) {
            accountRejectedUsage(usage, reservation);
            throw failure("USAGE_CURRENCY_MISMATCH",
                    FailureSource.USAGE_VALIDATION, response.statusCode(),
                    contentTypeCategory(response.contentType()), "VALID_JSON",
                    "NONE", "NONE", "NONE");
        }
        if (accountedCost.add(usage.estimatedCost())
                .compareTo(profile.hardCostLimit()) > 0) {
            accountRejectedUsage(usage, reservation);
            throw failure("COST_BUDGET_POSTCALL_EXCEEDED",
                    FailureSource.USAGE_VALIDATION, response.statusCode(),
                    contentTypeCategory(response.contentType()), "VALID_JSON",
                    "NONE", "NONE", "NONE");
        }
        accountCompletedUsage(usage);
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
                reasoningTokenCount, totalTokenCount, accountedCost,
                profile.hardCostLimit(),
                profile.costCurrency(), terminated, closed);
    }

    public synchronized FailureDiagnostics diagnostics() {
        return new FailureDiagnostics(FailureSource.NONE.name(),
                attemptedCallCount, networkCallCount, completedCallCount,
                inputTokenCount, outputTokenCount, reasoningTokenCount,
                totalTokenCount, accountedCost, profile.hardCostLimit(),
                profile.costCurrency(), List.copyOf(callTelemetry), 0,
                "NONE", "NOT_EVALUATED", "NONE", "NONE", "NONE");
    }

    /**
     * Captures completed provider usage when a later deterministic runtime
     * guard rejects the otherwise valid model response. No response content
     * or credential material is retained.
     */
    public synchronized FailureDiagnostics runtimeFailureDiagnostics() {
        return new FailureDiagnostics(
                FailureSource.RUNTIME_VALIDATION.name(), attemptedCallCount,
                networkCallCount, completedCallCount, inputTokenCount,
                outputTokenCount, reasoningTokenCount, totalTokenCount,
                accountedCost, profile.hardCostLimit(),
                profile.costCurrency(), List.copyOf(callTelemetry), 0,
                "NONE", "NOT_EVALUATED", "NONE", "NONE", "NONE");
    }

    public static Optional<FailureDiagnostics> failureDiagnostics(
            Throwable error
    ) {
        for (Throwable value = error; value != null;
                value = value.getCause()) {
            if (value instanceof ProviderCallException failure) {
                return Optional.of(failure.diagnostics());
            }
        }
        return Optional.empty();
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
        format.set("schema", schema(request));
        return writeRequest(root);
    }

    private String chatCompletionsRequestBody(ModelRequest request) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", profile.model());
        root.put("stream", false);
        root.put("temperature", 0);
        root.put("max_tokens", profile.maximumOutputTokens());
        if ("BAILIAN".equals(profile.provider())) {
            // qwen3.7-plus is a hybrid thinking model. Hidden reasoning tokens
            // are reported in completion_tokens, so pin non-thinking mode for
            // this bounded structured-output smoke instead of silently
            // exceeding the declared completion budget.
            root.put("enable_thinking", false);
        }
        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content",
                request.systemPrompt()
                        + "\nReturn only one JSON object that validates against "
                        + "this schema; do not use markdown fences: "
                        + AgentResearchCanonical.json(schema(request)));
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

    private ObjectNode schema(ModelRequest request) {
        boolean toolSelection = "PLAN".equals(request.phase())
                || request.phase().endsWith("_TOOL_SELECTION");
        boolean critic = request.agentRole()
                == AgentResearchModels.AgentRole.CRITIC_REVIEW;
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        ArrayNode required = root.putArray("required");
        List.of("requestedTools", "claims", "summary", "issueCodes",
                "reworkRequested").forEach(required::add);
        ObjectNode properties = root.putObject("properties");
        ObjectNode requestedTools = properties.putObject("requestedTools");
        enumArray(requestedTools, toolSelection
                ? request.allowedTools().toArray(ToolCode[]::new)
                : ToolCode.values());
        requestedTools.put("minItems", toolSelection
                ? request.allowedTools().size() : 0);
        requestedTools.put("maxItems", toolSelection
                ? request.allowedTools().size() : 0);
        ObjectNode claims = properties.putObject("claims");
        claims.put("type", "array");
        claims.put("maxItems", toolSelection ? 0 : 8);
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
        ObjectNode issueCodes = properties.putObject("issueCodes");
        enumArray(issueCodes, CriticIssueCode.values());
        if (!critic) {
            issueCodes.put("maxItems", 0);
        }
        ObjectNode reworkRequested = properties.putObject(
                "reworkRequested").put("type", "boolean");
        if (!critic) {
            reworkRequested.put("const", false);
        }
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

    private ModelResponse parseResponse(String body, ModelRequest request) {
        try {
            JsonNode root = mapper.readTree(body);
            if (root.path("error").isObject()) {
                throw providerBodyFailure(root.path("error"));
            }
            if (root.path("code").isTextual()
                    && !root.path("code").asText().isBlank()) {
                throw providerBodyFailure(root);
            }
            if (!profile.model().equals(root.path("model").asText())) {
                throw responseFailure("MODEL_MISMATCH",
                        FailureSource.RESPONSE_VALIDATION);
            }
            String outputText = profile.apiShape() == ApiShape.RESPONSES
                    ? responsesOutputText(root) : chatOutputText(root);
            return parseStructured(outputText, usage(root.path("usage")),
                    request);
        } catch (ResponseFailure exception) {
            throw exception;
        } catch (RuntimeException | IOException exception) {
            throw parseFailure();
        }
    }

    private ModelResponse parseStructured(
            String outputText,
            ModelUsage usage,
            ModelRequest request
    ) throws IOException {
        try {
            JsonNode structured = structuredJson(outputText);
            boolean toolSelection = "PLAN".equals(request.phase())
                    || request.phase().endsWith("_TOOL_SELECTION");
            boolean phaseToolSelection = request.phase().endsWith(
                    "_TOOL_SELECTION");
            if (!phaseToolSelection) {
                requireExactFields(structured, SetNames.RESPONSE_FIELDS);
            }
            boolean critic = request.agentRole()
                    == AgentResearchModels.AgentRole.CRITIC_REVIEW;
            List<ToolCode> tools = toolSelection
                    ? enums(structured.path("requestedTools"), ToolCode.class)
                    : List.of();
            List<ModelClaim> claims = new ArrayList<>();
            JsonNode claimNodes = structured.path("claims");
            if (!claimNodes.isArray() && phaseToolSelection
                    && claimNodes.isMissingNode()) {
                claimNodes = mapper.createArrayNode();
            } else if (!claimNodes.isArray()) {
                throw parseFailure();
            }
            if (!toolSelection) {
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
            }
            List<CriticIssueCode> issues = critic ? enums(
                    structured.path("issueCodes"), CriticIssueCode.class)
                    : List.of();
            if (critic && !structured.path("reworkRequested").isBoolean()) {
                throw parseFailure();
            }
            String summary = phaseToolSelection
                    && (!structured.path("summary").isTextual()
                    || structured.path("summary").asText().isBlank())
                    ? "Tool selection completed."
                    : requiredText(structured.path("summary"));
            return new ModelResponse(tools, claims,
                    summary, issues,
                    critic && structured.path("reworkRequested").asBoolean(),
                    usage);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw parseFailure();
        }
    }

    private JsonNode structuredJson(String outputText) throws IOException {
        String candidate = requiredText(mapper.getNodeFactory().textNode(
                outputText)).strip();
        if (candidate.startsWith("```")) {
            int lineEnd = candidate.indexOf('\n');
            int fenceEnd = candidate.lastIndexOf("```");
            if (lineEnd < 0 || fenceEnd <= lineEnd
                    || fenceEnd != candidate.length() - 3) {
                throw parseFailure();
            }
            String header = candidate.substring(0, lineEnd).strip();
            if (!"```".equals(header)
                    && !"```json".equalsIgnoreCase(header)) {
                throw parseFailure();
            }
            candidate = candidate.substring(lineEnd + 1, fenceEnd).strip();
        }
        try (com.fasterxml.jackson.core.JsonParser parser =
                     mapper.getFactory().createParser(candidate)) {
            JsonNode result = mapper.readTree(parser);
            if (result == null || !result.isObject()
                    || parser.nextToken() != null) {
                throw parseFailure();
            }
            return result;
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
            throw responseFailure("FINISH_REASON_INVALID",
                    FailureSource.RESPONSE_VALIDATION);
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
        String outputDetailsField = profile.apiShape() == ApiShape.RESPONSES
                ? "output_tokens_details" : "completion_tokens_details";
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
        int reasoning = 0;
        JsonNode outputDetails = usage.path(outputDetailsField);
        if (outputDetails.isObject()
                && outputDetails.has("reasoning_tokens")) {
            reasoning = nonNegativeInt(outputDetails.path(
                    "reasoning_tokens"));
        }
        int calculatedTotal;
        try {
            calculatedTotal = Math.addExact(input, output);
        } catch (ArithmeticException error) {
            throw parseFailure();
        }
        int total = usage.has("total_tokens")
                ? nonNegativeInt(usage.path("total_tokens"))
                : calculatedTotal;
        if (reasoning > output || total != calculatedTotal) {
            throw parseFailure();
        }
        BigDecimal cost = BigDecimal.valueOf(input - cached)
                .multiply(profile.inputPerMillion())
                .add(BigDecimal.valueOf(cached)
                        .multiply(profile.cachedInputPerMillion()))
                .add(BigDecimal.valueOf(output)
                        .multiply(profile.outputPerMillion()))
                .divide(MILLION, 12, RoundingMode.HALF_EVEN);
        return new ModelUsage(input, output, reasoning, total, cost,
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
        if (callTelemetry.stream().noneMatch(value ->
                value.callNumber() == attemptedCallCount)) {
            callTelemetry.add(new CallTelemetry(attemptedCallCount,
                    "USAGE_UNAVAILABLE", 0, 0, 0, 0, reservation,
                    reservation, null, "NOT_PROVIDED_BY_API"));
        }
        accountedCost = accountedCost.add(reservation);
        terminated = true;
    }

    private void accountRejectedUsage(
            ModelUsage usage,
            BigDecimal reservation
    ) {
        callTelemetry.add(callTelemetry(attemptedCallCount,
                "USAGE_REJECTED", usage, reservation));
        accountKnownUsage(usage);
        accountedCost = accountedCost.add(reservation);
        terminated = true;
    }

    private void accountRejectedResponse(
            String responseBody,
            BigDecimal reservation
    ) {
        ModelUsage recovered = recoverUsage(responseBody);
        if (recovered == null) {
            accountUnknownCall(reservation);
            return;
        }
        callTelemetry.add(callTelemetry(attemptedCallCount,
                "RESPONSE_REJECTED", recovered, reservation));
        accountKnownUsage(recovered);
        accountedCost = accountedCost.add(reservation);
        terminated = true;
    }

    private ModelUsage recoverUsage(String responseBody) {
        try {
            JsonNode root = mapper.readTree(responseBody);
            return usage(root.path("usage"));
        } catch (RuntimeException | IOException error) {
            return null;
        }
    }

    private void accountCompletedUsage(ModelUsage usage) {
        callTelemetry.add(callTelemetry(attemptedCallCount, "COMPLETED",
                usage, usage.estimatedCost()));
        accountedCost = accountedCost.add(usage.estimatedCost());
        completedCallCount++;
        accountKnownUsage(usage);
    }

    private void accountKnownUsage(ModelUsage usage) {
        inputTokenCount += usage.inputTokens();
        outputTokenCount += usage.outputTokens();
        reasoningTokenCount += usage.reasoningTokens();
        totalTokenCount += usage.totalTokens();
    }

    private CallTelemetry callTelemetry(
            int callNumber,
            String status,
            ModelUsage usage,
            BigDecimal callAccountedCost
    ) {
        return new CallTelemetry(callNumber, status, usage.inputTokens(),
                usage.outputTokens(), usage.reasoningTokens(),
                usage.totalTokens(), usage.estimatedCost(),
                callAccountedCost, null, "NOT_PROVIDED_BY_API");
    }

    private ProviderCallException httpFailure(TransportResponse response) {
        ErrorEnvelope envelope = inspectErrorEnvelope(response.body());
        String suffix;
        if (profile.apiShape() == ApiShape.RESPONSES) {
            suffix = "HTTP_STATUS_" + response.statusCode();
        } else {
            suffix = switch (response.statusCode()) {
                case 401 -> "AUTHENTICATION_FAILED";
                case 402 -> "QUOTA_EXHAUSTED";
                case 403 -> "PERMISSION_DENIED";
                case 429 -> "RATE_LIMITED";
                default -> response.statusCode() >= 500
                        ? "SERVICE_UNAVAILABLE" : "REQUEST_REJECTED";
            };
        }
        return failure(suffix, FailureSource.HTTP_STATUS,
                response.statusCode(), contentTypeCategory(
                        response.contentType()), envelope.jsonCategory(),
                envelope.providerCode(), envelope.providerCategory(),
                envelope.messageCategory());
    }

    private ResponseFailure providerBodyFailure(JsonNode error) {
        String code = normalizedProviderCode(error.path("code").asText(""));
        String category = providerCategory(code);
        String suffix = switch (category) {
            case "AUTHENTICATION" -> "AUTHENTICATION_FAILED";
            case "PERMISSION" -> "PERMISSION_DENIED";
            case "RATE_LIMIT" -> "RATE_LIMITED";
            case "QUOTA" -> "QUOTA_EXHAUSTED";
            default -> "API_ERROR";
        };
        return new ResponseFailure(suffix, FailureSource.PROVIDER_BODY,
                code, category, messageCategory(error.path("message")
                .asText("")));
    }

    private String providerCategory(String code) {
        if ("NONE".equals(code)) {
            return "NONE";
        }
        if (code.contains("API_KEY") || code.contains("APIKEY")
                || code.contains("AUTH") || code.contains("SIGNATURE")) {
            return "AUTHENTICATION";
        } else if (code.contains("PERMISSION") || code.contains("ACCESS")) {
            return "PERMISSION";
        } else if (code.contains("RATE") || code.contains("THROTTL")) {
            return "RATE_LIMIT";
        } else if (code.contains("QUOTA") || code.contains("BALANCE")
                || code.contains("ARREAR") || code.contains("CREDIT")) {
            return "QUOTA";
        }
        return "OTHER";
    }

    private String normalizedProviderCode(String value) {
        String normalized = value == null ? "" : value.strip()
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (normalized.isBlank()) {
            return "NONE";
        }
        return normalized.substring(0, Math.min(64, normalized.length()));
    }

    private String messageCategory(String value) {
        String normalized = value == null ? "" : value.toUpperCase(
                Locale.ROOT);
        if (normalized.contains("WORKSPACE")) {
            return "WORKSPACE_BINDING";
        }
        if (normalized.contains("REGION") || normalized.contains("ENDPOINT")) {
            return "REGION_OR_ENDPOINT";
        }
        if (normalized.contains("API KEY") || normalized.contains("API_KEY")
                || normalized.contains("APIKEY")
                || normalized.contains("AUTHENTICATION")) {
            return "INVALID_OR_UNBOUND_API_KEY";
        }
        if (normalized.contains("MODEL") && (normalized.contains("ACCESS")
                || normalized.contains("PERMISSION")
                || normalized.contains("AVAILABLE"))) {
            return "MODEL_ACCESS";
        }
        if (normalized.contains("BALANCE") || normalized.contains("ARREAR")
                || normalized.contains("QUOTA")
                || normalized.contains("CREDIT")) {
            return "QUOTA_OR_BALANCE";
        }
        return normalized.isBlank() ? "NONE" : "OTHER";
    }

    private ErrorEnvelope inspectErrorEnvelope(String body) {
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode error = root.path("error").isObject()
                    ? root.path("error") : root;
            String code = normalizedProviderCode(error.path("code")
                    .asText(""));
            return new ErrorEnvelope("VALID_JSON", code,
                    providerCategory(code), messageCategory(
                    error.path("message").asText("")));
        } catch (RuntimeException | IOException error) {
            return new ErrorEnvelope("INVALID_JSON", "NONE", "NONE",
                    "NONE");
        }
    }

    private String contentTypeCategory(String value) {
        if (value == null || value.isBlank()) {
            return "MISSING";
        }
        return value.toLowerCase(Locale.ROOT).startsWith("application/json")
                ? "APPLICATION_JSON" : "NON_JSON";
    }

    private ProviderCallException failure(
            String suffix,
            FailureSource source,
            int httpStatus,
            String contentTypeCategory,
            String jsonCategory,
            String providerCode,
            String providerCategory,
            String messageCategory
    ) {
        FailureDiagnostics diagnostics = new FailureDiagnostics(source.name(),
                attemptedCallCount, networkCallCount, completedCallCount,
                inputTokenCount, outputTokenCount, reasoningTokenCount,
                totalTokenCount, accountedCost, profile.hardCostLimit(),
                profile.costCurrency(), List.copyOf(callTelemetry),
                httpStatus, contentTypeCategory, jsonCategory, providerCode,
                providerCategory, messageCategory);
        return new ProviderCallException(reason(suffix), diagnostics);
    }

    private String reason(String suffix) {
        return "M3_" + profile.provider() + "_" + suffix;
    }

    private ResponseFailure parseFailure() {
        return responseFailure("RESPONSE_PARSE_FAILED",
                FailureSource.RESPONSE_PARSE);
    }

    private ResponseFailure responseFailure(
            String suffix,
            FailureSource source
    ) {
        return new ResponseFailure(suffix, source, "NONE", "NONE", "NONE");
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
            int reasoningTokenCount,
            int totalTokenCount,
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
                    || outputTokenCount < 0 || reasoningTokenCount < 0
                    || reasoningTokenCount > outputTokenCount
                    || totalTokenCount < 0
                    || (long) inputTokenCount + outputTokenCount
                    != totalTokenCount
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

    public record CallTelemetry(
            int callNumber,
            String status,
            int inputTokenCount,
            int outputTokenCount,
            int reasoningTokenCount,
            int totalTokenCount,
            BigDecimal estimatedCost,
            BigDecimal accountedCost,
            BigDecimal providerReportedActualCostCny,
            String actualCostStatus
    ) {
        public CallTelemetry {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(estimatedCost, "estimatedCost");
            Objects.requireNonNull(accountedCost, "accountedCost");
            Objects.requireNonNull(actualCostStatus, "actualCostStatus");
            if (callNumber < 1 || callNumber > MAXIMUM_MODEL_CALLS
                    || !status.matches(
                    "COMPLETED|RESPONSE_REJECTED|USAGE_REJECTED|"
                            + "USAGE_UNAVAILABLE")
                    || inputTokenCount < 0 || outputTokenCount < 0
                    || reasoningTokenCount < 0
                    || reasoningTokenCount > outputTokenCount
                    || totalTokenCount < 0
                    || (long) inputTokenCount + outputTokenCount
                    != totalTokenCount
                    || estimatedCost.signum() < 0
                    || accountedCost.signum() < 0
                    || !actualCostStatus.matches(
                    "PROVIDED|NOT_PROVIDED_BY_API")
                    || ("PROVIDED".equals(actualCostStatus)
                    != (providerReportedActualCostCny != null))
                    || providerReportedActualCostCny != null
                    && providerReportedActualCostCny.signum() < 0
                    || "USAGE_UNAVAILABLE".equals(status)
                    && (inputTokenCount != 0 || outputTokenCount != 0
                    || reasoningTokenCount != 0 || totalTokenCount != 0)) {
                throw AgentResearchModels.invalid(
                        "M3_LLM_CALL_TELEMETRY_INVALID");
            }
        }
    }

    public enum FailureSource {
        NONE,
        TRANSPORT,
        HTTP_STATUS,
        PROVIDER_BODY,
        RESPONSE_ENVELOPE,
        RESPONSE_PARSE,
        RESPONSE_VALIDATION,
        USAGE_VALIDATION,
        RUNTIME_VALIDATION
    }

    public record FailureDiagnostics(
            String failureSource,
            int attemptedCallCount,
            int networkCallCount,
            int completedCallCount,
            int inputTokenCount,
            int outputTokenCount,
            int reasoningTokenCount,
            int totalTokenCount,
            BigDecimal accountedCost,
            BigDecimal hardCostLimit,
            String costCurrency,
            List<CallTelemetry> callTelemetry,
            int httpStatus,
            String responseContentTypeCategory,
            String responseJsonCategory,
            String providerCode,
            String providerCategory,
            String providerMessageCategory
    ) {
        public FailureDiagnostics {
            Objects.requireNonNull(failureSource, "failureSource");
            Objects.requireNonNull(accountedCost, "accountedCost");
            Objects.requireNonNull(hardCostLimit, "hardCostLimit");
            Objects.requireNonNull(costCurrency, "costCurrency");
            callTelemetry = List.copyOf(callTelemetry);
            Objects.requireNonNull(responseContentTypeCategory,
                    "responseContentTypeCategory");
            Objects.requireNonNull(responseJsonCategory,
                    "responseJsonCategory");
            Objects.requireNonNull(providerCode, "providerCode");
            Objects.requireNonNull(providerCategory, "providerCategory");
            Objects.requireNonNull(providerMessageCategory,
                    "providerMessageCategory");
            if (!failureSource.matches("NONE|TRANSPORT|HTTP_STATUS|"
                    + "PROVIDER_BODY|RESPONSE_ENVELOPE|RESPONSE_PARSE|"
                    + "RESPONSE_VALIDATION|USAGE_VALIDATION|"
                    + "RUNTIME_VALIDATION")
                    || attemptedCallCount < 0 || networkCallCount < 0
                    || completedCallCount < 0 || inputTokenCount < 0
                    || outputTokenCount < 0 || reasoningTokenCount < 0
                    || reasoningTokenCount > outputTokenCount
                    || totalTokenCount < 0
                    || (long) inputTokenCount + outputTokenCount
                    != totalTokenCount
                    || completedCallCount > networkCallCount
                    || networkCallCount > attemptedCallCount
                    || attemptedCallCount > MAXIMUM_MODEL_CALLS
                    || callTelemetry.size() > networkCallCount
                    || callTelemetry.stream().map(CallTelemetry::callNumber)
                    .distinct().count() != callTelemetry.size()
                    || accountedCost.signum() < 0
                    || accountedCost.compareTo(hardCostLimit) > 0
                    || !costCurrency.matches("USD|CNY")
                    || httpStatus < 0 || httpStatus > 599
                    || !responseContentTypeCategory.matches(
                    "NONE|MISSING|APPLICATION_JSON|NON_JSON")
                    || !responseJsonCategory.matches(
                    "NOT_EVALUATED|VALID_JSON|INVALID_JSON")
                    || !providerCode.matches("NONE|[A-Z0-9_]{1,64}")
                    || !providerCategory.matches(
                    "NONE|AUTHENTICATION|PERMISSION|RATE_LIMIT|QUOTA|OTHER")
                    || !providerMessageCategory.matches(
                    "NONE|WORKSPACE_BINDING|REGION_OR_ENDPOINT|"
                            + "INVALID_OR_UNBOUND_API_KEY|MODEL_ACCESS|"
                            + "QUOTA_OR_BALANCE|OTHER")) {
                throw AgentResearchModels.invalid(
                        "M3_LLM_FAILURE_DIAGNOSTICS_INVALID");
            }
        }
    }

    public static final class ProviderCallException
            extends IllegalStateException {
        private final FailureDiagnostics diagnostics;

        private ProviderCallException(
                String reason,
                FailureDiagnostics diagnostics
        ) {
            super(reason);
            this.diagnostics = Objects.requireNonNull(diagnostics,
                    "diagnostics");
        }

        public FailureDiagnostics diagnostics() {
            return diagnostics;
        }
    }

    private static final class ResponseFailure extends RuntimeException {
        private final String reasonSuffix;
        private final FailureSource source;
        private final String providerCode;
        private final String providerCategory;
        private final String messageCategory;

        private ResponseFailure(
                String reasonSuffix,
                FailureSource source,
                String providerCode,
                String providerCategory,
                String messageCategory
        ) {
            super(null, null, false, false);
            this.reasonSuffix = Objects.requireNonNull(reasonSuffix,
                    "reasonSuffix");
            this.source = Objects.requireNonNull(source, "source");
            this.providerCode = Objects.requireNonNull(providerCode,
                    "providerCode");
            this.providerCategory = Objects.requireNonNull(providerCategory,
                    "providerCategory");
            this.messageCategory = Objects.requireNonNull(messageCategory,
                    "messageCategory");
        }

        private String reasonSuffix() {
            return reasonSuffix;
        }

        private FailureSource source() {
            return source;
        }

        private String providerCode() {
            return providerCode;
        }

        private String providerCategory() {
            return providerCategory;
        }

        private String messageCategory() {
            return messageCategory;
        }
    }

    private record ErrorEnvelope(
            String jsonCategory,
            String providerCode,
            String providerCategory,
            String messageCategory
    ) {
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
