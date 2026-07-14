package com.stockquant.server.agent.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.exception.AgentTeamException;
import com.stockquant.server.agent.model.AgentModels.FinalDecision;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class AgentDecisionRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AgentDecisionRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<FinalDecision> findByTaskId(long taskId) {
        return jdbcTemplate.query("""
                SELECT decision_json::text AS decision_json, source_run_ids, veto_ids
                FROM agent_decisions
                WHERE task_id = ?
                """, this::mapDecision, taskId).stream().findFirst();
    }

    public void save(
            FinalDecision decision,
            String status,
            Map<String, Long> persistedVetoIds,
            Duration duration
    ) {
        List<Long> vetoIds = decision.vetoIds().stream().map(persistedVetoIds::get).toList();
        jdbcTemplate.update("""
                INSERT INTO agent_decisions (
                    task_id, status, decision, gate_status, vetoed, score, confidence,
                    summary, findings_json, source_run_ids, veto_ids, decision_json,
                    context_hash, trade_date, rule_version, execution_mode, generated_at,
                    duration_ms, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::bigint[], ?::bigint[],
                          ?::jsonb, ?, ?, ?, ?, ?, ?, now(), now())
                """,
                decision.taskId(),
                status,
                decision.decision().name(),
                decision.gateStatus().name(),
                decision.vetoed(),
                decision.score(),
                decision.confidence(),
                decision.summary(),
                AgentJdbcSupport.writeJson(objectMapper, decision.findings()),
                AgentJdbcSupport.longArray(decision.sourceRunIds()),
                AgentJdbcSupport.longArray(vetoIds),
                AgentJdbcSupport.writeJson(objectMapper, decision),
                decision.contextHash(),
                decision.tradeDate(),
                decision.ruleVersion(),
                decision.executionMode().name(),
                decision.generatedAt(),
                duration.toMillis()
        );
    }

    private FinalDecision mapDecision(ResultSet resultSet, int rowNum) throws SQLException {
        String decisionJson = resultSet.getString("decision_json");
        if (decisionJson == null || decisionJson.isBlank()) {
            throw new AgentTeamException("数据库中的总控决策JSON为空");
        }
        try {
            return objectMapper.readValue(decisionJson, FinalDecision.class);
        } catch (Exception error) {
            throw new AgentTeamException("数据库中的总控决策无法解析", error);
        }
    }
}
