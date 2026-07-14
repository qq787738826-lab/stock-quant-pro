-- Stock Quant Pro 1.4.0: agent team persistence and security event contracts.
-- Cross-object evidence references and final-decision consistency are validated by Java.

CREATE TABLE agent_tasks (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(6) NOT NULL,
    trade_date DATE NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
    context_schema_version VARCHAR(32) NOT NULL,
    context_snapshot_json JSONB NOT NULL,
    context_generated_at TIMESTAMPTZ NOT NULL,
    context_hash VARCHAR(64) NOT NULL,
    rule_version VARCHAR(64) NOT NULL,
    execution_mode VARCHAR(32) NOT NULL DEFAULT 'LOCAL_RULES',
    trigger_type VARCHAR(32) NOT NULL,
    requested_by VARCHAR(128),
    force_refresh BOOLEAN NOT NULL DEFAULT FALSE,
    cache_hit BOOLEAN NOT NULL DEFAULT FALSE,
    cached_from_task_id BIGINT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_agent_tasks_symbol CHECK (symbol ~ '^[0-9]{6}$'),
    CONSTRAINT ck_agent_tasks_status CHECK (status IN (
        'QUEUED', 'RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED'
    )),
    CONSTRAINT ck_agent_tasks_context_snapshot_object CHECK (jsonb_typeof(context_snapshot_json) = 'object'),
    CONSTRAINT ck_agent_tasks_context_hash CHECK (context_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_agent_tasks_execution_mode CHECK (execution_mode = 'LOCAL_RULES'),
    CONSTRAINT ck_agent_tasks_trigger_type CHECK (trigger_type IN (
        'MANUAL', 'SCAN_CANDIDATE', 'SCHEDULED', 'RETRY'
    )),
    CONSTRAINT ck_agent_tasks_cache_consistency CHECK (
        (cache_hit = TRUE AND cached_from_task_id IS NOT NULL)
        OR (cache_hit = FALSE AND cached_from_task_id IS NULL)
    ),
    CONSTRAINT ck_agent_tasks_cache_not_self CHECK (
        cached_from_task_id IS NULL OR cached_from_task_id <> id
    ),
    CONSTRAINT fk_agent_tasks_cached_from FOREIGN KEY (cached_from_task_id)
        REFERENCES agent_tasks (id) ON DELETE RESTRICT
);

CREATE INDEX idx_agent_tasks_symbol_trade_date ON agent_tasks (symbol, trade_date);
CREATE INDEX idx_agent_tasks_trade_date ON agent_tasks (trade_date);
CREATE INDEX idx_agent_tasks_status_created_at ON agent_tasks (status, created_at);
CREATE INDEX idx_agent_tasks_created_at ON agent_tasks (created_at);
CREATE UNIQUE INDEX uq_agent_tasks_active_cache_key
    ON agent_tasks (symbol, trade_date, context_hash, rule_version, execution_mode)
    WHERE status IN ('QUEUED', 'RUNNING');

CREATE TABLE agent_runs (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    agent_code VARCHAR(64) NOT NULL,
    attempt_no INTEGER NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
    gate_status VARCHAR(32) NOT NULL DEFAULT 'NOT_APPLICABLE',
    decision VARCHAR(32) NOT NULL DEFAULT 'NOT_APPLICABLE',
    score INTEGER,
    confidence INTEGER,
    veto BOOLEAN NOT NULL DEFAULT FALSE,
    summary TEXT,
    output_json JSONB,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    duration_ms BIGINT,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_agent_runs_task FOREIGN KEY (task_id)
        REFERENCES agent_tasks (id) ON DELETE CASCADE,
    CONSTRAINT uq_agent_runs_task_id_id UNIQUE (task_id, id),
    CONSTRAINT uq_agent_runs_task_id_id_code UNIQUE (task_id, id, agent_code),
    CONSTRAINT uq_agent_runs_task_agent_attempt UNIQUE (task_id, agent_code, attempt_no),
    CONSTRAINT ck_agent_runs_agent_code CHECK (agent_code IN (
        'DATA_QUALITY', 'MARKET_REGIME', 'TECHNICAL_ANALYSIS',
        'STRATEGY_BACKTEST', 'ANNOUNCEMENT_RISK', 'POSITION_RISK'
    )),
    CONSTRAINT ck_agent_runs_attempt_no CHECK (attempt_no > 0),
    CONSTRAINT ck_agent_runs_status CHECK (status IN (
        'QUEUED', 'RUNNING', 'COMPLETED', 'PARTIAL',
        'INSUFFICIENT_DATA', 'FAILED', 'SKIPPED'
    )),
    CONSTRAINT ck_agent_runs_gate_status CHECK (gate_status IN (
        'PASS', 'WARN', 'BLOCKED', 'NOT_APPLICABLE'
    )),
    CONSTRAINT ck_agent_runs_decision CHECK (decision IN (
        'PASS', 'WARN', 'REJECT', 'NOT_APPLICABLE'
    )),
    CONSTRAINT ck_agent_runs_score CHECK (score IS NULL OR score BETWEEN 0 AND 100),
    CONSTRAINT ck_agent_runs_confidence CHECK (confidence IS NULL OR confidence BETWEEN 0 AND 100),
    CONSTRAINT ck_agent_runs_veto_consistency CHECK (
        veto = FALSE
        OR (
            agent_code = 'POSITION_RISK'
            AND status IN ('COMPLETED', 'PARTIAL')
            AND decision = 'REJECT'
        )
    ),
    CONSTRAINT ck_agent_runs_output_object CHECK (
        output_json IS NULL OR jsonb_typeof(output_json) = 'object'
    ),
    CONSTRAINT ck_agent_runs_terminal_result CHECK (
        status NOT IN ('COMPLETED', 'PARTIAL', 'INSUFFICIENT_DATA')
        OR (
            score IS NOT NULL
            AND confidence IS NOT NULL
            AND summary IS NOT NULL
            AND btrim(summary) <> ''
            AND output_json IS NOT NULL
        )
    ),
    CONSTRAINT ck_agent_runs_duration CHECK (duration_ms IS NULL OR duration_ms >= 0)
);

CREATE INDEX idx_agent_runs_task_id ON agent_runs (task_id);
CREATE INDEX idx_agent_runs_agent_code ON agent_runs (agent_code);
CREATE INDEX idx_agent_runs_status ON agent_runs (status);

CREATE TABLE agent_evidence (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    run_id BIGINT,
    evidence_key VARCHAR(128) NOT NULL,
    category VARCHAR(64) NOT NULL,
    source_type VARCHAR(64) NOT NULL,
    source_name VARCHAR(128) NOT NULL,
    source_ref TEXT NOT NULL,
    symbol VARCHAR(6),
    trade_date DATE,
    observed_at TIMESTAMPTZ NOT NULL,
    collected_at TIMESTAMPTZ NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    payload_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_agent_evidence_task FOREIGN KEY (task_id)
        REFERENCES agent_tasks (id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_evidence_task_run FOREIGN KEY (task_id, run_id)
        REFERENCES agent_runs (task_id, id) ON DELETE SET NULL (run_id),
    CONSTRAINT uq_agent_evidence_task_key UNIQUE (task_id, evidence_key),
    CONSTRAINT ck_agent_evidence_key CHECK (btrim(evidence_key) <> ''),
    CONSTRAINT ck_agent_evidence_category CHECK (category IN (
        'MARKET_DATA', 'MARKET_BREADTH', 'TECHNICAL_INDICATOR',
        'SCAN_RESULT', 'BACKTEST_RESULT', 'SECURITY_EVENT',
        'PORTFOLIO_STATE', 'DATA_QUALITY', 'QUERY_RESULT'
    )),
    CONSTRAINT ck_agent_evidence_source_type CHECK (source_type IN (
        'DATABASE', 'LOCAL_CACHE', 'CONFIGURED_PROVIDER',
        'JAVA_ENGINE', 'PYTHON_RULE_ENGINE'
    )),
    CONSTRAINT ck_agent_evidence_symbol CHECK (symbol IS NULL OR symbol ~ '^[0-9]{6}$'),
    CONSTRAINT ck_agent_evidence_content_hash CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_agent_evidence_payload_object CHECK (jsonb_typeof(payload_json) = 'object')
);

CREATE INDEX idx_agent_evidence_task_id ON agent_evidence (task_id);
CREATE INDEX idx_agent_evidence_run_id ON agent_evidence (run_id);
CREATE INDEX idx_agent_evidence_symbol_trade_date ON agent_evidence (symbol, trade_date);
CREATE INDEX idx_agent_evidence_category ON agent_evidence (category);

CREATE TABLE agent_vetoes (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    run_id BIGINT NOT NULL,
    agent_code VARCHAR(64) NOT NULL DEFAULT 'POSITION_RISK',
    veto_code VARCHAR(128) NOT NULL,
    reason TEXT NOT NULL,
    evidence_ids TEXT[] NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_agent_vetoes_task_run_agent FOREIGN KEY (task_id, run_id, agent_code)
        REFERENCES agent_runs (task_id, id, agent_code) ON DELETE CASCADE,
    CONSTRAINT uq_agent_vetoes_task_run_code UNIQUE (task_id, run_id, veto_code),
    CONSTRAINT ck_agent_vetoes_agent_code CHECK (agent_code = 'POSITION_RISK'),
    CONSTRAINT ck_agent_vetoes_code CHECK (btrim(veto_code) <> ''),
    CONSTRAINT ck_agent_vetoes_reason CHECK (btrim(reason) <> ''),
    CONSTRAINT ck_agent_vetoes_evidence_ids CHECK (
        cardinality(evidence_ids) > 0 AND array_position(evidence_ids, NULL) IS NULL
    )
);

CREATE INDEX idx_agent_vetoes_task_id ON agent_vetoes (task_id);
CREATE INDEX idx_agent_vetoes_run_id ON agent_vetoes (run_id);

CREATE TABLE agent_decisions (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    decision VARCHAR(64),
    gate_status VARCHAR(32),
    vetoed BOOLEAN NOT NULL DEFAULT FALSE,
    score INTEGER,
    confidence INTEGER,
    summary TEXT,
    findings_json JSONB,
    source_run_ids BIGINT[],
    veto_ids BIGINT[] NOT NULL DEFAULT ARRAY[]::BIGINT[],
    decision_json JSONB,
    context_hash VARCHAR(64) NOT NULL,
    trade_date DATE NOT NULL,
    rule_version VARCHAR(64) NOT NULL,
    execution_mode VARCHAR(32) NOT NULL DEFAULT 'LOCAL_RULES',
    generated_at TIMESTAMPTZ,
    duration_ms BIGINT,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_agent_decisions_task FOREIGN KEY (task_id)
        REFERENCES agent_tasks (id) ON DELETE CASCADE,
    CONSTRAINT uq_agent_decisions_task UNIQUE (task_id),
    CONSTRAINT ck_agent_decisions_status CHECK (status IN (
        'COMPLETED', 'PARTIAL', 'INSUFFICIENT_DATA', 'FAILED'
    )),
    CONSTRAINT ck_agent_decisions_decision CHECK (
        decision IS NULL OR decision IN (
            'REJECTED_BY_VETO', 'BLOCKED_BY_DATA_QUALITY', 'INSUFFICIENT_DATA',
            'RESEARCH_ONLY', 'WATCH', 'PASS_TO_MANUAL_REVIEW'
        )
    ),
    CONSTRAINT ck_agent_decisions_gate_status CHECK (
        gate_status IS NULL OR gate_status IN (
            'PASS', 'WARN', 'BLOCKED', 'NOT_APPLICABLE'
        )
    ),
    CONSTRAINT ck_agent_decisions_score CHECK (score IS NULL OR score BETWEEN 0 AND 100),
    CONSTRAINT ck_agent_decisions_confidence CHECK (
        confidence IS NULL OR confidence BETWEEN 0 AND 100
    ),
    CONSTRAINT ck_agent_decisions_findings_array CHECK (
        findings_json IS NULL OR jsonb_typeof(findings_json) = 'array'
    ),
    CONSTRAINT ck_agent_decisions_source_runs CHECK (
        source_run_ids IS NULL
        OR (
            cardinality(source_run_ids) > 0
            AND array_position(source_run_ids, NULL) IS NULL
        )
    ),
    CONSTRAINT ck_agent_decisions_veto_ids_no_null CHECK (
        array_position(veto_ids, NULL) IS NULL
    ),
    CONSTRAINT ck_agent_decisions_result_by_status CHECK (
        (
            status IN ('COMPLETED', 'PARTIAL', 'INSUFFICIENT_DATA')
            AND decision IS NOT NULL
            AND gate_status IS NOT NULL
            AND score IS NOT NULL
            AND confidence IS NOT NULL
            AND summary IS NOT NULL
            AND btrim(summary) <> ''
            AND findings_json IS NOT NULL
            AND source_run_ids IS NOT NULL
            AND cardinality(source_run_ids) > 0
            AND array_position(source_run_ids, NULL) IS NULL
            AND decision_json IS NOT NULL
            AND generated_at IS NOT NULL
        )
        OR (
            status = 'FAILED'
            AND decision IS NULL
            AND gate_status IS NULL
            AND score IS NULL
            AND confidence IS NULL
            AND summary IS NULL
            AND findings_json IS NULL
            AND source_run_ids IS NULL
            AND decision_json IS NULL
            AND generated_at IS NULL
            AND vetoed = FALSE
            AND cardinality(veto_ids) = 0
            AND error_message IS NOT NULL
            AND btrim(error_message) <> ''
        )
    ),
    CONSTRAINT ck_agent_decisions_veto_consistency CHECK (
        (
            status = 'FAILED'
            AND vetoed = FALSE
            AND cardinality(veto_ids) = 0
        )
        OR (
            status <> 'FAILED'
            AND (
                (
                    vetoed = TRUE
                    AND decision = 'REJECTED_BY_VETO'
                    AND cardinality(veto_ids) > 0
                )
                OR (
                    vetoed = FALSE
                    AND decision <> 'REJECTED_BY_VETO'
                    AND cardinality(veto_ids) = 0
                )
            )
        )
    ),
    CONSTRAINT ck_agent_decisions_decision_object CHECK (
        decision_json IS NULL OR jsonb_typeof(decision_json) = 'object'
    ),
    CONSTRAINT ck_agent_decisions_context_hash CHECK (context_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_agent_decisions_execution_mode CHECK (execution_mode = 'LOCAL_RULES'),
    CONSTRAINT ck_agent_decisions_duration CHECK (duration_ms IS NULL OR duration_ms >= 0)
);

CREATE INDEX idx_agent_decisions_decision ON agent_decisions (decision);
CREATE INDEX idx_agent_decisions_vetoed ON agent_decisions (vetoed);
CREATE INDEX idx_agent_decisions_trade_date ON agent_decisions (trade_date);
CREATE INDEX idx_agent_decisions_created_at ON agent_decisions (created_at);

CREATE TABLE security_events (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(6) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    title TEXT NOT NULL,
    published_at TIMESTAMPTZ NOT NULL,
    source_name VARCHAR(128) NOT NULL,
    source_ref TEXT NOT NULL,
    announcement_no VARCHAR(128),
    summary TEXT,
    content_hash VARCHAR(64) NOT NULL,
    risk_level VARCHAR(32) NOT NULL,
    payload_json JSONB NOT NULL,
    collected_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_security_events_symbol CHECK (symbol ~ '^[0-9]{6}$'),
    CONSTRAINT ck_security_events_event_type CHECK (btrim(event_type) <> ''),
    CONSTRAINT ck_security_events_content_hash CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_security_events_risk_level CHECK (risk_level IN (
        'INFO', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL'
    )),
    CONSTRAINT ck_security_events_payload_object CHECK (jsonb_typeof(payload_json) = 'object')
);

CREATE UNIQUE INDEX uq_security_events_source_announcement_content
    ON security_events (source_name, announcement_no, content_hash) NULLS NOT DISTINCT;
CREATE INDEX idx_security_events_symbol_published_at ON security_events (symbol, published_at);
CREATE INDEX idx_security_events_published_at ON security_events (published_at);
CREATE INDEX idx_security_events_event_type ON security_events (event_type);
CREATE INDEX idx_security_events_risk_level ON security_events (risk_level);

CREATE TABLE security_event_sync_runs (
    id BIGSERIAL PRIMARY KEY,
    provider_code VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    fetched_count INTEGER NOT NULL DEFAULT 0,
    inserted_count INTEGER NOT NULL DEFAULT 0,
    updated_count INTEGER NOT NULL DEFAULT 0,
    failed_count INTEGER NOT NULL DEFAULT 0,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_security_event_sync_runs_provider CHECK (btrim(provider_code) <> ''),
    CONSTRAINT ck_security_event_sync_runs_status CHECK (status IN (
        'QUEUED', 'RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED'
    )),
    CONSTRAINT ck_security_event_sync_runs_counts CHECK (
        fetched_count >= 0 AND inserted_count >= 0
        AND updated_count >= 0 AND failed_count >= 0
    )
);

CREATE INDEX idx_security_event_sync_runs_provider_code ON security_event_sync_runs (provider_code);
CREATE INDEX idx_security_event_sync_runs_status ON security_event_sync_runs (status);
CREATE INDEX idx_security_event_sync_runs_created_at ON security_event_sync_runs (created_at);

COMMENT ON TABLE agent_tasks IS 'One complete, immutable-context stock agent-team analysis task.';
COMMENT ON COLUMN agent_tasks.context_snapshot_json IS 'Immutable read-only analysis context generated by Java.';
COMMENT ON COLUMN agent_tasks.context_hash IS 'SHA-256 of normalized stable context fields, excluding volatile identifiers and timestamps.';
COMMENT ON COLUMN agent_tasks.cached_from_task_id IS 'Completed historical task reused by Java; referenced source deletion is restricted.';
COMMENT ON TABLE agent_runs IS 'Execution records for the six professional agents only; chief decisions are stored separately.';
COMMENT ON COLUMN agent_runs.agent_code IS 'One of the six professional agent codes; CHIEF_DECISION is forbidden.';
COMMENT ON COLUMN agent_runs.veto IS 'Formal veto flag; only POSITION_RISK may set it true.';
COMMENT ON TABLE agent_evidence IS 'Authoritative evidence collection persisted for an agent-team task.';
COMMENT ON COLUMN agent_evidence.evidence_key IS 'Task-scoped evidence identifier referenced by findings; Java validates references.';
COMMENT ON TABLE agent_vetoes IS 'Formal POSITION_RISK vetoes; Java validates evidence and task/run ownership.';
COMMENT ON COLUMN agent_vetoes.evidence_ids IS 'Non-empty evidence-key references; Java validates their existence.';
COMMENT ON TABLE agent_decisions IS 'Independent chief final decision, never an agent_runs row.';
COMMENT ON COLUMN agent_decisions.source_run_ids IS 'Professional run identifiers; Java validates task ownership.';
COMMENT ON COLUMN agent_decisions.veto_ids IS 'Formal veto identifiers; Java validates task ownership and final-decision consistency.';
COMMENT ON TABLE security_events IS 'Read-only security announcements and risk events available to later agent analysis.';
COMMENT ON COLUMN security_events.announcement_no IS 'Provider announcement identifier; nullable values remain deduplicated by NULLS NOT DISTINCT index.';
COMMENT ON TABLE security_event_sync_runs IS 'Operational history for security-event provider synchronization.';
