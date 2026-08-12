-- Stock Quant Pro 1.4.0 M4: immutable shadow research and paper portfolio.

CREATE TABLE shadow_research_runs (
    id BIGSERIAL PRIMARY KEY,
    run_key VARCHAR(128) NOT NULL,
    attempt INTEGER NOT NULL,
    run_version VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    trigger_mode VARCHAR(32) NOT NULL,
    trade_date DATE NOT NULL,
    research_slot VARCHAR(32) NOT NULL,
    research_as_of TIMESTAMPTZ(6) NOT NULL,
    signal_time TIMESTAMPTZ(6),
    paper_execution_time TIMESTAMPTZ(6),
    strategy_version VARCHAR(64) NOT NULL,
    model_provider VARCHAR(32) NOT NULL,
    model VARCHAR(128) NOT NULL,
    prompt_version VARCHAR(64) NOT NULL,
    agent_runtime_version VARCHAR(64) NOT NULL,
    dataset_fingerprint VARCHAR(64),
    strategy_fingerprint VARCHAR(64),
    research_fingerprint VARCHAR(64),
    request_fingerprint VARCHAR(64) NOT NULL,
    error_code VARCHAR(128),
    started_at TIMESTAMPTZ(6),
    completed_at TIMESTAMPTZ(6),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_shadow_research_runs_key_attempt UNIQUE (run_key, attempt),
    CONSTRAINT ck_shadow_research_runs_key CHECK (
        run_key ~ '^SHADOW_[0-9]{8}_[A-Z0-9_]{2,48}_[0-9a-f]{16}$'
    ),
    CONSTRAINT ck_shadow_research_runs_attempt CHECK (attempt > 0),
    CONSTRAINT ck_shadow_research_runs_version CHECK (
        run_version = 'SHADOW_RESEARCH_RUNTIME_V1'
    ),
    CONSTRAINT ck_shadow_research_runs_status CHECK (status IN (
        'QUEUED', 'RUNNING', 'FROZEN', 'FAILED', 'INTERRUPTED'
    )),
    CONSTRAINT ck_shadow_research_runs_trigger CHECK (
        trigger_mode IN ('SCHEDULED', 'MANUAL', 'HISTORICAL_REPLAY')
    ),
    CONSTRAINT ck_shadow_research_runs_slot CHECK (
        research_slot IN ('AFTER_CLOSE', 'HISTORICAL_REPLAY')
    ),
    CONSTRAINT ck_shadow_research_runs_times CHECK (
        signal_time IS NULL OR signal_time >= research_as_of
    ),
    CONSTRAINT ck_shadow_research_runs_execution_time CHECK (
        paper_execution_time IS NULL OR (
            signal_time IS NOT NULL AND paper_execution_time > signal_time
        )
    ),
    CONSTRAINT ck_shadow_research_runs_hashes CHECK (
        request_fingerprint ~ '^[0-9a-f]{64}$'
        AND (dataset_fingerprint IS NULL OR dataset_fingerprint ~ '^[0-9a-f]{64}$')
        AND (strategy_fingerprint IS NULL OR strategy_fingerprint ~ '^[0-9a-f]{64}$')
        AND (research_fingerprint IS NULL OR research_fingerprint ~ '^[0-9a-f]{64}$')
    ),
    CONSTRAINT ck_shadow_research_runs_lifecycle CHECK (
        (status = 'QUEUED' AND started_at IS NULL AND completed_at IS NULL)
        OR (status = 'RUNNING' AND started_at IS NOT NULL AND completed_at IS NULL)
        OR (status IN ('FROZEN', 'FAILED', 'INTERRUPTED')
            AND started_at IS NOT NULL AND completed_at IS NOT NULL)
    ),
    CONSTRAINT ck_shadow_research_runs_frozen CHECK (
        status <> 'FROZEN' OR (
            dataset_fingerprint IS NOT NULL
            AND strategy_fingerprint IS NOT NULL
            AND research_fingerprint IS NOT NULL
            AND signal_time IS NOT NULL
        )
    ),
    CONSTRAINT ck_shadow_research_runs_failure CHECK (
        status NOT IN ('FAILED', 'INTERRUPTED')
        OR (error_code IS NOT NULL AND btrim(error_code) <> '')
    )
);

CREATE UNIQUE INDEX uq_shadow_research_runs_one_frozen_slot
    ON shadow_research_runs (trade_date, research_slot, strategy_version)
    WHERE status = 'FROZEN';
CREATE UNIQUE INDEX uq_shadow_research_runs_claimable_slot
    ON shadow_research_runs (trade_date, research_slot, strategy_version)
    WHERE status IN ('QUEUED', 'RUNNING', 'FROZEN');
CREATE INDEX idx_shadow_research_runs_status
    ON shadow_research_runs (status, id);
CREATE INDEX idx_shadow_research_runs_trade_date
    ON shadow_research_runs (trade_date DESC, id DESC);

CREATE TABLE shadow_scheduler_dispatches (
    id BIGSERIAL PRIMARY KEY,
    trade_date DATE NOT NULL,
    research_slot VARCHAR(32) NOT NULL,
    strategy_version VARCHAR(64) NOT NULL,
    scheduler_version VARCHAR(64) NOT NULL,
    request_id VARCHAR(64) NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL,
    research_as_of TIMESTAMPTZ(6) NOT NULL,
    failure_code VARCHAR(128),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT clock_timestamp(),
    completed_at TIMESTAMPTZ(6),
    CONSTRAINT uq_shadow_scheduler_dispatch_slot UNIQUE (
        trade_date, research_slot, strategy_version
    ),
    CONSTRAINT ck_shadow_scheduler_dispatch_slot CHECK (
        research_slot = 'AFTER_CLOSE'
    ),
    CONSTRAINT ck_shadow_scheduler_dispatch_version CHECK (
        scheduler_version = 'SHADOW_SCHEDULER_V1'
    ),
    CONSTRAINT ck_shadow_scheduler_dispatch_request CHECK (
        request_id ~ '^SQHB_[0-9]{8}T[0-9]{6}Z_[A-F0-9]{12}$'
    ),
    CONSTRAINT ck_shadow_scheduler_dispatch_status CHECK (
        status IN ('CLAIMED', 'SUBMITTED', 'FAILED')
    ),
    CONSTRAINT ck_shadow_scheduler_dispatch_terminal CHECK (
        (status = 'CLAIMED' AND completed_at IS NULL
            AND failure_code IS NULL)
        OR (status = 'SUBMITTED' AND completed_at IS NOT NULL
            AND failure_code IS NULL)
        OR (status = 'FAILED' AND completed_at IS NOT NULL
            AND failure_code IS NOT NULL)
    )
);

CREATE INDEX idx_shadow_scheduler_dispatch_status
    ON shadow_scheduler_dispatches (status, created_at);

CREATE TABLE shadow_research_snapshots (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL UNIQUE REFERENCES shadow_research_runs(id)
        ON DELETE RESTRICT,
    snapshot_version VARCHAR(64) NOT NULL,
    report_json JSONB NOT NULL,
    recommendation_json JSONB NOT NULL,
    evidence_json JSONB NOT NULL,
    agent_findings_json JSONB NOT NULL,
    critic_json JSONB NOT NULL,
    limitations_json JSONB NOT NULL,
    snapshot_fingerprint VARCHAR(64) NOT NULL UNIQUE,
    frozen_at TIMESTAMPTZ(6) NOT NULL,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_shadow_research_snapshots_version CHECK (
        snapshot_version = 'SHADOW_SNAPSHOT_V1'
    ),
    CONSTRAINT ck_shadow_research_snapshots_json CHECK (
        jsonb_typeof(report_json) = 'object'
        AND jsonb_typeof(recommendation_json) = 'object'
        AND jsonb_typeof(evidence_json) = 'array'
        AND jsonb_typeof(agent_findings_json) = 'array'
        AND jsonb_typeof(critic_json) = 'object'
        AND jsonb_typeof(limitations_json) = 'array'
    ),
    CONSTRAINT ck_shadow_research_snapshots_hash CHECK (
        snapshot_fingerprint ~ '^[0-9a-f]{64}$'
    )
);

CREATE TABLE shadow_paper_portfolios (
    id BIGSERIAL PRIMARY KEY,
    portfolio_code VARCHAR(64) NOT NULL UNIQUE,
    portfolio_version VARCHAR(64) NOT NULL,
    initial_cash NUMERIC(24,8) NOT NULL,
    cash NUMERIC(24,8) NOT NULL,
    realized_pnl NUMERIC(24,8) NOT NULL DEFAULT 0,
    total_fees NUMERIC(24,8) NOT NULL DEFAULT 0,
    state_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_shadow_paper_portfolios_version CHECK (
        portfolio_version = 'PAPER_PORTFOLIO_V1'
    ),
    CONSTRAINT ck_shadow_paper_portfolios_values CHECK (
        initial_cash > 0 AND cash >= 0 AND total_fees >= 0
        AND state_version >= 0
    )
);

INSERT INTO shadow_paper_portfolios (
    portfolio_code, portfolio_version, initial_cash, cash
) VALUES (
    'M4_SHADOW_PAPER', 'PAPER_PORTFOLIO_V1', 1000000, 1000000
) ON CONFLICT (portfolio_code) DO NOTHING;

CREATE TABLE shadow_paper_positions (
    portfolio_id BIGINT NOT NULL REFERENCES shadow_paper_portfolios(id)
        ON DELETE RESTRICT,
    symbol VARCHAR(6) NOT NULL,
    exchange VARCHAR(16) NOT NULL,
    quantity INTEGER NOT NULL,
    available_quantity INTEGER NOT NULL,
    average_cost NUMERIC(24,8) NOT NULL,
    last_price NUMERIC(24,8) NOT NULL,
    last_buy_date DATE,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (portfolio_id, symbol, exchange),
    CONSTRAINT ck_shadow_paper_positions_identity CHECK (
        symbol ~ '^[0-9]{6}$' AND exchange IN ('SSE', 'SZSE')
    ),
    CONSTRAINT ck_shadow_paper_positions_values CHECK (
        quantity > 0 AND available_quantity BETWEEN 0 AND quantity
        AND average_cost > 0 AND last_price > 0
    )
);

CREATE TABLE shadow_paper_orders (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES shadow_research_runs(id)
        ON DELETE RESTRICT,
    portfolio_id BIGINT NOT NULL REFERENCES shadow_paper_portfolios(id)
        ON DELETE RESTRICT,
    order_key VARCHAR(128) NOT NULL UNIQUE,
    side VARCHAR(8) NOT NULL,
    symbol VARCHAR(6) NOT NULL,
    exchange VARCHAR(16) NOT NULL,
    signal_time TIMESTAMPTZ(6) NOT NULL,
    earliest_execution_time TIMESTAMPTZ(6) NOT NULL,
    target_weight NUMERIC(16,12) NOT NULL,
    status VARCHAR(32) NOT NULL,
    rejection_reason VARCHAR(128),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_shadow_paper_orders_side CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT ck_shadow_paper_orders_identity CHECK (
        symbol ~ '^[0-9]{6}$' AND exchange IN ('SSE', 'SZSE')
    ),
    CONSTRAINT ck_shadow_paper_orders_status CHECK (status IN (
        'PENDING', 'FILLED', 'REJECTED'
    )),
    CONSTRAINT ck_shadow_paper_orders_time CHECK (
        earliest_execution_time > signal_time
    ),
    CONSTRAINT ck_shadow_paper_orders_weight CHECK (
        target_weight BETWEEN 0 AND 1
    ),
    CONSTRAINT ck_shadow_paper_orders_rejection CHECK (
        status <> 'REJECTED'
        OR (rejection_reason IS NOT NULL AND btrim(rejection_reason) <> '')
    )
);

CREATE INDEX idx_shadow_paper_orders_pending
    ON shadow_paper_orders (earliest_execution_time, id)
    WHERE status = 'PENDING';

CREATE TABLE shadow_paper_fills (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL UNIQUE REFERENCES shadow_paper_orders(id)
        ON DELETE RESTRICT,
    portfolio_id BIGINT NOT NULL REFERENCES shadow_paper_portfolios(id)
        ON DELETE RESTRICT,
    run_id BIGINT NOT NULL REFERENCES shadow_research_runs(id)
        ON DELETE RESTRICT,
    execution_date DATE NOT NULL,
    execution_time TIMESTAMPTZ(6) NOT NULL,
    reference_price NUMERIC(24,8) NOT NULL,
    execution_price NUMERIC(24,8) NOT NULL,
    quantity INTEGER NOT NULL,
    gross_amount NUMERIC(24,8) NOT NULL,
    commission NUMERIC(24,8) NOT NULL,
    stamp_duty NUMERIC(24,8) NOT NULL,
    slippage_cost NUMERIC(24,8) NOT NULL,
    realized_pnl NUMERIC(24,8) NOT NULL,
    cash_after NUMERIC(24,8) NOT NULL,
    position_after INTEGER NOT NULL,
    fill_fingerprint VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_shadow_paper_fills_values CHECK (
        reference_price > 0 AND execution_price > 0 AND quantity > 0
        AND gross_amount > 0 AND commission >= 0 AND stamp_duty >= 0
        AND slippage_cost >= 0 AND cash_after >= 0 AND position_after >= 0
    ),
    CONSTRAINT ck_shadow_paper_fills_hash CHECK (
        fill_fingerprint ~ '^[0-9a-f]{64}$'
    )
);

CREATE TABLE shadow_portfolio_snapshots (
    id BIGSERIAL PRIMARY KEY,
    portfolio_id BIGINT NOT NULL REFERENCES shadow_paper_portfolios(id)
        ON DELETE RESTRICT,
    run_id BIGINT REFERENCES shadow_research_runs(id) ON DELETE RESTRICT,
    snapshot_date DATE NOT NULL,
    snapshot_time TIMESTAMPTZ(6) NOT NULL,
    cash NUMERIC(24,8) NOT NULL,
    market_value NUMERIC(24,8) NOT NULL,
    total_equity NUMERIC(24,8) NOT NULL,
    realized_pnl NUMERIC(24,8) NOT NULL,
    unrealized_pnl NUMERIC(24,8) NOT NULL,
    total_fees NUMERIC(24,8) NOT NULL,
    total_return NUMERIC(20,12) NOT NULL,
    position_count INTEGER NOT NULL,
    snapshot_fingerprint VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_shadow_portfolio_snapshots_values CHECK (
        cash >= 0 AND market_value >= 0 AND total_equity >= 0
        AND total_fees >= 0 AND position_count >= 0
    ),
    CONSTRAINT ck_shadow_portfolio_snapshots_hash CHECK (
        snapshot_fingerprint ~ '^[0-9a-f]{64}$'
    )
);

CREATE TABLE shadow_outcomes (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES shadow_research_runs(id)
        ON DELETE RESTRICT,
    horizon_code VARCHAR(16) NOT NULL,
    evaluation_date DATE NOT NULL,
    outcome_json JSONB NOT NULL,
    outcome_fingerprint VARCHAR(64) NOT NULL UNIQUE,
    evaluated_at TIMESTAMPTZ(6) NOT NULL,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_shadow_outcomes_run_horizon UNIQUE (run_id, horizon_code),
    CONSTRAINT ck_shadow_outcomes_horizon CHECK (
        horizon_code IN ('D1', 'D5', 'D20')
    ),
    CONSTRAINT ck_shadow_outcomes_json CHECK (
        jsonb_typeof(outcome_json) = 'object'
    ),
    CONSTRAINT ck_shadow_outcomes_hash CHECK (
        outcome_fingerprint ~ '^[0-9a-f]{64}$'
    )
);

CREATE FUNCTION protect_shadow_research_run()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'shadow research runs are audit facts and cannot be deleted';
    END IF;
    IF OLD.status IN ('FROZEN', 'FAILED', 'INTERRUPTED') THEN
        RAISE EXCEPTION 'terminal shadow research runs are immutable';
    END IF;
    IF ROW(NEW.run_key, NEW.attempt, NEW.run_version, NEW.trigger_mode,
           NEW.trade_date, NEW.research_slot, NEW.research_as_of,
           NEW.strategy_version, NEW.model_provider, NEW.model,
           NEW.prompt_version, NEW.agent_runtime_version,
           NEW.request_fingerprint, NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.run_key, OLD.attempt, OLD.run_version, OLD.trigger_mode,
           OLD.trade_date, OLD.research_slot, OLD.research_as_of,
           OLD.strategy_version, OLD.model_provider, OLD.model,
           OLD.prompt_version, OLD.agent_runtime_version,
           OLD.request_fingerprint, OLD.created_at) THEN
        RAISE EXCEPTION 'shadow research run identity is immutable';
    END IF;
    IF NOT (
        NEW.status = OLD.status
        OR (OLD.status = 'QUEUED' AND NEW.status IN ('RUNNING', 'FAILED', 'INTERRUPTED'))
        OR (OLD.status = 'RUNNING' AND NEW.status IN ('FROZEN', 'FAILED', 'INTERRUPTED'))
    ) THEN
        RAISE EXCEPTION 'invalid shadow research run transition: % -> %', OLD.status, NEW.status;
    END IF;
    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE FUNCTION reject_shadow_immutable_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only and cannot be %', TG_TABLE_NAME, lower(TG_OP);
END;
$$;

CREATE FUNCTION protect_shadow_paper_order()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'shadow paper orders cannot be deleted';
    END IF;
    IF OLD.status IN ('FILLED', 'REJECTED') THEN
        RAISE EXCEPTION 'terminal shadow paper orders are immutable';
    END IF;
    IF ROW(NEW.run_id, NEW.portfolio_id, NEW.order_key, NEW.side,
           NEW.symbol, NEW.exchange, NEW.signal_time,
           NEW.earliest_execution_time, NEW.target_weight, NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.run_id, OLD.portfolio_id, OLD.order_key, OLD.side,
           OLD.symbol, OLD.exchange, OLD.signal_time,
           OLD.earliest_execution_time, OLD.target_weight, OLD.created_at)
       OR NEW.status NOT IN ('PENDING', 'FILLED', 'REJECTED') THEN
        RAISE EXCEPTION 'shadow paper order identity or transition is invalid';
    END IF;
    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE FUNCTION protect_shadow_scheduler_dispatch()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'shadow scheduler dispatches cannot be deleted';
    END IF;
    IF OLD.status <> 'CLAIMED'
       OR NEW.status NOT IN ('SUBMITTED', 'FAILED')
       OR ROW(NEW.trade_date, NEW.research_slot, NEW.strategy_version,
              NEW.scheduler_version, NEW.request_id, NEW.research_as_of,
              NEW.created_at)
          IS DISTINCT FROM
          ROW(OLD.trade_date, OLD.research_slot, OLD.strategy_version,
              OLD.scheduler_version, OLD.request_id, OLD.research_as_of,
              OLD.created_at) THEN
        RAISE EXCEPTION 'shadow scheduler dispatch transition is invalid';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION protect_shadow_paper_portfolio()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'shadow paper portfolio cannot be deleted';
    END IF;
    IF ROW(NEW.portfolio_code, NEW.portfolio_version, NEW.initial_cash,
           NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.portfolio_code, OLD.portfolio_version, OLD.initial_cash,
           OLD.created_at)
       OR NEW.state_version <> OLD.state_version + 1
       OR NEW.cash < 0 OR NEW.total_fees < OLD.total_fees THEN
        RAISE EXCEPTION 'shadow paper portfolio identity or transition is invalid';
    END IF;
    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE FUNCTION protect_shadow_paper_position()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND ROW(NEW.portfolio_id, NEW.symbol, NEW.exchange)
       IS DISTINCT FROM ROW(OLD.portfolio_id, OLD.symbol, OLD.exchange) THEN
        RAISE EXCEPTION 'shadow paper position identity is immutable';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$;

CREATE TRIGGER trg_shadow_research_runs_protect
BEFORE UPDATE OR DELETE ON shadow_research_runs
FOR EACH ROW EXECUTE FUNCTION protect_shadow_research_run();

CREATE TRIGGER trg_shadow_research_snapshots_immutable
BEFORE UPDATE OR DELETE ON shadow_research_snapshots
FOR EACH ROW EXECUTE FUNCTION reject_shadow_immutable_mutation();
CREATE TRIGGER trg_shadow_scheduler_dispatches_protect
BEFORE UPDATE OR DELETE ON shadow_scheduler_dispatches
FOR EACH ROW EXECUTE FUNCTION protect_shadow_scheduler_dispatch();
CREATE TRIGGER trg_shadow_paper_orders_protect
BEFORE UPDATE OR DELETE ON shadow_paper_orders
FOR EACH ROW EXECUTE FUNCTION protect_shadow_paper_order();
CREATE TRIGGER trg_shadow_paper_portfolios_protect
BEFORE UPDATE OR DELETE ON shadow_paper_portfolios
FOR EACH ROW EXECUTE FUNCTION protect_shadow_paper_portfolio();
CREATE TRIGGER trg_shadow_paper_positions_protect
BEFORE UPDATE OR DELETE ON shadow_paper_positions
FOR EACH ROW EXECUTE FUNCTION protect_shadow_paper_position();
CREATE TRIGGER trg_shadow_paper_fills_immutable
BEFORE UPDATE OR DELETE ON shadow_paper_fills
FOR EACH ROW EXECUTE FUNCTION reject_shadow_immutable_mutation();
CREATE TRIGGER trg_shadow_portfolio_snapshots_immutable
BEFORE UPDATE OR DELETE ON shadow_portfolio_snapshots
FOR EACH ROW EXECUTE FUNCTION reject_shadow_immutable_mutation();
CREATE TRIGGER trg_shadow_outcomes_immutable
BEFORE UPDATE OR DELETE ON shadow_outcomes
FOR EACH ROW EXECUTE FUNCTION reject_shadow_immutable_mutation();

CREATE TRIGGER trg_shadow_research_runs_no_truncate
BEFORE TRUNCATE ON shadow_research_runs
FOR EACH STATEMENT EXECUTE FUNCTION reject_shadow_immutable_mutation();
CREATE TRIGGER trg_shadow_research_snapshots_no_truncate
BEFORE TRUNCATE ON shadow_research_snapshots
FOR EACH STATEMENT EXECUTE FUNCTION reject_shadow_immutable_mutation();
CREATE TRIGGER trg_shadow_scheduler_dispatches_no_truncate
BEFORE TRUNCATE ON shadow_scheduler_dispatches
FOR EACH STATEMENT EXECUTE FUNCTION reject_shadow_immutable_mutation();
CREATE TRIGGER trg_shadow_paper_orders_no_truncate
BEFORE TRUNCATE ON shadow_paper_orders
FOR EACH STATEMENT EXECUTE FUNCTION reject_shadow_immutable_mutation();
CREATE TRIGGER trg_shadow_paper_portfolios_no_truncate
BEFORE TRUNCATE ON shadow_paper_portfolios
FOR EACH STATEMENT EXECUTE FUNCTION reject_shadow_immutable_mutation();
CREATE TRIGGER trg_shadow_paper_positions_no_truncate
BEFORE TRUNCATE ON shadow_paper_positions
FOR EACH STATEMENT EXECUTE FUNCTION reject_shadow_immutable_mutation();
CREATE TRIGGER trg_shadow_paper_fills_no_truncate
BEFORE TRUNCATE ON shadow_paper_fills
FOR EACH STATEMENT EXECUTE FUNCTION reject_shadow_immutable_mutation();
CREATE TRIGGER trg_shadow_portfolio_snapshots_no_truncate
BEFORE TRUNCATE ON shadow_portfolio_snapshots
FOR EACH STATEMENT EXECUTE FUNCTION reject_shadow_immutable_mutation();
CREATE TRIGGER trg_shadow_outcomes_no_truncate
BEFORE TRUNCATE ON shadow_outcomes
FOR EACH STATEMENT EXECUTE FUNCTION reject_shadow_immutable_mutation();

COMMENT ON TABLE shadow_research_runs IS
    'M4 bounded shadow research attempts; terminal rows are immutable.';
COMMENT ON TABLE shadow_research_snapshots IS
    'Frozen M3 report, evidence, critic and recommendation as known at research_as_of.';
COMMENT ON TABLE shadow_paper_orders IS
    'Research-only paper intents; no broker connectivity or real-order semantics.';
COMMENT ON TABLE shadow_paper_fills IS
    'Deterministic simulated fills at the next legal session; append-only.';
