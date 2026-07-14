package com.stockquant.server.agent.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.model.AgentModels.AgentOutput;
import com.stockquant.server.agent.model.AgentModels.AgentRun;
import com.stockquant.server.agent.model.AgentTypes.AgentCode;
import com.stockquant.server.agent.model.AgentTypes.GateStatus;
import com.stockquant.server.agent.model.AgentTypes.RunDecision;
import com.stockquant.server.agent.model.AgentTypes.RunStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Repository
public class AgentRunRepository {

    private static final String COLUMNS = """
            id, task_id, agent_code, attempt_no, status, gate_status, decision,
            score, confidence, veto, summary, output_json::text AS output_json,
            started_at, finished_at, duration_ms, error_message, created_at, updated_at
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AgentRunRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<AgentRun> createProfessionalRuns(long taskId) {
        List<AgentRun> runs = new ArrayList<>();
        for (AgentCode code : AgentCode.PROFESSIONAL_AGENTS) {
            runs.add(jdbcTemplate.queryForObject("""
                    INSERT INTO agent_runs (
                        task_id, agent_code, attempt_no, status, gate_status, decision,
                        veto, created_at, updated_at
                    ) VALUES (?, ?, 1, 'QUEUED', 'NOT_APPLICABLE', 'NOT_APPLICABLE', FALSE, now(), now())
                    RETURNING
                    """ + COLUMNS, this::mapRun, taskId, code.name()));
        }
        return List.copyOf(runs);
    }

    public List<AgentRun> findByTaskId(long taskId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM agent_runs WHERE task_id = ? ORDER BY id",
                this::mapRun,
                taskId
        );
    }

    public void markAllRunning(long taskId) {
        int updated = jdbcTemplate.update("""
                UPDATE agent_runs
                SET status = 'RUNNING', started_at = now(), error_message = NULL, updated_at = now()
                WHERE task_id = ? AND status = 'QUEUED'
                """, taskId);
        if (updated != AgentCode.PROFESSIONAL_AGENTS.size()) {
            throw new IllegalStateException("必须将6个专业智能体运行转换为RUNNING：" + taskId);
        }
    }

    public void markUnfinishedFailed(long taskId, String errorMessage, long durationMs) {
        jdbcTemplate.update("""
                UPDATE agent_runs
                SET status = 'FAILED', score = NULL, confidence = NULL, summary = NULL,
                    output_json = NULL, veto = FALSE, error_message = ?, finished_at = now(),
                    duration_ms = ?, updated_at = now()
                WHERE task_id = ? AND status IN ('QUEUED', 'RUNNING')
                """, errorMessage, durationMs, taskId);
    }

    public void updateResult(AgentOutput output, Duration duration) {
        int updated = jdbcTemplate.update("""
                UPDATE agent_runs
                SET status = ?, gate_status = ?, decision = ?, score = ?, confidence = ?,
                    veto = ?, summary = ?, output_json = ?::jsonb, finished_at = now(),
                    duration_ms = ?, error_message = NULL, updated_at = now()
                WHERE id = ? AND task_id = ? AND agent_code = ?
                """,
                output.status().name(),
                output.gateStatus().name(),
                output.decision().name(),
                output.score(),
                output.confidence(),
                output.veto(),
                output.summary(),
                AgentJdbcSupport.writeJson(objectMapper, output),
                duration.toMillis(),
                output.runId(),
                output.taskId(),
                output.agentCode().name()
        );
        if (updated != 1) {
            throw new IllegalStateException("智能体运行结果更新失败：" + output.runId());
        }
    }

    private AgentRun mapRun(ResultSet resultSet, int rowNum) throws SQLException {
        return new AgentRun(
                resultSet.getLong("id"),
                resultSet.getLong("task_id"),
                AgentCode.valueOf(resultSet.getString("agent_code")),
                resultSet.getInt("attempt_no"),
                RunStatus.valueOf(resultSet.getString("status")),
                GateStatus.valueOf(resultSet.getString("gate_status")),
                RunDecision.valueOf(resultSet.getString("decision")),
                (Integer) resultSet.getObject("score"),
                (Integer) resultSet.getObject("confidence"),
                resultSet.getBoolean("veto"),
                resultSet.getString("summary"),
                AgentJdbcSupport.readJson(objectMapper, resultSet.getString("output_json")),
                AgentJdbcSupport.instant(resultSet.getObject("started_at")),
                AgentJdbcSupport.instant(resultSet.getObject("finished_at")),
                (Long) resultSet.getObject("duration_ms"),
                resultSet.getString("error_message"),
                AgentJdbcSupport.instant(resultSet.getObject("created_at")),
                AgentJdbcSupport.instant(resultSet.getObject("updated_at"))
        );
    }
}
