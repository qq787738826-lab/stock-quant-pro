package com.stockquant.server.agent;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSchemaContractTest {

    private static final String MIGRATION = "db/migration/V5__agent_team.sql";
    private static String sql;
    private static String normalizedSql;

    @BeforeAll
    static void loadMigration() throws IOException {
        try (InputStream stream = AgentSchemaContractTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            assertNotNull(stream, "V5 migration must exist on the test classpath");
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            normalizedSql = sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        }
    }

    @Test
    void createsExactlyTheRequiredAgentTeamTables() {
        List<String> tables = List.of(
                "agent_tasks", "agent_runs", "agent_evidence", "agent_vetoes",
                "agent_decisions", "security_events", "security_event_sync_runs"
        );

        tables.forEach(table -> assertContains("create table " + table + " ("));
        long createTableCount = Pattern.compile("(?i)\\bcreate\\s+table\\s+")
                .matcher(sql).results().count();
        assertTrue(createTableCount == tables.size(), "V5 must create only the seven approved tables");
    }

    @Test
    void containsNoDestructiveDataOrSchemaStatements() {
        assertFalse(Pattern.compile("(?i)\\bdrop\\s+table\\b").matcher(sql).find());
        assertFalse(Pattern.compile("(?i)\\btruncate\\b").matcher(sql).find());
        assertFalse(Pattern.compile("(?i)\\bdelete\\s+from\\b").matcher(sql).find());
        assertFalse(Pattern.compile("(?i)\\balter\\s+table\\b").matcher(sql).find());
    }

    @Test
    void storesImmutableContextAndValidatesItsShapeAndHash() {
        assertContains("context_snapshot_json jsonb not null");
        assertContains("context_hash varchar(64) not null");
        assertContains("jsonb_typeof(context_snapshot_json) = 'object'");
        assertContains("context_hash ~ '^[0-9a-f]{64}$'");
    }

    @Test
    void enforcesOneActiveTaskPerIdempotencyKey() {
        assertContains("create unique index uq_agent_tasks_active_cache_key");
        assertContains("on agent_tasks (symbol, trade_date, context_hash, rule_version, execution_mode)");
        assertContains("where status in ('queued', 'running')");
    }

    @Test
    void enforcesCacheFieldConsistencyWithoutSelfReference() {
        String tasks = tableSection("agent_tasks", "agent_runs");
        assertMatches(tasks, "constraint ck_agent_tasks_cache_consistency check \\( "
                + "\\(cache_hit = true and cached_from_task_id is not null\\) or "
                + "\\(cache_hit = false and cached_from_task_id is null\\) \\)");
        assertMatches(tasks, "constraint ck_agent_tasks_cache_not_self check \\( "
                + "cached_from_task_id is null or cached_from_task_id <> id \\)");
    }

    @Test
    void permitsOnlySixProfessionalAgentRuns() {
        String runTable = tableSection("agent_runs", "agent_evidence");
        List.of(
                "'data_quality'", "'market_regime'", "'technical_analysis'",
                "'strategy_backtest'", "'announcement_risk'", "'position_risk'"
        ).forEach(code -> assertTrue(runTable.contains(code), "Missing professional agent code " + code));
        assertFalse(runTable.contains("chief_decision"), "CHIEF_DECISION must never be an agent_run");
    }

    @Test
    void allowsPrecreatedQueuedRunsWithoutResultFields() {
        String runs = tableSection("agent_runs", "agent_evidence");
        assertMatches(runs, "score integer, confidence integer, veto boolean not null default false, "
                + "summary text, output_json jsonb,");
        List.of("score integer not null", "confidence integer not null",
                        "summary text not null", "output_json jsonb not null")
                .forEach(fragment -> assertFalse(runs.contains(fragment),
                        "Precreated runs must not require result field: " + fragment));
    }

    @Test
    void requiresCompleteResultsForSuccessfulTerminalRunStatuses() {
        String runs = tableSection("agent_runs", "agent_evidence");
        assertMatches(runs, "constraint ck_agent_runs_terminal_result check \\( "
                + "status not in \\('completed', 'partial', 'insufficient_data'\\) or \\( "
                + "score is not null and confidence is not null and summary is not null "
                + "and btrim\\(summary\\) <> '' and output_json is not null \\) \\)");
    }

    @Test
    void constrainsNullableRunResultTypes() {
        String runs = tableSection("agent_runs", "agent_evidence");
        assertMatches(runs, "constraint ck_agent_runs_score check \\(score is null or score between 0 and 100\\)");
        assertMatches(runs, "constraint ck_agent_runs_confidence check "
                + "\\(confidence is null or confidence between 0 and 100\\)");
        assertMatches(runs, "constraint ck_agent_runs_output_object check "
                + "\\( output_json is null or jsonb_typeof\\(output_json\\) = 'object' \\)");
    }

    @Test
    void usesTaskScopedCompositeRunRelationships() {
        String runs = tableSection("agent_runs", "agent_evidence");
        String evidence = tableSection("agent_evidence", "agent_vetoes");
        String vetoes = tableSection("agent_vetoes", "agent_decisions");

        assertMatches(runs, "constraint uq_agent_runs_task_id_id unique \\(task_id, id\\)");
        assertMatches(evidence, "constraint fk_agent_evidence_task_run foreign key \\(task_id, run_id\\) "
                + "references agent_runs \\(task_id, id\\) on delete set null \\(run_id\\)");
        assertFalse(evidence.contains("foreign key (run_id) references agent_runs (id)"));
        assertMatches(runs, "constraint uq_agent_runs_task_id_id_code unique "
                + "\\(task_id, id, agent_code\\)");
        assertMatches(vetoes, "constraint fk_agent_vetoes_task_run_agent foreign key "
                + "\\(task_id, run_id, agent_code\\) references agent_runs "
                + "\\(task_id, id, agent_code\\) on delete cascade");
        assertFalse(vetoes.contains("foreign key (run_id) references agent_runs (id)"));
        assertFalse(vetoes.contains("foreign key (task_id) references agent_tasks (id)"));
    }

    @Test
    void restrictsFormalVetoToPositionRisk() {
        String runs = tableSection("agent_runs", "agent_evidence");
        assertMatches(runs, "constraint ck_agent_runs_veto_consistency check \\( veto = false or \\( "
                + "agent_code = 'position_risk' and status in \\('completed', 'partial'\\) "
                + "and decision = 'reject' \\) \\)");
        String vetoTable = tableSection("agent_vetoes", "agent_decisions");
        assertTrue(vetoTable.contains("agent_code = 'position_risk'"));
        assertTrue(vetoTable.contains("cardinality(evidence_ids) > 0"));
    }

    @Test
    void constrainsAllScoresAndConfidenceToZeroThroughOneHundred() {
        assertContains("ck_agent_runs_score check (score is null or score between 0 and 100)");
        assertContains("ck_agent_runs_confidence check (confidence is null or confidence between 0 and 100)");
        assertContains("ck_agent_decisions_score check (score is null or score between 0 and 100)");
        assertContains("ck_agent_decisions_confidence check ( confidence is null or confidence between 0 and 100 )");
    }

    @Test
    void permitsOnlyResearchFinalDecisions() {
        String decisions = tableSection("agent_decisions", "security_events");
        List.of(
                "'rejected_by_veto'", "'blocked_by_data_quality'", "'insufficient_data'",
                "'research_only'", "'watch'", "'pass_to_manual_review'"
        ).forEach(value -> assertTrue(decisions.contains(value), "Missing decision " + value));
        List.of("'buy'", "'sell'", "'auto_buy'", "'auto_sell'")
                .forEach(value -> assertFalse(decisions.contains(value), "Trading decision is forbidden: " + value));
    }

    @Test
    void enforcesBidirectionalFinalVetoConsistency() {
        String decisions = tableSection("agent_decisions", "security_events");
        assertMatches(decisions, "constraint ck_agent_decisions_veto_consistency check \\( "
                + "\\( status = 'failed' and vetoed = false and cardinality\\(veto_ids\\) = 0 \\) or "
                + "\\( status <> 'failed' and \\( \\( vetoed = true and decision = 'rejected_by_veto' "
                + "and cardinality\\(veto_ids\\) > 0 \\) or \\( vetoed = false "
                + "and decision <> 'rejected_by_veto' and cardinality\\(veto_ids\\) = 0 \\) \\) \\) \\)");
    }

    @Test
    void permitsFailedDecisionOnlyWithErrorAndNoFabricatedResult() {
        String decisions = tableSection("agent_decisions", "security_events");
        assertMatches(decisions, "status = 'failed' and decision is null and gate_status is null "
                + "and score is null and confidence is null and summary is null "
                + "and findings_json is null and source_run_ids is null and decision_json is null "
                + "and generated_at is null and vetoed = false and cardinality\\(veto_ids\\) = 0 "
                + "and error_message is not null and btrim\\(error_message\\) <> ''");
        List.of("decision varchar(64) not null", "gate_status varchar(32) not null",
                        "score integer not null", "confidence integer not null", "summary text not null",
                        "findings_json jsonb not null", "source_run_ids bigint[] not null",
                        "decision_json jsonb not null", "generated_at timestamptz not null")
                .forEach(fragment -> assertFalse(decisions.contains(fragment),
                        "FAILED decisions must not require fabricated field: " + fragment));
    }

    @Test
    void requiresCompleteSuccessfulDecisionResult() {
        String decisions = tableSection("agent_decisions", "security_events");
        assertMatches(decisions, "status in \\('completed', 'partial', 'insufficient_data'\\) "
                + "and decision is not null and gate_status is not null and score is not null "
                + "and confidence is not null and summary is not null and btrim\\(summary\\) <> '' "
                + "and findings_json is not null and source_run_ids is not null "
                + "and cardinality\\(source_run_ids\\) > 0 "
                + "and array_position\\(source_run_ids, null\\) is null "
                + "and decision_json is not null and generated_at is not null");
    }

    @Test
    void rejectsNullElementsInDecisionReferenceArrays() {
        String decisions = tableSection("agent_decisions", "security_events");
        assertMatches(decisions, "constraint ck_agent_decisions_source_runs check \\( "
                + "source_run_ids is null or \\( cardinality\\(source_run_ids\\) > 0 "
                + "and array_position\\(source_run_ids, null\\) is null \\) \\)");
        assertMatches(decisions, "constraint ck_agent_decisions_veto_ids_no_null check \\( "
                + "array_position\\(veto_ids, null\\) is null \\)");
    }

    @Test
    void keepsExistingSimulatedTradingTablesUntouched() {
        List.of("portfolio_accounts", "positions", "manual_orders", "trades")
                .forEach(table -> assertFalse(Pattern.compile(
                        "(?i)\\b(alter\\s+table|delete\\s+from|truncate(?:\\s+table)?|drop\\s+table)\\s+" + table + "\\b"
                ).matcher(sql).find(), "V5 must not modify simulated trading table " + table));
    }

    @Test
    void definesWholeTaskDeletionAndCacheReferenceBehavior() {
        assertContains("constraint fk_agent_tasks_cached_from foreign key (cached_from_task_id) "
                + "references agent_tasks (id) on delete restrict");
        assertTrue(Pattern.compile("references agent_tasks \\(id\\) on delete cascade")
                .matcher(normalizedSql).results().count() >= 3);
        assertContains("references agent_runs (task_id, id, agent_code) on delete cascade");
    }

    @Test
    void handlesNullableAnnouncementNumbersInDeduplication() {
        assertContains("on security_events (source_name, announcement_no, content_hash) nulls not distinct");
    }

    @Test
    void containsNoEmbeddedCredentialsOrApiSecrets() {
        List<Pattern> forbidden = List.of(
                Pattern.compile("(?i)jdbc:(postgresql|mysql|mariadb):"),
                Pattern.compile("(?i)password\\s*="),
                Pattern.compile("(?i)api[_-]?key\\s*="),
                Pattern.compile("(?i)secret[_-]?key\\s*=")
        );
        forbidden.forEach(pattern -> assertFalse(pattern.matcher(sql).find(),
                "Migration must not contain credentials or sensitive configuration"));
    }

    private static void assertContains(String expected) {
        assertTrue(normalizedSql.contains(expected), "Expected SQL contract fragment: " + expected);
    }

    private static void assertMatches(String section, String regex) {
        assertTrue(Pattern.compile(regex).matcher(section).find(),
                "Expected SQL contract pattern: " + regex);
    }

    private static String tableSection(String table, String nextTable) {
        int start = normalizedSql.indexOf("create table " + table + " (");
        int end = normalizedSql.indexOf("create table " + nextTable + " (", start);
        assertTrue(start >= 0 && end > start, "Unable to locate table section for " + table);
        return normalizedSql.substring(start, end);
    }
}
