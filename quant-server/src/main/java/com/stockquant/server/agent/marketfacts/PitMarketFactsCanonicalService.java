package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.backtest.BacktestCanonicalHashService;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.AdjustmentFactor;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.CorporateAction;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RawDailyBar;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.TradingCalendar;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.ContentQualification;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Java-authoritative canonical projection for V2 market facts.
 *
 * <p>The delegated serializer already freezes UTF-8, NFC, lexicographic object
 * keys, business array order, plain decimals, null semantics, and SHA-256.</p>
 */
@Service
public class PitMarketFactsCanonicalService {

    private final ObjectMapper objectMapper;
    private final BacktestCanonicalHashService canonical;

    public PitMarketFactsCanonicalService(
            ObjectMapper objectMapper,
            BacktestCanonicalHashService canonical
    ) {
        this.objectMapper = objectMapper;
        this.canonical = canonical;
    }

    public String canonicalText(JsonNode value) {
        return canonical.canonicalText(value);
    }

    public String hash(JsonNode value) {
        return canonical.hash(value);
    }

    public String contentHash(
            FactType type,
            String sourceCode,
            String sourceIdentity,
            String naturalKey,
            Object fact,
            ContentQualification qualification
    ) {
        return hash(contentPayload(
                type, sourceCode, sourceIdentity, naturalKey,
                fact, qualification));
    }

    public String observationVersion(
            FactType type,
            String sourceCode,
            String sourceIdentity,
            String naturalKey,
            int chainSequence,
            String predecessorObservationVersion,
            String batchVersion,
            Instant firstObservedAt,
            Instant knownAt,
            String contentHash,
            Object fact
    ) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("canonicalContractVersion",
                PitMarketFactsContracts.MARKET_FACTS_CANONICAL_VERSION);
        node.put("factContractVersion", type.contractVersion());
        node.put("factType", type.name());
        node.put("sourceCode", sourceCode);
        node.put("sourceIdentity", sourceIdentity);
        node.put("naturalKey", naturalKey);
        node.put("chainSequence", chainSequence);
        if (predecessorObservationVersion == null) {
            node.putNull("predecessorObservationVersion");
        } else {
            node.put("predecessorObservationVersion",
                    predecessorObservationVersion);
        }
        node.put("batchVersion", batchVersion);
        node.put("firstObservedAt",
                BacktestCanonicalHashService.formatInstant(firstObservedAt));
        node.put("knownAt", BacktestCanonicalHashService.formatInstant(knownAt));
        node.put("canonicalContentHash", contentHash);
        var version = MarketFactProviderModels.version(fact);
        putNullable(node, "providerDatasetVersion",
                version.providerDatasetVersion());
        putNullable(node, "providerRevision", version.providerRevision());
        putNullable(node, "providerSnapshotId", version.providerSnapshotId());
        putNullableInstant(node, "providerPublishedAt",
                version.providerPublishedAt());
        putNullableInstant(node, "providerUpdatedAt",
                version.providerUpdatedAt());
        node.put("revisionQualification",
                version.revisionQualification().name());
        return hash(node);
    }

    public ObjectNode contentPayload(
            FactType type,
            String sourceCode,
            String sourceIdentity,
            String naturalKey,
            Object fact,
            ContentQualification qualification
    ) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("canonicalContractVersion",
                PitMarketFactsContracts.MARKET_FACTS_CANONICAL_VERSION);
        node.put("factContractVersion", type.contractVersion());
        node.put("factType", type.name());
        node.put("sourceCode", sourceCode);
        node.put("sourceIdentity", sourceIdentity);
        node.put("naturalKey", naturalKey);
        switch (type) {
            case RAW_DAILY_BAR -> raw(node, (RawDailyBar) fact);
            case ADJUSTMENT_FACTOR -> factor(node, (AdjustmentFactor) fact);
            case TRADING_CALENDAR -> calendar(node, (TradingCalendar) fact);
            case CORPORATE_ACTION -> action(node, (CorporateAction) fact);
        }
        var version = MarketFactProviderModels.version(fact);
        node.put("revisionQualification",
                version.revisionQualification().name());
        node.put("assuranceLevel",
                qualification.assuranceLevel().name());
        node.put("usageQualification",
                qualification.usageQualification().name());
        node.put("formalEligible", qualification.formalEligible());
        node.put("localPersistenceAllowed",
                qualification.localPersistenceAllowed());
        node.put("historicalReplayAllowed",
                qualification.historicalReplayAllowed());
        node.put("backtestAllowed", qualification.backtestAllowed());
        node.put("agentUseAllowed", qualification.agentUseAllowed());
        putNullable(node, "providerDatasetVersion",
                version.providerDatasetVersion());
        putNullable(node, "providerRevision", version.providerRevision());
        putNullable(node, "providerSnapshotId",
                version.providerSnapshotId());
        putNullableInstant(node, "providerPublishedAt",
                version.providerPublishedAt());
        putNullableInstant(node, "providerUpdatedAt",
                version.providerUpdatedAt());
        return node;
    }

    private static void raw(ObjectNode node, RawDailyBar value) {
        node.put("symbol", value.symbol());
        node.put("exchange", value.exchange());
        node.put("tradeDate", value.tradeDate().toString());
        decimal(node, "open", value.open());
        decimal(node, "high", value.high());
        decimal(node, "low", value.low());
        decimal(node, "close", value.close());
        qualifiedField(node, "volume", value.volume());
        qualifiedField(node, "amount", value.amount());
        qualifiedField(node, "turnoverRate", value.turnoverRate());
    }

    private static void factor(ObjectNode node, AdjustmentFactor value) {
        node.put("symbol", value.symbol());
        node.put("factorEffectiveTradeDate",
                value.factorEffectiveTradeDate().toString());
        node.put("factorType", value.factorType());
        node.put("coverageMode", value.coverageMode());
        decimal(node, "factor", value.factor());
    }

    private static void calendar(ObjectNode node, TradingCalendar value) {
        node.put("exchange", value.exchange());
        node.put("calendarDate", value.calendarDate().toString());
        node.put("open", value.open());
        node.put("sessionCode", value.sessionCode());
    }

    private static void action(ObjectNode node, CorporateAction value) {
        node.put("sourceActionId", value.sourceActionId());
        node.put("symbol", value.symbol());
        node.put("actionType", value.actionType().name());
        if (value.announcementDate() == null) {
            node.putNull("announcementDate");
        } else {
            node.put("announcementDate", value.announcementDate().toString());
        }
        node.put("effectiveTradeDate", value.effectiveTradeDate().toString());
        node.set("terms", value.terms());
    }

    private static void decimal(ObjectNode node, String field, BigDecimal value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static void qualifiedField(
            ObjectNode node,
            String field,
            MarketFactProviderModels.QualifiedMarketField value
    ) {
        ObjectNode result = node.putObject(field);
        decimal(result, "value", value.value());
        result.put("qualification", value.qualification().name());
        result.put("unitCode", value.unitCode().name());
        result.put("semanticCode", value.semanticCode().name());
    }

    private static void putNullable(ObjectNode node, String field, String value) {
        if (value == null) node.putNull(field);
        else node.put(field, value);
    }

    private static void putNullableInstant(
            ObjectNode node,
            String field,
            Instant value
    ) {
        if (value == null) node.putNull(field);
        else node.put(field, BacktestCanonicalHashService.formatInstant(value));
    }
}
