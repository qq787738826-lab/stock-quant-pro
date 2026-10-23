package com.stockquant.server.production;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Validates only the fixed, sanitized terminal result for the requested day. */
@Component
public final class BrokerResultMarketDataReadyGate
        implements MarketDataReadyGate {
    private static final long MAXIMUM_TERMINAL_BYTES = 65_536;
    private static final long MAXIMUM_MAINTENANCE_BYTES = 1_048_576;
    private static final String OPERATION =
            "AUTOMATIC_DAILY_MARKET_DATA_MAINTENANCE";
    private static final List<String> CHAIN = List.of(
            "MAINBOARD_TRADE_CAL_FORWARD_INCREMENT",
            "MAINBOARD_CATCHUP_READONLY_AUDIT",
            "MAINBOARD_DAILY_INCREMENT",
            "MAINBOARD_CATCHUP_READONLY_AUDIT");

    private final ObjectMapper mapper;
    private final Path brokerBase;
    private final Supplier<String> gitCommit;

    @Autowired
    public BrokerResultMarketDataReadyGate(ObjectMapper mapper) {
        this(mapper, repositoryRoot().resolve(
                        "quant-server/target/stock-quant-host-broker"),
                () -> ProductionRuntimeState.require().gitCommit());
    }

    BrokerResultMarketDataReadyGate(
            ObjectMapper mapper,
            Path brokerBase,
            Supplier<String> gitCommit
    ) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.brokerBase = Objects.requireNonNull(brokerBase, "brokerBase")
                .toAbsolutePath().normalize();
        this.gitCommit = Objects.requireNonNull(gitCommit, "gitCommit");
    }

    @Override
    public ReadyResult evaluate(LocalDate tradeDate) {
        Objects.requireNonNull(tradeDate, "tradeDate");
        String commit = gitCommit.get();
        String requestId = AutomaticMarketDataMaintenanceRequestIds.forDate(
                tradeDate, commit);
        Path terminalPath = brokerBase.resolve("results")
                .resolve(requestId + ".result.json");
        if (!Files.isRegularFile(terminalPath)) {
            return nonTerminal(requestId);
        }
        try {
            JsonNode terminal = read(terminalPath, MAXIMUM_TERMINAL_BYTES);
            JsonNode summary = terminal.path("summary");
            String submissionMode = text(summary, "submissionMode");
            if (!"STOCK_QUANT_HOST_BROKER_RESULT_V1".equals(text(terminal,
                    "schemaVersion"))
                    || !requestId.equals(text(terminal, "requestId"))
                    || !OPERATION.equals(text(terminal, "operation"))
                    || !commit.equals(text(terminal, "gitCommit"))
                    || !("AUTOMATIC".equals(submissionMode)
                    || "MANUAL".equals(submissionMode))
                    || !tradeDate.toString().equals(text(summary,
                    "targetEndDate"))) {
                return ReadyResult.skip(
                        "AUTOMATIC_MARKET_DATA_MAINTENANCE_RESULT_INVALID",
                        requestId);
            }
            if (!"SUCCEEDED".equals(text(terminal, "status"))) {
                return ReadyResult.skip(safeReason(terminal.path("reason")),
                        requestId);
            }
            Path resultPath = brokerBase.resolve("results").resolve(
                    requestId
                            + ".automatic-daily-market-data-maintenance.json");
            JsonNode result = read(resultPath, MAXIMUM_MAINTENANCE_BYTES);
            return validReady(result, terminal, requestId, tradeDate, commit)
                    ? ReadyResult.ready(requestId)
                    : ReadyResult.skip(
                    "AUTOMATIC_MARKET_DATA_MAINTENANCE_RESULT_INVALID",
                    requestId);
        } catch (RuntimeException | IOException error) {
            return ReadyResult.skip(
                    "AUTOMATIC_MARKET_DATA_MAINTENANCE_RESULT_INVALID",
                    requestId);
        }
    }

    private ReadyResult nonTerminal(String requestId) {
        Path requests = brokerBase.resolve("requests");
        for (String state : List.of("request", "processing")) {
            if (Files.isRegularFile(requests.resolve(
                    requestId + '.' + state + ".properties"))) {
                return ReadyResult.skip(
                        "AUTOMATIC_MARKET_DATA_MAINTENANCE_IN_PROGRESS",
                        requestId);
            }
        }
        if (Files.isRegularFile(requests.resolve(
                requestId + ".processed.properties"))) {
            return ReadyResult.skip(
                    "AUTOMATIC_MARKET_DATA_MAINTENANCE_RESULT_MISSING",
                    requestId);
        }
        return ReadyResult.skip(
                "AUTOMATIC_MARKET_DATA_MAINTENANCE_NOT_RUN", requestId);
    }

    private static boolean validReady(
            JsonNode result,
            JsonNode terminal,
            String requestId,
            LocalDate tradeDate,
            String commit
    ) {
        JsonNode chain = result.path("operationChain");
        if (!chain.isArray() || chain.size() != CHAIN.size()) return false;
        for (int index = 0; index < CHAIN.size(); index++) {
            if (!CHAIN.get(index).equals(chain.get(index).asText())) {
                return false;
            }
        }
        JsonNode missing = result.path("finalMissingTradeDates");
        JsonNode partial = result.path("finalPartialTradeDates");
        int providerCalls = result.path("tushareProviderCallCount").asInt(-1);
        int calendarCalls = result.path(
                "tradeCalendarProviderCallCount").asInt(-1);
        int dailyCalls = result.path("dailyProviderCallCount").asInt(-1);
        int factorCalls = result.path(
                "adjustmentFactorProviderCallCount").asInt(-1);
        String latestComplete = text(result,
                "finalLatestCompleteTradeDate");
        String latestCommon = text(result,
                "finalLatestCommonCompletedOpenTradeDate");
        return "AUTOMATIC_DAILY_MARKET_DATA_MAINTENANCE_RESULT_V1".equals(
                text(result, "schemaVersion"))
                && "SUCCEEDED".equals(text(result, "status"))
                && requestId.equals(text(result, "requestId"))
                && ("MBMAINT_" + requestId.substring("SQHB_".length()))
                .equals(text(result, "executionId"))
                && commit.equals(text(result, "gitCommit"))
                && tradeDate.toString().equals(text(result,
                "targetEndDate"))
                && tradeDate.toString().equals(text(result,
                "calendarAfterSse"))
                && tradeDate.toString().equals(text(result,
                "calendarAfterSzse"))
                && List.of("APPENDED", "NO_OP").contains(text(result,
                "forwardAction"))
                && result.path("readyForShadow").asBoolean(false)
                && !latestComplete.isBlank()
                && latestComplete.equals(latestCommon)
                && missing.isArray() && missing.isEmpty()
                && partial.isArray() && partial.isEmpty()
                && result.path("duplicateCount").asInt(-1) == 0
                && result.path("retryCount").asInt(-1) >= 0
                && result.path("retryCount").asInt(-1) <= 2
                && result.path("networkRecoveryBudget").asInt(-1) == 2
                && calendarCalls >= 0 && calendarCalls <= 4
                && dailyCalls >= 0 && dailyCalls == factorCalls
                && providerCalls == calendarCalls + dailyCalls + factorCalls
                && result.path("finalLedger").asInt(-1)
                == result.path("ledgerBefore").asInt(-2) + providerCalls
                && result.path("finalLedger").asInt(-1)
                <= result.path("ledgerLimit").asInt(-2)
                && result.path("modelCallCount").asInt(-1) == 0
                && result.path("researchSelectionRunsCreated").asLong(-1)
                == 0
                && result.path("shadowRunsCreated").asLong(-1) == 0
                && result.path("paperOrdersCreated").asLong(-1) == 0
                && result.path("evaluationRowsCreated").asLong(-1) == 0
                && result.path("outputAuditClean").asBoolean(false)
                && result.path("dataOnly").asBoolean(false)
                && !result.path("realTradingStarted").asBoolean(true)
                && "POST_HOC_RESEARCH".equals(text(result,
                "historicalResearchClassification"))
                && "PIT_PARTIAL".equals(text(result,
                "pitClassification"))
                && providerCalls >= 0
                && providerCalls == terminal.path("providerCallCount")
                .asInt(-1)
                && result.path("failureReason").isNull();
    }

    private JsonNode read(Path path, long maximumBytes) throws IOException {
        Path full = path.toAbsolutePath().normalize();
        if (!full.startsWith(brokerBase)
                || !Files.isRegularFile(full)
                || Files.size(full) < 2 || Files.size(full) > maximumBytes) {
            throw new IOException("INVALID");
        }
        return mapper.readTree(full.toFile());
    }

    private static String safeReason(JsonNode value) {
        String reason = value.asText("");
        return reason.matches("[A-Z][A-Z0-9_]{3,127}")
                ? reason
                : "AUTOMATIC_MARKET_DATA_MAINTENANCE_FAILED";
    }

    private static String text(JsonNode value, String field) {
        return value.path(field).asText("");
    }

    private static Path repositoryRoot() {
        Path value = Path.of("").toAbsolutePath().normalize();
        for (int depth = 0; value != null && depth < 5;
             depth++, value = value.getParent()) {
            if (Files.exists(value.resolve(".git"))
                    && Files.isDirectory(value.resolve("quant-server"))) {
                return value;
            }
        }
        throw new IllegalStateException(
                "AUTOMATIC_MARKET_DATA_MAINTENANCE_REPOSITORY_INVALID");
    }
}
