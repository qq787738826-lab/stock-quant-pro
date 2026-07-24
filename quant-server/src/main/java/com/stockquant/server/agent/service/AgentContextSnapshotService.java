package com.stockquant.server.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.model.AgentModels.ContextSnapshot;
import com.stockquant.server.agent.backtest.AgentBacktestContextService;
import com.stockquant.server.agent.backtest.BacktestContracts;
import com.stockquant.server.agent.portfolio.AgentPortfolioContextService;
import com.stockquant.server.agent.portfolio.PortfolioContracts;
import com.stockquant.server.agent.repository.AgentContextReadRepository;
import com.stockquant.server.agent.repository.AgentContextReadRepository.DailyBarRecord;
import com.stockquant.server.agent.repository.AgentContextReadRepository.SecurityRecord;
import com.stockquant.server.agent.service.AgentDataQualityContextService.DataQualityFacts;
import com.stockquant.server.agent.service.AgentTechnicalMetricsService.TechnicalMetrics;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Comparator;

@Service
public class AgentContextSnapshotService {

    public static final String CONTEXT_SCHEMA_VERSION = "1.0";
    private static final List<String> UNAVAILABLE_SECTIONS = List.of("securityEvents");
    private static final String UNAVAILABLE_REASON = "该只读上下文尚未接入现有业务数据源";

    private final ObjectMapper objectMapper;
    private final AgentContextHashService hashService;
    private final AgentContextReadRepository readRepository;
    private final AgentTechnicalMetricsService technicalMetricsService;
    private final AgentDataQualityContextService dataQualityContextService;
    private final AgentMarketBreadthContextService marketBreadthContextService;
    private final AgentScanResultContextService scanResultContextService;
    private final AgentBacktestContextService backtestContextService;
    private final AgentPortfolioContextService portfolioContextService;
    private final Clock clock;

    public AgentContextSnapshotService(
            ObjectMapper objectMapper,
            AgentContextHashService hashService,
            AgentContextReadRepository readRepository,
            AgentTechnicalMetricsService technicalMetricsService,
            AgentDataQualityContextService dataQualityContextService,
            AgentMarketBreadthContextService marketBreadthContextService,
            AgentScanResultContextService scanResultContextService
    ) {
        this(
                objectMapper,
                hashService,
                readRepository,
                technicalMetricsService,
                dataQualityContextService,
                marketBreadthContextService,
                scanResultContextService,
                null,
                null,
                Clock.systemUTC());
    }

    @Autowired
    public AgentContextSnapshotService(
            ObjectMapper objectMapper,
            AgentContextHashService hashService,
            AgentContextReadRepository readRepository,
            AgentTechnicalMetricsService technicalMetricsService,
            AgentDataQualityContextService dataQualityContextService,
            AgentMarketBreadthContextService marketBreadthContextService,
            AgentScanResultContextService scanResultContextService,
            AgentBacktestContextService backtestContextService,
            AgentPortfolioContextService portfolioContextService,
            @Qualifier("agentTemporalClock") Clock clock
    ) {
        this.objectMapper = objectMapper;
        this.hashService = hashService;
        this.readRepository = readRepository;
        this.technicalMetricsService = technicalMetricsService;
        this.dataQualityContextService = dataQualityContextService;
        this.marketBreadthContextService = marketBreadthContextService;
        this.scanResultContextService = scanResultContextService;
        this.backtestContextService = backtestContextService;
        this.portfolioContextService = portfolioContextService;
        this.clock = clock;
    }

    AgentContextSnapshotService(
            ObjectMapper objectMapper,
            AgentContextHashService hashService,
            AgentContextReadRepository readRepository,
            AgentTechnicalMetricsService technicalMetricsService,
            AgentDataQualityContextService dataQualityContextService,
            AgentMarketBreadthContextService marketBreadthContextService,
            AgentScanResultContextService scanResultContextService,
            Clock clock
    ) {
        this(
                objectMapper,
                hashService,
                readRepository,
                technicalMetricsService,
                dataQualityContextService,
                marketBreadthContextService,
                scanResultContextService,
                null,
                null,
                clock);
    }

    AgentContextSnapshotService(
            ObjectMapper objectMapper,
            AgentContextHashService hashService,
            AgentContextReadRepository readRepository,
            AgentTechnicalMetricsService technicalMetricsService,
            AgentDataQualityContextService dataQualityContextService,
            AgentMarketBreadthContextService marketBreadthContextService,
            AgentScanResultContextService scanResultContextService,
            AgentBacktestContextService backtestContextService,
            Clock clock
    ) {
        this(
                objectMapper,
                hashService,
                readRepository,
                technicalMetricsService,
                dataQualityContextService,
                marketBreadthContextService,
                scanResultContextService,
                backtestContextService,
                null,
                clock);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ContextSnapshot create(String symbol, LocalDate tradeDate) {
        return createInternal(symbol, tradeDate, false, false);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ContextSnapshot create(String symbol, LocalDate tradeDate, String ruleVersion) {
        return createInternal(
                symbol,
                tradeDate,
                BacktestContracts.RULE_VERSION.equals(ruleVersion)
                        || PortfolioContracts.RULE_VERSION.equals(ruleVersion),
                PortfolioContracts.RULE_VERSION.equals(ruleVersion));
    }

    private ContextSnapshot createInternal(
            String symbol,
            LocalDate tradeDate,
            boolean reliableBacktestProfile,
            boolean stage2HProfile
    ) {
        Instant queriedAt = clock.instant();
        Optional<SecurityRecord> security = readRepository.findSecurity(symbol);
        List<DailyBarRecord> dailyBars = readRepository.findQfqDailyBars(symbol, tradeDate).stream()
                .sorted(Comparator.comparing(DailyBarRecord::tradeDate))
                .toList();
        List<String> adjustTypes = readRepository.findAdjustTypes(symbol, tradeDate);
        TechnicalMetrics technicalMetrics = technicalMetricsService.evaluate(dailyBars);
        DataQualityFacts dataQuality = dataQualityContextService.analyze(
                security, dailyBars, adjustTypes, tradeDate
        );

        ObjectNode root = objectMapper.createObjectNode();
        root.set("security", securityContext(security, symbol, tradeDate, queriedAt));
        root.set("marketData", marketDataContext(dailyBars, symbol, tradeDate, queriedAt));
        root.set("marketBreadth", marketBreadthContextService.create(symbol, tradeDate, queriedAt));
        root.set("scanResult", scanResultContextService.create(symbol, tradeDate, queriedAt));
        root.set("technicalMetrics", technicalMetricsContext(
                technicalMetrics, dailyBars, symbol, tradeDate, queriedAt
        ));
        root.set(
                "backtestContext",
                reliableBacktestProfile
                        ? requiredBacktestContextService().create(symbol, tradeDate, queriedAt)
                        : backtestContext(symbol, tradeDate, queriedAt));
        for (String section : UNAVAILABLE_SECTIONS) {
            root.set(section, unavailableContext(symbol, tradeDate, queriedAt));
        }
        root.set(
                "portfolioContext",
                stage2HProfile
                        ? requiredPortfolioContextService().create(symbol, tradeDate, queriedAt)
                        : unavailableContext(symbol, tradeDate, queriedAt));
        root.set("dataQualityContext", dataQualityContext(dataQuality, symbol, tradeDate, queriedAt));
        return new ContextSnapshot(CONTEXT_SCHEMA_VERSION, root, queriedAt, hashService.hash(root));
    }

    private AgentBacktestContextService requiredBacktestContextService() {
        if (backtestContextService == null) {
            throw new IllegalStateException("阶段2F backtestContext服务不可用");
        }
        return backtestContextService;
    }

    private AgentPortfolioContextService requiredPortfolioContextService() {
        if (portfolioContextService == null) {
            throw new IllegalStateException("阶段2H portfolioContext服务不可用");
        }
        return portfolioContextService;
    }

    private ObjectNode securityContext(
            Optional<SecurityRecord> security,
            String symbol,
            LocalDate tradeDate,
            Instant queriedAt
    ) {
        ObjectNode context = baseContext(security.isPresent(), symbol, tradeDate, queriedAt);
        if (security.isEmpty()) {
            context.put("reasonCode", "NO_LOCAL_SECURITY_DATA");
            context.put("reason", "本地数据库中不存在该证券基础信息");
            return context;
        }

        SecurityRecord value = security.get();
        putNullable(context, "symbol", value.symbol());
        putNullable(context, "name", value.name());
        putNullable(context, "exchange", value.exchange());
        putNullable(context, "board", value.board());
        putNullable(context, "industry", value.industry());
        putNullable(context, "listDate", value.listDate());
        context.put("isSt", value.isSt());
        context.put("isActive", value.isActive());
        putNullable(context, "dataSource", value.dataSource());
        putNullable(context, "updatedAt", value.updatedAt());
        context.put("updatedAtTimezoneSemantics", "UNSPECIFIED_DATABASE_LOCAL_TIME");
        ObjectNode qualityFacts = context.putObject("qualityFacts");
        qualityFacts.put("placeholderSuspected", AgentDataQualityContextService.placeholderSuspected(value));
        qualityFacts.put("sourceKnown", AgentDataQualityContextService.sourceKnown(value));
        qualityFacts.put("pointInTimeGuaranteed", false);
        return context;
    }

    private ObjectNode marketDataContext(
            List<DailyBarRecord> dailyBars,
            String symbol,
            LocalDate requestedTradeDate,
            Instant queriedAt
    ) {
        ObjectNode context = baseContext(!dailyBars.isEmpty(), symbol, requestedTradeDate, queriedAt);
        context.put("adjustType", AgentContextReadRepository.ADJUST_TYPE);
        context.put("requestedTradeDate", requestedTradeDate.toString());
        LocalDate effectiveTradeDate = effectiveTradeDate(dailyBars);
        putNullable(context, "effectiveTradeDate", effectiveTradeDate);
        context.put("exactTradeDateMatch", requestedTradeDate.equals(effectiveTradeDate));
        context.put("actualBars", dailyBars.size());
        ArrayNode bars = context.putArray("bars");
        dailyBars.forEach(value -> bars.add(barNode(value)));
        if (dailyBars.isEmpty()) {
            context.put("reasonCode", "NO_LOCAL_DAILY_BARS_ON_OR_BEFORE_TRADE_DATE");
            context.put("reason", "本地数据库中不存在请求日期及以前的QFQ日线");
        }
        return context;
    }

    private ObjectNode technicalMetricsContext(
            TechnicalMetrics metrics,
            List<DailyBarRecord> dailyBars,
            String symbol,
            LocalDate requestedTradeDate,
            Instant queriedAt
    ) {
        ObjectNode context = baseContext(metrics.available(), symbol, requestedTradeDate, queriedAt);
        context.put("formulaVersion", AgentTechnicalMetricsService.FORMULA_VERSION);
        context.put("adjustType", AgentContextReadRepository.ADJUST_TYPE);
        context.put("requestedTradeDate", requestedTradeDate.toString());
        putNullable(context, "effectiveTradeDate", effectiveTradeDate(dailyBars));
        context.put("requiredBars", AgentTechnicalMetricsService.REQUIRED_BARS);
        context.put("actualBars", metrics.actualBars());
        ObjectNode windows = context.putObject("windows");
        windows.put("ma5", 5);
        windows.put("ma20", 20);
        windows.put("ma60", 60);
        windows.put("rsi14", 14);
        windows.put("atr14", 14);
        windows.put("averageVolume20", 20);
        windows.put("highestClose20", 20);
        if (!metrics.available()) {
            context.put("reasonCode", metrics.reasonCode());
            context.put("reason", "INVALID_LOCAL_DAILY_BARS".equals(metrics.reasonCode())
                    ? "本地日线存在非法必要字段，未生成技术指标"
                    : "本地QFQ日线不足61条，未生成技术指标");
            if (metrics.invalidBarCount() > 0) {
                context.put("invalidBarCount", metrics.invalidBarCount());
            }
            return context;
        }
        ObjectNode values = context.putObject("values");
        putDecimal(values, "ma5", metrics.ma5());
        putDecimal(values, "ma20", metrics.ma20());
        putDecimal(values, "ma60", metrics.ma60());
        putDecimal(values, "rsi14", metrics.rsi14());
        putDecimal(values, "atr14", metrics.atr14());
        putDecimal(values, "averageVolume20", metrics.averageVolume20());
        putDecimal(values, "highestClose20", metrics.highestClose20());
        return context;
    }

    private ObjectNode dataQualityContext(
            DataQualityFacts facts,
            String symbol,
            LocalDate tradeDate,
            Instant queriedAt
    ) {
        ObjectNode context = baseContext(true, symbol, tradeDate, queriedAt);
        ObjectNode values = context.putObject("facts");
        values.put("securityRecordPresent", facts.securityRecordPresent());
        values.put("securityPlaceholderSuspected", facts.securityPlaceholderSuspected());
        values.put("securitySourceKnown", facts.securitySourceKnown());
        values.put("securityPointInTimeGuaranteed", facts.securityPointInTimeGuaranteed());
        values.put("loadedBarCount", facts.loadedBarCount());
        values.put("requiredBarsForTechnicalMetrics", facts.requiredBarsForTechnicalMetrics());
        values.put("exactTradeDatePresent", facts.exactTradeDatePresent());
        putNullable(values, "requestedTradeDate", facts.requestedTradeDate());
        putNullable(values, "effectiveTradeDate", facts.effectiveTradeDate());
        putNullableNumber(values, "naturalDayLag", facts.naturalDayLag());
        values.put("tradingCalendarAvailable", facts.tradingCalendarAvailable());
        values.put("missingAmountCount", facts.missingAmountCount());
        values.put("missingTurnoverRateCount", facts.missingTurnoverRateCount());
        values.put("invalidBarCount", facts.invalidBarCount());
        ArrayNode invalidBarDates = values.putArray("invalidBarDates");
        facts.invalidBarDates().forEach(value -> invalidBarDates.add(value.toString()));
        putNullableNumber(values, "maximumObservedNaturalDayGap", facts.maximumObservedNaturalDayGap());
        values.put("duplicateProtection", facts.duplicateProtection());
        values.put("sourceConsistencyAssessable", facts.sourceConsistencyAssessable());
        ArrayNode adjustTypes = values.putArray("adjustTypesObserved");
        facts.adjustTypesObserved().forEach(adjustTypes::add);
        ArrayNode missingSecurityFields = values.putArray("missingSecurityFields");
        facts.missingSecurityFields().forEach(missingSecurityFields::add);
        return context;
    }

    private ObjectNode unavailableContext(String symbol, LocalDate tradeDate, Instant queriedAt) {
        ObjectNode context = baseContext(false, symbol, tradeDate, queriedAt);
        context.put("reason", UNAVAILABLE_REASON);
        return context;
    }

    private ObjectNode backtestContext(String symbol, LocalDate tradeDate, Instant queriedAt) {
        ObjectNode context = baseContext(false, symbol, tradeDate, queriedAt);
        context.put("reasonCode", "BACKTEST_INPUT_CUTOFF_UNVERIFIABLE");
        context.put("reason", "Existing backtest records do not persist a verifiable input data cutoff.");
        context.put("sourceType", "DATABASE");
        ArrayNode tables = context.putArray("sourceTables");
        List.of("backtest_runs", "scan_backtest_results", "scan_backtest_tasks").forEach(tables::add);
        context.put("sourceStatus", "UNAVAILABLE");
        context.putNull("producer");
        context.putNull("producerVersion");
        context.put("versionAvailable", false);
        context.put("requestedTradeDate", tradeDate.toString());
        context.putNull("effectiveTradeDate");
        context.put("exactTradeDateMatch", false);
        context.put("pointInTimeGuaranteed", false);
        context.put("readSelectionFutureExcluded", false);
        context.put("producerInputCutoffGuaranteed", false);
        context.put("futureDataExcluded", false);
        context.put("timestampTimezoneSemantics", "LEGACY_DATABASE_LOCAL_TIMESTAMP_WITHOUT_TIME_ZONE");
        ArrayNode limitations = context.putArray("limitations");
        List.of("INPUT_END_DATE_NOT_PERSISTED", "INPUT_START_DATE_NOT_PERSISTED",
                "SCAN_BACKTEST_READ_HAS_NO_DATE_CUTOFF", "STRATEGY_VERSION_NOT_PERSISTED")
                .forEach(limitations::add);
        return context;
    }

    private ObjectNode baseContext(
            boolean available,
            String symbol,
            LocalDate tradeDate,
            Instant queriedAt
    ) {
        ObjectNode context = objectMapper.createObjectNode();
        context.put("available", available);
        context.put("queriedAt", queriedAt.toString());
        ObjectNode queryScope = context.putObject("queryScope");
        queryScope.put("symbol", symbol);
        queryScope.put("tradeDate", tradeDate.toString());
        return context;
    }

    private ObjectNode barNode(DailyBarRecord value) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("symbol", value.symbol());
        node.put("tradeDate", value.tradeDate().toString());
        putDecimal(node, "open", value.open());
        putDecimal(node, "high", value.high());
        putDecimal(node, "low", value.low());
        putDecimal(node, "close", value.close());
        node.put("volume", value.volume());
        putDecimal(node, "amount", value.amount());
        putDecimal(node, "turnoverRate", value.turnoverRate());
        return node;
    }

    private static LocalDate effectiveTradeDate(List<DailyBarRecord> dailyBars) {
        return dailyBars.isEmpty() ? null : dailyBars.get(dailyBars.size() - 1).tradeDate();
    }

    private static void putDecimal(ObjectNode node, String field, BigDecimal value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static void putNullable(ObjectNode node, String field, Object value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value.toString());
        }
    }

    private static void putNullableNumber(ObjectNode node, String field, Long value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }
}
