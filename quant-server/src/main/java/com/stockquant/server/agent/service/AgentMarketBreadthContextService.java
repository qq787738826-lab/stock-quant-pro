package com.stockquant.server.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.repository.AgentResearchContextReadRepository;
import com.stockquant.server.agent.repository.AgentResearchContextReadRepository.BreadthRecord;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Service
public class AgentMarketBreadthContextService {
    public static final String PRODUCER_VERSION = "MARKET_BREADTH_V1";
    private final ObjectMapper objectMapper;
    private final AgentResearchContextReadRepository repository;

    public AgentMarketBreadthContextService(ObjectMapper objectMapper,
                                            AgentResearchContextReadRepository repository) {
        this.objectMapper = objectMapper;
        this.repository = repository;
    }

    public ObjectNode create(String symbol, LocalDate requestedTradeDate, Instant queriedAt) {
        BreadthRecord facts = repository.marketBreadth(requestedTradeDate);
        String reasonCode = reasonCode(facts);
        ObjectNode node = objectMapper.createObjectNode();
        node.put("available", reasonCode == null);
        node.put("queriedAt", queriedAt.toString());
        queryScope(node, symbol, requestedTradeDate);
        nullable(node, "reasonCode", reasonCode);
        nullable(node, "reason", reason(reasonCode));
        node.put("sourceType", "DATABASE");
        array(node, "sourceTables", List.of("daily_bars", "securities"));
        node.put("sourceStatus", reasonCode == null ? "AVAILABLE" : "UNAVAILABLE");
        node.put("producer", "AgentMarketBreadthContextService");
        node.put("producerVersion", PRODUCER_VERSION);
        node.put("versionAvailable", true);
        node.put("requestedTradeDate", requestedTradeDate.toString());
        nullable(node, "effectiveTradeDate", facts.effectiveTradeDate());
        nullable(node, "previousEffectiveTradeDate", facts.previousEffectiveTradeDate());
        node.put("exactTradeDateMatch", requestedTradeDate.equals(facts.effectiveTradeDate()));
        node.put("pointInTimeGuaranteed", false);
        node.put("barFutureDataExcluded", true);
        node.put("universePointInTimeGuaranteed", false);
        node.put("futureDataExcluded", false);
        node.put("timestampTimezoneSemantics", "TRADE_DATES_ARE_LOCAL_DATE_QUERIED_AT_IS_UTC_INSTANT");
        node.put("adjustType", "QFQ");
        node.put("selectionRule", "CURRENT_MAIN_ACTIVE_NON_ST_UNIVERSE_UNIFIED_EFFECTIVE_DATE");
        node.put("universeCount", facts.universeCount());
        node.put("coveredSymbolCount", facts.coveredSymbolCount());
        node.put("comparableSymbolCount", facts.comparableSymbolCount());
        node.put("advancingCount", facts.advancingCount());
        node.put("decliningCount", facts.decliningCount());
        node.put("unchangedCount", facts.unchangedCount());
        node.put("missingCurrentBarCount", facts.missingCurrentBarCount());
        node.put("missingPreviousBarCount", facts.missingPreviousBarCount());
        if (facts.universeCount() == 0) node.putNull("coverageRatio");
        else node.put("coverageRatio", BigDecimal.valueOf(facts.comparableSymbolCount())
                .divide(BigDecimal.valueOf(facts.universeCount()), 8, RoundingMode.HALF_UP));
        array(node, "limitations", List.of("CURRENT_SECURITIES_ATTRIBUTES_ARE_NOT_HISTORICALLY_VERSIONED"));
        return node;
    }

    private static String reasonCode(BreadthRecord f) {
        if (f.universeCount() == 0) return "NO_ELIGIBLE_UNIVERSE";
        if (f.effectiveTradeDate() == null) return "NO_EFFECTIVE_TRADE_DATE";
        if (f.previousEffectiveTradeDate() == null) return "NO_PREVIOUS_EFFECTIVE_TRADE_DATE";
        if (f.comparableSymbolCount() == 0) return "ZERO_COMPARABLE_SYMBOLS";
        return null;
    }

    private static String reason(String code) {
        if (code == null) return null;
        return switch (code) {
            case "NO_ELIGIBLE_UNIVERSE" -> "No current MAIN, active, non-ST securities exist.";
            case "NO_EFFECTIVE_TRADE_DATE" -> "No unified QFQ trade date exists on or before the requested date.";
            case "NO_PREVIOUS_EFFECTIVE_TRADE_DATE" -> "No prior unified QFQ trade date exists.";
            default -> "No securities have comparable bars on both unified trade dates.";
        };
    }

    static void queryScope(ObjectNode node, String symbol, LocalDate date) {
        ObjectNode scope = node.putObject("queryScope"); scope.put("symbol", symbol); scope.put("tradeDate", date.toString());
    }
    static void nullable(ObjectNode node, String name, Object value) {
        if (value == null) node.putNull(name); else node.put(name, value.toString());
    }
    static void array(ObjectNode node, String name, List<String> values) {
        ArrayNode array = node.putArray(name); values.stream().distinct().sorted().forEach(array::add);
    }
}
