package com.stockquant.server.agent.repository;

import com.stockquant.server.agent.model.AgentModels.FormalVeto;
import com.stockquant.server.agent.model.AgentTypes.AgentCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class AgentVetoRepository {

    private final JdbcTemplate jdbcTemplate;

    public AgentVetoRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<FormalVeto> findByTaskId(long taskId) {
        return jdbcTemplate.query("""
                SELECT id, task_id, run_id, agent_code, veto_code, reason,
                       evidence_ids, created_at
                FROM agent_vetoes
                WHERE task_id = ?
                ORDER BY id
                """, this::mapVeto, taskId);
    }

    public Map<String, Long> saveAll(List<FormalVeto> vetoes) {
        Map<String, Long> persistedIds = new LinkedHashMap<>();
        for (FormalVeto veto : vetoes) {
            Long id = jdbcTemplate.queryForObject("""
                    INSERT INTO agent_vetoes (
                        task_id, run_id, agent_code, veto_code, reason, evidence_ids, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?::text[], ?)
                    RETURNING id
                    """,
                    Long.class,
                    veto.taskId(),
                    veto.runId(),
                    veto.agentCode().name(),
                    veto.vetoCode(),
                    veto.reason(),
                    AgentJdbcSupport.textArray(veto.evidenceIds()),
                    AgentJdbcSupport.timestamptz(veto.createdAt())
            );
            persistedIds.put(veto.vetoId(), id);
        }
        return Map.copyOf(persistedIds);
    }

    private FormalVeto mapVeto(ResultSet resultSet, int rowNum) throws SQLException {
        String[] evidenceIds = (String[]) resultSet.getArray("evidence_ids").getArray();
        return new FormalVeto(
                String.valueOf(resultSet.getLong("id")),
                resultSet.getLong("task_id"),
                resultSet.getLong("run_id"),
                AgentCode.valueOf(resultSet.getString("agent_code")),
                resultSet.getString("veto_code"),
                resultSet.getString("reason"),
                List.of(evidenceIds),
                AgentJdbcSupport.instant(resultSet.getObject("created_at"))
        );
    }
}
