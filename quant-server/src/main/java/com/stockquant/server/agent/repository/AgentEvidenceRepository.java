package com.stockquant.server.agent.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.model.AgentModels.Evidence;
import com.stockquant.server.agent.model.AgentTypes.EvidenceCategory;
import com.stockquant.server.agent.model.AgentTypes.EvidenceSourceType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Repository
public class AgentEvidenceRepository {

    private static final String COLUMNS = """
            evidence_key, category, source_type, source_name, source_ref, symbol,
            trade_date, observed_at, collected_at, content_hash, payload_json::text AS payload_json
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AgentEvidenceRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<Evidence> findByTaskId(long taskId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM agent_evidence WHERE task_id = ? ORDER BY id",
                this::mapEvidence,
                taskId
        );
    }

    public void saveAll(long taskId, List<Evidence> evidence, Map<String, Long> owningRuns) {
        for (Evidence item : evidence) {
            jdbcTemplate.update("""
                    INSERT INTO agent_evidence (
                        task_id, run_id, evidence_key, category, source_type, source_name,
                        source_ref, symbol, trade_date, observed_at, collected_at,
                        content_hash, payload_json, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, now())
                    """,
                    taskId,
                    owningRuns.get(item.evidenceId()),
                    item.evidenceId(),
                    item.category().name(),
                    item.sourceType().name(),
                    item.sourceName(),
                    item.sourceRef(),
                    item.symbol(),
                    item.tradeDate(),
                    item.observedAt(),
                    item.collectedAt(),
                    item.contentHash(),
                    AgentJdbcSupport.writeJson(objectMapper, item.fields())
            );
        }
    }

    private Evidence mapEvidence(ResultSet resultSet, int rowNum) throws SQLException {
        return new Evidence(
                resultSet.getString("evidence_key"),
                EvidenceCategory.valueOf(resultSet.getString("category")),
                EvidenceSourceType.valueOf(resultSet.getString("source_type")),
                resultSet.getString("source_name"),
                resultSet.getString("source_ref"),
                resultSet.getString("symbol"),
                resultSet.getObject("trade_date", LocalDate.class),
                AgentJdbcSupport.instant(resultSet.getObject("observed_at")),
                AgentJdbcSupport.instant(resultSet.getObject("collected_at")),
                AgentJdbcSupport.readJson(objectMapper, resultSet.getString("payload_json")),
                resultSet.getString("content_hash")
        );
    }
}
