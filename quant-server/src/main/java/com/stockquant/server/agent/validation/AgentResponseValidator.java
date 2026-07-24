package com.stockquant.server.agent.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockquant.server.agent.exception.AgentResponseValidationException;
import com.stockquant.server.agent.model.AgentModels.AgentError;
import com.stockquant.server.agent.model.AgentModels.AgentOutput;
import com.stockquant.server.agent.model.AgentModels.AgentTeamRequest;
import com.stockquant.server.agent.model.AgentModels.AgentTeamResponse;
import com.stockquant.server.agent.model.AgentModels.Evidence;
import com.stockquant.server.agent.model.AgentModels.Finding;
import com.stockquant.server.agent.model.AgentModels.FinalDecision;
import com.stockquant.server.agent.model.AgentModels.FormalVeto;
import com.stockquant.server.agent.model.AgentTypes.AgentCode;
import com.stockquant.server.agent.model.AgentTypes.EvidenceCategory;
import com.stockquant.server.agent.model.AgentTypes.EvidenceSourceType;
import com.stockquant.server.agent.model.AgentTypes.FinalDecisionCode;
import com.stockquant.server.agent.model.AgentTypes.GateStatus;
import com.stockquant.server.agent.model.AgentTypes.RunDecision;
import com.stockquant.server.agent.model.AgentTypes.RunStatus;
import com.stockquant.server.agent.model.AgentTypes.Severity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
public class AgentResponseValidator {

    private static final String STAGE_2B_DATA_QUALITY_RULE_VERSION = "1.4.0-stage-2b-dq-v1";
    private static final String STAGE_2D_MARKET_REGIME_RULE_VERSION =
            "1.4.0-stage-2d-market-regime-v1";
    private static final String STAGE_2E_TECHNICAL_ANALYSIS_RULE_VERSION =
            "1.4.0-stage-2e-technical-analysis-v1";
    private static final String STAGE_2F_STRATEGY_BACKTEST_RULE_VERSION =
            "1.4.0-stage-2f-strategy-backtest-v1";
    private static final String STAGE_2H_POSITION_RISK_RULE_VERSION =
            "1.4.0-stage-2h-position-risk-v1";
    private static final ZoneId STAGE_2D_MARKET_ZONE = ZoneId.of("Asia/Shanghai");
    private static final BigDecimal STAGE_2D_MIN_COVERAGE_RATIO = new BigDecimal("1.00000000");
    private static final int STAGE_2D_MIN_COMPARABLE_SYMBOL_COUNT = 2;
    private static final List<String> STAGE_2D_MARKET_BREADTH_FIELDS = List.of(
            "available",
            "reasonCode",
            "sourceType",
            "sourceTables",
            "sourceStatus",
            "producer",
            "producerVersion",
            "versionAvailable",
            "requestedTradeDate",
            "effectiveTradeDate",
            "previousEffectiveTradeDate",
            "exactTradeDateMatch",
            "pointInTimeGuaranteed",
            "barFutureDataExcluded",
            "universePointInTimeGuaranteed",
            "futureDataExcluded",
            "timestampTimezoneSemantics",
            "adjustType",
            "selectionRule",
            "universeCount",
            "coveredSymbolCount",
            "comparableSymbolCount",
            "advancingCount",
            "decliningCount",
            "unchangedCount",
            "missingCurrentBarCount",
            "missingPreviousBarCount",
            "coverageRatio",
            "limitations"
    );
    private static final List<String> STAGE_2D_FINDING_ORDER = List.of(
            "MARKET_BREADTH_FACT_INCONSISTENT",
            "MARKET_BREADTH_UNAVAILABLE",
            "MARKET_BREADTH_LOW_COVERAGE",
            "MARKET_BREADTH_DATE_NOT_EXACT",
            "MARKET_BREADTH_POINT_IN_TIME_UNVERIFIED",
            "MARKET_BREADTH_POSITIVE",
            "MARKET_BREADTH_MIXED",
            "MARKET_BREADTH_NEGATIVE"
    );
    private static final Map<String, String> STAGE_2D_FINDING_TITLES = Map.of(
            "MARKET_BREADTH_FACT_INCONSISTENT", "当前证券池宽度事实不一致",
            "MARKET_BREADTH_UNAVAILABLE", "当前证券池宽度事实不可用",
            "MARKET_BREADTH_LOW_COVERAGE", "当前证券池宽度覆盖不足",
            "MARKET_BREADTH_DATE_NOT_EXACT", "当前证券池宽度日期未精确命中",
            "MARKET_BREADTH_POINT_IN_TIME_UNVERIFIED", "当前证券池宽度时点属性不可验证",
            "MARKET_BREADTH_POSITIVE", "当前证券池宽度偏正向",
            "MARKET_BREADTH_MIXED", "当前证券池宽度混合",
            "MARKET_BREADTH_NEGATIVE", "当前证券池宽度偏负向"
    );
    private static final Map<String, String> STAGE_2D_FINDING_DETAILS = Map.of(
            "MARKET_BREADTH_FACT_INCONSISTENT", "市场宽度来源、版本、日期、计数或比例事实无法互相验证。",
            "MARKET_BREADTH_UNAVAILABLE", "本地只读市场宽度上下文未提供可比较事实，未形成宽度方向。",
            "MARKET_BREADTH_LOW_COVERAGE", "当前证券池宽度覆盖率未达到1.00000000或可比较证券少于2只，未形成宽度方向。",
            "MARKET_BREADTH_DATE_NOT_EXACT", "有效交易日未精确匹配请求交易日，未形成宽度方向。",
            "MARKET_BREADTH_POINT_IN_TIME_UNVERIFIED", "当前证券池不是历史版本，结果仅描述冻结请求时的当前证券池宽度状态。",
            "MARKET_BREADTH_POSITIVE", "可比较证券中上涨数量多于下跌数量，仅描述冻结的当前证券池宽度事实。",
            "MARKET_BREADTH_MIXED", "可比较证券中上涨数量等于下跌数量，仅描述冻结的当前证券池宽度事实。",
            "MARKET_BREADTH_NEGATIVE", "可比较证券中下跌数量多于上涨数量，仅描述冻结的当前证券池宽度事实。"
    );
    private static final List<String> STAGE_2B_RULE_ORDER = List.of(
            "QUERY_SCOPE_INCONSISTENT",
            "REQUEST_DATE_INCONSISTENT",
            "EFFECTIVE_DATE_FACT_CONTRADICTION",
            "EFFECTIVE_DATE_INCONSISTENT",
            "NATURAL_DAY_LAG_INCONSISTENT",
            "EXACT_TRADE_DATE_INCONSISTENT",
            "SECURITY_AVAILABILITY_INCONSISTENT",
            "SECURITY_SYMBOL_INCONSISTENT",
            "SECURITY_QUALITY_FACTS_INCONSISTENT",
            "SECURITY_FIELDS_INCONSISTENT",
            "BAR_COUNT_INCONSISTENT",
            "REQUIRED_BAR_COUNT_INCONSISTENT",
            "ADJUST_TYPE_INCONSISTENT",
            "FORMULA_VERSION_INCONSISTENT",
            "DUPLICATE_PROTECTION_INCONSISTENT",
            "INVALID_BAR_DATES_INCONSISTENT",
            "OPTIONAL_FIELD_COUNT_INCONSISTENT",
            "NATURAL_DAY_GAP_INCONSISTENT",
            "TECHNICAL_AVAILABILITY_INCONSISTENT",
            "SECURITY_RECORD_MISSING",
            "SECURITY_PLACEHOLDER_SUSPECTED",
            "SECURITY_SOURCE_UNKNOWN",
            "MARKET_DATA_MISSING",
            "INSUFFICIENT_DAILY_BARS",
            "INVALID_DAILY_BARS",
            "TECHNICAL_METRICS_UNAVAILABLE",
            "MARKET_DATA_TOO_STALE",
            "REQUEST_DATE_NOT_EXACT",
            "SECURITY_POINT_IN_TIME_UNVERIFIED",
            "MISSING_SECURITY_FIELDS",
            "OPTIONAL_MARKET_FIELDS_MISSING",
            "LARGE_NATURAL_DAY_GAP",
            "TRADING_CALENDAR_UNAVAILABLE",
            "SOURCE_CONSISTENCY_UNASSESSABLE",
            "SECURITY_SCOPE_FACT"
    );
    private static final Set<String> STAGE_2B_WARN_CODES = Set.of(
            "REQUEST_DATE_NOT_EXACT",
            "SECURITY_POINT_IN_TIME_UNVERIFIED",
            "MISSING_SECURITY_FIELDS",
            "OPTIONAL_MARKET_FIELDS_MISSING",
            "LARGE_NATURAL_DAY_GAP"
    );
    private static final Set<String> STAGE_2B_INFO_CODES = Set.of(
            "TRADING_CALENDAR_UNAVAILABLE",
            "SOURCE_CONSISTENCY_UNASSESSABLE",
            "SECURITY_SCOPE_FACT"
    );
    private static final Set<String> STAGE_2B_FORBIDDEN_EVIDENCE_CONCLUSION_FIELDS = Set.of(
            "gate", "gatestatus", "decision", "score", "finding", "findings", "veto"
    );

    private static final Set<RunStatus> PYTHON_RESULT_STATUSES = EnumSet.of(
            RunStatus.COMPLETED,
            RunStatus.PARTIAL,
            RunStatus.INSUFFICIENT_DATA,
            RunStatus.FAILED
    );

    public void validate(AgentTeamRequest request, AgentTeamResponse response) {
        require(response != null, "团队响应不能为空");
        require("1.0".equals(response.schemaVersion()), "团队响应schemaVersion必须为1.0");
        require(response.generatedAt() != null, "团队响应generatedAt不能为空");
        require(response.taskId() == request.taskId(), "响应taskId与请求不一致");
        require(Objects.equals(response.contextHash(), request.contextHash()), "响应contextHash与请求不一致");
        require(Objects.equals(response.ruleVersion(), request.ruleVersion()), "响应ruleVersion与请求不一致");
        require(response.executionMode() == request.executionMode(), "响应executionMode与请求不一致");
        require(Objects.equals(response.tradeDate(), request.tradeDate()), "响应tradeDate与请求不一致");

        Map<AgentCode, Long> expectedRuns = request.runIds().byAgentCode();
        List<AgentOutput> runs = safe(response.agentRuns());
        require(response.agentRuns() != null, "agentRuns不能为空");
        require(response.evidence() != null, "顶层evidence不能为空");
        require(response.vetoes() != null, "vetoes不能为空");
        require(runs.size() == AgentCode.PROFESSIONAL_AGENTS.size(), "团队响应必须恰好包含6个专业智能体");
        Set<AgentCode> seenCodes = new HashSet<>();
        for (AgentOutput run : runs) {
            require(run != null && run.agentCode() != null, "agentRun或agentCode不能为空");
            require("1.0".equals(run.schemaVersion()), "agentRun schemaVersion必须为1.0");
            require(run.generatedAt() != null, "agentRun generatedAt不能为空");
            require(seenCodes.add(run.agentCode()), "agentCode重复：" + run.agentCode());
            require(expectedRuns.containsKey(run.agentCode()), "未知专业智能体：" + run.agentCode());
            require(expectedRuns.get(run.agentCode()) == run.runId(), "runId与Java预分配映射不一致");
            require(run.taskId() == request.taskId(), "agentRun taskId不一致");
            require(Objects.equals(run.contextHash(), request.contextHash()), "agentRun contextHash不一致");
            require(Objects.equals(run.ruleVersion(), request.ruleVersion()), "agentRun ruleVersion不一致");
            require(run.executionMode() == request.executionMode(), "agentRun executionMode不一致");
            requireRange(run.score(), "agentRun score");
            requireRange(run.confidence(), "agentRun confidence");
            require(run.status() != null && run.gateStatus() != null && run.decision() != null,
                    "agentRun状态字段不能为空");
            require(PYTHON_RESULT_STATUSES.contains(run.status()),
                    "Python响应中的agentRun status必须是结果终态");
            require(notBlank(run.summary()), "agentRun summary不能为空");
            require(run.findings() != null && run.evidence() != null && run.errors() != null,
                    "agentRun findings、evidence和errors不能为空");
            validateRunVeto(run);
        }
        require(seenCodes.equals(expectedRuns.keySet()), "缺少专业智能体结果");

        Map<String, Evidence> authoritative = authoritativeEvidence(response.evidence());
        for (AgentOutput run : runs) {
            validateFindings(run.findings(), authoritative);
            Set<String> subsetIds = new HashSet<>();
            for (Evidence subset : safe(run.evidence())) {
                require(subset != null && notBlank(subset.evidenceId()),
                        "单智能体evidenceId不能为空");
                require(subsetIds.add(subset.evidenceId()),
                        "单智能体evidenceId不能重复：" + subset.evidenceId());
                Evidence topLevel = authoritative.get(subset.evidenceId());
                require(topLevel != null, "单智能体证据不在顶层权威证据集合：" + subset.evidenceId());
                require(sameEvidence(topLevel, subset), "相同evidenceId内容冲突：" + subset.evidenceId());
            }
        }

        List<FormalVeto> vetoes = safe(response.vetoes());
        Set<String> vetoIds = new HashSet<>();
        for (FormalVeto veto : vetoes) {
            require(veto != null && notBlank(veto.vetoId()), "vetoId不能为空");
            require(vetoIds.add(veto.vetoId()), "vetoId重复：" + veto.vetoId());
            require(veto.taskId() == request.taskId(), "正式veto taskId不一致");
            require(veto.agentCode() == AgentCode.POSITION_RISK, "正式veto只能来自POSITION_RISK");
            require(notBlank(veto.vetoCode()) && notBlank(veto.reason()), "正式veto代码和原因不能为空");
            require(veto.createdAt() != null, "正式veto createdAt不能为空");
            require(expectedRuns.get(AgentCode.POSITION_RISK) == veto.runId(), "正式veto runId不是POSITION_RISK运行");
            require(!safe(veto.evidenceIds()).isEmpty(), "正式veto必须引用证据");
            validateEvidenceIds(veto.evidenceIds(), authoritative);
        }

        FinalDecision decision = response.finalDecision();
        require(decision != null, "finalDecision不能为空");
        require("1.0".equals(decision.schemaVersion()), "finalDecision schemaVersion必须为1.0");
        require(decision.generatedAt() != null, "finalDecision generatedAt不能为空");
        require(decision.taskId() == request.taskId(), "finalDecision taskId不一致");
        require(Objects.equals(decision.contextHash(), request.contextHash()), "finalDecision contextHash不一致");
        require(Objects.equals(decision.ruleVersion(), request.ruleVersion()), "finalDecision ruleVersion不一致");
        require(decision.executionMode() == request.executionMode(), "finalDecision executionMode不一致");
        require(Objects.equals(decision.tradeDate(), request.tradeDate()), "finalDecision tradeDate不一致");
        requireRange(decision.score(), "finalDecision score");
        requireRange(decision.confidence(), "finalDecision confidence");
        require(decision.decision() != null && decision.gateStatus() != null,
                "finalDecision状态字段不能为空");
        require(notBlank(decision.summary()), "finalDecision summary不能为空");
        require(decision.findings() != null && decision.sourceRunIds() != null && decision.vetoIds() != null,
                "finalDecision集合字段不能为空");
        validateFindings(decision.findings(), authoritative);

        Set<Long> expectedRunIds = new HashSet<>(expectedRuns.values());
        List<Long> sourceRunIds = safe(decision.sourceRunIds());
        require(!sourceRunIds.isEmpty(), "sourceRunIds不能为空");
        require(new HashSet<>(sourceRunIds).size() == sourceRunIds.size(), "sourceRunIds不能重复");
        require(sourceRunIds.stream().allMatch(expectedRunIds::contains), "sourceRunIds包含未知或其他任务运行");
        require(new HashSet<>(sourceRunIds).equals(expectedRunIds),
                "sourceRunIds必须恰好包含6个Java权威runId");

        Set<String> decisionVetoIds = new HashSet<>(safe(decision.vetoIds()));
        require(decisionVetoIds.size() == safe(decision.vetoIds()).size(), "finalDecision vetoIds不能重复");
        require(decisionVetoIds.equals(vetoIds), "finalDecision vetoIds与正式veto集合不一致");
        if (!vetoes.isEmpty()) {
            AgentOutput positionRisk = runs.stream()
                    .filter(run -> run.agentCode() == AgentCode.POSITION_RISK)
                    .findFirst().orElseThrow();
            require(positionRisk.veto(), "存在正式veto时POSITION_RISK运行必须veto=true");
            require(decision.vetoed(), "存在正式veto时vetoed必须为true");
            require(decision.decision() == FinalDecisionCode.REJECTED_BY_VETO,
                    "存在正式veto时必须REJECTED_BY_VETO");
        } else {
            AgentOutput positionRisk = runs.stream()
                    .filter(run -> run.agentCode() == AgentCode.POSITION_RISK)
                    .findFirst().orElseThrow();
            require(!positionRisk.veto(), "POSITION_RISK veto=true时必须返回正式veto");
            require(!decision.vetoed(), "不存在正式veto时vetoed必须为false");
            require(decision.decision() != FinalDecisionCode.REJECTED_BY_VETO,
                    "不存在正式veto时不能REJECTED_BY_VETO");
        }

        AgentOutput dataQuality = runs.stream()
                .filter(run -> run.agentCode() == AgentCode.DATA_QUALITY)
                .findFirst().orElseThrow();
        if (dataQuality.gateStatus() == GateStatus.BLOCKED && vetoes.isEmpty()) {
            require(decision.decision() == FinalDecisionCode.BLOCKED_BY_DATA_QUALITY,
                    "数据质量阻断时必须BLOCKED_BY_DATA_QUALITY");
            require(decision.gateStatus() == GateStatus.BLOCKED,
                    "数据质量阻断时finalDecision gateStatus必须为BLOCKED");
        }
        if (STAGE_2B_DATA_QUALITY_RULE_VERSION.equals(request.ruleVersion())) {
            validateStage2BDataQuality(request, response, runs, dataQuality);
        } else if (STAGE_2D_MARKET_REGIME_RULE_VERSION.equals(request.ruleVersion())) {
            validateStage2DMarketBreadth(request, response, runs, dataQuality);
        } else if (STAGE_2E_TECHNICAL_ANALYSIS_RULE_VERSION.equals(request.ruleVersion())) {
            validateStage2ETechnicalAnalysis(request, response, runs, dataQuality);
        } else if (STAGE_2F_STRATEGY_BACKTEST_RULE_VERSION.equals(request.ruleVersion())) {
            validateStage2FStrategyBacktest(request, response, runs, dataQuality);
        } else if (STAGE_2H_POSITION_RISK_RULE_VERSION.equals(request.ruleVersion())) {
            validateStage2HPositionRisk(request, response, runs, dataQuality);
        }
    }

    private static void validateStage2BDataQuality(
            AgentTeamRequest request,
            AgentTeamResponse response,
            List<AgentOutput> runs,
            AgentOutput dataQuality
    ) {
        FinalDecision decision = response.finalDecision();
        require(response.vetoes().isEmpty() && !decision.vetoed() && decision.vetoIds().isEmpty(),
                "阶段2B DATA_QUALITY规则不得产生正式veto");
        require(runs.stream().noneMatch(AgentOutput::veto),
                "阶段2B六个专业智能体均不得产生正式veto");

        validateStage2BDataQualityOutput(request, dataQuality);

        if (dataQuality.status() == RunStatus.INSUFFICIENT_DATA) {
            require(response.evidence().isEmpty(),
                    "阶段2B无效上下文不得生成顶层证据");
        } else {
            require(response.evidence().size() == 1
                            && sameEvidence(response.evidence().get(0), dataQuality.evidence().get(0)),
                    "阶段2B顶层证据必须恰好等于DATA_QUALITY证据");
        }

        validateUnimplementedRuns(runs, Set.of(AgentCode.DATA_QUALITY), "阶段2B");

        require(Objects.equals(decision.findings(), dataQuality.findings()),
                "阶段2B总控finding必须来自DATA_QUALITY运行");
        if (dataQuality.gateStatus() == GateStatus.BLOCKED) {
            require(decision.decision() == FinalDecisionCode.BLOCKED_BY_DATA_QUALITY
                            && decision.gateStatus() == GateStatus.BLOCKED
                            && decision.score() == 0
                            && Objects.equals(decision.confidence(), dataQuality.confidence()),
                    "阶段2B DATA_QUALITY阻断时finalDecision状态映射不一致");
        } else {
            require(decision.decision() == FinalDecisionCode.INSUFFICIENT_DATA
                            && decision.gateStatus() == dataQuality.gateStatus()
                            && decision.score() == 0 && decision.confidence() == 0,
                    "阶段2B DATA_QUALITY未阻断时finalDecision状态映射不一致");
            require(!decision.summary().contains("数据质量门禁阻断"),
                    "阶段2B DATA_QUALITY未阻断时总控摘要不得声称数据质量阻断");
        }
    }

    private static void validateStage2BDataQualityOutput(
            AgentTeamRequest request,
            AgentOutput dataQuality
    ) {

        if (dataQuality.status() == RunStatus.INSUFFICIENT_DATA) {
            require(dataQuality.gateStatus() == GateStatus.BLOCKED
                            && dataQuality.decision() == RunDecision.REJECT
                            && dataQuality.score() == 0 && dataQuality.confidence() == 0,
                    "阶段2B无效上下文状态映射不一致");
            require(dataQuality.findings().isEmpty() && dataQuality.evidence().isEmpty()
                            && !dataQuality.errors().isEmpty(),
                    "阶段2B无效上下文不得生成证据或finding且必须返回错误");
        } else {
            require(dataQuality.status() == RunStatus.COMPLETED,
                    "阶段2B有效DATA_QUALITY status必须为COMPLETED");
            validateStage2BEvidence(request, dataQuality);
            validateStage2BFindings(dataQuality);
            boolean blocked = dataQuality.findings().stream()
                    .anyMatch(finding -> finding.severity() == Severity.HIGH);
            boolean warned = dataQuality.findings().stream()
                    .anyMatch(finding -> finding.severity() == Severity.WARN);
            GateStatus expectedGate = blocked ? GateStatus.BLOCKED
                    : warned ? GateStatus.WARN : GateStatus.PASS;
            RunDecision expectedDecision = blocked ? RunDecision.REJECT
                    : warned ? RunDecision.WARN : RunDecision.PASS;
            int expectedScore = blocked ? 0 : warned ? 50 : 100;
            require(dataQuality.gateStatus() == expectedGate
                            && dataQuality.decision() == expectedDecision
                            && dataQuality.score() == expectedScore
                            && dataQuality.confidence() == 100
                            && dataQuality.errors().isEmpty(),
                    "阶段2B有效DATA_QUALITY状态映射不一致");
        }
    }

    private static void validateUnimplementedRuns(
            List<AgentOutput> runs,
            Set<AgentCode> implemented,
            String stage
    ) {
        for (AgentOutput run : runs) {
            if (implemented.contains(run.agentCode())) {
                continue;
            }
            require(run.status() == RunStatus.INSUFFICIENT_DATA
                            && run.gateStatus() == GateStatus.NOT_APPLICABLE
                            && run.decision() == RunDecision.NOT_APPLICABLE
                            && !run.veto() && run.score() == 0 && run.confidence() == 0
                            && run.findings().isEmpty() && run.evidence().isEmpty(),
                    stage + "未实现的专业规则必须保持数据不足状态");
            require(run.errors().isEmpty(),
                    stage + "未实现的专业规则不得返回错误");
        }
    }

    private static void validateStage2BEvidence(
            AgentTeamRequest request,
            AgentOutput dataQuality
    ) {
        require(dataQuality.evidence().size() == 1,
                "阶段2B有效上下文必须生成唯一质量证据");
        Evidence evidence = dataQuality.evidence().get(0);
        require(evidence.category() == EvidenceCategory.DATA_QUALITY
                        && evidence.sourceType() == EvidenceSourceType.JAVA_ENGINE
                        && "AgentContextSnapshotService".equals(evidence.sourceName())
                        && "contextSnapshot".equals(evidence.sourceRef())
                        && ("dq-context-" + request.contextHash()).equals(evidence.evidenceId())
                        && Objects.equals(request.symbol(), evidence.symbol())
                        && Objects.equals(request.tradeDate(), evidence.tradeDate())
                        && sameInstantAtMicrosecondPrecision(
                                request.requestedAt(), evidence.collectedAt())
                        && Objects.equals(request.contextHash(), evidence.contentHash()),
                "阶段2B质量证据元数据不一致");

        JsonNode fields = evidence.fields();
        JsonNode snapshot = request.contextSnapshot();
        Set<String> allowed = Set.of(
                "security", "marketData", "technicalMetrics", "dataQualityContext");
        require(fields.size() == allowed.size()
                        && allowed.stream().allMatch(fields::has)
                        && allowed.stream().allMatch(name ->
                        jsonSemanticallyEquals(fields.get(name), snapshot.get(name))),
                "阶段2B质量证据fields必须直接投影四类冻结上下文");
        require(!containsForbiddenEvidenceConclusion(fields),
                "阶段2B质量证据不得包含gate、decision、score、finding或veto结论");
        String queriedAt = snapshot.path("dataQualityContext").path("queriedAt").asText(null);
        Instant queriedAtInstant = parseInstant(queriedAt);
        require(sameInstantAtMicrosecondPrecision(queriedAtInstant, evidence.observedAt()),
                "阶段2B质量证据observedAt必须来自dataQualityContext.queriedAt");
    }

    private static void validateStage2BFindings(AgentOutput dataQuality) {
        Set<String> seen = new HashSet<>();
        int previousRank = -1;
        String evidenceId = dataQuality.evidence().get(0).evidenceId();
        for (Finding finding : dataQuality.findings()) {
            int rank = STAGE_2B_RULE_ORDER.indexOf(finding.code());
            require(rank >= 0, "阶段2B出现未冻结的finding代码：" + finding.code());
            require(seen.add(finding.code()), "阶段2B同一finding代码最多出现一次：" + finding.code());
            require(rank > previousRank, "阶段2B finding未按固定规则代码顺序输出");
            previousRank = rank;
            Severity expectedSeverity = STAGE_2B_INFO_CODES.contains(finding.code())
                    ? Severity.INFO
                    : STAGE_2B_WARN_CODES.contains(finding.code()) ? Severity.WARN : Severity.HIGH;
            require(finding.severity() == expectedSeverity,
                    "阶段2B finding严重性不符合冻结规则：" + finding.code());
            require(finding.evidenceIds().equals(List.of(evidenceId)),
                    "阶段2B finding必须仅引用统一质量证据");
        }
    }

    private static void validateStage2DMarketBreadth(
            AgentTeamRequest request,
            AgentTeamResponse response,
            List<AgentOutput> runs,
            AgentOutput dataQuality
    ) {
        FinalDecision decision = response.finalDecision();
        require(response.vetoes().isEmpty() && !decision.vetoed() && decision.vetoIds().isEmpty(),
                "阶段2D-1不得产生正式veto");
        require(runs.stream().noneMatch(AgentOutput::veto),
                "阶段2D-1六个专业智能体均不得产生正式veto");
        validateStage2BDataQualityOutput(request, dataQuality);

        AgentOutput marketRegime = runs.stream()
                .filter(run -> run.agentCode() == AgentCode.MARKET_REGIME)
                .findFirst().orElseThrow();
        validateUnimplementedRuns(
                runs,
                Set.of(AgentCode.DATA_QUALITY, AgentCode.MARKET_REGIME),
                "阶段2D-1");

        if (dataQuality.gateStatus() == GateStatus.BLOCKED) {
            validateStage2DBlockedMarketRegime(marketRegime);
        } else {
            require(dataQuality.gateStatus() == GateStatus.PASS
                            || dataQuality.gateStatus() == GateStatus.WARN,
                    "阶段2D-1 DATA_QUALITY门禁必须为PASS、WARN或BLOCKED");
            validateStage2DMarketRegimeOutput(request, marketRegime, dataQuality.gateStatus());
        }

        validateStage2DTopLevelEvidence(response, dataQuality, marketRegime);
        validateStage2DFinalDecision(request, decision, dataQuality, marketRegime);
    }

    private static void validateStage2DBlockedMarketRegime(AgentOutput marketRegime) {
        require(marketRegime.status() == RunStatus.INSUFFICIENT_DATA
                        && marketRegime.gateStatus() == GateStatus.NOT_APPLICABLE
                        && marketRegime.decision() == RunDecision.NOT_APPLICABLE
                        && !marketRegime.veto()
                        && marketRegime.score() == 0
                        && marketRegime.confidence() == 0
                        && marketRegime.findings().isEmpty()
                        && marketRegime.evidence().isEmpty()
                        && marketRegime.errors().isEmpty(),
                "DATA_QUALITY阻断时阶段2D-1 MARKET_REGIME不得执行");
        require("DATA_QUALITY门禁已阻断，未执行当前证券池市场宽度状态规则。"
                        .equals(marketRegime.summary()),
                "DATA_QUALITY阻断时阶段2D-1 MARKET_REGIME摘要不一致");
    }

    private static void validateStage2DMarketRegimeOutput(
            AgentTeamRequest request,
            AgentOutput marketRegime,
            GateStatus dataQualityGate
    ) {
        Stage2DMarketBreadth breadth = parseStage2DMarketBreadth(request.contextSnapshot());
        if (breadth == null) {
            validateStage2DInputInvalid(marketRegime, dataQualityGate);
            return;
        }

        Evidence evidence = validateStage2DMarketBreadthEvidence(request, marketRegime, breadth);
        Stage2DExpected expected = stage2DExpected(request, breadth);
        require(marketRegime.gateStatus() == dataQualityGate && !marketRegime.veto(),
                "阶段2D-1 MARKET_REGIME必须继承DATA_QUALITY门禁且veto=false");
        require(marketRegime.errors().isEmpty(),
                "可解析的阶段2D-1 marketBreadth不得返回错误");
        require(expected.summary().equals(marketRegime.summary()),
                "阶段2D-1 MARKET_REGIME摘要不符合冻结规则");

        if (expected.classified()) {
            require(marketRegime.status() == RunStatus.COMPLETED
                            && marketRegime.decision() == RunDecision.WARN
                            && marketRegime.score() == expected.score()
                            && marketRegime.confidence() == 0,
                    "阶段2D-1有效宽度分类状态、score或confidence不一致");
        } else {
            require(marketRegime.status() == RunStatus.INSUFFICIENT_DATA
                            && marketRegime.decision() == RunDecision.NOT_APPLICABLE
                            && marketRegime.score() == 0
                            && marketRegime.confidence() == 0,
                    "阶段2D-1资格不足时必须保持INSUFFICIENT_DATA");
        }
        validateStage2DFindings(request, marketRegime.findings(), evidence, expected.findingCodes());
    }

    private static void validateStage2DInputInvalid(
            AgentOutput marketRegime,
            GateStatus dataQualityGate
    ) {
        require(marketRegime.status() == RunStatus.INSUFFICIENT_DATA
                        && marketRegime.gateStatus() == dataQualityGate
                        && marketRegime.decision() == RunDecision.NOT_APPLICABLE
                        && !marketRegime.veto()
                        && marketRegime.score() == 0
                        && marketRegime.confidence() == 0
                        && marketRegime.findings().isEmpty()
                        && marketRegime.evidence().isEmpty()
                        && marketRegime.errors().size() == 1,
                "阶段2D-1无法解析marketBreadth时必须安全降级");
        AgentError error = marketRegime.errors().get(0);
        require("MARKET_BREADTH_INPUT_INVALID".equals(error.code())
                        && "marketBreadth上下文无法按阶段2D-1冻结契约安全解析。".equals(error.message()),
                "阶段2D-1无法解析marketBreadth时错误代码或消息不一致");
        require("当前证券池市场宽度输入无法安全解析，未形成宽度方向。".equals(marketRegime.summary()),
                "阶段2D-1无法解析marketBreadth时摘要不一致");
    }

    private static Stage2DExpected stage2DExpected(
            AgentTeamRequest request,
            Stage2DMarketBreadth breadth
    ) {
        if (!stage2DFactsConsistent(request, breadth)) {
            return Stage2DExpected.insufficient(
                    "MARKET_BREADTH_FACT_INCONSISTENT",
                    "当前证券池市场宽度事实不一致，未形成宽度方向。");
        }
        if (!breadth.available() || breadth.universeCount() == 0 || breadth.comparableSymbolCount() == 0) {
            return Stage2DExpected.insufficient(
                    "MARKET_BREADTH_UNAVAILABLE",
                    "当前证券池市场宽度事实不可用，未形成宽度方向。");
        }
        if (breadth.coverageRatio() == null
                || breadth.coverageRatio().compareTo(STAGE_2D_MIN_COVERAGE_RATIO) != 0
                || breadth.comparableSymbolCount() < STAGE_2D_MIN_COMPARABLE_SYMBOL_COUNT) {
            return Stage2DExpected.insufficient(
                    "MARKET_BREADTH_LOW_COVERAGE",
                    "当前证券池市场宽度覆盖不足，未形成宽度方向。");
        }
        if (!breadth.exactTradeDateMatch()
                || !Objects.equals(breadth.effectiveTradeDate(), request.tradeDate())) {
            return Stage2DExpected.insufficient(
                    "MARKET_BREADTH_DATE_NOT_EXACT",
                    "当前证券池市场宽度日期未精确命中，未形成宽度方向。");
        }
        LocalDate frozenCurrentDate = request.requestedAt()
                .atZone(STAGE_2D_MARKET_ZONE)
                .toLocalDate();
        if (!Objects.equals(request.tradeDate(), frozenCurrentDate)) {
            return Stage2DExpected.insufficient(
                    "MARKET_BREADTH_POINT_IN_TIME_UNVERIFIED",
                    "请求日期不是冻结请求时的上海自然日，历史或未来日期未形成宽度方向。");
        }

        BigDecimal netBreadthRatio = ratio(
                breadth.advancingCount() - breadth.decliningCount(),
                breadth.comparableSymbolCount());
        int score = netBreadthRatio.add(BigDecimal.ONE)
                .multiply(BigDecimal.valueOf(50))
                .setScale(0, RoundingMode.HALF_UP)
                .intValueExact();
        String directionCode;
        String stateText;
        if (netBreadthRatio.signum() > 0) {
            directionCode = "MARKET_BREADTH_POSITIVE";
            stateText = "偏正向";
        } else if (netBreadthRatio.signum() < 0) {
            directionCode = "MARKET_BREADTH_NEGATIVE";
            stateText = "偏负向";
        } else {
            directionCode = "MARKET_BREADTH_MIXED";
            stateText = "混合";
        }
        return new Stage2DExpected(
                true,
                score,
                List.of("MARKET_BREADTH_POINT_IN_TIME_UNVERIFIED", directionCode),
                "当前证券池市场宽度状态" + stateText + "；证券池时点属性不可验证，confidence固定为0。");
    }

    private static boolean stage2DFactsConsistent(
            AgentTeamRequest request,
            Stage2DMarketBreadth value
    ) {
        int[] counts = {
                value.universeCount(), value.coveredSymbolCount(), value.comparableSymbolCount(),
                value.advancingCount(), value.decliningCount(), value.unchangedCount(),
                value.missingCurrentBarCount(), value.missingPreviousBarCount()
        };
        for (int count : counts) {
            if (count < 0) return false;
        }
        if (!(value.comparableSymbolCount() <= value.coveredSymbolCount()
                && value.coveredSymbolCount() <= value.universeCount())) return false;
        if ((long) value.advancingCount() + value.decliningCount() + value.unchangedCount()
                != value.comparableSymbolCount()) return false;
        if ((long) value.coveredSymbolCount() + value.missingCurrentBarCount()
                != value.universeCount()) return false;
        if ((long) value.comparableSymbolCount() + value.missingPreviousBarCount()
                != value.coveredSymbolCount()) return false;

        BigDecimal expectedCoverage = value.universeCount() == 0
                ? null : ratio(value.comparableSymbolCount(), value.universeCount());
        if (expectedCoverage == null) {
            if (value.coverageRatio() != null) return false;
        } else if (value.coverageRatio() == null
                || value.coverageRatio().compareTo(expectedCoverage) != 0) {
            return false;
        }

        if (!Objects.equals(value.queryScopeSymbol(), request.symbol())
                || !Objects.equals(value.queryScopeTradeDate(), request.tradeDate())
                || !Objects.equals(value.requestedTradeDate(), request.tradeDate())) return false;
        if (value.effectiveTradeDate() != null
                && value.effectiveTradeDate().isAfter(request.tradeDate())) return false;
        if (value.previousEffectiveTradeDate() != null
                && (value.effectiveTradeDate() == null
                || !value.previousEffectiveTradeDate().isBefore(value.effectiveTradeDate()))) return false;
        if (value.available() && value.previousEffectiveTradeDate() == null) return false;

        return "DATABASE".equals(value.sourceType())
                && value.sourceTables().equals(List.of("daily_bars", "securities"))
                && (value.available() ? "AVAILABLE" : "UNAVAILABLE").equals(value.sourceStatus())
                && "AgentMarketBreadthContextService".equals(value.producer())
                && "MARKET_BREADTH_V1".equals(value.producerVersion())
                && value.versionAvailable()
                && !value.pointInTimeGuaranteed()
                && value.barFutureDataExcluded()
                && !value.universePointInTimeGuaranteed()
                && !value.futureDataExcluded()
                && "TRADE_DATES_ARE_LOCAL_DATE_QUERIED_AT_IS_UTC_INSTANT"
                .equals(value.timestampTimezoneSemantics())
                && "QFQ".equals(value.adjustType())
                && "CURRENT_MAIN_ACTIVE_NON_ST_UNIVERSE_UNIFIED_EFFECTIVE_DATE"
                .equals(value.selectionRule())
                && value.limitations().equals(
                List.of("CURRENT_SECURITIES_ATTRIBUTES_ARE_NOT_HISTORICALLY_VERSIONED"))
                && Objects.equals(value.reasonCode(), expectedStage2DReasonCode(value))
                && value.available() == (value.reasonCode() == null);
    }

    private static String expectedStage2DReasonCode(Stage2DMarketBreadth value) {
        if (value.universeCount() == 0) return "NO_ELIGIBLE_UNIVERSE";
        if (value.effectiveTradeDate() == null) return "NO_EFFECTIVE_TRADE_DATE";
        if (value.previousEffectiveTradeDate() == null) return "NO_PREVIOUS_EFFECTIVE_TRADE_DATE";
        if (value.comparableSymbolCount() == 0) return "ZERO_COMPARABLE_SYMBOLS";
        return null;
    }

    private static Evidence validateStage2DMarketBreadthEvidence(
            AgentTeamRequest request,
            AgentOutput marketRegime,
            Stage2DMarketBreadth breadth
    ) {
        require(marketRegime.evidence().size() == 1,
                "阶段2D-1可解析marketBreadth必须生成唯一证据");
        Evidence evidence = marketRegime.evidence().get(0);
        require(("mr-breadth-" + request.contextHash()).equals(evidence.evidenceId())
                        && evidence.category() == EvidenceCategory.MARKET_BREADTH
                        && evidence.sourceType() == EvidenceSourceType.JAVA_ENGINE
                        && "AgentMarketBreadthContextService".equals(evidence.sourceName())
                        && "contextSnapshot.marketBreadth".equals(evidence.sourceRef())
                        && Objects.equals(request.symbol(), evidence.symbol())
                        && Objects.equals(request.tradeDate(), evidence.tradeDate())
                        && sameInstantAtMicrosecondPrecision(breadth.queriedAt(), evidence.observedAt())
                        && sameInstantAtMicrosecondPrecision(request.requestedAt(), evidence.collectedAt())
                        && Objects.equals(request.contextHash(), evidence.contentHash()),
                "阶段2D-1 marketBreadth证据元数据不一致");

        JsonNode fields = evidence.fields();
        JsonNode projection = fields == null ? null : fields.get("marketBreadth");
        JsonNode source = request.contextSnapshot().get("marketBreadth");
        require(fields != null && fields.isObject() && fields.size() == 1
                        && projection != null && projection.isObject()
                        && projection.size() == STAGE_2D_MARKET_BREADTH_FIELDS.size()
                        && STAGE_2D_MARKET_BREADTH_FIELDS.stream().allMatch(projection::has)
                        && STAGE_2D_MARKET_BREADTH_FIELDS.stream()
                        .allMatch(name -> jsonSemanticallyEquals(projection.get(name), source.get(name))),
                "阶段2D-1 marketBreadth证据fields必须严格匹配冻结白名单投影");
        require(!containsForbiddenEvidenceConclusion(fields),
                "阶段2D-1 marketBreadth证据不得包含结论字段");
        return evidence;
    }

    private static void validateStage2DFindings(
            AgentTeamRequest request,
            List<Finding> actual,
            Evidence evidence,
            List<String> expectedCodes
    ) {
        require(actual.size() == expectedCodes.size(),
                "阶段2D-1 finding数量不符合冻结规则");
        for (int index = 0; index < expectedCodes.size(); index++) {
            String code = expectedCodes.get(index);
            Finding finding = actual.get(index);
            int rank = STAGE_2D_FINDING_ORDER.indexOf(code) + 1;
            Severity expectedSeverity = rank == 1
                    ? Severity.HIGH : rank <= 5 ? Severity.WARN : Severity.INFO;
            String expectedId = "mr-%02d-%s-%s".formatted(
                    rank,
                    code.toLowerCase(java.util.Locale.ROOT).replace('_', '-'),
                    request.contextHash());
            require(code.equals(finding.code())
                            && expectedId.equals(finding.findingId())
                            && finding.severity() == expectedSeverity
                            && STAGE_2D_FINDING_TITLES.get(code).equals(finding.title())
                            && STAGE_2D_FINDING_DETAILS.get(code).equals(finding.detail())
                            && finding.evidenceIds().equals(List.of(evidence.evidenceId())),
                    "阶段2D-1 finding内容、顺序或证据引用不一致：" + code);
        }
    }

    private static void validateStage2DTopLevelEvidence(
            AgentTeamResponse response,
            AgentOutput dataQuality,
            AgentOutput marketRegime
    ) {
        List<Evidence> expected = new ArrayList<>();
        expected.addAll(dataQuality.evidence());
        expected.addAll(marketRegime.evidence());
        require(response.evidence().size() == expected.size(),
                "阶段2D-1顶层evidence数量不一致");
        for (int index = 0; index < expected.size(); index++) {
            require(sameEvidence(response.evidence().get(index), expected.get(index)),
                    "阶段2D-1顶层evidence必须按DATA_QUALITY、MARKET_REGIME顺序输出");
        }
    }

    private static void validateStage2DFinalDecision(
            AgentTeamRequest request,
            FinalDecision decision,
            AgentOutput dataQuality,
            AgentOutput marketRegime
    ) {
        List<Finding> expectedFindings = new ArrayList<>(dataQuality.findings());
        if (dataQuality.gateStatus() != GateStatus.BLOCKED) {
            expectedFindings.addAll(marketRegime.findings());
        }
        require(Objects.equals(decision.findings(), expectedFindings),
                "阶段2D-1总控finding必须按DATA_QUALITY、MARKET_REGIME顺序精确拼接");
        List<Long> expectedRunIds = AgentCode.PROFESSIONAL_AGENTS.stream()
                .map(code -> request.runIds().byAgentCode().get(code))
                .toList();
        require(decision.sourceRunIds().equals(expectedRunIds),
                "阶段2D-1 sourceRunIds必须按固定六智能体顺序输出");
        require(!decision.vetoed() && decision.vetoIds().isEmpty()
                        && decision.score() == 0,
                "阶段2D-1总控不得产生veto或非零score");
        if (dataQuality.gateStatus() == GateStatus.BLOCKED) {
            require(decision.decision() == FinalDecisionCode.BLOCKED_BY_DATA_QUALITY
                            && decision.gateStatus() == GateStatus.BLOCKED
                            && Objects.equals(decision.confidence(), dataQuality.confidence()),
                    "阶段2D-1 DATA_QUALITY阻断时总控状态不一致");
        } else {
            require(decision.decision() == FinalDecisionCode.INSUFFICIENT_DATA
                            && decision.gateStatus() == dataQuality.gateStatus()
                            && decision.confidence() == 0,
                    "阶段2D-1总控不得因宽度规则提前升级团队结论");
        }
        require(!containsForbiddenStage2DSummary(decision.summary()),
                "阶段2D-1总控摘要不得包含投资建议、交易指令或完整市场环境声明");
    }

    private static void validateStage2ETechnicalAnalysis(
            AgentTeamRequest request,
            AgentTeamResponse response,
            List<AgentOutput> runs,
            AgentOutput dataQuality
    ) {
        FinalDecision decision = response.finalDecision();
        require(response.vetoes().isEmpty() && !decision.vetoed() && decision.vetoIds().isEmpty(),
                "阶段2E-1不得产生正式veto");
        require(runs.stream().noneMatch(AgentOutput::veto),
                "阶段2E-1六个专业智能体均不得产生正式veto");
        validateStage2BDataQualityOutput(request, dataQuality);

        AgentOutput marketRegime = runs.stream()
                .filter(run -> run.agentCode() == AgentCode.MARKET_REGIME)
                .findFirst().orElseThrow();
        AgentOutput technicalAnalysis = runs.stream()
                .filter(run -> run.agentCode() == AgentCode.TECHNICAL_ANALYSIS)
                .findFirst().orElseThrow();
        validateUnimplementedRuns(
                runs,
                Set.of(
                        AgentCode.DATA_QUALITY,
                        AgentCode.MARKET_REGIME,
                        AgentCode.TECHNICAL_ANALYSIS),
                "阶段2E-1");

        if (dataQuality.gateStatus() == GateStatus.BLOCKED) {
            validateStage2DBlockedMarketRegime(marketRegime);
            AgentStage2ETechnicalAnalysisValidator.validateBlocked(technicalAnalysis);
        } else {
            require(dataQuality.gateStatus() == GateStatus.PASS
                            || dataQuality.gateStatus() == GateStatus.WARN,
                    "阶段2E-1 DATA_QUALITY门禁必须为PASS、WARN或BLOCKED");
            validateStage2DMarketRegimeOutput(request, marketRegime, dataQuality.gateStatus());
            AgentStage2ETechnicalAnalysisValidator.validate(
                    request,
                    technicalAnalysis,
                    dataQuality.gateStatus());
        }

        validateStage2ETopLevelEvidence(
                response,
                dataQuality,
                marketRegime,
                technicalAnalysis);
        validateStage2EFinalDecision(
                request,
                decision,
                dataQuality,
                marketRegime,
                technicalAnalysis);
    }

    private static void validateStage2ETopLevelEvidence(
            AgentTeamResponse response,
            AgentOutput dataQuality,
            AgentOutput marketRegime,
            AgentOutput technicalAnalysis
    ) {
        List<Evidence> expected = new ArrayList<>();
        expected.addAll(dataQuality.evidence());
        expected.addAll(marketRegime.evidence());
        expected.addAll(technicalAnalysis.evidence());
        require(response.evidence().size() == expected.size(),
                "阶段2E-1顶层evidence数量不一致");
        for (int index = 0; index < expected.size(); index++) {
            require(sameEvidence(response.evidence().get(index), expected.get(index)),
                    "阶段2E-1顶层evidence必须按DATA_QUALITY、MARKET_REGIME、"
                            + "TECHNICAL_ANALYSIS顺序输出");
        }
    }

    private static void validateStage2EFinalDecision(
            AgentTeamRequest request,
            FinalDecision decision,
            AgentOutput dataQuality,
            AgentOutput marketRegime,
            AgentOutput technicalAnalysis
    ) {
        List<Finding> expectedFindings = new ArrayList<>(dataQuality.findings());
        if (dataQuality.gateStatus() != GateStatus.BLOCKED) {
            expectedFindings.addAll(marketRegime.findings());
            expectedFindings.addAll(technicalAnalysis.findings());
        }
        require(Objects.equals(decision.findings(), expectedFindings),
                "阶段2E-1总控finding必须按DATA_QUALITY、MARKET_REGIME、"
                        + "TECHNICAL_ANALYSIS顺序精确拼接");
        List<Long> expectedRunIds = AgentCode.PROFESSIONAL_AGENTS.stream()
                .map(code -> request.runIds().byAgentCode().get(code))
                .toList();
        require(decision.sourceRunIds().equals(expectedRunIds),
                "阶段2E-1 sourceRunIds必须按固定六智能体顺序输出");
        require(!decision.vetoed() && decision.vetoIds().isEmpty()
                        && decision.score() == 0,
                "阶段2E-1总控不得产生veto或非零score");
        if (dataQuality.gateStatus() == GateStatus.BLOCKED) {
            require(decision.decision() == FinalDecisionCode.BLOCKED_BY_DATA_QUALITY
                            && decision.gateStatus() == GateStatus.BLOCKED
                            && Objects.equals(decision.confidence(), dataQuality.confidence()),
                    "阶段2E-1 DATA_QUALITY阻断时总控状态不一致");
        } else {
            require(decision.decision() == FinalDecisionCode.INSUFFICIENT_DATA
                            && decision.gateStatus() == dataQuality.gateStatus()
                            && decision.confidence() == 0,
                    "阶段2E-1不得因技术分析规则提前升级团队结论");
            require(("DATA_QUALITY检查未阻断，阶段2D-1市场宽度规则与阶段2E-1"
                            + "技术指标确定性规则已按冻结边界执行；其余三个专业规则尚未实现，"
                            + "无法形成团队结论。")
                            .equals(decision.summary()),
                    "阶段2E-1未阻断总控摘要不符合冻结能力边界");
        }
        require(!containsForbiddenStage2ESummary(decision.summary()),
                "阶段2E-1总控摘要不得包含投资建议、交易指令或收益承诺");
    }

    private static void validateStage2FStrategyBacktest(
            AgentTeamRequest request,
            AgentTeamResponse response,
            List<AgentOutput> runs,
            AgentOutput dataQuality
    ) {
        FinalDecision decision = response.finalDecision();
        require(response.vetoes().isEmpty()
                        && !decision.vetoed()
                        && decision.vetoIds().isEmpty()
                        && runs.stream().noneMatch(AgentOutput::veto),
                "阶段2F不得产生正式veto");
        validateStage2BDataQualityOutput(request, dataQuality);
        AgentOutput marketRegime = runs.stream()
                .filter(run -> run.agentCode() == AgentCode.MARKET_REGIME)
                .findFirst().orElseThrow();
        AgentOutput technicalAnalysis = runs.stream()
                .filter(run -> run.agentCode() == AgentCode.TECHNICAL_ANALYSIS)
                .findFirst().orElseThrow();
        AgentOutput strategyBacktest = runs.stream()
                .filter(run -> run.agentCode() == AgentCode.STRATEGY_BACKTEST)
                .findFirst().orElseThrow();
        validateUnimplementedRuns(
                runs,
                Set.of(
                        AgentCode.DATA_QUALITY,
                        AgentCode.MARKET_REGIME,
                        AgentCode.TECHNICAL_ANALYSIS,
                        AgentCode.STRATEGY_BACKTEST),
                "阶段2F");

        if (dataQuality.gateStatus() == GateStatus.BLOCKED) {
            validateStage2DBlockedMarketRegime(marketRegime);
            AgentStage2ETechnicalAnalysisValidator.validateBlocked(technicalAnalysis);
            AgentStage2FStrategyBacktestValidator.validateBlocked(strategyBacktest);
        } else {
            require(dataQuality.gateStatus() == GateStatus.PASS
                            || dataQuality.gateStatus() == GateStatus.WARN,
                    "阶段2F DATA_QUALITY门禁必须为PASS、WARN或BLOCKED");
            validateStage2DMarketRegimeOutput(
                    request, marketRegime, dataQuality.gateStatus());
            AgentStage2ETechnicalAnalysisValidator.validate(
                    request,
                    technicalAnalysis,
                    dataQuality.gateStatus());
            AgentStage2FStrategyBacktestValidator.validate(
                    request,
                    strategyBacktest,
                    dataQuality.gateStatus());
        }

        List<Evidence> expectedEvidence = new ArrayList<>(dataQuality.evidence());
        if (dataQuality.gateStatus() != GateStatus.BLOCKED) {
            expectedEvidence.addAll(marketRegime.evidence());
            expectedEvidence.addAll(technicalAnalysis.evidence());
            expectedEvidence.addAll(strategyBacktest.evidence());
        }
        require(response.evidence().size() == expectedEvidence.size(),
                "阶段2F顶层evidence数量不一致");
        for (int index = 0; index < expectedEvidence.size(); index++) {
            require(sameEvidence(response.evidence().get(index), expectedEvidence.get(index)),
                    "阶段2F顶层evidence必须按四个已实现专业智能体顺序输出");
        }

        List<Finding> expectedFindings = new ArrayList<>(dataQuality.findings());
        if (dataQuality.gateStatus() != GateStatus.BLOCKED) {
            expectedFindings.addAll(marketRegime.findings());
            expectedFindings.addAll(technicalAnalysis.findings());
            expectedFindings.addAll(strategyBacktest.findings());
        }
        require(decision.findings().equals(expectedFindings),
                "阶段2F总控finding必须按四个已实现专业智能体顺序拼接");
        List<Long> expectedRunIds = AgentCode.PROFESSIONAL_AGENTS.stream()
                .map(code -> request.runIds().byAgentCode().get(code))
                .toList();
        require(decision.sourceRunIds().equals(expectedRunIds),
                "阶段2F sourceRunIds必须保持六智能体固定顺序");
        require(!decision.vetoed() && decision.vetoIds().isEmpty()
                        && decision.score() == 0,
                "阶段2F总控不得产生veto或非零score");
        if (dataQuality.gateStatus() == GateStatus.BLOCKED) {
            require(decision.decision() == FinalDecisionCode.BLOCKED_BY_DATA_QUALITY
                            && decision.gateStatus() == GateStatus.BLOCKED
                            && Objects.equals(
                            decision.confidence(), dataQuality.confidence()),
                    "阶段2F DATA_QUALITY阻断时总控状态不一致");
        } else {
            require(decision.decision() == FinalDecisionCode.INSUFFICIENT_DATA
                            && decision.gateStatus() == dataQuality.gateStatus()
                            && decision.confidence() == 0
                            && ("DATA_QUALITY、MARKET_REGIME、TECHNICAL_ANALYSIS与可靠"
                            + "STRATEGY_BACKTEST规则已按冻结边界执行；公告风险和仓位风险"
                            + "尚未实现，无法形成团队投资结论。")
                            .equals(decision.summary()),
                    "阶段2F不得因回测规则提前升级团队结论");
        }
        require(!containsForbiddenStage2ESummary(decision.summary()),
                "阶段2F总控摘要不得包含投资建议、交易指令或收益承诺");
    }

    private static void validateStage2HPositionRisk(
            AgentTeamRequest request,
            AgentTeamResponse response,
            List<AgentOutput> runs,
            AgentOutput dataQuality
    ) {
        validateStage2BDataQualityOutput(request, dataQuality);
        AgentOutput marketRegime = runs.stream()
                .filter(run -> run.agentCode() == AgentCode.MARKET_REGIME)
                .findFirst().orElseThrow();
        AgentOutput technicalAnalysis = runs.stream()
                .filter(run -> run.agentCode() == AgentCode.TECHNICAL_ANALYSIS)
                .findFirst().orElseThrow();
        AgentOutput strategyBacktest = runs.stream()
                .filter(run -> run.agentCode() == AgentCode.STRATEGY_BACKTEST)
                .findFirst().orElseThrow();
        AgentOutput announcementRisk = runs.stream()
                .filter(run -> run.agentCode() == AgentCode.ANNOUNCEMENT_RISK)
                .findFirst().orElseThrow();
        AgentOutput positionRisk = runs.stream()
                .filter(run -> run.agentCode() == AgentCode.POSITION_RISK)
                .findFirst().orElseThrow();

        validateUnimplementedRuns(
                runs,
                Set.of(
                        AgentCode.DATA_QUALITY,
                        AgentCode.MARKET_REGIME,
                        AgentCode.TECHNICAL_ANALYSIS,
                        AgentCode.STRATEGY_BACKTEST,
                        AgentCode.POSITION_RISK),
                "阶段2H");
        require(announcementRisk.status() == RunStatus.INSUFFICIENT_DATA,
                "阶段2H ANNOUNCEMENT_RISK必须保持安全未实现");

        if (dataQuality.gateStatus() == GateStatus.BLOCKED) {
            validateStage2DBlockedMarketRegime(marketRegime);
            AgentStage2ETechnicalAnalysisValidator.validateBlocked(technicalAnalysis);
            AgentStage2FStrategyBacktestValidator.validateBlocked(strategyBacktest);
        } else {
            require(dataQuality.gateStatus() == GateStatus.PASS
                            || dataQuality.gateStatus() == GateStatus.WARN,
                    "阶段2H DATA_QUALITY门禁必须为PASS、WARN或BLOCKED");
            validateStage2DMarketRegimeOutput(
                    request, marketRegime, dataQuality.gateStatus());
            AgentStage2ETechnicalAnalysisValidator.validate(
                    request, technicalAnalysis, dataQuality.gateStatus());
            AgentStage2FStrategyBacktestValidator.validate(
                    request, strategyBacktest, dataQuality.gateStatus());
        }

        AgentStage2HPositionRiskValidator.validate(
                request, response, positionRisk, dataQuality);

        List<Evidence> expectedEvidence = new ArrayList<>();
        List<Finding> expectedFindings = new ArrayList<>();
        for (AgentCode code : AgentCode.PROFESSIONAL_AGENTS) {
            AgentOutput run = runs.stream()
                    .filter(candidate -> candidate.agentCode() == code)
                    .findFirst().orElseThrow();
            expectedEvidence.addAll(run.evidence());
            expectedFindings.addAll(run.findings());
        }
        require(response.evidence().size() == expectedEvidence.size(),
                "阶段2H顶层evidence数量不一致");
        for (int index = 0; index < expectedEvidence.size(); index++) {
            require(sameEvidence(response.evidence().get(index), expectedEvidence.get(index)),
                    "阶段2H顶层evidence必须按六智能体固定顺序输出");
        }
        FinalDecision decision = response.finalDecision();
        require(decision.findings().equals(expectedFindings),
                "阶段2H总控finding必须按六智能体固定顺序拼接");
        List<Long> expectedRunIds = AgentCode.PROFESSIONAL_AGENTS.stream()
                .map(code -> request.runIds().byAgentCode().get(code))
                .toList();
        require(decision.sourceRunIds().equals(expectedRunIds),
                "阶段2H sourceRunIds必须保持六智能体固定顺序");

        if (!response.vetoes().isEmpty()) {
            require(decision.decision() == FinalDecisionCode.REJECTED_BY_VETO
                            && decision.gateStatus() == GateStatus.BLOCKED
                            && decision.vetoed()
                            && decision.score() == 0
                            && Objects.equals(decision.confidence(), positionRisk.confidence())
                            && decision.vetoIds().equals(
                            response.vetoes().stream().map(FormalVeto::vetoId).toList()),
                    "阶段2H正式veto必须优先形成REJECTED_BY_VETO");
        } else if (dataQuality.gateStatus() == GateStatus.BLOCKED) {
            require(decision.decision() == FinalDecisionCode.BLOCKED_BY_DATA_QUALITY
                            && decision.gateStatus() == GateStatus.BLOCKED
                            && !decision.vetoed()
                            && decision.score() == 0
                            && Objects.equals(decision.confidence(), dataQuality.confidence()),
                    "阶段2H无veto时DATA_QUALITY阻断优先级无效");
        } else {
            GateStatus expectedGate = dataQuality.gateStatus() == GateStatus.WARN
                    || positionRisk.gateStatus() == GateStatus.WARN
                    ? GateStatus.WARN : GateStatus.PASS;
            require(decision.decision() == FinalDecisionCode.INSUFFICIENT_DATA
                            && decision.gateStatus() == expectedGate
                            && !decision.vetoed()
                            && decision.score() == 0
                            && decision.confidence() == 0,
                    "阶段2H无正式veto时必须因ANNOUNCEMENT_RISK保持不足");
        }
        require(!containsForbiddenStage2ESummary(decision.summary()),
                "阶段2H总控摘要不得包含投资建议、交易指令或收益承诺");
    }

    private static boolean containsForbiddenStage2ESummary(String summary) {
        if (!notBlank(summary)) return true;
        return summary.contains("投资建议")
                || summary.contains("买入")
                || summary.contains("卖出")
                || summary.contains("加仓")
                || summary.contains("减仓")
                || summary.contains("目标价")
                || summary.contains("收益承诺");
    }

    private static boolean containsForbiddenStage2DSummary(String summary) {
        if (!notBlank(summary)) return true;
        String normalized = summary.toUpperCase(java.util.Locale.ROOT);
        return normalized.contains("RISK_ON")
                || normalized.contains("RISK_OFF")
                || summary.contains("投资建议")
                || summary.contains("买入")
                || summary.contains("卖出")
                || summary.contains("交易指令")
                || summary.contains("完整市场环境");
    }

    private static BigDecimal ratio(int numerator, int denominator) {
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 8, RoundingMode.HALF_UP);
    }

    private static Stage2DMarketBreadth parseStage2DMarketBreadth(JsonNode snapshot) {
        JsonNode value = snapshot == null ? null : snapshot.get("marketBreadth");
        if (value == null || !value.isObject()
                || !isBoolean(value, "available")
                || !isText(value, "queriedAt")
                || !nullableText(value, "reasonCode")
                || !isText(value, "sourceType")
                || !stringArray(value, "sourceTables")
                || !isText(value, "sourceStatus")
                || !isText(value, "producer")
                || !isText(value, "producerVersion")
                || !isBoolean(value, "versionAvailable")
                || !isText(value, "requestedTradeDate")
                || !nullableText(value, "effectiveTradeDate")
                || !nullableText(value, "previousEffectiveTradeDate")
                || !isBoolean(value, "exactTradeDateMatch")
                || !isBoolean(value, "pointInTimeGuaranteed")
                || !isBoolean(value, "barFutureDataExcluded")
                || !isBoolean(value, "universePointInTimeGuaranteed")
                || !isBoolean(value, "futureDataExcluded")
                || !isText(value, "timestampTimezoneSemantics")
                || !isText(value, "adjustType")
                || !isText(value, "selectionRule")
                || !isInt(value, "universeCount")
                || !isInt(value, "coveredSymbolCount")
                || !isInt(value, "comparableSymbolCount")
                || !isInt(value, "advancingCount")
                || !isInt(value, "decliningCount")
                || !isInt(value, "unchangedCount")
                || !isInt(value, "missingCurrentBarCount")
                || !isInt(value, "missingPreviousBarCount")
                || !nullableNumber(value, "coverageRatio")
                || !stringArray(value, "limitations")) {
            return null;
        }
        JsonNode scope = value.get("queryScope");
        if (scope == null || !scope.isObject()
                || !isText(scope, "symbol") || !isText(scope, "tradeDate")) {
            return null;
        }
        try {
            Instant queriedAt = parseInstant(value.get("queriedAt").textValue());
            if (queriedAt == null) return null;
            return new Stage2DMarketBreadth(
                    value,
                    value.get("available").booleanValue(),
                    queriedAt,
                    scope.get("symbol").textValue(),
                    LocalDate.parse(scope.get("tradeDate").textValue()),
                    nullableTextValue(value.get("reasonCode")),
                    value.get("sourceType").textValue(),
                    textList(value.get("sourceTables")),
                    value.get("sourceStatus").textValue(),
                    value.get("producer").textValue(),
                    value.get("producerVersion").textValue(),
                    value.get("versionAvailable").booleanValue(),
                    LocalDate.parse(value.get("requestedTradeDate").textValue()),
                    nullableDateValue(value.get("effectiveTradeDate")),
                    nullableDateValue(value.get("previousEffectiveTradeDate")),
                    value.get("exactTradeDateMatch").booleanValue(),
                    value.get("pointInTimeGuaranteed").booleanValue(),
                    value.get("barFutureDataExcluded").booleanValue(),
                    value.get("universePointInTimeGuaranteed").booleanValue(),
                    value.get("futureDataExcluded").booleanValue(),
                    value.get("timestampTimezoneSemantics").textValue(),
                    value.get("adjustType").textValue(),
                    value.get("selectionRule").textValue(),
                    value.get("universeCount").intValue(),
                    value.get("coveredSymbolCount").intValue(),
                    value.get("comparableSymbolCount").intValue(),
                    value.get("advancingCount").intValue(),
                    value.get("decliningCount").intValue(),
                    value.get("unchangedCount").intValue(),
                    value.get("missingCurrentBarCount").intValue(),
                    value.get("missingPreviousBarCount").intValue(),
                    value.get("coverageRatio").isNull()
                            ? null : value.get("coverageRatio").decimalValue(),
                    textList(value.get("limitations")));
        } catch (DateTimeException | ArithmeticException error) {
            return null;
        }
    }

    private static boolean isBoolean(JsonNode object, String field) {
        return object.has(field) && object.get(field).isBoolean();
    }

    private static boolean isText(JsonNode object, String field) {
        return object.has(field) && object.get(field).isTextual();
    }

    private static boolean nullableText(JsonNode object, String field) {
        return object.has(field) && (object.get(field).isNull() || object.get(field).isTextual());
    }

    private static boolean nullableNumber(JsonNode object, String field) {
        return object.has(field) && (object.get(field).isNull() || object.get(field).isNumber());
    }

    private static boolean isInt(JsonNode object, String field) {
        return object.has(field) && object.get(field).isIntegralNumber()
                && object.get(field).canConvertToInt();
    }

    private static boolean stringArray(JsonNode object, String field) {
        if (!object.has(field) || !object.get(field).isArray()) return false;
        for (JsonNode item : object.get(field)) {
            if (!item.isTextual()) return false;
        }
        return true;
    }

    private static String nullableTextValue(JsonNode node) {
        return node.isNull() ? null : node.textValue();
    }

    private static LocalDate nullableDateValue(JsonNode node) {
        return node.isNull() ? null : LocalDate.parse(node.textValue());
    }

    private static List<String> textList(JsonNode array) {
        List<String> result = new ArrayList<>();
        array.forEach(item -> result.add(item.textValue()));
        return List.copyOf(result);
    }

    private static boolean jsonSemanticallyEquals(JsonNode left, JsonNode right) {
        if (left == null || right == null) return left == right;
        if (left.isNumber() && right.isNumber()) {
            return left.decimalValue().compareTo(right.decimalValue()) == 0;
        }
        if (left.isObject() && right.isObject()) {
            if (left.size() != right.size()) return false;
            var fields = left.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!right.has(field.getKey())
                        || !jsonSemanticallyEquals(field.getValue(), right.get(field.getKey()))) return false;
            }
            return true;
        }
        if (left.isArray() && right.isArray()) {
            if (left.size() != right.size()) return false;
            for (int index = 0; index < left.size(); index++) {
                if (!jsonSemanticallyEquals(left.get(index), right.get(index))) return false;
            }
            return true;
        }
        return left.getNodeType() == right.getNodeType() && left.equals(right);
    }

    private record Stage2DMarketBreadth(
            JsonNode source,
            boolean available,
            Instant queriedAt,
            String queryScopeSymbol,
            LocalDate queryScopeTradeDate,
            String reasonCode,
            String sourceType,
            List<String> sourceTables,
            String sourceStatus,
            String producer,
            String producerVersion,
            boolean versionAvailable,
            LocalDate requestedTradeDate,
            LocalDate effectiveTradeDate,
            LocalDate previousEffectiveTradeDate,
            boolean exactTradeDateMatch,
            boolean pointInTimeGuaranteed,
            boolean barFutureDataExcluded,
            boolean universePointInTimeGuaranteed,
            boolean futureDataExcluded,
            String timestampTimezoneSemantics,
            String adjustType,
            String selectionRule,
            int universeCount,
            int coveredSymbolCount,
            int comparableSymbolCount,
            int advancingCount,
            int decliningCount,
            int unchangedCount,
            int missingCurrentBarCount,
            int missingPreviousBarCount,
            BigDecimal coverageRatio,
            List<String> limitations
    ) {}

    private record Stage2DExpected(
            boolean classified,
            int score,
            List<String> findingCodes,
            String summary
    ) {
        private static Stage2DExpected insufficient(String findingCode, String summary) {
            return new Stage2DExpected(false, 0, List.of(findingCode), summary);
        }
    }

    private static boolean containsForbiddenEvidenceConclusion(JsonNode value) {
        if (value == null) {
            return false;
        }
        if (value.isObject()) {
            var fields = value.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                String normalized = field.getKey().replace("_", "").toLowerCase(java.util.Locale.ROOT);
                if (STAGE_2B_FORBIDDEN_EVIDENCE_CONCLUSION_FIELDS.contains(normalized)
                        || containsForbiddenEvidenceConclusion(field.getValue())) {
                    return true;
                }
            }
            return false;
        }
        if (value.isArray()) {
            for (JsonNode item : value) {
                if (containsForbiddenEvidenceConclusion(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void validateRunVeto(AgentOutput run) {
        if (run.agentCode() == AgentCode.DATA_QUALITY || run.agentCode() == AgentCode.ANNOUNCEMENT_RISK) {
            require(!run.veto(), run.agentCode() + "不能产生正式veto");
        }
        if (run.veto()) {
            require(run.agentCode() == AgentCode.POSITION_RISK, "只有POSITION_RISK可以veto=true");
            require(run.status() == com.stockquant.server.agent.model.AgentTypes.RunStatus.COMPLETED
                            || run.status() == com.stockquant.server.agent.model.AgentTypes.RunStatus.PARTIAL,
                    "正式veto运行必须为COMPLETED或PARTIAL");
            require(run.decision() == com.stockquant.server.agent.model.AgentTypes.RunDecision.REJECT,
                    "正式veto运行必须decision=REJECT");
        }
    }

    private static Map<String, Evidence> authoritativeEvidence(List<Evidence> evidence) {
        Map<String, Evidence> result = new HashMap<>();
        for (Evidence item : safe(evidence)) {
            require(item != null && notBlank(item.evidenceId()), "evidenceId不能为空");
            require(item.category() != null && item.sourceType() != null, "evidence类型字段不能为空");
            require(notBlank(item.sourceName()) && notBlank(item.sourceRef()), "evidence来源不能为空");
            require(item.tradeDate() != null && item.observedAt() != null && item.collectedAt() != null,
                    "evidence日期时间不能为空");
            require(item.symbol() != null && item.symbol().matches("^[0-9]{6}$"),
                    "evidence symbol必须是6位数字");
            require(item.fields() != null && item.fields().isObject() && !item.fields().isEmpty(),
                    "evidence fields必须是非空对象");
            require(item.contentHash() != null && item.contentHash().matches("^[0-9a-f]{64}$"),
                    "evidence contentHash必须是SHA-256小写十六进制");
            require(result.putIfAbsent(item.evidenceId(), item) == null,
                    "顶层evidenceId必须唯一：" + item.evidenceId());
        }
        return Map.copyOf(result);
    }

    private static void validateFindings(List<Finding> findings, Map<String, Evidence> evidence) {
        for (Finding finding : safe(findings)) {
            require(finding != null, "finding不能为空");
            require(notBlank(finding.findingId()) && notBlank(finding.code())
                            && notBlank(finding.title()) && notBlank(finding.detail())
                            && finding.severity() != null,
                    "finding必填字段不能为空");
            require(!safe(finding.evidenceIds()).isEmpty(), "finding必须至少引用一个evidenceId");
            validateEvidenceIds(finding.evidenceIds(), evidence);
        }
    }

    private static void validateEvidenceIds(List<String> ids, Map<String, Evidence> evidence) {
        Set<String> unique = new HashSet<>();
        for (String id : safe(ids)) {
            require(notBlank(id), "引用的evidenceId不能为空");
            require(unique.add(id), "引用的evidenceId不能重复：" + id);
            require(evidence.containsKey(id), "引用的evidenceId不存在：" + id);
        }
    }

    private static boolean sameEvidence(Evidence left, Evidence right) {
        return left.category() == right.category()
                && left.sourceType() == right.sourceType()
                && Objects.equals(left.sourceName(), right.sourceName())
                && Objects.equals(left.sourceRef(), right.sourceRef())
                && Objects.equals(left.symbol(), right.symbol())
                && Objects.equals(left.tradeDate(), right.tradeDate())
                && Objects.equals(left.observedAt(), right.observedAt())
                && Objects.equals(left.collectedAt(), right.collectedAt())
                && Objects.equals(left.contentHash(), right.contentHash())
                && jsonEquals(left.fields(), right.fields());
    }

    private static boolean jsonEquals(JsonNode left, JsonNode right) {
        return Objects.equals(left, right);
    }

    private static Instant parseInstant(String value) {
        if (!notBlank(value)) {
            return null;
        }
        try {
            return Instant.from(DateTimeFormatter.ISO_DATE_TIME.parse(value));
        } catch (DateTimeException error) {
            return null;
        }
    }

    private static boolean sameInstantAtMicrosecondPrecision(Instant left, Instant right) {
        return left != null && right != null
                && left.truncatedTo(ChronoUnit.MICROS)
                .equals(right.truncatedTo(ChronoUnit.MICROS));
    }

    private static void requireRange(Integer value, String field) {
        require(value != null && value >= 0 && value <= 100, field + "必须是0到100整数");
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AgentResponseValidationException("智能体响应校验失败：" + message);
        }
    }
}
