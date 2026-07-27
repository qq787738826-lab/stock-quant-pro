package com.stockquant.server.agent.validation;

import com.stockquant.server.agent.chief.AgentChiefDecisionRules;
import com.stockquant.server.agent.chief.AgentChiefDecisionRules.Evaluation;
import com.stockquant.server.agent.exception.AgentResponseValidationException;
import com.stockquant.server.agent.model.AgentModels.AgentOutput;
import com.stockquant.server.agent.model.AgentModels.AgentTeamRequest;
import com.stockquant.server.agent.model.AgentModels.AgentTeamResponse;
import com.stockquant.server.agent.model.AgentModels.Evidence;
import com.stockquant.server.agent.model.AgentModels.Finding;
import com.stockquant.server.agent.model.AgentModels.FinalDecision;
import com.stockquant.server.agent.model.AgentTypes.AgentCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class AgentStage2IChiefDecisionValidator {

    private static final AgentChiefDecisionRules RULES =
            new AgentChiefDecisionRules();

    private static final List<String> FORBIDDEN_SUMMARY_FRAGMENTS = List.of(
            "立即买入",
            "立即卖出",
            "自动下单",
            "清仓",
            "加仓",
            "减仓",
            "保证收益",
            "必涨",
            "必跌",
            "AUTO_BUY",
            "AUTO_SELL");

    private AgentStage2IChiefDecisionValidator() {
    }

    static void validate(
            AgentTeamRequest request,
            AgentTeamResponse response,
            List<AgentOutput> runs
    ) {
        List<AgentOutput> orderedRuns = AgentCode.PROFESSIONAL_AGENTS.stream()
                .map(code -> runs.stream()
                        .filter(run -> run.agentCode() == code)
                        .findFirst()
                        .orElseThrow())
                .toList();

        List<Evidence> expectedEvidence = new ArrayList<>();
        List<Finding> expectedFindings = new ArrayList<>();
        orderedRuns.forEach(run -> {
            expectedEvidence.addAll(run.evidence());
            expectedFindings.addAll(run.findings());
        });
        require(response.evidence().equals(expectedEvidence),
                "阶段2I顶层evidence必须按六智能体固定顺序拼接");

        FinalDecision actual = response.finalDecision();
        require(actual.findings().equals(expectedFindings),
                "阶段2I总控finding必须按六智能体固定顺序拼接");
        List<Long> expectedRunIds = AgentCode.PROFESSIONAL_AGENTS.stream()
                .map(code -> request.runIds().byAgentCode().get(code))
                .toList();
        require(actual.sourceRunIds().equals(expectedRunIds),
                "阶段2I sourceRunIds必须按固定六智能体顺序输出");
        require(actual.vetoIds().equals(
                        response.vetoes().stream()
                                .map(item -> item.vetoId())
                                .toList()),
                "阶段2I vetoIds必须精确保持正式POSITION_RISK veto顺序");

        Evaluation expected = RULES.evaluate(orderedRuns, response.vetoes());
        require(actual.decision() == expected.decision()
                        && actual.gateStatus() == expected.gateStatus()
                        && actual.vetoed() == expected.vetoed()
                        && Objects.equals(actual.score(), expected.score())
                        && Objects.equals(
                        actual.confidence(), expected.confidence())
                        && Objects.equals(actual.summary(), expected.summary())
                        && actual.vetoIds().equals(expected.vetoIds()),
                "阶段2I finalDecision未通过Java独立确定性复算");
        require(FORBIDDEN_SUMMARY_FRAGMENTS.stream()
                        .noneMatch(actual.summary()::contains),
                "阶段2I总控summary包含禁止的交易执行或收益语言");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AgentResponseValidationException(message);
        }
    }
}
