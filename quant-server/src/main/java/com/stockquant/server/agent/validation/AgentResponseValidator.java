package com.stockquant.server.agent.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockquant.server.agent.exception.AgentResponseValidationException;
import com.stockquant.server.agent.model.AgentModels.AgentOutput;
import com.stockquant.server.agent.model.AgentModels.AgentTeamRequest;
import com.stockquant.server.agent.model.AgentModels.AgentTeamResponse;
import com.stockquant.server.agent.model.AgentModels.Evidence;
import com.stockquant.server.agent.model.AgentModels.Finding;
import com.stockquant.server.agent.model.AgentModels.FinalDecision;
import com.stockquant.server.agent.model.AgentModels.FormalVeto;
import com.stockquant.server.agent.model.AgentTypes.AgentCode;
import com.stockquant.server.agent.model.AgentTypes.FinalDecisionCode;
import com.stockquant.server.agent.model.AgentTypes.GateStatus;
import com.stockquant.server.agent.model.AgentTypes.RunStatus;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
public class AgentResponseValidator {

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
            for (Evidence subset : safe(run.evidence())) {
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
        }
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
        for (String id : safe(ids)) {
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
                && Objects.equals(left.contentHash(), right.contentHash())
                && jsonEquals(left.fields(), right.fields());
    }

    private static boolean jsonEquals(JsonNode left, JsonNode right) {
        return Objects.equals(left, right);
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
