package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.AdjustmentFactor;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.AssuranceLevel;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.CorporateAction;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.CorporateActionType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactRequest;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactResponse;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.ProviderCapability;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.ProviderError;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.ProviderErrorType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.ProviderVersion;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RawDailyBar;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RevisionQualification;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RunNamespace;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.TradingCalendar;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic TEST/DEMO-only provider. Values are synthetic and no network,
 * SDK, credential, filesystem, or database access is possible.
 */
public final class MockMarketFactProvider implements MarketFactProvider {

    public static final String PROVIDER_CODE = "MOCK_PIT_MARKET_FACTS_V2";
    public static final String ADAPTER_VERSION = "MOCK_PROVIDER_V1";
    public static final String FIXTURE_VERSION = "SYNTHETIC_MARKET_FACTS_V1";

    public enum Scenario {
        NORMAL,
        EMPTY,
        PARTIAL,
        ERROR,
        TIMEOUT,
        RATE_LIMITED,
        STRUCTURE_CHANGED,
        FACTOR_MISSING,
        UNEXPLAINED_FACTOR_CHANGE
    }

    private final ObjectMapper objectMapper;
    private final Scenario scenario;
    private int fetchCount;

    public MockMarketFactProvider(ObjectMapper objectMapper, Scenario scenario) {
        this.objectMapper = objectMapper;
        this.scenario = scenario;
    }

    @Override
    public ProviderCapability capability() {
        ObjectNode coverage = objectMapper.createObjectNode();
        coverage.put("fixtureVersion", FIXTURE_VERSION);
        coverage.put("synthetic", true);
        ObjectNode licensing = objectMapper.createObjectNode();
        licensing.put("usageQualification", "TEST_DEMO_ONLY");
        licensing.put("formalEligible", false);
        ObjectNode rateLimit = objectMapper.createObjectNode();
        rateLimit.put("networkCallsAllowed", 0);
        return new ProviderCapability(
                PitMarketFactsContracts.PROVIDER_CONTRACT_VERSION,
                PROVIDER_CODE,
                ADAPTER_VERSION,
                Set.of(FactType.values()),
                false,
                false,
                false,
                false,
                false,
                true,
                true,
                true,
                true,
                20,
                366,
                Duration.ZERO,
                Map.of(
                        "price", "CNY_PER_SHARE",
                        "volume", "SHARES",
                        "amount", "CNY",
                        "turnoverRate", "RATIO",
                        "factor", "DIMENSIONLESS"
                ),
                Map.of(
                        "price", 4,
                        "volume", 0,
                        "amount", 4,
                        "factor", 8,
                        "turnoverRate", 8),
                coverage,
                licensing,
                rateLimit
        );
    }

    @Override
    public MarketFactResponse fetch(MarketFactRequest request) {
        fetchCount++;
        if (request.runNamespace() == RunNamespace.FORMAL) {
            throw new IllegalArgumentException(
                    "Mock provider cannot run in FORMAL namespace");
        }
        if (!PROVIDER_CODE.equals(request.sourceCode())) {
            throw new IllegalArgumentException("mock sourceCode mismatch");
        }
        if (scenario == Scenario.TIMEOUT) {
            return failed(request, ProviderErrorType.TIMEOUT, "MOCK_TIMEOUT", true);
        }
        if (scenario == Scenario.RATE_LIMITED) {
            return failed(request, ProviderErrorType.RATE_LIMITED,
                    "MOCK_RATE_LIMITED", false);
        }
        if (scenario == Scenario.STRUCTURE_CHANGED) {
            return failed(request, ProviderErrorType.STRUCTURE_CHANGED,
                    "MOCK_STRUCTURE_CHANGED", false);
        }
        if (scenario == Scenario.ERROR) {
            return failed(request, ProviderErrorType.UNAVAILABLE,
                    "MOCK_UNAVAILABLE", true);
        }
        if (scenario == Scenario.EMPTY) {
            return response(request, true, List.of(), List.of(), List.of(),
                    List.of(), List.of());
        }

        ProviderVersion version = new ProviderVersion(
                FIXTURE_VERSION,
                null,
                null,
                null,
                null,
                RevisionQualification.SYSTEM_KNOWLEDGE_ONLY);
        List<RawDailyBar> bars = new ArrayList<>();
        List<AdjustmentFactor> factors = new ArrayList<>();
        List<TradingCalendar> calendar = new ArrayList<>();
        List<CorporateAction> actions = new ArrayList<>();
        LocalDate date = request.rangeStart();
        int openIndex = 0;
        LocalDate actionDate = null;
        while (!date.isAfter(request.rangeEnd())) {
            boolean open = date.getDayOfWeek().getValue() <= 5;
            calendar.add(new TradingCalendar(
                    request.exchange(),
                    date,
                    open,
                    open ? "REGULAR" : "CLOSED",
                    version,
                    object("fixtureVersion", FIXTURE_VERSION)));
            if (open) {
                BigDecimal base = new BigDecimal("10")
                        .add(new BigDecimal(openIndex).multiply(new BigDecimal("0.03")));
                BigDecimal close = base.add(
                        new BigDecimal(openIndex % 7 - 3)
                                .multiply(new BigDecimal("0.01")));
                bars.add(new RawDailyBar(
                        request.symbol(),
                        request.exchange(),
                        date,
                        base,
                        base.add(new BigDecimal("0.20")),
                        base.subtract(new BigDecimal("0.20")),
                        close,
                        new BigDecimal("1000000").add(
                                new BigDecimal(openIndex * 1000L)),
                        new BigDecimal("10000000").add(
                                new BigDecimal(openIndex * 10000L)),
                        new BigDecimal("0.01"),
                        version,
                        object("fixtureVersion", FIXTURE_VERSION)));
                BigDecimal factor = openIndex < 80
                        ? BigDecimal.ONE : new BigDecimal("1.10");
                if (!(scenario == Scenario.FACTOR_MISSING && openIndex == 60)) {
                    factors.add(new AdjustmentFactor(
                            request.symbol(),
                            date,
                            PitMarketFactsContracts.FACTOR_TYPE,
                            PitMarketFactsContracts.FACTOR_COVERAGE_MODE,
                            factor,
                            version,
                            object("fixtureVersion", FIXTURE_VERSION)));
                }
                if (openIndex == 80) {
                    actionDate = date;
                }
                openIndex++;
            }
            date = date.plusDays(1);
        }
        if (actionDate != null
                && scenario != Scenario.UNEXPLAINED_FACTOR_CHANGE) {
            ObjectNode terms = objectMapper.createObjectNode();
            terms.put("fixtureExplanation", "TEN_PERCENT_STOCK_DIVIDEND");
            actions.add(new CorporateAction(
                    "MOCK-ACTION-001",
                    request.symbol(),
                    CorporateActionType.STOCK_DIVIDEND,
                    actionDate.minusDays(5),
                    actionDate,
                    terms,
                    version,
                    object("fixtureVersion", FIXTURE_VERSION)));
        }
        boolean complete = scenario != Scenario.PARTIAL;
        List<ProviderError> errors = complete
                ? List.of()
                : List.of(new ProviderError(
                        ProviderErrorType.PARTIAL,
                        "MOCK_PARTIAL_RESPONSE",
                        "synthetic partial response",
                        false,
                        null));
        return response(request, complete, bars, factors, calendar, actions, errors);
    }

    public int fetchCount() {
        return fetchCount;
    }

    private MarketFactResponse failed(
            MarketFactRequest request,
            ProviderErrorType type,
            String code,
            boolean retryable
    ) {
        return response(request, false, List.of(), List.of(), List.of(),
                List.of(), List.of(new ProviderError(
                        type, code, "synthetic provider failure", retryable,
                        type == ProviderErrorType.RATE_LIMITED ? 60 : null)));
    }

    private MarketFactResponse response(
            MarketFactRequest request,
            boolean complete,
            List<RawDailyBar> bars,
            List<AdjustmentFactor> factors,
            List<TradingCalendar> calendar,
            List<CorporateAction> actions,
            List<ProviderError> errors
    ) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("fixtureVersion", FIXTURE_VERSION);
        metadata.put("scenario", scenario.name());
        metadata.put("synthetic", true);
        metadata.put("networkCalls", 0);
        return new MarketFactResponse(
                PitMarketFactsContracts.PROVIDER_CONTRACT_VERSION,
                PROVIDER_CODE,
                ADAPTER_VERSION,
                request.runNamespace(),
                request.sourceCode(),
                request.sourceInstrumentId(),
                request.rangeStart(),
                request.rangeEnd(),
                complete,
                capability(),
                bars,
                factors,
                calendar,
                actions,
                errors,
                metadata);
    }

    private ObjectNode object(String field, String value) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put(field, value);
        return result;
    }
}
