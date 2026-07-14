package com.stockquant.server.agent.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.model.AgentModels.AgentTask;
import com.stockquant.server.agent.model.AgentModels.CacheKey;
import com.stockquant.server.agent.model.AgentModels.ContextSnapshot;
import com.stockquant.server.agent.model.AgentModels.PageResult;
import com.stockquant.server.agent.model.AgentTypes.ExecutionMode;
import com.stockquant.server.agent.model.AgentTypes.TaskStatus;
import com.stockquant.server.agent.model.AgentTypes.TriggerType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class AgentTaskRepository {

    private static final String COLUMNS = """
            id, symbol, trade_date, status, context_schema_version,
            context_snapshot_json::text AS context_snapshot_json, context_generated_at,
            context_hash, rule_version, execution_mode, trigger_type, requested_by,
            force_refresh, started_at, finished_at, error_message, created_at, updated_at
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AgentTaskRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<AgentTask> findById(long taskId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM agent_tasks WHERE id = ?",
                this::mapTask,
                taskId
        ).stream().findFirst();
    }

    public Optional<AgentTask> findActive(CacheKey key) {
        return findByCacheKey(key, "AND status IN ('QUEUED', 'RUNNING')");
    }

    public Optional<AgentTask> findLatestCompleted(CacheKey key) {
        return findByCacheKey(key, "AND status = 'COMPLETED'");
    }

    private Optional<AgentTask> findByCacheKey(CacheKey key, String statusClause) {
        String sql = "SELECT " + COLUMNS + " FROM agent_tasks "
                + "WHERE symbol = ? AND trade_date = ? AND context_hash = ? "
                + "AND rule_version = ? AND execution_mode = ? " + statusClause
                + " ORDER BY created_at DESC, id DESC LIMIT 1";
        return jdbcTemplate.query(
                sql,
                this::mapTask,
                key.symbol(), key.tradeDate(), key.contextHash(), key.ruleVersion(), key.executionMode().name()
        ).stream().findFirst();
    }

    public AgentTask insert(
            String symbol,
            LocalDate tradeDate,
            ContextSnapshot context,
            String ruleVersion,
            ExecutionMode executionMode,
            TriggerType triggerType,
            String requestedBy,
            boolean forceRefresh
    ) {
        String sql = """
                INSERT INTO agent_tasks (
                    symbol, trade_date, status, context_schema_version,
                    context_snapshot_json, context_generated_at, context_hash,
                    rule_version, execution_mode, trigger_type, requested_by,
                    force_refresh, cache_hit, cached_from_task_id, created_at, updated_at
                ) VALUES (?, ?, 'QUEUED', ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, FALSE, NULL, now(), now())
                RETURNING
                """ + COLUMNS;
        return jdbcTemplate.queryForObject(
                sql,
                this::mapTask,
                symbol,
                tradeDate,
                context.schemaVersion(),
                AgentJdbcSupport.writeJson(objectMapper, context.value()),
                context.generatedAt(),
                context.contextHash(),
                ruleVersion,
                executionMode.name(),
                triggerType.name(),
                requestedBy,
                forceRefresh
        );
    }

    public PageResult<AgentTask> history(int page, int size) {
        Long total = jdbcTemplate.queryForObject("SELECT count(id) FROM agent_tasks", Long.class);
        List<AgentTask> content = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM agent_tasks "
                        + "ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                this::mapTask,
                size,
                (long) page * size
        );
        return new PageResult<>(content, page, size, total == null ? 0 : total);
    }

    public int claimQueuedTask(long taskId) {
        return jdbcTemplate.update("""
                UPDATE agent_tasks
                SET status = 'RUNNING', started_at = now(), error_message = NULL, updated_at = now()
                WHERE id = ? AND status = 'QUEUED'
                """, taskId);
    }

    public void markFailed(long taskId, String errorMessage) {
        jdbcTemplate.update("""
                UPDATE agent_tasks
                SET status = 'FAILED', error_message = ?, finished_at = now(), updated_at = now()
                WHERE id = ? AND status IN ('QUEUED', 'RUNNING')
                """, errorMessage, taskId);
    }

    public void markFinished(long taskId, TaskStatus status) {
        int updated = jdbcTemplate.update("""
                UPDATE agent_tasks
                SET status = ?, error_message = NULL, finished_at = now(), updated_at = now()
                WHERE id = ? AND status = 'RUNNING'
                """, status.name(), taskId);
        requireUpdated(updated, "任务最终状态更新失败：" + taskId);
    }

    private static void requireUpdated(int updated, String message) {
        if (updated != 1) {
            throw new IllegalStateException(message);
        }
    }

    private AgentTask mapTask(ResultSet resultSet, int rowNum) throws SQLException {
        return new AgentTask(
                resultSet.getLong("id"),
                resultSet.getString("symbol"),
                resultSet.getObject("trade_date", LocalDate.class),
                TaskStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("context_schema_version"),
                AgentJdbcSupport.readJson(objectMapper, resultSet.getString("context_snapshot_json")),
                AgentJdbcSupport.instant(resultSet.getObject("context_generated_at")),
                resultSet.getString("context_hash"),
                resultSet.getString("rule_version"),
                ExecutionMode.valueOf(resultSet.getString("execution_mode")),
                TriggerType.valueOf(resultSet.getString("trigger_type")),
                resultSet.getString("requested_by"),
                resultSet.getBoolean("force_refresh"),
                AgentJdbcSupport.instant(resultSet.getObject("started_at")),
                AgentJdbcSupport.instant(resultSet.getObject("finished_at")),
                resultSet.getString("error_message"),
                AgentJdbcSupport.instant(resultSet.getObject("created_at")),
                AgentJdbcSupport.instant(resultSet.getObject("updated_at"))
        );
    }
}
