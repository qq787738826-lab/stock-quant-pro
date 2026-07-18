package com.stockquant.server.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.repository.AgentResearchContextReadRepository;
import com.stockquant.server.agent.repository.AgentResearchContextReadRepository.ScanFailureRecord;
import com.stockquant.server.agent.repository.AgentResearchContextReadRepository.ScanResultRecord;
import com.stockquant.server.agent.repository.AgentResearchContextReadRepository.ScanTaskRecord;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

@Service
public class AgentScanResultContextService {
    private static final List<String> BASE_LIMITATIONS = List.of(
            "PRODUCER_INPUT_CUTOFF_NOT_PERSISTED", "PRODUCER_VERSION_NOT_PERSISTED",
            "RAW_METRICS_JSON_NOT_COPIED");
    private final ObjectMapper objectMapper;
    private final AgentResearchContextReadRepository repository;

    public AgentScanResultContextService(ObjectMapper objectMapper,
                                         AgentResearchContextReadRepository repository) {
        this.objectMapper = objectMapper;
        this.repository = repository;
    }

    public ObjectNode create(String symbol, LocalDate requestedTradeDate, Instant queriedAt) {
        Optional<ScanTaskRecord> selected = repository.latestOfficialScan(requestedTradeDate);
        if (selected.isEmpty()) return noTask(symbol, requestedTradeDate, queriedAt);
        ScanTaskRecord task = selected.get();
        Optional<ScanResultRecord> result = repository.scanResult(task.id(), symbol);
        Optional<ScanFailureRecord> failure = repository.scanFailure(task.id(), symbol);
        boolean mismatch = result.isPresent() && !task.tradeDate().equals(result.get().tradeDate());
        TreeSet<String> limitations = new TreeSet<>(BASE_LIMITATIONS);
        if (result.isEmpty() && failure.isEmpty()) limitations.add("SYMBOL_PARTICIPATION_UNVERIFIABLE");
        if (result.isPresent() && failure.isPresent()) limitations.add("RESULT_AND_FAILURE_RECORDS_COEXIST");
        FilterReasons filterReasons = result.map(this::filterReasons).orElse(new FilterReasons(List.of(), false));
        if (filterReasons.nonStringValue()) limitations.add("FILTER_REASONS_CONTAINS_NON_STRING_VALUE");

        ObjectNode node = common(symbol, requestedTradeDate, queriedAt);
        node.put("available", !mismatch);
        AgentMarketBreadthContextService.nullable(node, "reasonCode", mismatch ? "SCAN_TASK_RESULT_DATE_MISMATCH" : null);
        AgentMarketBreadthContextService.nullable(node, "reason", mismatch
                ? "The stored result trade date differs from its selected scan task trade date." : null);
        node.put("sourceStatus", mismatch ? "UNAVAILABLE" : task.status());
        node.put("sourceTaskId", task.id());
        if (result.isPresent()) node.put("sourceRecordId", result.get().id());
        else if (failure.isPresent()) node.put("sourceRecordId", failure.get().id());
        else node.putNull("sourceRecordId");
        AgentMarketBreadthContextService.nullable(node, "sourceRecordType",
                result.isPresent() ? "SCAN_RESULT" : failure.isPresent() ? "SCAN_FAILURE" : null);
        AgentMarketBreadthContextService.nullable(node, "sourceCreatedAt", task.createdAt());
        AgentMarketBreadthContextService.nullable(node, "sourceFinishedAt", task.finishedAt());
        AgentMarketBreadthContextService.nullable(node, "effectiveTradeDate", task.tradeDate());
        AgentMarketBreadthContextService.nullable(node, "sourceTaskTradeDate", task.tradeDate());
        AgentMarketBreadthContextService.nullable(node, "sourceResultTradeDate",
                result.map(ScanResultRecord::tradeDate).orElse(null));
        node.put("exactTradeDateMatch", requestedTradeDate.equals(task.tradeDate()));
        node.put("scanType", task.scanType());
        node.put("official", task.official());
        node.put("symbolParticipationKnown", result.isPresent() || failure.isPresent());
        node.put("symbolResultAvailable", result.isPresent());
        node.put("symbolScanFailed", result.isEmpty() && failure.isPresent());
        if (result.isPresent()) node.put("symbolSelected", result.get().eligible()); else node.putNull("symbolSelected");
        if (result.isPresent()) {
            ScanResultRecord r = result.get();
            node.put("rank", r.rank());
            decimal(node, "sourceScanScore", r.sourceScanScore());
            ArrayNode reasons = node.putArray("filterReasons"); filterReasons.values().forEach(reasons::add);
            decimal(node, "latestClose", r.latestClose());
            AgentMarketBreadthContextService.nullable(node, "dataSource", r.dataSource());
            ObjectNode metrics = node.putObject("metrics");
            decimal(metrics, "avgAmount20", r.avgAmount20()); decimal(metrics, "return5Pct", r.return5Pct());
            decimal(metrics, "return20Pct", r.return20Pct()); decimal(metrics, "rsi14", r.rsi14());
            decimal(metrics, "atr14Pct", r.atr14Pct()); decimal(metrics, "volumeRatio20", r.volumeRatio20());
            if (r.breakout20() == null) metrics.putNull("breakout20"); else metrics.put("breakout20", r.breakout20());
        } else {
            for (String field : List.of("rank", "sourceScanScore", "latestClose", "dataSource")) node.putNull(field);
            node.putArray("filterReasons");
            ObjectNode metrics = node.putObject("metrics");
            for (String field : List.of("avgAmount20", "return5Pct", "return20Pct", "rsi14", "atr14Pct", "volumeRatio20", "breakout20")) metrics.putNull(field);
        }
        if (failure.isPresent()) {
            ScanFailureRecord f = failure.get(); ObjectNode fact = node.putObject("failureFact");
            fact.put("sourceFailureId", f.id()); fact.put("retryCount", f.retryCount());
            fact.put("resolved", f.resolved()); AgentMarketBreadthContextService.nullable(fact, "failureCreatedAt", f.createdAt());
            AgentMarketBreadthContextService.nullable(fact, "failureUpdatedAt", f.updatedAt());
        } else node.putNull("failureFact");
        AgentMarketBreadthContextService.array(node, "limitations", new ArrayList<>(limitations));
        return node;
    }

    private ObjectNode noTask(String symbol, LocalDate date, Instant queriedAt) {
        ObjectNode node = common(symbol, date, queriedAt);
        node.put("available", false); node.put("reasonCode", "NO_ELIGIBLE_OFFICIAL_SCAN_TASK");
        node.put("reason", "No completed official FULL scan satisfies the point-in-time selection rule.");
        node.put("sourceStatus", "UNAVAILABLE");
        for (String field : List.of("sourceTaskId", "sourceRecordId", "sourceRecordType", "sourceCreatedAt",
                "sourceFinishedAt", "effectiveTradeDate", "sourceTaskTradeDate", "sourceResultTradeDate",
                "scanType", "official", "symbolSelected", "rank", "sourceScanScore", "latestClose", "dataSource", "failureFact")) node.putNull(field);
        node.put("exactTradeDateMatch", false); node.put("symbolParticipationKnown", false);
        node.put("symbolResultAvailable", false); node.put("symbolScanFailed", false);
        node.putArray("filterReasons"); ObjectNode metrics = node.putObject("metrics");
        for (String field : List.of("avgAmount20", "return5Pct", "return20Pct", "rsi14", "atr14Pct", "volumeRatio20", "breakout20")) metrics.putNull(field);
        AgentMarketBreadthContextService.array(node, "limitations", BASE_LIMITATIONS);
        return node;
    }

    private ObjectNode common(String symbol, LocalDate date, Instant queriedAt) {
        ObjectNode node = objectMapper.createObjectNode(); node.put("queriedAt", queriedAt.toString());
        AgentMarketBreadthContextService.queryScope(node, symbol, date); node.put("sourceType", "DATABASE");
        AgentMarketBreadthContextService.array(node, "sourceTables", List.of("market_scan_failures", "market_scan_results", "market_scan_tasks"));
        node.put("producer", "MarketDataCenterService"); node.putNull("producerVersion"); node.put("versionAvailable", false);
        node.put("requestedTradeDate", date.toString()); node.put("pointInTimeGuaranteed", false);
        node.put("readSelectionFutureExcluded", true); node.put("producerInputCutoffGuaranteed", false);
        node.put("futureDataExcluded", false); node.put("timestampTimezoneSemantics", "DATABASE_LOCAL_TIMESTAMP_WITHOUT_TIME_ZONE");
        node.put("selectionRule", "LATEST_COMPLETED_OFFICIAL_FULL_BEFORE_REQUEST_DATE_END"); return node;
    }

    private FilterReasons filterReasons(ScanResultRecord result) {
        TreeSet<String> values = new TreeSet<>(); boolean invalid = false;
        try {
            JsonNode array = objectMapper.readTree(result.filterReasonsJson());
            if (array != null && array.isArray()) for (JsonNode item : array) {
                if (!item.isTextual()) { invalid = true; continue; }
                String value = item.asText().trim(); if (!value.isEmpty()) values.add(value);
            }
        } catch (Exception error) { invalid = true; }
        return new FilterReasons(List.copyOf(values), invalid);
    }

    private static void decimal(ObjectNode node, String field, BigDecimal value) {
        if (value == null) node.putNull(field); else node.put(field, value);
    }
    private record FilterReasons(List<String> values, boolean nonStringValue) {}
}
