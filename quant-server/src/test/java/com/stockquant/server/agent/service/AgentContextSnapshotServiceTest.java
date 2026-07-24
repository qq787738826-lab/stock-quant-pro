package com.stockquant.server.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.core.domain.Bar;
import com.stockquant.core.indicator.Indicators;
import com.stockquant.server.agent.backtest.AgentBacktestContextService;
import com.stockquant.server.agent.backtest.BacktestContracts;
import com.stockquant.server.agent.repository.AgentContextReadRepository;
import com.stockquant.server.agent.repository.AgentContextReadRepository.DailyBarRecord;
import com.stockquant.server.agent.repository.AgentContextReadRepository.SecurityRecord;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentContextSnapshotServiceTest {

    private static final String SYMBOL = "600000";
    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 7, 14);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AgentContextHashService hashService = new AgentContextHashService(objectMapper);

    @Test
    void createUsesRepeatableReadReadOnlyTransaction() throws Exception {
        Transactional transaction = AgentContextSnapshotService.class
                .getMethod("create", String.class, LocalDate.class)
                .getAnnotation(Transactional.class);
        assertTrue(transaction.readOnly());
        assertEquals(Isolation.REPEATABLE_READ, transaction.isolation());
        Transactional versioned = AgentContextSnapshotService.class
                .getMethod("create", String.class, LocalDate.class, String.class)
                .getAnnotation(Transactional.class);
        assertTrue(versioned.readOnly());
        assertEquals(Isolation.REPEATABLE_READ, versioned.isolation());
    }

    @Test
    void selectsStage2FProfileWithoutChangingLegacyEntry() {
        Instant instant = Instant.parse("2026-07-14T05:00:00Z");
        AgentContextReadRepository repository = emptyRepository();
        AgentMarketBreadthContextService breadth = mock(AgentMarketBreadthContextService.class);
        AgentScanResultContextService scan = mock(AgentScanResultContextService.class);
        AgentBacktestContextService backtest = mock(AgentBacktestContextService.class);
        when(breadth.create(SYMBOL, TRADE_DATE, instant))
                .thenReturn(unavailableResearch("marketBreadth", instant));
        when(scan.create(SYMBOL, TRADE_DATE, instant))
                .thenReturn(unavailableResearch("scanResult", instant));
        var reliable = objectMapper.createObjectNode();
        reliable.put("available", true);
        reliable.put("contextProfile", BacktestContracts.CONTEXT_PROFILE);
        when(backtest.create(SYMBOL, TRADE_DATE, instant)).thenReturn(reliable);
        AgentContextSnapshotService service = new AgentContextSnapshotService(
                objectMapper,
                hashService,
                repository,
                new AgentTechnicalMetricsService(),
                new AgentDataQualityContextService(),
                breadth,
                scan,
                backtest,
                Clock.fixed(instant, ZoneOffset.UTC));

        var legacy = service.create(SYMBOL, TRADE_DATE);
        var explicitlyLegacy = service.create(
                SYMBOL, TRADE_DATE, "1.4.0-stage-2e-technical-analysis-v1");
        var stage2F = service.create(
                SYMBOL, TRADE_DATE, BacktestContracts.RULE_VERSION);

        assertEquals(legacy.contextHash(), explicitlyLegacy.contextHash());
        assertEquals(
                "BACKTEST_INPUT_CUTOFF_UNVERIFIABLE",
                legacy.value().path("backtestContext").path("reasonCode").asText());
        assertEquals(
                BacktestContracts.CONTEXT_PROFILE,
                stage2F.value().path("backtestContext").path("contextProfile").asText());
        assertFalse(legacy.contextHash().equals(stage2F.contextHash()));
        verify(backtest).create(SYMBOL, TRADE_DATE, instant);
    }

    @Test
    void missingLocalDataIsExplicitWhileDataQualityRemainsAvailable() {
        JsonNode value = service(emptyRepository(), Instant.parse("2026-07-14T05:00:00Z"))
                .create(SYMBOL, TRADE_DATE).value();

        assertFalse(value.path("security").path("available").asBoolean());
        assertEquals("NO_LOCAL_SECURITY_DATA", value.path("security").path("reasonCode").asText());
        assertFalse(value.path("marketData").path("available").asBoolean());
        assertEquals(0, value.path("marketData").path("actualBars").asInt());
        assertFalse(value.path("technicalMetrics").path("available").asBoolean());
        assertEquals("INSUFFICIENT_LOCAL_DAILY_BARS",
                value.path("technicalMetrics").path("reasonCode").asText());
        JsonNode facts = value.path("dataQualityContext").path("facts");
        assertTrue(value.path("dataQualityContext").path("available").asBoolean());
        assertFalse(facts.path("securityRecordPresent").asBoolean());
        assertEquals(0, facts.path("loadedBarCount").asInt());
        assertTrue(facts.path("effectiveTradeDate").isNull());
        assertTrue(facts.path("naturalDayLag").isNull());
        for (String forbidden : List.of("score", "gateStatus", "decision", "veto")) {
            assertFalse(value.path("dataQualityContext").has(forbidden));
            assertFalse(facts.has(forbidden));
        }
    }

    @Test
    void realSecurityAndSixtyOneBarsProduceStableMetricsWithoutInventingNulls() {
        List<DailyBarRecord> bars = validBars(61);
        List<DailyBarRecord> reversed = new ArrayList<>(bars);
        Collections.reverse(reversed);
        AgentContextReadRepository repository = mock(AgentContextReadRepository.class);
        when(repository.findSecurity(SYMBOL)).thenReturn(Optional.of(security(SYMBOL, null, null)));
        when(repository.findQfqDailyBars(SYMBOL, TRADE_DATE)).thenReturn(reversed);
        when(repository.findAdjustTypes(SYMBOL, TRADE_DATE)).thenReturn(List.of("HFQ", "QFQ", "BFQ"));

        JsonNode value = service(repository, Instant.parse("2026-07-14T05:00:00Z"))
                .create(SYMBOL, TRADE_DATE).value();
        JsonNode security = value.path("security");
        assertTrue(security.path("available").asBoolean());
        assertTrue(security.path("industry").isNull());
        assertTrue(security.path("listDate").isNull());
        assertTrue(security.path("isSt").asBoolean());
        assertFalse(security.path("isActive").asBoolean());
        assertEquals("2026-07-14T13:00", security.path("updatedAt").asText());
        assertEquals("UNSPECIFIED_DATABASE_LOCAL_TIME",
                security.path("updatedAtTimezoneSemantics").asText());
        assertTrue(security.path("qualityFacts").path("placeholderSuspected").asBoolean());
        assertFalse(security.path("qualityFacts").path("sourceKnown").asBoolean());
        assertFalse(security.path("qualityFacts").path("pointInTimeGuaranteed").asBoolean());

        JsonNode market = value.path("marketData");
        assertTrue(market.path("available").asBoolean());
        assertEquals(61, market.path("actualBars").asInt());
        assertEquals(bars.get(0).tradeDate().toString(), market.path("bars").get(0).path("tradeDate").asText());
        assertEquals(TRADE_DATE.toString(), market.path("bars").get(60).path("tradeDate").asText());
        assertTrue(market.path("bars").get(0).path("amount").isNull());
        assertTrue(market.path("bars").get(0).path("turnoverRate").isNull());

        JsonNode technical = value.path("technicalMetrics");
        assertTrue(technical.path("available").asBoolean());
        assertEquals("JAVA_INDICATORS_V1", technical.path("formulaVersion").asText());
        List<Bar> coreBars = bars.stream().map(AgentContextSnapshotServiceTest::toCoreBar).toList();
        assertDecimal(Indicators.sma(coreBars, 5), technical.path("values").path("ma5"));
        assertDecimal(Indicators.sma(coreBars, 20), technical.path("values").path("ma20"));
        assertDecimal(Indicators.sma(coreBars, 60), technical.path("values").path("ma60"));
        assertDecimal(Indicators.rsi(coreBars, 14), technical.path("values").path("rsi14"));
        assertDecimal(Indicators.atr(coreBars, 14), technical.path("values").path("atr14"));
        assertDecimal(Indicators.averageVolume(coreBars, 20),
                technical.path("values").path("averageVolume20"));
        assertDecimal(Indicators.highestClose(coreBars, 20),
                technical.path("values").path("highestClose20"));
        assertFalse(technical.path("values").has("ema"));
        assertFalse(technical.path("values").has("macd"));
        assertFalse(technical.path("values").has("boll"));
        assertEquals(List.of("BFQ", "HFQ", "QFQ"), objectMapper.convertValue(
                value.path("dataQualityContext").path("facts").path("adjustTypesObserved"), List.class));
    }

    @Test
    void invalidBarsRemainVisibleButPreventTrustedMetrics() {
        List<DailyBarRecord> bars = new ArrayList<>(validBars(61));
        DailyBarRecord original = bars.get(10);
        bars.set(10, new DailyBarRecord(
                original.symbol(), original.tradeDate(), original.open(), new BigDecimal("8"),
                original.low(), original.close(), -1, original.amount(), original.turnoverRate(), "QFQ"));
        AgentContextReadRepository repository = repository(Optional.of(security("浦发银行", "银行", "LOCAL")), bars);

        JsonNode value = service(repository, Instant.parse("2026-07-14T05:00:00Z"))
                .create(SYMBOL, TRADE_DATE).value();
        assertEquals(-1, value.path("marketData").path("bars").get(10).path("volume").asLong());
        assertFalse(value.path("technicalMetrics").path("available").asBoolean());
        assertEquals("INVALID_LOCAL_DAILY_BARS",
                value.path("technicalMetrics").path("reasonCode").asText());
        assertFalse(value.path("technicalMetrics").has("values"));
        JsonNode facts = value.path("dataQualityContext").path("facts");
        assertEquals(1, facts.path("invalidBarCount").asInt());
        assertEquals(original.tradeDate().toString(), facts.path("invalidBarDates").get(0).asText());
    }

    @Test
    void duplicateInvalidDatesAreDeduplicatedWithoutRemovingMarketBarsAndOrderIsStable() {
        List<DailyBarRecord> valid = validBars(3);
        DailyBarRecord firstInvalid = invalidBar(valid.get(0), -1);
        DailyBarRecord duplicateFirstInvalid = invalidBar(valid.get(0), -1);
        DailyBarRecord secondInvalid = invalidBar(valid.get(2), -2);
        List<DailyBarRecord> firstOrder = List.of(
                secondInvalid, valid.get(1), duplicateFirstInvalid, firstInvalid);
        List<DailyBarRecord> secondOrder = List.of(
                firstInvalid, secondInvalid, valid.get(1), duplicateFirstInvalid);

        var first = service(repository(Optional.empty(), firstOrder),
                Instant.parse("2026-07-14T05:00:00Z")).create(SYMBOL, TRADE_DATE);
        var second = service(repository(Optional.empty(), secondOrder),
                Instant.parse("2026-07-14T06:00:00Z")).create(SYMBOL, TRADE_DATE);

        JsonNode firstFacts = first.value().path("dataQualityContext").path("facts");
        assertEquals(3, firstFacts.path("invalidBarCount").asInt());
        assertEquals(2, firstFacts.path("invalidBarDates").size());
        assertEquals(valid.get(0).tradeDate().toString(),
                firstFacts.path("invalidBarDates").get(0).asText());
        assertEquals(valid.get(2).tradeDate().toString(),
                firstFacts.path("invalidBarDates").get(1).asText());
        assertEquals(4, first.value().path("marketData").path("actualBars").asInt());
        assertEquals(4, first.value().path("marketData").path("bars").size());
        assertEquals(first.value().path("marketData").path("bars"),
                second.value().path("marketData").path("bars"));
        assertEquals(first.value().path("dataQualityContext").path("facts"),
                second.value().path("dataQualityContext").path("facts"));
        assertEquals(first.contextHash(), second.contextHash());
    }

    @Test
    void queriedAtAndRepositoryOrderDoNotChangeSnapshotHash() {
        List<DailyBarRecord> bars = validBars(61);
        List<DailyBarRecord> reversed = new ArrayList<>(bars);
        Collections.reverse(reversed);
        AgentContextReadRepository firstRepository = repository(
                Optional.of(security("浦发银行", "银行", "LOCAL")), bars);
        AgentContextReadRepository secondRepository = repository(
                Optional.of(security("浦发银行", "银行", "LOCAL")), reversed);

        var first = service(firstRepository, Instant.parse("2026-07-14T05:00:00Z"))
                .create(SYMBOL, TRADE_DATE);
        var second = service(secondRepository, Instant.parse("2026-07-14T06:00:00Z"))
                .create(SYMBOL, TRADE_DATE);
        assertEquals(first.contextHash(), second.contextHash());
        assertEquals("2026-07-14T05:00:00Z", first.value().path("security").path("queriedAt").asText());
        assertEquals("2026-07-14T06:00:00Z", second.value().path("security").path("queriedAt").asText());
        assertEquals(first.value().path("marketData").path("bars"),
                second.value().path("marketData").path("bars"));
    }

    @Test
    void databaseFailureIsPropagatedInsteadOfBecomingUnavailable() {
        AgentContextReadRepository repository = mock(AgentContextReadRepository.class);
        when(repository.findSecurity(SYMBOL))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        assertThrows(DataAccessResourceFailureException.class,
                () -> service(repository, Instant.EPOCH).create(SYMBOL, TRADE_DATE));
    }

    private AgentContextSnapshotService service(AgentContextReadRepository repository, Instant instant) {
        AgentMarketBreadthContextService breadth = mock(AgentMarketBreadthContextService.class);
        AgentScanResultContextService scan = mock(AgentScanResultContextService.class);
        when(breadth.create(SYMBOL, TRADE_DATE, instant)).thenReturn(unavailableResearch("marketBreadth", instant));
        when(scan.create(SYMBOL, TRADE_DATE, instant)).thenReturn(unavailableResearch("scanResult", instant));
        return new AgentContextSnapshotService(
                objectMapper, hashService, repository, new AgentTechnicalMetricsService(),
                new AgentDataQualityContextService(), breadth, scan,
                Clock.fixed(instant, ZoneOffset.UTC));
    }

    private com.fasterxml.jackson.databind.node.ObjectNode unavailableResearch(String section, Instant instant) {
        var node = objectMapper.createObjectNode();
        node.put("available", false); node.put("queriedAt", instant.toString());
        var scope = node.putObject("queryScope"); scope.put("symbol", SYMBOL); scope.put("tradeDate", TRADE_DATE.toString());
        node.put("reason", section + " unavailable"); return node;
    }

    private AgentContextReadRepository emptyRepository() {
        return repository(Optional.empty(), List.of());
    }

    private AgentContextReadRepository repository(Optional<SecurityRecord> security, List<DailyBarRecord> bars) {
        AgentContextReadRepository repository = mock(AgentContextReadRepository.class);
        when(repository.findSecurity(SYMBOL)).thenReturn(security);
        when(repository.findQfqDailyBars(SYMBOL, TRADE_DATE)).thenReturn(bars);
        when(repository.findAdjustTypes(SYMBOL, TRADE_DATE)).thenReturn(List.of("QFQ"));
        return repository;
    }

    private SecurityRecord security(String name, String industry, String dataSource) {
        return new SecurityRecord(
                SYMBOL, name, "SSE", "MAIN", industry, null, true, false,
                dataSource, LocalDateTime.of(2026, 7, 14, 13, 0));
    }

    private static List<DailyBarRecord> validBars(int count) {
        LocalDate start = TRADE_DATE.minusDays(count - 1L);
        List<DailyBarRecord> bars = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            BigDecimal close = BigDecimal.valueOf(10L + index).movePointLeft(1);
            bars.add(new DailyBarRecord(
                    SYMBOL, start.plusDays(index), close, close.add(BigDecimal.ONE),
                    close.subtract(new BigDecimal("0.5")), close.add(new BigDecimal("0.2")),
                    1000L + index, index == 0 ? null : BigDecimal.valueOf(10000L + index),
                    index == 0 ? null : new BigDecimal("1.25"), "QFQ"));
        }
        return bars;
    }

    private static Bar toCoreBar(DailyBarRecord value) {
        return new Bar(value.symbol(), value.tradeDate(), value.open(), value.high(), value.low(),
                value.close(), value.volume(), value.amount(), value.turnoverRate());
    }

    private static DailyBarRecord invalidBar(DailyBarRecord source, long volume) {
        return new DailyBarRecord(
                source.symbol(), source.tradeDate(), source.open(), source.high(), source.low(),
                source.close(), volume, source.amount(), source.turnoverRate(), source.adjustType());
    }

    private static void assertDecimal(BigDecimal expected, JsonNode actual) {
        assertEquals(0, expected.compareTo(actual.decimalValue()));
    }
}
