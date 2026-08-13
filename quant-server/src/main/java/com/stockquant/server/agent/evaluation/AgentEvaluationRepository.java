package com.stockquant.server.agent.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.evaluation.AgentEvaluationModels.AgentVersion;
import com.stockquant.server.agent.evaluation.AgentEvaluationModels.ChampionChallengerComparison;
import com.stockquant.server.agent.evaluation.AgentEvaluationModels.ResearchPerformanceReport;
import com.stockquant.server.agent.evaluation.AgentEvaluationModels.VersionKind;
import com.stockquant.server.agent.research.AgentResearchModels.AgentRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Append-only persistence for M5 versions and score reports. */
@Repository
public class AgentEvaluationRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AgentEvaluationRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public AgentVersion register(AgentVersion version) {
        String prompts = AgentEvaluationCanonical.json(version.promptVersions());
        Optional<AgentVersion> existing = version(version.versionKey());
        if (existing.isPresent()) {
            if (!existing.get().fingerprint().equals(version.fingerprint())) {
                throw new IllegalStateException("M5_VERSION_REGISTRY_CONFLICT");
            }
            return version;
        }
        int inserted = jdbc.update("""
                INSERT INTO agent_evaluation_versions (
                    version_key, version_kind, parent_version_key,
                    runtime_version, tool_version, strategy_version,
                    model_provider, model, prompt_versions_json,
                    evaluation_rule_version, version_fingerprint,
                    registered_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                ON CONFLICT (version_key) DO NOTHING
                """, version.versionKey(), version.kind().name(),
                "NONE".equals(version.parentVersionKey()) ? null
                        : version.parentVersionKey(),
                version.runtimeVersion(), version.toolVersion(),
                version.strategyVersion(), version.modelProvider(),
                version.model(), prompts, version.evaluationRuleVersion(),
                version.fingerprint(), Timestamp.from(version.registeredAt()));
        AgentVersion stored = version(version.versionKey()).orElseThrow();
        if (!stored.fingerprint().equals(version.fingerprint())) {
            throw new IllegalStateException("M5_VERSION_REGISTRY_CONFLICT");
        }
        return version;
    }

    public Optional<AgentVersion> version(String key) {
        return jdbc.query("""
                SELECT version_key, version_kind, parent_version_key,
                       runtime_version, tool_version, strategy_version,
                       model_provider, model, prompt_versions_json::text,
                       evaluation_rule_version, version_fingerprint,
                       registered_at
                  FROM agent_evaluation_versions WHERE version_key=?
                """, (row, number) -> mapVersion(row), key).stream()
                .findFirst();
    }

    public List<AgentVersion> versions() {
        return jdbc.query("""
                SELECT version_key, version_kind, parent_version_key,
                       runtime_version, tool_version, strategy_version,
                       model_provider, model, prompt_versions_json::text,
                       evaluation_rule_version, version_fingerprint,
                       registered_at
                  FROM agent_evaluation_versions
                 ORDER BY registered_at, version_key
                """, (row, number) -> mapVersion(row));
    }

    public Optional<AgentVersion> currentChampion() {
        return latest().flatMap(report -> version(
                report.currentChampionVersionKey()));
    }

    public ResearchPerformanceReport save(ResearchPerformanceReport report) {
        String json = AgentEvaluationCanonical.json(report);
        jdbc.update("""
                INSERT INTO agent_evaluation_reports (
                    report_version, champion_version_key, report_json,
                    report_fingerprint, generated_at
                ) VALUES (?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (report_fingerprint) DO NOTHING
                """, report.reportVersion(), report.currentChampionVersionKey(),
                json, report.fingerprint(), Timestamp.from(report.generatedAt()));
        jdbc.update("""
                INSERT INTO agent_evaluation_decisions (
                    comparison_version, champion_version_key,
                    challenger_version_key, decision,
                    comparison_fingerprint, decided_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (comparison_fingerprint) DO NOTHING
                """, report.comparison().comparisonVersion(),
                report.comparison().championVersionKey(),
                report.comparison().challengerVersionKey(),
                report.comparison().decision().name(),
                report.comparison().fingerprint(),
                Timestamp.from(report.generatedAt()));
        return report(report.fingerprint()).orElseThrow();
    }

    public Optional<ResearchPerformanceReport> report(String fingerprint) {
        return jdbc.query("""
                SELECT report_json::text FROM agent_evaluation_reports
                 WHERE report_fingerprint=?
                """, (row, number) -> read(row.getString(1),
                ResearchPerformanceReport.class), fingerprint)
                .stream().findFirst();
    }

    public Optional<ResearchPerformanceReport> latest() {
        return jdbc.query("""
                SELECT report_json::text FROM agent_evaluation_reports
                 ORDER BY generated_at DESC, id DESC LIMIT 1
                """, (row, number) -> read(row.getString(1),
                ResearchPerformanceReport.class)).stream().findFirst();
    }

    public long versionCount() {
        return jdbc.queryForObject("SELECT count(*) FROM agent_evaluation_versions",
                Long.class);
    }

    private AgentVersion mapVersion(java.sql.ResultSet row)
            throws java.sql.SQLException {
        return new AgentVersion(row.getString("version_key"),
                VersionKind.valueOf(row.getString("version_kind")),
                row.getString("parent_version_key"),
                row.getString("runtime_version"),
                row.getString("tool_version"),
                row.getString("strategy_version"),
                row.getString("model_provider"), row.getString("model"),
                promptMap(row.getString("prompt_versions_json")),
                row.getString("evaluation_rule_version"),
                instant(row.getObject("registered_at")),
                row.getString("version_fingerprint"));
    }

    private Map<AgentRole, String> promptMap(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> raw = mapper.readValue(json, Map.class);
            EnumMap<AgentRole, String> result = new EnumMap<>(AgentRole.class);
            raw.forEach((key, value) -> result.put(AgentRole.valueOf(key), value));
            return Map.copyOf(result);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("M5_VERSION_JSON_INVALID", error);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("M5_REPORT_JSON_INVALID", error);
        }
    }

    private static Instant instant(Object value) {
        if (value instanceof OffsetDateTime offset) {
            return offset.toInstant();
        }
        if (value instanceof java.time.ZonedDateTime zoned) {
            return zoned.toInstant();
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        throw new IllegalStateException("M5_TIMESTAMP_INVALID");
    }
}
