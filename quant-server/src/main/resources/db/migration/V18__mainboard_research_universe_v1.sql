-- Stock Quant Pro V1.0.9: immutable Tushare main-board universe snapshots
-- and paged per-run eligibility evidence.

CREATE TABLE research_universe_snapshots (
    id BIGSERIAL PRIMARY KEY,
    snapshot_id VARCHAR(64) NOT NULL UNIQUE,
    universe_version VARCHAR(64) NOT NULL,
    member_count INTEGER NOT NULL,
    sse_count INTEGER NOT NULL,
    szse_count INTEGER NOT NULL,
    st_count INTEGER NOT NULL,
    observed_at TIMESTAMPTZ(6) NOT NULL,
    effective_date DATE NOT NULL,
    source VARCHAR(64) NOT NULL,
    source_fingerprint VARCHAR(64) NOT NULL,
    member_fingerprint VARCHAR(64) NOT NULL,
    git_commit VARCHAR(40) NOT NULL,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_research_universe_snapshot_contract CHECK (
        universe_version = 'RESEARCH_UNIVERSE_MAINBOARD_V1'
        AND source = 'TUSHARE_STOCK_BASIC'
        AND member_count >= 1000
        AND member_count = sse_count + szse_count
        AND sse_count > 0 AND szse_count > 0
        AND st_count BETWEEN 0 AND member_count
        AND snapshot_id ~
            '^MAINBOARD_[0-9]{8}T[0-9]{12}Z_[0-9a-f]{12}$'
        AND source_fingerprint ~ '^[0-9a-f]{64}$'
        AND member_fingerprint ~ '^[0-9a-f]{64}$'
        AND git_commit ~ '^[0-9a-f]{40}$'
    )
);

CREATE INDEX idx_research_universe_snapshot_membership
    ON research_universe_snapshots (universe_version, member_fingerprint);
CREATE INDEX idx_research_universe_snapshot_latest
    ON research_universe_snapshots (observed_at DESC, id DESC);

CREATE TABLE research_universe_members (
    snapshot_db_id BIGINT NOT NULL REFERENCES research_universe_snapshots(id)
        ON DELETE RESTRICT,
    ts_code VARCHAR(16) NOT NULL,
    symbol VARCHAR(6) NOT NULL,
    exchange VARCHAR(8) NOT NULL,
    name VARCHAR(128) NOT NULL,
    industry VARCHAR(128) NOT NULL,
    market VARCHAR(32) NOT NULL,
    list_status VARCHAR(8) NOT NULL,
    list_date DATE NOT NULL,
    delist_date DATE,
    snapshot_observed_at TIMESTAMPTZ(6) NOT NULL,
    source VARCHAR(64) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    st_security BOOLEAN NOT NULL,
    PRIMARY KEY (snapshot_db_id, ts_code),
    CONSTRAINT uq_research_universe_member_identity UNIQUE (
        snapshot_db_id, symbol, exchange
    ),
    CONSTRAINT ck_research_universe_member_contract CHECK (
        ts_code ~ '^[0-9]{6}\.(SH|SZ)$'
        AND symbol ~ '^[0-9]{6}$'
        AND exchange IN ('SSE', 'SZSE')
        AND market = '主板'
        AND list_status = 'L'
        AND source = 'TUSHARE_STOCK_BASIC'
        AND content_hash ~ '^[0-9a-f]{64}$'
        AND (delist_date IS NULL OR delist_date >= list_date)
    )
);

CREATE INDEX idx_research_universe_members_security
    ON research_universe_members (snapshot_db_id, exchange, symbol);

CREATE TABLE research_universe_snapshot_observations (
    id BIGSERIAL PRIMARY KEY,
    snapshot_db_id BIGINT NOT NULL REFERENCES research_universe_snapshots(id)
        ON DELETE RESTRICT,
    observed_at TIMESTAMPTZ(6) NOT NULL,
    source_fingerprint VARCHAR(64) NOT NULL,
    git_commit VARCHAR(40) NOT NULL,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_research_universe_snapshot_observation UNIQUE (
        snapshot_db_id, observed_at, source_fingerprint
    ),
    CONSTRAINT ck_research_universe_observation_contract CHECK (
        source_fingerprint ~ '^[0-9a-f]{64}$'
        AND git_commit ~ '^[0-9a-f]{40}$'
    )
);

CREATE INDEX idx_research_universe_observation_latest
    ON research_universe_snapshot_observations (
        snapshot_db_id, observed_at DESC, id DESC
    );

ALTER TABLE research_selection_runs
    ADD COLUMN universe_snapshot_db_id BIGINT
        REFERENCES research_universe_snapshots(id) ON DELETE RESTRICT;

ALTER TABLE research_selection_runs
    ADD CONSTRAINT uq_research_selection_run_snapshot
        UNIQUE (id, universe_snapshot_db_id);

ALTER TABLE research_selection_runs
    DROP CONSTRAINT ck_research_selection_contract,
    ADD CONSTRAINT ck_research_selection_contract CHECK (
        contract_version = 'RESEARCH_SELECTION_V1'
        AND universe_version IN (
            'RESEARCH_UNIVERSE_V1',
            'RESEARCH_UNIVERSE_MAINBOARD_V1'
        )
        AND ranking_version = 'RESEARCH_SELECTION_RANKING_V1'
        AND (
            universe_version = 'RESEARCH_UNIVERSE_V1'
                AND universe_snapshot_db_id IS NULL
            OR universe_version = 'RESEARCH_UNIVERSE_MAINBOARD_V1'
                AND (universe_snapshot_db_id IS NOT NULL
                     OR status <> 'COMPLETED')
        )
    );

CREATE OR REPLACE FUNCTION protect_research_selection_run()
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
    IF NEW.universe_snapshot_db_id IS DISTINCT FROM
            OLD.universe_snapshot_db_id AND NOT (
        OLD.universe_snapshot_db_id IS NULL
        AND NEW.universe_snapshot_db_id IS NOT NULL
        AND OLD.status = 'QUEUED' AND NEW.status = 'QUEUED'
        AND NEW.universe_version = 'RESEARCH_UNIVERSE_MAINBOARD_V1'
    ) THEN
        RAISE EXCEPTION 'research selection universe binding is immutable';
    END IF;
    IF ROW(NEW.public_run_id, NEW.contract_version, NEW.trigger_mode,
           NEW.requested_at, NEW.primary_window, NEW.auxiliary_window,
           NEW.shortlist_limit, NEW.final_limit, NEW.paper_enabled,
           NEW.universe_version, NEW.ranking_version, NEW.git_commit,
           NEW.request_fingerprint, NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.public_run_id, OLD.contract_version, OLD.trigger_mode,
           OLD.requested_at, OLD.primary_window, OLD.auxiliary_window,
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

CREATE TABLE research_selection_member_results (
    run_id BIGINT NOT NULL REFERENCES research_selection_runs(id)
        ON DELETE RESTRICT,
    snapshot_db_id BIGINT NOT NULL REFERENCES research_universe_snapshots(id)
        ON DELETE RESTRICT,
    ts_code VARCHAR(16) NOT NULL,
    symbol VARCHAR(6) NOT NULL,
    exchange VARCHAR(8) NOT NULL,
    name VARCHAR(128) NOT NULL,
    industry VARCHAR(128) NOT NULL,
    eligibility_status VARCHAR(32) NOT NULL,
    exclusion_reasons TEXT[] NOT NULL,
    available_sessions INTEGER NOT NULL,
    missing_daily INTEGER NOT NULL,
    missing_adj_factor INTEGER NOT NULL,
    average_traded_amount NUMERIC(24, 8),
    basic_rank INTEGER,
    basic_score NUMERIC(12, 4),
    historical_rank INTEGER,
    stability_score NUMERIC(12, 4),
    historical_grade VARCHAR(8),
    strategy_rank INTEGER,
    agent_selected BOOLEAN NOT NULL,
    final_candidate BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (run_id, ts_code),
    FOREIGN KEY (run_id, snapshot_db_id)
        REFERENCES research_selection_runs(id, universe_snapshot_db_id)
        ON DELETE RESTRICT,
    FOREIGN KEY (snapshot_db_id, ts_code)
        REFERENCES research_universe_members(snapshot_db_id, ts_code)
        ON DELETE RESTRICT,
    CONSTRAINT ck_research_selection_member_status CHECK (
        eligibility_status IN ('ELIGIBLE', 'EXCLUDED')
        AND available_sessions BETWEEN 0 AND 250
        AND missing_daily >= 0
        AND missing_adj_factor >= 0
        AND (historical_grade IS NULL
             OR historical_grade IN ('A', 'B', 'C'))
        AND (basic_rank IS NULL OR basic_rank > 0)
        AND (historical_rank IS NULL OR historical_rank > 0)
        AND (strategy_rank IS NULL OR strategy_rank > 0)
        AND (NOT final_candidate OR agent_selected)
    )
);

CREATE INDEX idx_research_selection_member_page
    ON research_selection_member_results (
        run_id, eligibility_status, basic_rank NULLS LAST, ts_code
    );

CREATE TRIGGER trg_research_universe_snapshots_immutable
BEFORE UPDATE OR DELETE ON research_universe_snapshots
FOR EACH ROW EXECUTE FUNCTION reject_shadow_immutable_mutation();
CREATE TRIGGER trg_research_universe_members_immutable
BEFORE UPDATE OR DELETE ON research_universe_members
FOR EACH ROW EXECUTE FUNCTION reject_shadow_immutable_mutation();
CREATE TRIGGER trg_research_universe_observations_immutable
BEFORE UPDATE OR DELETE ON research_universe_snapshot_observations
FOR EACH ROW EXECUTE FUNCTION reject_shadow_immutable_mutation();
CREATE TRIGGER trg_research_selection_members_immutable
BEFORE UPDATE OR DELETE ON research_selection_member_results
FOR EACH ROW EXECUTE FUNCTION reject_shadow_immutable_mutation();

CREATE TRIGGER trg_research_universe_snapshots_no_truncate
BEFORE TRUNCATE ON research_universe_snapshots
FOR EACH STATEMENT EXECUTE FUNCTION reject_shadow_immutable_mutation();
CREATE TRIGGER trg_research_universe_members_no_truncate
BEFORE TRUNCATE ON research_universe_members
FOR EACH STATEMENT EXECUTE FUNCTION reject_shadow_immutable_mutation();
CREATE TRIGGER trg_research_universe_observations_no_truncate
BEFORE TRUNCATE ON research_universe_snapshot_observations
FOR EACH STATEMENT EXECUTE FUNCTION reject_shadow_immutable_mutation();
CREATE TRIGGER trg_research_selection_members_no_truncate
BEFORE TRUNCATE ON research_selection_member_results
FOR EACH STATEMENT EXECUTE FUNCTION reject_shadow_immutable_mutation();

COMMENT ON TABLE research_universe_snapshots IS
    'Immutable stock_basic-derived main-board universe snapshots; membership changes append a new snapshot.';
COMMENT ON TABLE research_universe_members IS
    'Immutable members of one main-board snapshot, including ST securities; eligibility is evaluated per run.';
COMMENT ON TABLE research_universe_snapshot_observations IS
    'Append-only successful stock_basic refresh evidence; unchanged membership never mutates or duplicates a snapshot.';
COMMENT ON TABLE research_selection_member_results IS
    'Immutable paged per-security eligibility and funnel evidence for one completed V1.0.9 selection run.';
