-- Stock Quant Pro V1.0.1: current-as-of research selection history.

ALTER TABLE shadow_research_runs
    DROP CONSTRAINT ck_shadow_research_runs_trigger,
    ADD CONSTRAINT ck_shadow_research_runs_trigger CHECK (
        trigger_mode IN (
            'SCHEDULED', 'MANUAL', 'HISTORICAL_REPLAY',
            'ON_DEMAND_SELECTION'
        )
    ),
    DROP CONSTRAINT ck_shadow_research_runs_slot,
    ADD CONSTRAINT ck_shadow_research_runs_slot CHECK (
        research_slot IN (
            'AFTER_CLOSE', 'HISTORICAL_REPLAY'
        ) OR research_slot ~ '^ON_DEMAND_[A-F0-9]{12}$'
    ),
    DROP CONSTRAINT ck_shadow_research_runs_key,
    ADD CONSTRAINT ck_shadow_research_runs_key CHECK (
        run_key ~ '^SHADOW_[0-9]{8}_[A-Z0-9_]{2,64}_[0-9a-f]{16}$'
    );

CREATE TABLE research_selection_runs (
    id BIGSERIAL PRIMARY KEY,
    public_run_id VARCHAR(64) NOT NULL UNIQUE,
    broker_request_id VARCHAR(64) UNIQUE,
    contract_version VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    trigger_mode VARCHAR(32) NOT NULL,
    requested_at TIMESTAMPTZ(6) NOT NULL,
    research_as_of TIMESTAMPTZ(6) NOT NULL,
    anchor_trade_date DATE,
    primary_window INTEGER NOT NULL,
    auxiliary_window INTEGER NOT NULL,
    shortlist_limit INTEGER NOT NULL,
    final_limit INTEGER NOT NULL,
    paper_enabled BOOLEAN NOT NULL,
    universe_version VARCHAR(64) NOT NULL,
    ranking_version VARCHAR(64) NOT NULL,
    git_commit VARCHAR(40) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    result_json JSONB,
    result_fingerprint VARCHAR(64),
    shadow_run_id BIGINT REFERENCES shadow_research_runs(id) ON DELETE RESTRICT,
    failure_category VARCHAR(32),
    failure_reason VARCHAR(128),
    started_at TIMESTAMPTZ(6),
    completed_at TIMESTAMPTZ(6),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_research_selection_contract CHECK (
        contract_version = 'RESEARCH_SELECTION_V1'
        AND universe_version = 'RESEARCH_UNIVERSE_V1'
        AND ranking_version = 'RESEARCH_SELECTION_RANKING_V1'
    ),
    CONSTRAINT ck_research_selection_public_id CHECK (
        public_run_id ~ '^SELECT_[0-9]{8}T[0-9]{6}Z_[A-F0-9]{12}$'
    ),
    CONSTRAINT ck_research_selection_broker_id CHECK (
        broker_request_id IS NULL OR broker_request_id ~
        '^SQHB_[0-9]{8}T[0-9]{6}Z_[A-F0-9]{12}$'
    ),
    CONSTRAINT ck_research_selection_status CHECK (status IN (
        'QUEUED', 'PREPARING_DATA', 'QUANTITATIVE_SCAN',
        'STRATEGY_ANALYSIS', 'AI_RESEARCH', 'CRITIC_REVIEW',
        'COMPLETED', 'FAILED'
    )),
    CONSTRAINT ck_research_selection_trigger CHECK (
        trigger_mode IN ('ON_DEMAND', 'SCHEDULED_SHADOW')
    ),
    CONSTRAINT ck_research_selection_windows CHECK (
        primary_window IN (20, 60, 120, 250)
        AND auxiliary_window BETWEEN primary_window AND 250
        AND shortlist_limit BETWEEN 1 AND 10
        AND final_limit BETWEEN 1 AND 5
        AND final_limit <= shortlist_limit
    ),
    CONSTRAINT ck_research_selection_hashes CHECK (
        git_commit ~ '^[0-9a-f]{40}$'
        AND request_fingerprint ~ '^[0-9a-f]{64}$'
        AND (result_fingerprint IS NULL
             OR result_fingerprint ~ '^[0-9a-f]{64}$')
    ),
    CONSTRAINT ck_research_selection_result CHECK (
        (status = 'COMPLETED' AND result_json IS NOT NULL
            AND result_fingerprint IS NOT NULL AND completed_at IS NOT NULL
            AND failure_category IS NULL AND failure_reason IS NULL)
        OR (status = 'FAILED' AND result_json IS NOT NULL
            AND result_fingerprint IS NOT NULL AND completed_at IS NOT NULL
            AND failure_category IS NOT NULL AND failure_reason IS NOT NULL)
        OR (status NOT IN ('COMPLETED', 'FAILED') AND result_json IS NULL
            AND result_fingerprint IS NULL AND completed_at IS NULL
            AND failure_category IS NULL AND failure_reason IS NULL)
    ),
    CONSTRAINT ck_research_selection_json CHECK (
        result_json IS NULL OR jsonb_typeof(result_json) = 'object'
    )
);

CREATE UNIQUE INDEX uq_research_selection_active
    ON research_selection_runs (trigger_mode)
    WHERE status NOT IN ('COMPLETED', 'FAILED');
CREATE INDEX idx_research_selection_history
    ON research_selection_runs (created_at DESC, id DESC);

CREATE FUNCTION protect_research_selection_run()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'research selection history cannot be deleted';
    END IF;
    IF OLD.status IN ('COMPLETED', 'FAILED') THEN
        RAISE EXCEPTION 'terminal research selection is immutable';
    END IF;
    IF NEW.broker_request_id IS DISTINCT FROM OLD.broker_request_id AND NOT (
        OLD.broker_request_id IS NULL
        AND NEW.broker_request_id ~
            '^SQHB_[0-9]{8}T[0-9]{6}Z_[A-F0-9]{12}$'
    ) THEN
        RAISE EXCEPTION 'research selection broker binding is immutable';
    END IF;
    IF ROW(NEW.public_run_id, NEW.contract_version, NEW.trigger_mode,
           NEW.requested_at,
           NEW.primary_window, NEW.auxiliary_window,
           NEW.shortlist_limit, NEW.final_limit, NEW.paper_enabled,
           NEW.universe_version, NEW.ranking_version, NEW.git_commit,
           NEW.request_fingerprint, NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.public_run_id, OLD.contract_version, OLD.trigger_mode,
           OLD.requested_at,
           OLD.primary_window, OLD.auxiliary_window,
           OLD.shortlist_limit, OLD.final_limit, OLD.paper_enabled,
           OLD.universe_version, OLD.ranking_version, OLD.git_commit,
           OLD.request_fingerprint, OLD.created_at) THEN
        RAISE EXCEPTION 'research selection identity is immutable';
    END IF;
    IF NEW.research_as_of IS DISTINCT FROM OLD.research_as_of AND NOT (
        OLD.status = 'QUEUED' AND NEW.status = 'QUEUED'
        AND NEW.research_as_of >= OLD.research_as_of
        AND OLD.result_json IS NULL AND NEW.result_json IS NULL
    ) THEN
        RAISE EXCEPTION 'research selection as-of transition is invalid';
    END IF;
    IF NOT (
        NEW.status = OLD.status
        OR OLD.status = 'QUEUED' AND NEW.status IN ('PREPARING_DATA', 'FAILED')
        OR OLD.status = 'PREPARING_DATA' AND NEW.status IN ('QUANTITATIVE_SCAN', 'FAILED')
        OR OLD.status = 'QUANTITATIVE_SCAN' AND NEW.status IN ('STRATEGY_ANALYSIS', 'FAILED')
        OR OLD.status = 'STRATEGY_ANALYSIS' AND NEW.status IN ('AI_RESEARCH', 'FAILED')
        OR OLD.status = 'AI_RESEARCH' AND NEW.status IN ('CRITIC_REVIEW', 'FAILED')
        OR OLD.status = 'CRITIC_REVIEW' AND NEW.status IN ('COMPLETED', 'FAILED')
    ) THEN
        RAISE EXCEPTION 'invalid research selection transition: % -> %',
            OLD.status, NEW.status;
    END IF;
    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_research_selection_runs_protect
BEFORE UPDATE OR DELETE ON research_selection_runs
FOR EACH ROW EXECUTE FUNCTION protect_research_selection_run();

CREATE TRIGGER trg_research_selection_runs_no_truncate
BEFORE TRUNCATE ON research_selection_runs
FOR EACH STATEMENT EXECUTE FUNCTION reject_shadow_immutable_mutation();

COMMENT ON TABLE research_selection_runs IS
    'Append-only current-as-of V1.0.1 selection history; never historical live shadow.';
