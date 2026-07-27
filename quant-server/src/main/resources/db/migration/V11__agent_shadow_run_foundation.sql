-- Stock Quant Pro 1.4.0 stage 3A-1:
-- controlled shadow execution and readiness observation foundation.
-- Frozen contracts:
-- SHADOW_RUN_CONTROL_V1
-- SHADOW_SELECTION_V1
-- SHADOW_OUTCOME_SNAPSHOT_V1
-- SHADOW_REVIEW_V1
-- SHADOW_METRICS_V1

ALTER TABLE agent_tasks
    DROP CONSTRAINT ck_agent_tasks_trigger_type;

ALTER TABLE agent_tasks
    ADD CONSTRAINT ck_agent_tasks_trigger_type CHECK (trigger_type IN (
        'MANUAL', 'SCAN_CANDIDATE', 'SCHEDULED', 'RETRY', 'SHADOW'
    ));

CREATE TABLE agent_shadow_batches (
    id BIGSERIAL PRIMARY KEY,
    contract_version VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    trigger_mode VARCHAR(32) NOT NULL,
    trade_date DATE NOT NULL,
    rule_version VARCHAR(64) NOT NULL,
    selection_mode VARCHAR(32) NOT NULL,
    selection_hash VARCHAR(64) NOT NULL,
    configured_max_symbols INTEGER NOT NULL,
    selected_count INTEGER NOT NULL,
    launched_count INTEGER NOT NULL DEFAULT 0,
    terminal_count INTEGER NOT NULL DEFAULT 0,
    determined_count INTEGER NOT NULL DEFAULT 0,
    insufficient_count INTEGER NOT NULL DEFAULT 0,
    failed_count INTEGER NOT NULL DEFAULT 0,
    veto_count INTEGER NOT NULL DEFAULT 0,
    data_quality_blocked_count INTEGER NOT NULL DEFAULT 0,
    cache_hit_count INTEGER NOT NULL DEFAULT 0,
    cancellation_requested BOOLEAN NOT NULL DEFAULT FALSE,
    configuration_json JSONB NOT NULL,
    error_message TEXT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_agent_shadow_batches_contract CHECK (
        contract_version = 'SHADOW_RUN_CONTROL_V1'
    ),
    CONSTRAINT ck_agent_shadow_batches_status CHECK (status IN (
        'QUEUED', 'RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED'
    )),
    CONSTRAINT ck_agent_shadow_batches_trigger_mode CHECK (
        trigger_mode IN ('MANUAL', 'SCHEDULED')
    ),
    CONSTRAINT ck_agent_shadow_batches_rule CHECK (
        rule_version = '1.4.0-stage-2i-chief-decision-v1'
    ),
    CONSTRAINT ck_agent_shadow_batches_selection_mode CHECK (
        selection_mode IN ('EXPLICIT', 'AUTO')
    ),
    CONSTRAINT ck_agent_shadow_batches_selection_hash CHECK (
        selection_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_agent_shadow_batches_max_symbols CHECK (
        configured_max_symbols BETWEEN 1 AND 20
    ),
    CONSTRAINT ck_agent_shadow_batches_counts CHECK (
        selected_count BETWEEN 0 AND configured_max_symbols
        AND launched_count BETWEEN 0 AND selected_count
        AND terminal_count BETWEEN 0 AND selected_count
        AND determined_count BETWEEN 0 AND selected_count
        AND insufficient_count BETWEEN 0 AND selected_count
        AND failed_count BETWEEN 0 AND selected_count
        AND veto_count BETWEEN 0 AND selected_count
        AND data_quality_blocked_count BETWEEN 0 AND selected_count
        AND cache_hit_count BETWEEN 0 AND selected_count
        AND determined_count + insufficient_count + failed_count <= terminal_count
    ),
    CONSTRAINT ck_agent_shadow_batches_configuration CHECK (
        jsonb_typeof(configuration_json) = 'object'
    ),
    CONSTRAINT ck_agent_shadow_batches_created_by CHECK (
        btrim(created_by) <> ''
    ),
    CONSTRAINT ck_agent_shadow_batches_lifecycle CHECK (
        (
            status IN ('QUEUED', 'RUNNING')
            AND finished_at IS NULL
        )
        OR (
            status IN ('COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED')
            AND finished_at IS NOT NULL
            AND terminal_count = selected_count
        )
    ),
    CONSTRAINT ck_agent_shadow_batches_started CHECK (
        status = 'QUEUED' OR started_at IS NOT NULL
    ),
    CONSTRAINT ck_agent_shadow_batches_error CHECK (
        status <> 'FAILED'
        OR (error_message IS NOT NULL AND btrim(error_message) <> '')
    )
);

CREATE UNIQUE INDEX uq_agent_shadow_batches_one_active
    ON agent_shadow_batches ((1))
    WHERE status IN ('QUEUED', 'RUNNING');
CREATE INDEX idx_agent_shadow_batches_trade_date
    ON agent_shadow_batches (trade_date DESC, id DESC);
CREATE INDEX idx_agent_shadow_batches_rule_status
    ON agent_shadow_batches (rule_version, status, id DESC);
CREATE INDEX idx_agent_shadow_batches_created_at
    ON agent_shadow_batches (created_at DESC);

CREATE TABLE agent_shadow_items (
    id BIGSERIAL PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    selection_order INTEGER NOT NULL,
    symbol VARCHAR(6) NOT NULL,
    selection_source VARCHAR(32) NOT NULL,
    selection_source_ref TEXT NOT NULL,
    agent_task_id BIGINT,
    task_newly_created BOOLEAN NOT NULL DEFAULT FALSE,
    cache_hit BOOLEAN NOT NULL DEFAULT FALSE,
    task_status VARCHAR(32),
    final_decision VARCHAR(64),
    gate_status VARCHAR(32),
    score INTEGER,
    confidence INTEGER,
    vetoed BOOLEAN,
    outcome_class VARCHAR(32),
    primary_reason_code VARCHAR(128),
    reason_codes_json JSONB,
    run_snapshot_json JSONB,
    context_hash VARCHAR(64),
    duration_ms BIGINT,
    previous_item_id BIGINT,
    context_changed BOOLEAN,
    decision_changed BOOLEAN,
    score_delta INTEGER,
    confidence_delta INTEGER,
    changed_agents_json JSONB,
    error_message TEXT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_agent_shadow_items_batch FOREIGN KEY (batch_id)
        REFERENCES agent_shadow_batches (id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_shadow_items_task FOREIGN KEY (agent_task_id)
        REFERENCES agent_tasks (id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_shadow_items_previous FOREIGN KEY (previous_item_id)
        REFERENCES agent_shadow_items (id) ON DELETE RESTRICT,
    CONSTRAINT uq_agent_shadow_items_batch_symbol UNIQUE (batch_id, symbol),
    CONSTRAINT uq_agent_shadow_items_batch_order UNIQUE (batch_id, selection_order),
    CONSTRAINT uq_agent_shadow_items_batch_id_id UNIQUE (batch_id, id),
    CONSTRAINT ck_agent_shadow_items_selection_order CHECK (selection_order > 0),
    CONSTRAINT ck_agent_shadow_items_symbol CHECK (symbol ~ '^[0-9]{6}$'),
    CONSTRAINT ck_agent_shadow_items_selection_source CHECK (
        selection_source IN (
            'EXPLICIT', 'CURRENT_POSITION', 'LATEST_SCAN_CANDIDATE'
        )
    ),
    CONSTRAINT ck_agent_shadow_items_selection_ref CHECK (
        btrim(selection_source_ref) <> ''
    ),
    CONSTRAINT ck_agent_shadow_items_task_identity CHECK (
        NOT (task_newly_created AND cache_hit)
        AND (
            agent_task_id IS NOT NULL
            OR (task_newly_created = FALSE AND cache_hit = FALSE)
        )
    ),
    CONSTRAINT ck_agent_shadow_items_task_status CHECK (
        task_status IS NULL OR task_status IN (
            'QUEUED', 'RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED'
        )
    ),
    CONSTRAINT ck_agent_shadow_items_final_decision CHECK (
        final_decision IS NULL OR final_decision IN (
            'REJECTED_BY_VETO', 'BLOCKED_BY_DATA_QUALITY',
            'INSUFFICIENT_DATA', 'RESEARCH_ONLY', 'WATCH',
            'PASS_TO_MANUAL_REVIEW'
        )
    ),
    CONSTRAINT ck_agent_shadow_items_gate_status CHECK (
        gate_status IS NULL OR gate_status IN (
            'PASS', 'WARN', 'BLOCKED', 'NOT_APPLICABLE'
        )
    ),
    CONSTRAINT ck_agent_shadow_items_score CHECK (
        score IS NULL OR score BETWEEN 0 AND 100
    ),
    CONSTRAINT ck_agent_shadow_items_confidence CHECK (
        confidence IS NULL OR confidence BETWEEN 0 AND 100
    ),
    CONSTRAINT ck_agent_shadow_items_outcome CHECK (
        outcome_class IS NULL OR outcome_class IN (
            'DETERMINED', 'INSUFFICIENT', 'FAILED', 'CANCELLED'
        )
    ),
    CONSTRAINT ck_agent_shadow_items_reason_codes CHECK (
        reason_codes_json IS NULL OR jsonb_typeof(reason_codes_json) = 'array'
    ),
    CONSTRAINT ck_agent_shadow_items_run_snapshot CHECK (
        run_snapshot_json IS NULL OR jsonb_typeof(run_snapshot_json) = 'object'
    ),
    CONSTRAINT ck_agent_shadow_items_context_hash CHECK (
        context_hash IS NULL OR context_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_agent_shadow_items_duration CHECK (
        duration_ms IS NULL OR duration_ms >= 0
    ),
    CONSTRAINT ck_agent_shadow_items_score_delta CHECK (
        score_delta IS NULL OR score_delta BETWEEN -100 AND 100
    ),
    CONSTRAINT ck_agent_shadow_items_confidence_delta CHECK (
        confidence_delta IS NULL OR confidence_delta BETWEEN -100 AND 100
    ),
    CONSTRAINT ck_agent_shadow_items_changed_agents CHECK (
        changed_agents_json IS NULL
        OR jsonb_typeof(changed_agents_json) = 'array'
    ),
    CONSTRAINT ck_agent_shadow_items_lifecycle CHECK (
        (
            outcome_class IS NULL
            AND finished_at IS NULL
        )
        OR (
            outcome_class IS NOT NULL
            AND finished_at IS NOT NULL
        )
    ),
    CONSTRAINT ck_agent_shadow_items_determined CHECK (
        outcome_class <> 'DETERMINED'
        OR (
            agent_task_id IS NOT NULL
            AND task_status = 'COMPLETED'
            AND final_decision IS NOT NULL
            AND final_decision <> 'INSUFFICIENT_DATA'
            AND gate_status IS NOT NULL
            AND score IS NOT NULL
            AND confidence IS NOT NULL
            AND vetoed IS NOT NULL
            AND run_snapshot_json IS NOT NULL
            AND context_hash IS NOT NULL
        )
    ),
    CONSTRAINT ck_agent_shadow_items_insufficient CHECK (
        outcome_class <> 'INSUFFICIENT'
        OR (
            agent_task_id IS NOT NULL
            AND task_status = 'PARTIAL'
            AND final_decision = 'INSUFFICIENT_DATA'
            AND gate_status = 'NOT_APPLICABLE'
            AND score = 0
            AND confidence = 0
            AND vetoed = FALSE
            AND primary_reason_code IS NOT NULL
            AND btrim(primary_reason_code) <> ''
            AND reason_codes_json IS NOT NULL
            AND run_snapshot_json IS NOT NULL
            AND context_hash IS NOT NULL
        )
    ),
    CONSTRAINT ck_agent_shadow_items_failed CHECK (
        outcome_class <> 'FAILED'
        OR (
            task_status IS NOT NULL
            AND error_message IS NOT NULL
            AND btrim(error_message) <> ''
        )
    ),
    CONSTRAINT ck_agent_shadow_items_cancelled CHECK (
        outcome_class <> 'CANCELLED'
        OR (
            agent_task_id IS NULL
            AND task_status = 'CANCELLED'
            AND task_newly_created = FALSE
            AND cache_hit = FALSE
        )
    )
);

CREATE INDEX idx_agent_shadow_items_batch_order
    ON agent_shadow_items (batch_id, selection_order);
CREATE INDEX idx_agent_shadow_items_symbol_rule_history
    ON agent_shadow_items (symbol, finished_at DESC, id DESC)
    WHERE outcome_class IS NOT NULL;
CREATE INDEX idx_agent_shadow_items_task
    ON agent_shadow_items (agent_task_id)
    WHERE agent_task_id IS NOT NULL;
CREATE INDEX idx_agent_shadow_items_outcome
    ON agent_shadow_items (outcome_class, finished_at DESC);
CREATE INDEX idx_agent_shadow_items_reason
    ON agent_shadow_items (primary_reason_code)
    WHERE primary_reason_code IS NOT NULL;

CREATE TABLE agent_shadow_reviews (
    id BIGSERIAL PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    item_id BIGINT NOT NULL,
    review_contract_version VARCHAR(64) NOT NULL,
    label VARCHAR(32) NOT NULL,
    note TEXT NOT NULL,
    reviewer VARCHAR(128) NOT NULL,
    supersedes_review_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_agent_shadow_reviews_batch_item FOREIGN KEY (batch_id, item_id)
        REFERENCES agent_shadow_items (batch_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_shadow_reviews_supersedes FOREIGN KEY (supersedes_review_id)
        REFERENCES agent_shadow_reviews (id) ON DELETE RESTRICT,
    CONSTRAINT ck_agent_shadow_reviews_contract CHECK (
        review_contract_version = 'SHADOW_REVIEW_V1'
    ),
    CONSTRAINT ck_agent_shadow_reviews_label CHECK (label IN (
        'EXPECTED', 'UNEXPECTED', 'DATA_ISSUE', 'RULE_ISSUE',
        'FALSE_POSITIVE', 'FALSE_NEGATIVE', 'NEEDS_FOLLOW_UP'
    )),
    CONSTRAINT ck_agent_shadow_reviews_note CHECK (btrim(note) <> ''),
    CONSTRAINT ck_agent_shadow_reviews_reviewer CHECK (btrim(reviewer) <> ''),
    CONSTRAINT ck_agent_shadow_reviews_not_self CHECK (
        supersedes_review_id IS NULL OR supersedes_review_id <> id
    )
);

CREATE INDEX idx_agent_shadow_reviews_item_created
    ON agent_shadow_reviews (item_id, created_at, id);
CREATE INDEX idx_agent_shadow_reviews_batch
    ON agent_shadow_reviews (batch_id, id);
CREATE INDEX idx_agent_shadow_reviews_label
    ON agent_shadow_reviews (label);

CREATE FUNCTION protect_agent_shadow_batch()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'agent_shadow_batches are audit facts and cannot be deleted';
    END IF;
    IF OLD.status IN ('COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED') THEN
        RAISE EXCEPTION 'terminal agent_shadow_batches are immutable';
    END IF;
    IF ROW(
        NEW.contract_version, NEW.trigger_mode, NEW.trade_date,
        NEW.rule_version, NEW.selection_mode, NEW.selection_hash,
        NEW.configured_max_symbols, NEW.selected_count,
        NEW.configuration_json, NEW.created_by, NEW.created_at
    ) IS DISTINCT FROM ROW(
        OLD.contract_version, OLD.trigger_mode, OLD.trade_date,
        OLD.rule_version, OLD.selection_mode, OLD.selection_hash,
        OLD.configured_max_symbols, OLD.selected_count,
        OLD.configuration_json, OLD.created_by, OLD.created_at
    ) THEN
        RAISE EXCEPTION 'agent_shadow_batch identity and frozen configuration are immutable';
    END IF;
    IF NOT (
        NEW.status = OLD.status
        OR (OLD.status = 'QUEUED' AND NEW.status IN (
            'RUNNING', 'FAILED', 'CANCELLED'
        ))
        OR (OLD.status = 'RUNNING' AND NEW.status IN (
            'COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED'
        ))
    ) THEN
        RAISE EXCEPTION 'invalid agent_shadow_batch status transition: % -> %',
            OLD.status, NEW.status;
    END IF;
    NEW.updated_at := CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_agent_shadow_batches_protect
BEFORE UPDATE OR DELETE ON agent_shadow_batches
FOR EACH ROW EXECUTE FUNCTION protect_agent_shadow_batch();

CREATE FUNCTION protect_agent_shadow_item()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'agent_shadow_items are audit facts and cannot be deleted';
    END IF;
    IF OLD.outcome_class IS NOT NULL THEN
        RAISE EXCEPTION 'terminal agent_shadow_items are immutable';
    END IF;
    IF ROW(
        NEW.batch_id, NEW.selection_order, NEW.symbol,
        NEW.selection_source, NEW.selection_source_ref, NEW.created_at
    ) IS DISTINCT FROM ROW(
        OLD.batch_id, OLD.selection_order, OLD.symbol,
        OLD.selection_source, OLD.selection_source_ref, OLD.created_at
    ) THEN
        RAISE EXCEPTION 'agent_shadow_item selection identity is immutable';
    END IF;
    IF OLD.agent_task_id IS NOT NULL
       AND NEW.agent_task_id IS DISTINCT FROM OLD.agent_task_id THEN
        RAISE EXCEPTION 'agent_shadow_item task identity is immutable once assigned';
    END IF;
    NEW.updated_at := CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;

CREATE FUNCTION validate_agent_shadow_item_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    batch_status VARCHAR(32);
    batch_selected_count INTEGER;
BEGIN
    SELECT status, selected_count
      INTO batch_status, batch_selected_count
     FROM agent_shadow_batches
     WHERE id = NEW.batch_id
     FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'shadow item batch does not exist';
    END IF;
    IF batch_status NOT IN ('QUEUED', 'RUNNING') THEN
        RAISE EXCEPTION 'terminal agent_shadow_batches cannot accept new items';
    END IF;
    IF NEW.selection_order > batch_selected_count THEN
        RAISE EXCEPTION 'shadow item selection order exceeds frozen selected count';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_agent_shadow_items_validate_insert
BEFORE INSERT ON agent_shadow_items
FOR EACH ROW EXECUTE FUNCTION validate_agent_shadow_item_insert();

CREATE TRIGGER trg_agent_shadow_items_protect
BEFORE UPDATE OR DELETE ON agent_shadow_items
FOR EACH ROW EXECUTE FUNCTION protect_agent_shadow_item();

CREATE FUNCTION validate_agent_shadow_review_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    superseded_batch BIGINT;
    superseded_item BIGINT;
    reviewed_outcome VARCHAR(32);
BEGIN
    SELECT outcome_class
      INTO reviewed_outcome
      FROM agent_shadow_items
     WHERE batch_id = NEW.batch_id AND id = NEW.item_id;
    IF NOT FOUND OR reviewed_outcome IS NULL THEN
        RAISE EXCEPTION 'only terminal shadow items may be reviewed';
    END IF;
    IF NEW.supersedes_review_id IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT batch_id, item_id
      INTO superseded_batch, superseded_item
      FROM agent_shadow_reviews
     WHERE id = NEW.supersedes_review_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'superseded shadow review does not exist';
    END IF;
    IF superseded_batch <> NEW.batch_id OR superseded_item <> NEW.item_id THEN
        RAISE EXCEPTION 'a shadow review may only supersede a review for the same item';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_agent_shadow_reviews_validate_insert
BEFORE INSERT ON agent_shadow_reviews
FOR EACH ROW EXECUTE FUNCTION validate_agent_shadow_review_insert();

CREATE FUNCTION reject_agent_shadow_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% is append-only and cannot be %',
        TG_TABLE_NAME, lower(TG_OP);
END;
$$;

CREATE TRIGGER trg_agent_shadow_reviews_reject_mutation
BEFORE UPDATE OR DELETE ON agent_shadow_reviews
FOR EACH ROW EXECUTE FUNCTION reject_agent_shadow_mutation();

CREATE TRIGGER trg_agent_shadow_batches_reject_truncate
BEFORE TRUNCATE ON agent_shadow_batches
FOR EACH STATEMENT EXECUTE FUNCTION reject_agent_shadow_mutation();

CREATE TRIGGER trg_agent_shadow_items_reject_truncate
BEFORE TRUNCATE ON agent_shadow_items
FOR EACH STATEMENT EXECUTE FUNCTION reject_agent_shadow_mutation();

CREATE TRIGGER trg_agent_shadow_reviews_reject_truncate
BEFORE TRUNCATE ON agent_shadow_reviews
FOR EACH STATEMENT EXECUTE FUNCTION reject_agent_shadow_mutation();

COMMENT ON TABLE agent_shadow_batches IS
    'Controlled 2I shadow execution batches; terminal audit facts are immutable.';
COMMENT ON TABLE agent_shadow_items IS
    'Per-symbol immutable shadow outcomes, structured insufficiency reasons and drift.';
COMMENT ON TABLE agent_shadow_reviews IS
    'Append-only human review history; corrections supersede earlier rows.';
COMMENT ON COLUMN agent_shadow_items.run_snapshot_json IS
    'SHADOW_OUTCOME_SNAPSHOT_V1 projection of the final decision, vetoes and six professional runs.';
COMMENT ON COLUMN agent_shadow_items.changed_agents_json IS
    'Changed professional agents in the fixed six-agent order; NULL when no previous comparable item exists.';
