package com.stockquant.server.agent.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockquant.server.agent.exception.AgentResponseValidationException;
import com.stockquant.server.agent.model.AgentModels.AgentError;
import com.stockquant.server.agent.model.AgentModels.AgentOutput;
import com.stockquant.server.agent.model.AgentModels.AgentTeamRequest;
import com.stockquant.server.agent.model.AgentModels.Evidence;
import com.stockquant.server.agent.model.AgentModels.Finding;
import com.stockquant.server.agent.model.AgentTypes.EvidenceCategory;
import com.stockquant.server.agent.model.AgentTypes.EvidenceSourceType;
import com.stockquant.server.agent.model.AgentTypes.GateStatus;
import com.stockquant.server.agent.model.AgentTypes.RunDecision;
import com.stockquant.server.agent.model.AgentTypes.RunStatus;
import com.stockquant.server.agent.model.AgentTypes.Severity;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class AgentStage2ETechnicalAnalysisValidator {

    private static final int REQUIRED_BARS = 61;
    private static final String ADJUST_TYPE = "QFQ";
    private static final String FORMULA_VERSION = "JAVA_INDICATORS_V1";
    private static final BigDecimal RSI_OVERBOUGHT = new BigDecimal("70");
    private static final BigDecimal RSI_MIDPOINT = new BigDecimal("50");
    private static final BigDecimal RSI_OVERSOLD = new BigDecimal("30");
    private static final BigDecimal DEVIATION_LIMIT = new BigDecimal("0.10");
    private static final BigDecimal ATR_RATIO_LIMIT = new BigDecimal("0.05");
    private static final MathContext PYTHON_DECIMAL_CONTEXT =
            new MathContext(28, RoundingMode.HALF_EVEN);

    private static final List<String> METRICS_FIELDS = List.of(
            "available", "formulaVersion", "adjustType", "requestedTradeDate",
            "effectiveTradeDate", "requiredBars", "actualBars", "windows", "values"
    );
    private static final List<String> MARKET_FIELDS = List.of(
            "available", "adjustType", "requestedTradeDate", "effectiveTradeDate",
            "exactTradeDateMatch", "actualBars", "latestBar"
    );
    private static final List<String> BAR_FIELDS = List.of(
            "symbol", "tradeDate", "open", "high", "low", "close",
            "volume", "amount", "turnoverRate"
    );
    private static final Map<String, Integer> WINDOWS = Map.of(
            "ma5", 5,
            "ma20", 20,
            "ma60", 60,
            "rsi14", 14,
            "atr14", 14,
            "averageVolume20", 20,
            "highestClose20", 20
    );
    private static final Set<String> VALUE_FIELDS = Set.of(
            "ma5", "ma20", "ma60", "rsi14", "atr14",
            "averageVolume20", "highestClose20"
    );
    private static final List<String> FINDING_ORDER = List.of(
            "TECH_TREND_BULLISH_ALIGNED",
            "TECH_TREND_MIXED",
            "TECH_TREND_BEARISH_ALIGNED",
            "TECH_RSI_OVERBOUGHT_RISK",
            "TECH_RSI_POSITIVE_MOMENTUM",
            "TECH_RSI_NEUTRAL",
            "TECH_RSI_NEGATIVE_MOMENTUM",
            "TECH_RSI_OVERSOLD_RISK",
            "TECH_PRICE_ABOVE_MA20_EXTENDED",
            "TECH_PRICE_NEAR_MA20",
            "TECH_PRICE_BELOW_MA20_EXTENDED",
            "TECH_VOLATILITY_ELEVATED",
            "TECH_VOLATILITY_NORMAL",
            "TECH_INDICATORS_BULLISH_CONFIRMED",
            "TECH_INDICATORS_BEARISH_CONFIRMED",
            "TECH_INDICATORS_CONFLICT_OR_UNCONFIRMED"
    );
    private static final Map<String, String> TITLES = Map.ofEntries(
            Map.entry("TECH_TREND_BULLISH_ALIGNED", "均线多头排列"),
            Map.entry("TECH_TREND_MIXED", "均线状态混合"),
            Map.entry("TECH_TREND_BEARISH_ALIGNED", "均线空头排列"),
            Map.entry("TECH_RSI_OVERBOUGHT_RISK", "RSI达到超买风险阈值"),
            Map.entry("TECH_RSI_POSITIVE_MOMENTUM", "RSI处于正向动量区间"),
            Map.entry("TECH_RSI_NEUTRAL", "RSI位于中点"),
            Map.entry("TECH_RSI_NEGATIVE_MOMENTUM", "RSI处于负向动量区间"),
            Map.entry("TECH_RSI_OVERSOLD_RISK", "RSI达到超卖风险阈值"),
            Map.entry("TECH_PRICE_ABOVE_MA20_EXTENDED", "价格高于MA20偏离阈值"),
            Map.entry("TECH_PRICE_NEAR_MA20", "价格位于MA20偏离阈值内"),
            Map.entry("TECH_PRICE_BELOW_MA20_EXTENDED", "价格低于MA20偏离阈值"),
            Map.entry("TECH_VOLATILITY_ELEVATED", "ATR相对波动达到升高阈值"),
            Map.entry("TECH_VOLATILITY_NORMAL", "ATR相对波动低于升高阈值"),
            Map.entry("TECH_INDICATORS_BULLISH_CONFIRMED", "趋势与动量正向确认"),
            Map.entry("TECH_INDICATORS_BEARISH_CONFIRMED", "趋势与动量负向确认"),
            Map.entry("TECH_INDICATORS_CONFLICT_OR_UNCONFIRMED", "技术指标冲突或未确认")
    );
    private static final Map<String, Severity> SEVERITIES = Map.ofEntries(
            Map.entry("TECH_TREND_BULLISH_ALIGNED", Severity.INFO),
            Map.entry("TECH_TREND_MIXED", Severity.WARN),
            Map.entry("TECH_TREND_BEARISH_ALIGNED", Severity.WARN),
            Map.entry("TECH_RSI_OVERBOUGHT_RISK", Severity.WARN),
            Map.entry("TECH_RSI_POSITIVE_MOMENTUM", Severity.INFO),
            Map.entry("TECH_RSI_NEUTRAL", Severity.INFO),
            Map.entry("TECH_RSI_NEGATIVE_MOMENTUM", Severity.WARN),
            Map.entry("TECH_RSI_OVERSOLD_RISK", Severity.WARN),
            Map.entry("TECH_PRICE_ABOVE_MA20_EXTENDED", Severity.WARN),
            Map.entry("TECH_PRICE_NEAR_MA20", Severity.INFO),
            Map.entry("TECH_PRICE_BELOW_MA20_EXTENDED", Severity.WARN),
            Map.entry("TECH_VOLATILITY_ELEVATED", Severity.WARN),
            Map.entry("TECH_VOLATILITY_NORMAL", Severity.INFO),
            Map.entry("TECH_INDICATORS_BULLISH_CONFIRMED", Severity.INFO),
            Map.entry("TECH_INDICATORS_BEARISH_CONFIRMED", Severity.WARN),
            Map.entry("TECH_INDICATORS_CONFLICT_OR_UNCONFIRMED", Severity.WARN)
    );
    private static final Map<String, Integer> IMPACTS = Map.ofEntries(
            Map.entry("TECH_TREND_BULLISH_ALIGNED", 20),
            Map.entry("TECH_TREND_MIXED", 0),
            Map.entry("TECH_TREND_BEARISH_ALIGNED", -20),
            Map.entry("TECH_RSI_OVERBOUGHT_RISK", -10),
            Map.entry("TECH_RSI_POSITIVE_MOMENTUM", 15),
            Map.entry("TECH_RSI_NEUTRAL", 0),
            Map.entry("TECH_RSI_NEGATIVE_MOMENTUM", -15),
            Map.entry("TECH_RSI_OVERSOLD_RISK", -10),
            Map.entry("TECH_PRICE_ABOVE_MA20_EXTENDED", -10),
            Map.entry("TECH_PRICE_NEAR_MA20", 0),
            Map.entry("TECH_PRICE_BELOW_MA20_EXTENDED", -10),
            Map.entry("TECH_VOLATILITY_ELEVATED", -10),
            Map.entry("TECH_VOLATILITY_NORMAL", 0),
            Map.entry("TECH_INDICATORS_BULLISH_CONFIRMED", 15),
            Map.entry("TECH_INDICATORS_BEARISH_CONFIRMED", -15),
            Map.entry("TECH_INDICATORS_CONFLICT_OR_UNCONFIRMED", 0)
    );

    private AgentStage2ETechnicalAnalysisValidator() {}

    static void validateBlocked(AgentOutput run) {
        require(run.status() == RunStatus.INSUFFICIENT_DATA
                        && run.gateStatus() == GateStatus.NOT_APPLICABLE
                        && run.decision() == RunDecision.NOT_APPLICABLE
                        && !run.veto()
                        && run.score() == 0
                        && run.confidence() == 0
                        && run.findings().isEmpty()
                        && run.evidence().isEmpty()
                        && run.errors().isEmpty(),
                "DATA_QUALITY阻断时阶段2E-1 TECHNICAL_ANALYSIS不得执行");
        require("DATA_QUALITY门禁已阻断，未执行技术指标确定性规则。".equals(run.summary()),
                "DATA_QUALITY阻断时阶段2E-1 TECHNICAL_ANALYSIS摘要不一致");
    }

    static void validate(
            AgentTeamRequest request,
            AgentOutput run,
            GateStatus dataQualityGate
    ) {
        TechnicalInput input = parseInput(request);
        if (input == null) {
            validateInputInvalid(run, dataQualityGate);
            return;
        }

        require(run.status() == RunStatus.COMPLETED
                        && run.gateStatus() == dataQualityGate
                        && run.decision() == RunDecision.WARN
                        && !run.veto()
                        && run.confidence() == (dataQualityGate == GateStatus.PASS ? 100 : 50)
                        && run.errors().isEmpty(),
                "阶段2E-1有效输入的状态、门禁、decision或confidence不一致");

        Evidence metricsEvidence = validateMetricsEvidence(request, run, input);
        Evidence marketEvidence = validateMarketEvidence(request, run, input);
        List<ExpectedFinding> expected = expectedFindings(input, metricsEvidence, marketEvidence);
        validateFindings(request, run.findings(), expected);
        int expectedScore = Math.max(0, Math.min(100,
                50 + expected.stream().mapToInt(item -> IMPACTS.get(item.code())).sum()));
        require(run.score() == expectedScore,
                "阶段2E-1 TECHNICAL_ANALYSIS score与冻结reasonCode影响不一致");
        require(("技术指标确定性规则执行完成，technicalStateScore=" + expectedScore
                        + "；仅描述冻结技术状态，不构成交易指令或收益判断。")
                        .equals(run.summary()),
                "阶段2E-1 TECHNICAL_ANALYSIS摘要不符合冻结规则");
    }

    private static void validateInputInvalid(AgentOutput run, GateStatus gate) {
        require(run.status() == RunStatus.INSUFFICIENT_DATA
                        && run.gateStatus() == gate
                        && run.decision() == RunDecision.NOT_APPLICABLE
                        && !run.veto()
                        && run.score() == 0
                        && run.confidence() == 0
                        && run.findings().isEmpty()
                        && run.evidence().isEmpty()
                        && run.errors().size() == 1,
                "阶段2E-1非法技术输入必须安全降级且不得形成评分");
        AgentError error = run.errors().get(0);
        require("TECHNICAL_ANALYSIS_INPUT_INVALID".equals(error.code())
                        && "technicalMetrics或marketData未满足阶段2E-1冻结输入契约。"
                        .equals(error.message()),
                "阶段2E-1非法技术输入错误代码或消息不一致");
        require("技术指标或行情输入无法安全解析，未形成技术状态评分。".equals(run.summary()),
                "阶段2E-1非法技术输入摘要不一致");
    }

    private static Evidence validateMetricsEvidence(
            AgentTeamRequest request,
            AgentOutput run,
            TechnicalInput input
    ) {
        require(run.evidence().size() == 2,
                "阶段2E-1有效技术输入必须生成两份证据");
        Evidence evidence = run.evidence().get(0);
        require(("ta-metrics-" + request.contextHash()).equals(evidence.evidenceId())
                        && evidence.category() == EvidenceCategory.TECHNICAL_INDICATOR
                        && evidence.sourceType() == EvidenceSourceType.JAVA_ENGINE
                        && "AgentTechnicalMetricsService".equals(evidence.sourceName())
                        && "contextSnapshot.technicalMetrics".equals(evidence.sourceRef())
                        && Objects.equals(request.symbol(), evidence.symbol())
                        && Objects.equals(request.tradeDate(), evidence.tradeDate())
                        && sameInstant(input.queriedAt(), evidence.observedAt())
                        && sameInstant(request.requestedAt(), evidence.collectedAt())
                        && Objects.equals(request.contextHash(), evidence.contentHash()),
                "阶段2E-1 technicalMetrics证据元数据不一致");
        JsonNode fields = evidence.fields();
        JsonNode projection = fields == null ? null : fields.get("technicalMetrics");
        require(fields != null && fields.isObject() && fields.size() == 1
                        && projection != null && projection.isObject()
                        && projection.size() == METRICS_FIELDS.size()
                        && METRICS_FIELDS.stream().allMatch(projection::has)
                        && METRICS_FIELDS.stream().allMatch(name ->
                        semanticJsonEquals(projection.get(name), input.metricsSource().get(name))),
                "阶段2E-1 technicalMetrics证据必须严格匹配冻结白名单投影");
        return evidence;
    }

    private static Evidence validateMarketEvidence(
            AgentTeamRequest request,
            AgentOutput run,
            TechnicalInput input
    ) {
        Evidence evidence = run.evidence().get(1);
        require(("ta-market-" + request.contextHash()).equals(evidence.evidenceId())
                        && evidence.category() == EvidenceCategory.MARKET_DATA
                        && evidence.sourceType() == EvidenceSourceType.JAVA_ENGINE
                        && "AgentContextSnapshotService".equals(evidence.sourceName())
                        && "contextSnapshot.marketData".equals(evidence.sourceRef())
                        && Objects.equals(request.symbol(), evidence.symbol())
                        && Objects.equals(request.tradeDate(), evidence.tradeDate())
                        && sameInstant(input.queriedAt(), evidence.observedAt())
                        && sameInstant(request.requestedAt(), evidence.collectedAt())
                        && Objects.equals(request.contextHash(), evidence.contentHash()),
                "阶段2E-1 marketData证据元数据不一致");
        JsonNode fields = evidence.fields();
        JsonNode projection = fields == null ? null : fields.get("marketData");
        require(fields != null && fields.isObject() && fields.size() == 1
                        && projection != null && projection.isObject()
                        && projection.size() == MARKET_FIELDS.size()
                        && MARKET_FIELDS.stream().allMatch(projection::has),
                "阶段2E-1 marketData证据字段白名单不一致");
        for (String name : MARKET_FIELDS) {
            JsonNode source = "latestBar".equals(name)
                    ? input.latestBar() : input.marketSource().get(name);
            require(semanticJsonEquals(projection.get(name), source),
                    "阶段2E-1 marketData证据不是冻结输入的直接投影：" + name);
        }
        return evidence;
    }

    private static List<ExpectedFinding> expectedFindings(
            TechnicalInput input,
            Evidence metricsEvidence,
            Evidence marketEvidence
    ) {
        List<ExpectedFinding> result = new ArrayList<>();
        String metricsId = metricsEvidence.evidenceId();
        List<String> bothIds = List.of(metricsId, marketEvidence.evidenceId());

        String trendCode;
        String trendState;
        String trendCondition;
        if (input.ma5().compareTo(input.ma20()) > 0
                && input.ma20().compareTo(input.ma60()) > 0) {
            trendCode = "TECH_TREND_BULLISH_ALIGNED";
            trendState = "BULLISH_ALIGNED";
            trendCondition = "ma5>ma20>ma60";
        } else if (input.ma5().compareTo(input.ma20()) < 0
                && input.ma20().compareTo(input.ma60()) < 0) {
            trendCode = "TECH_TREND_BEARISH_ALIGNED";
            trendState = "BEARISH_ALIGNED";
            trendCondition = "ma5<ma20<ma60";
        } else {
            trendCode = "TECH_TREND_MIXED";
            trendState = "MIXED";
            trendCondition = "not(ma5>ma20>ma60 or ma5<ma20<ma60)";
        }
        String trendDetail = "evidencePaths=technicalMetrics.values.ma5,technicalMetrics.values.ma20,"
                + "technicalMetrics.values.ma60；observed=ma5:" + fixed6(input.ma5())
                + ",ma20:" + fixed6(input.ma20()) + ",ma60:" + fixed6(input.ma60())
                + "；condition=" + trendCondition + "；scoreImpact=" + signed(IMPACTS.get(trendCode)) + "。";
        result.add(new ExpectedFinding(trendCode, trendDetail, List.of(metricsId)));

        String rsiCode;
        String rsiCondition;
        if (input.rsi14().compareTo(RSI_OVERBOUGHT) >= 0) {
            rsiCode = "TECH_RSI_OVERBOUGHT_RISK";
            rsiCondition = "rsi14>=70.000000";
        } else if (input.rsi14().compareTo(RSI_MIDPOINT) > 0) {
            rsiCode = "TECH_RSI_POSITIVE_MOMENTUM";
            rsiCondition = "50.000000<rsi14<70.000000";
        } else if (input.rsi14().compareTo(RSI_MIDPOINT) == 0) {
            rsiCode = "TECH_RSI_NEUTRAL";
            rsiCondition = "rsi14=50.000000";
        } else if (input.rsi14().compareTo(RSI_OVERSOLD) > 0) {
            rsiCode = "TECH_RSI_NEGATIVE_MOMENTUM";
            rsiCondition = "30.000000<rsi14<50.000000";
        } else {
            rsiCode = "TECH_RSI_OVERSOLD_RISK";
            rsiCondition = "rsi14<=30.000000";
        }
        String rsiDetail = "evidencePath=technicalMetrics.values.rsi14；observed=rsi14:"
                + fixed6(input.rsi14()) + "；condition=" + rsiCondition
                + "；scoreImpact=" + signed(IMPACTS.get(rsiCode)) + "。";
        result.add(new ExpectedFinding(rsiCode, rsiDetail, List.of(metricsId)));

        BigDecimal deviation = input.latestClose().subtract(input.ma20())
                .divide(input.ma20(), PYTHON_DECIMAL_CONTEXT);
        String deviationCode;
        String deviationCondition;
        if (deviation.compareTo(DEVIATION_LIMIT) >= 0) {
            deviationCode = "TECH_PRICE_ABOVE_MA20_EXTENDED";
            deviationCondition = "deviation>=10.0000%";
        } else if (deviation.compareTo(DEVIATION_LIMIT.negate()) <= 0) {
            deviationCode = "TECH_PRICE_BELOW_MA20_EXTENDED";
            deviationCondition = "deviation<=-10.0000%";
        } else {
            deviationCode = "TECH_PRICE_NEAR_MA20";
            deviationCondition = "-10.0000%<deviation<10.0000%";
        }
        String deviationDetail = "evidencePaths=marketData.bars[-1].close,technicalMetrics.values.ma20；"
                + "observed=latestClose:" + fixed6(input.latestClose())
                + ",ma20:" + fixed6(input.ma20()) + ",deviationPct:" + percent4(deviation)
                + "%；condition=" + deviationCondition + "；scoreImpact="
                + signed(IMPACTS.get(deviationCode)) + "。";
        result.add(new ExpectedFinding(deviationCode, deviationDetail, bothIds));

        BigDecimal atrRatio = input.atr14().divide(
                input.latestClose(),
                PYTHON_DECIMAL_CONTEXT);
        String volatilityCode;
        String volatilityCondition;
        if (atrRatio.compareTo(ATR_RATIO_LIMIT) >= 0) {
            volatilityCode = "TECH_VOLATILITY_ELEVATED";
            volatilityCondition = "atrRatio>=5.0000%";
        } else {
            volatilityCode = "TECH_VOLATILITY_NORMAL";
            volatilityCondition = "atrRatio<5.0000%";
        }
        String volatilityDetail = "evidencePaths=technicalMetrics.values.atr14,marketData.bars[-1].close；"
                + "observed=atr14:" + fixed6(input.atr14()) + ",latestClose:"
                + fixed6(input.latestClose()) + ",atrRatioPct:" + percent4(atrRatio)
                + "%；condition=" + volatilityCondition + "；scoreImpact="
                + signed(IMPACTS.get(volatilityCode)) + "。";
        result.add(new ExpectedFinding(volatilityCode, volatilityDetail, bothIds));

        String confirmationCode;
        String confirmationCondition;
        if ("TECH_TREND_BULLISH_ALIGNED".equals(trendCode)
                && input.rsi14().compareTo(RSI_MIDPOINT) > 0
                && input.rsi14().compareTo(RSI_OVERBOUGHT) < 0
                && input.latestClose().compareTo(input.ma20()) > 0) {
            confirmationCode = "TECH_INDICATORS_BULLISH_CONFIRMED";
            confirmationCondition =
                    "bullishTrend and 50.000000<rsi14<70.000000 and latestClose>ma20";
        } else if ("TECH_TREND_BEARISH_ALIGNED".equals(trendCode)
                && input.rsi14().compareTo(RSI_OVERSOLD) > 0
                && input.rsi14().compareTo(RSI_MIDPOINT) < 0
                && input.latestClose().compareTo(input.ma20()) < 0) {
            confirmationCode = "TECH_INDICATORS_BEARISH_CONFIRMED";
            confirmationCondition =
                    "bearishTrend and 30.000000<rsi14<50.000000 and latestClose<ma20";
        } else {
            confirmationCode = "TECH_INDICATORS_CONFLICT_OR_UNCONFIRMED";
            confirmationCondition = "neither frozen confirmation condition is satisfied";
        }
        String confirmationDetail = "evidencePaths=technicalMetrics.values.ma5,technicalMetrics.values.ma20,"
                + "technicalMetrics.values.ma60,technicalMetrics.values.rsi14,marketData.bars[-1].close；"
                + "observed=trend:" + trendState + ",rsi14:" + fixed6(input.rsi14())
                + ",latestClose:" + fixed6(input.latestClose()) + ",ma20:" + fixed6(input.ma20())
                + "；condition=" + confirmationCondition + "；scoreImpact="
                + signed(IMPACTS.get(confirmationCode)) + "。";
        result.add(new ExpectedFinding(confirmationCode, confirmationDetail, bothIds));
        return List.copyOf(result);
    }

    private static void validateFindings(
            AgentTeamRequest request,
            List<Finding> actual,
            List<ExpectedFinding> expected
    ) {
        require(actual.size() == expected.size(),
                "阶段2E-1 TECHNICAL_ANALYSIS finding数量必须为5");
        for (int index = 0; index < expected.size(); index++) {
            ExpectedFinding expectedFinding = expected.get(index);
            Finding finding = actual.get(index);
            int rank = FINDING_ORDER.indexOf(expectedFinding.code()) + 1;
            String expectedId = "ta-%02d-%s-%s".formatted(
                    rank,
                    expectedFinding.code().toLowerCase(Locale.ROOT).replace('_', '-'),
                    request.contextHash());
            require(expectedFinding.code().equals(finding.code())
                            && expectedId.equals(finding.findingId())
                            && SEVERITIES.get(expectedFinding.code()) == finding.severity()
                            && TITLES.get(expectedFinding.code()).equals(finding.title())
                            && expectedFinding.detail().equals(finding.detail())
                            && expectedFinding.evidenceIds().equals(finding.evidenceIds()),
                    "阶段2E-1 TECHNICAL_ANALYSIS finding内容、顺序或证据引用不一致："
                            + expectedFinding.code());
        }
    }

    private static TechnicalInput parseInput(AgentTeamRequest request) {
        try {
            JsonNode snapshot = request.contextSnapshot();
            JsonNode market = snapshot == null ? null : snapshot.get("marketData");
            JsonNode metrics = snapshot == null ? null : snapshot.get("technicalMetrics");
            if (market == null || !market.isObject() || metrics == null || !metrics.isObject()
                    || !booleanValue(market, "available") || !booleanValue(metrics, "available")
                    || !textEquals(market, "adjustType", ADJUST_TYPE)
                    || !textEquals(metrics, "adjustType", ADJUST_TYPE)
                    || !textEquals(metrics, "formulaVersion", FORMULA_VERSION)
                    || !textDateEquals(market, "requestedTradeDate", request.tradeDate())
                    || !textDateEquals(metrics, "requestedTradeDate", request.tradeDate())
                    || !integralEquals(market, "actualBars", REQUIRED_BARS)
                    || !integralEquals(metrics, "actualBars", REQUIRED_BARS)
                    || !integralEquals(metrics, "requiredBars", REQUIRED_BARS)
                    || !queryScopeMatches(market, request)
                    || !queryScopeMatches(metrics, request)) {
                return null;
            }
            Instant marketQueriedAt = instantValue(market.get("queriedAt"));
            Instant metricsQueriedAt = instantValue(metrics.get("queriedAt"));
            if (!sameInstant(marketQueriedAt, metricsQueriedAt)) return null;

            JsonNode windows = metrics.get("windows");
            if (windows == null || !windows.isObject() || windows.size() != WINDOWS.size()) return null;
            for (var entry : WINDOWS.entrySet()) {
                if (!integralEquals(windows, entry.getKey(), entry.getValue())) return null;
            }
            JsonNode values = metrics.get("values");
            if (values == null || !values.isObject() || values.size() != VALUE_FIELDS.size()
                    || !VALUE_FIELDS.stream().allMatch(values::has)
                    || !VALUE_FIELDS.stream().allMatch(name -> finiteNumber(values.get(name)))) {
                return null;
            }

            JsonNode bars = market.get("bars");
            if (bars == null || !bars.isArray() || bars.size() != REQUIRED_BARS) return null;
            LocalDate previous = null;
            JsonNode latest = null;
            for (JsonNode bar : bars) {
                if (bar == null || !bar.isObject() || bar.size() != BAR_FIELDS.size()
                        || !BAR_FIELDS.stream().allMatch(bar::has)
                        || !textEquals(bar, "symbol", request.symbol())
                        || !bar.path("tradeDate").isTextual()
                        || !finiteNumber(bar.get("open"))
                        || !finiteNumber(bar.get("high"))
                        || !finiteNumber(bar.get("low"))
                        || !finiteNumber(bar.get("close"))
                        || !bar.path("volume").isIntegralNumber()
                        || !bar.path("volume").canConvertToLong()
                        || !nullableFiniteNumber(bar.get("amount"))
                        || !nullableFiniteNumber(bar.get("turnoverRate"))) {
                    return null;
                }
                LocalDate tradeDate = LocalDate.parse(bar.get("tradeDate").textValue());
                if (tradeDate.isAfter(request.tradeDate())
                        || previous != null && !tradeDate.isAfter(previous)) return null;
                previous = tradeDate;
                BigDecimal open = decimal(bar.get("open"));
                BigDecimal high = decimal(bar.get("high"));
                BigDecimal low = decimal(bar.get("low"));
                BigDecimal close = decimal(bar.get("close"));
                if (open.signum() <= 0 || high.signum() <= 0 || low.signum() <= 0
                        || close.signum() <= 0 || bar.get("volume").longValue() < 0
                        || high.compareTo(open) < 0 || high.compareTo(close) < 0
                        || high.compareTo(low) < 0 || low.compareTo(open) > 0
                        || low.compareTo(close) > 0 || low.compareTo(high) > 0) return null;
                latest = bar;
            }
            if (latest == null) return null;
            LocalDate effective = LocalDate.parse(latest.get("tradeDate").textValue());
            if (!textDateEquals(market, "effectiveTradeDate", effective)
                    || !textDateEquals(metrics, "effectiveTradeDate", effective)
                    || !market.path("exactTradeDateMatch").isBoolean()
                    || market.get("exactTradeDateMatch").booleanValue()
                    != effective.equals(request.tradeDate())) return null;

            BigDecimal ma5 = decimal(values.get("ma5"));
            BigDecimal ma20 = decimal(values.get("ma20"));
            BigDecimal ma60 = decimal(values.get("ma60"));
            BigDecimal rsi14 = decimal(values.get("rsi14"));
            BigDecimal atr14 = decimal(values.get("atr14"));
            BigDecimal averageVolume20 = decimal(values.get("averageVolume20"));
            BigDecimal highestClose20 = decimal(values.get("highestClose20"));
            BigDecimal latestClose = decimal(latest.get("close"));
            if (ma5.signum() <= 0 || ma20.signum() <= 0 || ma60.signum() <= 0
                    || highestClose20.signum() <= 0
                    || rsi14.compareTo(BigDecimal.ZERO) < 0
                    || rsi14.compareTo(new BigDecimal("100")) > 0
                    || atr14.signum() < 0 || averageVolume20.signum() < 0
                    || highestClose20.compareTo(latestClose) < 0) return null;
            return new TechnicalInput(
                    market, metrics, latest, marketQueriedAt,
                    ma5, ma20, ma60, rsi14, atr14, latestClose);
        } catch (DateTimeException | ArithmeticException | NullPointerException error) {
            return null;
        }
    }

    private static boolean queryScopeMatches(JsonNode source, AgentTeamRequest request) {
        JsonNode scope = source.get("queryScope");
        return scope != null && scope.isObject()
                && textEquals(scope, "symbol", request.symbol())
                && textDateEquals(scope, "tradeDate", request.tradeDate());
    }

    private static boolean booleanValue(JsonNode source, String field) {
        return source.has(field) && source.get(field).isBoolean()
                && source.get(field).booleanValue();
    }

    private static boolean textEquals(JsonNode source, String field, String expected) {
        return source.has(field) && source.get(field).isTextual()
                && Objects.equals(source.get(field).textValue(), expected);
    }

    private static boolean textDateEquals(JsonNode source, String field, LocalDate expected) {
        return source.has(field) && source.get(field).isTextual()
                && Objects.equals(LocalDate.parse(source.get(field).textValue()), expected);
    }

    private static boolean integralEquals(JsonNode source, String field, int expected) {
        return source.has(field) && source.get(field).isIntegralNumber()
                && source.get(field).canConvertToInt() && source.get(field).intValue() == expected;
    }

    private static boolean finiteNumber(JsonNode node) {
        if (node == null || !node.isNumber()) return false;
        try {
            return node.decimalValue() != null;
        } catch (ArithmeticException | NumberFormatException error) {
            return false;
        }
    }

    private static boolean nullableFiniteNumber(JsonNode node) {
        return node != null && (node.isNull() || finiteNumber(node));
    }

    private static BigDecimal decimal(JsonNode node) {
        return node.decimalValue();
    }

    private static Instant instantValue(JsonNode node) {
        if (node == null || !node.isTextual()) return null;
        try {
            return Instant.from(DateTimeFormatter.ISO_DATE_TIME.parse(node.textValue()));
        } catch (DateTimeException error) {
            return null;
        }
    }

    private static boolean sameInstant(Instant left, Instant right) {
        return left != null && right != null
                && left.truncatedTo(ChronoUnit.MICROS)
                .equals(right.truncatedTo(ChronoUnit.MICROS));
    }

    private static String fixed6(BigDecimal value) {
        return value.setScale(6, RoundingMode.HALF_UP).toPlainString();
    }

    private static String percent4(BigDecimal ratio) {
        return ratio.multiply(new BigDecimal("100"))
                .setScale(4, RoundingMode.HALF_UP).toPlainString();
    }

    private static String signed(int value) {
        return value > 0 ? "+" + value : Integer.toString(value);
    }

    private static boolean semanticJsonEquals(JsonNode left, JsonNode right) {
        if (left == null || right == null) return left == right;
        if (left.isNumber() && right.isNumber()) {
            return decimal(left).compareTo(decimal(right)) == 0;
        }
        if (left.isObject() && right.isObject()) {
            if (left.size() != right.size()) return false;
            var fields = left.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!right.has(field.getKey())
                        || !semanticJsonEquals(field.getValue(), right.get(field.getKey()))) return false;
            }
            return true;
        }
        if (left.isArray() && right.isArray()) {
            if (left.size() != right.size()) return false;
            for (int index = 0; index < left.size(); index++) {
                if (!semanticJsonEquals(left.get(index), right.get(index))) return false;
            }
            return true;
        }
        return left.getNodeType() == right.getNodeType() && left.equals(right);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AgentResponseValidationException("智能体响应校验失败：" + message);
        }
    }

    private record TechnicalInput(
            JsonNode marketSource,
            JsonNode metricsSource,
            JsonNode latestBar,
            Instant queriedAt,
            BigDecimal ma5,
            BigDecimal ma20,
            BigDecimal ma60,
            BigDecimal rsi14,
            BigDecimal atr14,
            BigDecimal latestClose
    ) {}

    private record ExpectedFinding(
            String code,
            String detail,
            List<String> evidenceIds
    ) {}
}
