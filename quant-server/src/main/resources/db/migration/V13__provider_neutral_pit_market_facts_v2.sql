-- Stock Quant Pro 1.4.0 stage 3A-R3B-0:
-- provider-neutral, append-only PIT market facts V2.
--
-- V6 market_data_dataset_versions remains the shared immutable dataset
-- lineage. V9 QFQ observations remain untouched and are not treated as raw
-- facts. The common envelope below carries qualification and version-chain
-- semantics while four type-specific tables retain typed database checks.

CREATE TABLE pit_market_fact_batches (
    id BIGSERIAL PRIMARY KEY,
    batch_version VARCHAR(64) NOT NULL,
    dataset_version_id BIGINT NOT NULL,
    dataset_version VARCHAR(128) NOT NULL,
    provider_contract_version VARCHAR(64) NOT NULL,
    market_facts_contract_version VARCHAR(64) NOT NULL,
    run_namespace VARCHAR(16) NOT NULL,
    capture_mode VARCHAR(32) NOT NULL,
    source_code VARCHAR(128) NOT NULL,
    source_instrument_id VARCHAR(256) NOT NULL,
    provider_dataset_version VARCHAR(256),
    revision_qualification VARCHAR(32) NOT NULL,
    assurance_level VARCHAR(32) NOT NULL,
    usage_qualification VARCHAR(32) NOT NULL,
    formal_eligible BOOLEAN NOT NULL,
    local_persistence_allowed BOOLEAN NOT NULL,
    historical_replay_allowed BOOLEAN NOT NULL,
    backtest_allowed BOOLEAN NOT NULL,
    agent_use_allowed BOOLEAN NOT NULL,
    range_start DATE NOT NULL,
    range_end DATE NOT NULL,
    observed_at TIMESTAMPTZ(6) NOT NULL,
    recorded_at TIMESTAMPTZ(6) NOT NULL DEFAULT clock_timestamp(),
    response_complete BOOLEAN NOT NULL,
    record_count INTEGER NOT NULL,
    fact_contracts_json JSONB NOT NULL,
    provider_capabilities_json JSONB NOT NULL,
    provider_metadata_json JSONB NOT NULL,
    CONSTRAINT fk_pit_market_fact_batches_dataset
        FOREIGN KEY (dataset_version_id)
        REFERENCES market_data_dataset_versions(id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_pit_market_fact_batches_version UNIQUE (batch_version),
    CONSTRAINT ck_pit_market_fact_batches_hash
        CHECK (batch_version ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_pit_market_fact_batches_contracts CHECK (
        provider_contract_version = 'MARKET_FACT_PROVIDER_CONTRACT_V1'
        AND market_facts_contract_version = 'PIT_MARKET_FACTS_V2'
    ),
    CONSTRAINT ck_pit_market_fact_batches_namespace
        CHECK (run_namespace IN ('FORMAL', 'TEST', 'DEMO')),
    CONSTRAINT ck_pit_market_fact_batches_capture_mode
        CHECK (capture_mode IN (
            'PROVIDER_CAPTURE', 'TEST_FIXTURE', 'DEMO_FIXTURE'
        )),
    CONSTRAINT ck_pit_market_fact_batches_text CHECK (
        btrim(dataset_version) <> ''
        AND btrim(source_code) <> ''
        AND btrim(source_instrument_id) <> ''
        AND (
            provider_dataset_version IS NULL
            OR btrim(provider_dataset_version) <> ''
        )
    ),
    CONSTRAINT ck_pit_market_fact_batches_revision_qualification
        CHECK (revision_qualification IN (
            'PROVIDER_VERIFIED',
            'PROVIDER_UNVERIFIED',
            'PROVIDER_UNAVAILABLE',
            'SYSTEM_KNOWLEDGE_ONLY'
        )),
    CONSTRAINT ck_pit_market_fact_batches_provider_dataset CHECK (
        revision_qualification = 'PROVIDER_VERIFIED'
        OR provider_dataset_version IS NULL
    ),
    CONSTRAINT ck_pit_market_fact_batches_assurance
        CHECK (assurance_level IN (
            'PROVIDER_PIT_VERIFIED', 'SYSTEM_KNOWLEDGE_PIT'
        )),
    CONSTRAINT ck_pit_market_fact_batches_usage
        CHECK (usage_qualification IN (
            'TEST_DEMO_ONLY', 'RESEARCH_ONLY', 'LICENSED_INTERNAL'
        )),
    CONSTRAINT ck_pit_market_fact_batches_test_demo CHECK (
        run_namespace = 'FORMAL'
        OR (
            capture_mode IN ('TEST_FIXTURE', 'DEMO_FIXTURE')
            AND usage_qualification = 'TEST_DEMO_ONLY'
            AND NOT formal_eligible
        )
    ),
    CONSTRAINT ck_pit_market_fact_batches_pit_qualification CHECK (
        (
            assurance_level = 'PROVIDER_PIT_VERIFIED'
            AND revision_qualification = 'PROVIDER_VERIFIED'
        )
        OR (
            assurance_level = 'SYSTEM_KNOWLEDGE_PIT'
            AND revision_qualification <> 'PROVIDER_VERIFIED'
        )
    ),
    CONSTRAINT ck_pit_market_fact_batches_range
        CHECK (range_end >= range_start),
    CONSTRAINT ck_pit_market_fact_batches_time
        CHECK (recorded_at >= observed_at),
    CONSTRAINT ck_pit_market_fact_batches_count
        CHECK (record_count >= 0),
    CONSTRAINT ck_pit_market_fact_batches_json CHECK (
        jsonb_typeof(fact_contracts_json) = 'object'
        AND jsonb_typeof(provider_capabilities_json) = 'object'
        AND jsonb_typeof(provider_metadata_json) = 'object'
    )
);

CREATE TABLE pit_market_fact_observations (
    id BIGSERIAL PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    fact_type VARCHAR(32) NOT NULL,
    fact_contract_version VARCHAR(64) NOT NULL,
    natural_key VARCHAR(512) NOT NULL,
    chain_sequence INTEGER NOT NULL,
    predecessor_observation_id BIGINT,
    source_code VARCHAR(128) NOT NULL,
    source_instrument_id VARCHAR(256) NOT NULL,
    provider_dataset_version VARCHAR(256),
    provider_revision VARCHAR(256),
    provider_snapshot_id VARCHAR(256),
    provider_published_at TIMESTAMPTZ(6),
    provider_updated_at TIMESTAMPTZ(6),
    first_observed_at TIMESTAMPTZ(6) NOT NULL,
    known_at TIMESTAMPTZ(6) NOT NULL,
    recorded_at TIMESTAMPTZ(6) NOT NULL DEFAULT clock_timestamp(),
    canonical_content_hash VARCHAR(64) NOT NULL,
    observation_version VARCHAR(64) NOT NULL,
    revision_qualification VARCHAR(32) NOT NULL,
    assurance_level VARCHAR(32) NOT NULL,
    usage_qualification VARCHAR(32) NOT NULL,
    formal_eligible BOOLEAN NOT NULL,
    local_persistence_allowed BOOLEAN NOT NULL,
    historical_replay_allowed BOOLEAN NOT NULL,
    backtest_allowed BOOLEAN NOT NULL,
    agent_use_allowed BOOLEAN NOT NULL,
    raw_payload_json JSONB NOT NULL,
    CONSTRAINT fk_pit_market_fact_observations_batch
        FOREIGN KEY (batch_id)
        REFERENCES pit_market_fact_batches(id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_pit_market_fact_observations_predecessor
        FOREIGN KEY (predecessor_observation_id)
        REFERENCES pit_market_fact_observations(id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_pit_market_fact_observations_version
        UNIQUE (observation_version),
    CONSTRAINT uq_pit_market_fact_observations_predecessor
        UNIQUE (predecessor_observation_id),
    CONSTRAINT uq_pit_market_fact_observations_chain
        UNIQUE (
            fact_type, source_code, source_instrument_id,
            natural_key, chain_sequence
        ),
    CONSTRAINT ck_pit_market_fact_observations_type
        CHECK (fact_type IN (
            'RAW_DAILY_BAR',
            'ADJUSTMENT_FACTOR',
            'TRADING_CALENDAR',
            'CORPORATE_ACTION'
        )),
    CONSTRAINT ck_pit_market_fact_observations_contract CHECK (
        (fact_type = 'RAW_DAILY_BAR'
         AND fact_contract_version = 'RAW_DAILY_BAR_OBSERVATION_V2')
        OR
        (fact_type = 'ADJUSTMENT_FACTOR'
         AND fact_contract_version = 'ADJUSTMENT_FACTOR_OBSERVATION_V1')
        OR
        (fact_type = 'TRADING_CALENDAR'
         AND fact_contract_version = 'TRADING_CALENDAR_OBSERVATION_V1')
        OR
        (fact_type = 'CORPORATE_ACTION'
         AND fact_contract_version = 'CORPORATE_ACTION_OBSERVATION_V1')
    ),
    CONSTRAINT ck_pit_market_fact_observations_text CHECK (
        btrim(natural_key) <> ''
        AND btrim(source_code) <> ''
        AND btrim(source_instrument_id) <> ''
        AND (
            provider_dataset_version IS NULL
            OR btrim(provider_dataset_version) <> ''
        )
        AND (
            provider_revision IS NULL
            OR btrim(provider_revision) <> ''
        )
        AND (
            provider_snapshot_id IS NULL
            OR btrim(provider_snapshot_id) <> ''
        )
    ),
    CONSTRAINT ck_pit_market_fact_observations_chain_sequence CHECK (
        chain_sequence > 0
        AND (
            (chain_sequence = 1 AND predecessor_observation_id IS NULL)
            OR
            (chain_sequence > 1 AND predecessor_observation_id IS NOT NULL)
        )
    ),
    CONSTRAINT ck_pit_market_fact_observations_time CHECK (
        recorded_at >= first_observed_at
        AND (
            (
                revision_qualification = 'PROVIDER_VERIFIED'
                AND provider_revision IS NOT NULL
                AND provider_published_at IS NOT NULL
                AND known_at = provider_published_at
                AND provider_published_at <= first_observed_at
                AND (
                    provider_updated_at IS NULL
                    OR (
                        provider_updated_at >= provider_published_at
                        AND provider_updated_at <= first_observed_at
                    )
                )
            )
            OR (
                revision_qualification <> 'PROVIDER_VERIFIED'
                AND known_at = first_observed_at
                AND provider_dataset_version IS NULL
                AND provider_revision IS NULL
                AND provider_snapshot_id IS NULL
                AND provider_published_at IS NULL
                AND provider_updated_at IS NULL
            )
        )
    ),
    CONSTRAINT ck_pit_market_fact_observations_hashes CHECK (
        canonical_content_hash ~ '^[0-9a-f]{64}$'
        AND observation_version ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_pit_market_fact_observations_revision_qualification
        CHECK (revision_qualification IN (
            'PROVIDER_VERIFIED',
            'PROVIDER_UNVERIFIED',
            'PROVIDER_UNAVAILABLE',
            'SYSTEM_KNOWLEDGE_ONLY'
        )),
    CONSTRAINT ck_pit_market_fact_observations_provider_revision CHECK (
        (
            revision_qualification = 'PROVIDER_VERIFIED'
            AND provider_revision IS NOT NULL
            AND provider_published_at IS NOT NULL
        )
        OR (
            revision_qualification <> 'PROVIDER_VERIFIED'
            AND provider_dataset_version IS NULL
            AND provider_revision IS NULL
            AND provider_snapshot_id IS NULL
            AND provider_published_at IS NULL
            AND provider_updated_at IS NULL
        )
    ),
    CONSTRAINT ck_pit_market_fact_observations_assurance CHECK (
        (
            assurance_level = 'PROVIDER_PIT_VERIFIED'
            AND revision_qualification = 'PROVIDER_VERIFIED'
        )
        OR (
            assurance_level = 'SYSTEM_KNOWLEDGE_PIT'
            AND revision_qualification <> 'PROVIDER_VERIFIED'
        )
    ),
    CONSTRAINT ck_pit_market_fact_observations_usage
        CHECK (usage_qualification IN (
            'TEST_DEMO_ONLY', 'RESEARCH_ONLY', 'LICENSED_INTERNAL'
        )),
    CONSTRAINT ck_pit_market_fact_observations_payload
        CHECK (jsonb_typeof(raw_payload_json) = 'object')
);

CREATE TABLE raw_daily_bar_facts_v2 (
    observation_id BIGINT PRIMARY KEY,
    symbol VARCHAR(6) NOT NULL,
    exchange VARCHAR(16) NOT NULL,
    trade_date DATE NOT NULL,
    open NUMERIC(30,12) NOT NULL,
    high NUMERIC(30,12) NOT NULL,
    low NUMERIC(30,12) NOT NULL,
    close NUMERIC(30,12) NOT NULL,
    volume NUMERIC(30,8),
    volume_qualification VARCHAR(32) NOT NULL,
    volume_unit_code VARCHAR(32) NOT NULL,
    volume_semantic_code VARCHAR(64) NOT NULL,
    amount NUMERIC(30,8),
    amount_qualification VARCHAR(32) NOT NULL,
    amount_unit_code VARCHAR(32) NOT NULL,
    amount_semantic_code VARCHAR(64) NOT NULL,
    turnover_rate NUMERIC(20,12),
    turnover_rate_qualification VARCHAR(32) NOT NULL,
    turnover_rate_unit_code VARCHAR(32) NOT NULL,
    turnover_rate_semantic_code VARCHAR(64) NOT NULL,
    CONSTRAINT fk_raw_daily_bar_facts_v2_observation
        FOREIGN KEY (observation_id)
        REFERENCES pit_market_fact_observations(id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_raw_daily_bar_facts_v2_symbol
        CHECK (symbol ~ '^[0-9]{6}$'),
    CONSTRAINT ck_raw_daily_bar_facts_v2_exchange
        CHECK (exchange IN ('SSE', 'SZSE')),
    CONSTRAINT ck_raw_daily_bar_facts_v2_weekday
        CHECK (EXTRACT(ISODOW FROM trade_date) BETWEEN 1 AND 5),
    CONSTRAINT ck_raw_daily_bar_facts_v2_finite CHECK (
        open::TEXT NOT IN ('NaN', 'Infinity', '-Infinity')
        AND high::TEXT NOT IN ('NaN', 'Infinity', '-Infinity')
        AND low::TEXT NOT IN ('NaN', 'Infinity', '-Infinity')
        AND close::TEXT NOT IN ('NaN', 'Infinity', '-Infinity')
        AND (volume IS NULL
             OR volume::TEXT NOT IN ('NaN', 'Infinity', '-Infinity'))
        AND (amount IS NULL
             OR amount::TEXT NOT IN ('NaN', 'Infinity', '-Infinity'))
        AND (turnover_rate IS NULL
             OR turnover_rate::TEXT NOT IN ('NaN', 'Infinity', '-Infinity'))
    ),
    CONSTRAINT ck_raw_daily_bar_facts_v2_ohlc CHECK (
        open > 0 AND high > 0 AND low > 0 AND close > 0
        AND high >= open AND high >= low AND high >= close
        AND low <= open AND low <= high AND low <= close
    ),
    CONSTRAINT ck_raw_daily_bar_facts_v2_volume
        CHECK (volume IS NULL OR volume >= 0),
    CONSTRAINT ck_raw_daily_bar_facts_v2_amount
        CHECK (amount IS NULL OR amount >= 0),
    CONSTRAINT ck_raw_daily_bar_facts_v2_turnover
        CHECK (turnover_rate IS NULL OR turnover_rate >= 0),
    CONSTRAINT ck_raw_daily_bar_facts_v2_field_qualification CHECK (
        volume_qualification IN (
            'PRESENT_VERIFIED', 'PRESENT_UNVERIFIED', 'MISSING'
        )
        AND amount_qualification IN (
            'PRESENT_VERIFIED', 'PRESENT_UNVERIFIED', 'MISSING'
        )
        AND turnover_rate_qualification IN (
            'PRESENT_VERIFIED', 'PRESENT_UNVERIFIED', 'MISSING'
        )
        AND (
            (volume_qualification = 'MISSING' AND volume IS NULL)
            OR
            (volume_qualification <> 'MISSING' AND volume IS NOT NULL)
        )
        AND (
            (amount_qualification = 'MISSING' AND amount IS NULL)
            OR
            (amount_qualification <> 'MISSING' AND amount IS NOT NULL)
        )
        AND (
            (turnover_rate_qualification = 'MISSING'
             AND turnover_rate IS NULL)
            OR
            (turnover_rate_qualification <> 'MISSING'
             AND turnover_rate IS NOT NULL)
        )
    ),
    CONSTRAINT ck_raw_daily_bar_facts_v2_field_identity CHECK (
        volume_unit_code = 'SHARES'
        AND volume_semantic_code = 'TRADED_VOLUME'
        AND amount_unit_code = 'CNY'
        AND amount_semantic_code = 'TRADED_AMOUNT'
        AND turnover_rate_unit_code = 'RATIO'
        AND turnover_rate_semantic_code = 'TURNOVER_RATE'
    )
);

CREATE TABLE adjustment_factor_facts_v1 (
    observation_id BIGINT PRIMARY KEY,
    symbol VARCHAR(6) NOT NULL,
    factor_effective_trade_date DATE NOT NULL,
    factor_type VARCHAR(32) NOT NULL,
    coverage_mode VARCHAR(32) NOT NULL,
    factor NUMERIC(36,18) NOT NULL,
    CONSTRAINT fk_adjustment_factor_facts_v1_observation
        FOREIGN KEY (observation_id)
        REFERENCES pit_market_fact_observations(id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_adjustment_factor_facts_v1_symbol
        CHECK (symbol ~ '^[0-9]{6}$'),
    CONSTRAINT ck_adjustment_factor_facts_v1_weekday
        CHECK (
            EXTRACT(ISODOW FROM factor_effective_trade_date)
            BETWEEN 1 AND 5
        ),
    CONSTRAINT ck_adjustment_factor_facts_v1_type
        CHECK (factor_type = 'QFQ'),
    CONSTRAINT ck_adjustment_factor_facts_v1_coverage
        CHECK (coverage_mode = 'DAILY_EXACT'),
    CONSTRAINT ck_adjustment_factor_facts_v1_factor CHECK (
        factor::TEXT NOT IN ('NaN', 'Infinity', '-Infinity')
        AND factor > 0
    )
);

CREATE TABLE trading_calendar_facts_v1 (
    observation_id BIGINT PRIMARY KEY,
    exchange VARCHAR(16) NOT NULL,
    calendar_date DATE NOT NULL,
    is_open BOOLEAN NOT NULL,
    session_code VARCHAR(32) NOT NULL,
    CONSTRAINT fk_trading_calendar_facts_v1_observation
        FOREIGN KEY (observation_id)
        REFERENCES pit_market_fact_observations(id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_trading_calendar_facts_v1_exchange
        CHECK (exchange IN ('SSE', 'SZSE')),
    CONSTRAINT ck_trading_calendar_facts_v1_session CHECK (
        (is_open AND session_code = 'REGULAR')
        OR (NOT is_open AND session_code = 'CLOSED')
    )
);

CREATE TABLE corporate_action_facts_v1 (
    observation_id BIGINT PRIMARY KEY,
    source_action_id VARCHAR(256) NOT NULL,
    symbol VARCHAR(6) NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    announcement_date DATE,
    effective_trade_date DATE NOT NULL,
    terms_json JSONB NOT NULL,
    CONSTRAINT fk_corporate_action_facts_v1_observation
        FOREIGN KEY (observation_id)
        REFERENCES pit_market_fact_observations(id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_corporate_action_facts_v1_action_id
        CHECK (btrim(source_action_id) <> ''),
    CONSTRAINT ck_corporate_action_facts_v1_symbol
        CHECK (symbol ~ '^[0-9]{6}$'),
    CONSTRAINT ck_corporate_action_facts_v1_type CHECK (
        action_type IN (
            'CASH_DIVIDEND',
            'STOCK_DIVIDEND',
            'CAPITALIZATION',
            'RIGHTS_ISSUE',
            'SPLIT',
            'REVERSE_SPLIT',
            'OTHER'
        )
    ),
    CONSTRAINT ck_corporate_action_facts_v1_dates CHECK (
        announcement_date IS NULL
        OR announcement_date <= effective_trade_date
    ),
    CONSTRAINT ck_corporate_action_facts_v1_terms
        CHECK (jsonb_typeof(terms_json) = 'object')
);

CREATE INDEX idx_pit_market_fact_batches_source_time
    ON pit_market_fact_batches (
        source_code, source_instrument_id, observed_at DESC, id DESC
    );
CREATE INDEX idx_pit_market_fact_batches_dataset
    ON pit_market_fact_batches (dataset_version_id, id);

CREATE INDEX idx_pit_market_fact_observations_as_of
    ON pit_market_fact_observations (
        fact_type, source_code, source_instrument_id,
        natural_key, known_at DESC, chain_sequence DESC, id DESC
    );
CREATE INDEX idx_pit_market_fact_observations_batch
    ON pit_market_fact_observations (batch_id, fact_type, id);
CREATE INDEX idx_pit_market_fact_observations_predecessor
    ON pit_market_fact_observations (predecessor_observation_id)
    WHERE predecessor_observation_id IS NOT NULL;

CREATE INDEX idx_raw_daily_bar_facts_v2_window
    ON raw_daily_bar_facts_v2 (symbol, exchange, trade_date DESC);
CREATE INDEX idx_adjustment_factor_facts_v1_window
    ON adjustment_factor_facts_v1 (
        symbol, factor_type, factor_effective_trade_date DESC
    );
CREATE INDEX idx_trading_calendar_facts_v1_window
    ON trading_calendar_facts_v1 (
        exchange, calendar_date DESC, is_open
    );
CREATE INDEX idx_corporate_action_facts_v1_window
    ON corporate_action_facts_v1 (
        symbol, effective_trade_date DESC, source_action_id
    );

CREATE FUNCTION reject_pit_market_fact_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
BEGIN
    RAISE EXCEPTION '% is append-only; % is forbidden',
        TG_TABLE_NAME, TG_OP
        USING ERRCODE = '55000';
END;
$$;

CREATE FUNCTION validate_pit_market_fact_observation()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
DECLARE
    batch_record RECORD;
    predecessor_record RECORD;
    latest_record RECORD;
BEGIN
    EXECUTE format(
        'SELECT * FROM %I.pit_market_fact_batches WHERE id = $1',
        TG_TABLE_SCHEMA
    )
    INTO STRICT batch_record
    USING NEW.batch_id;

    IF NEW.source_code <> batch_record.source_code
       OR NEW.revision_qualification <> batch_record.revision_qualification
       OR NEW.assurance_level <> batch_record.assurance_level
       OR NEW.usage_qualification <> batch_record.usage_qualification
       OR NEW.formal_eligible <> batch_record.formal_eligible
       OR NEW.local_persistence_allowed
          <> batch_record.local_persistence_allowed
       OR NEW.historical_replay_allowed
          <> batch_record.historical_replay_allowed
       OR NEW.backtest_allowed <> batch_record.backtest_allowed
       OR NEW.agent_use_allowed <> batch_record.agent_use_allowed
       OR NEW.provider_dataset_version IS DISTINCT FROM
          batch_record.provider_dataset_version THEN
        RAISE EXCEPTION
            'PIT fact observation qualification does not match batch'
            USING ERRCODE = '23514';
    END IF;

    EXECUTE format(
        'SELECT id, chain_sequence, canonical_content_hash '
        || 'FROM %I.pit_market_fact_observations '
        || 'WHERE fact_type = $1 AND source_code = $2 '
        || 'AND source_instrument_id = $3 AND natural_key = $4 '
        || 'ORDER BY chain_sequence DESC, id DESC LIMIT 1',
        TG_TABLE_SCHEMA
    )
    INTO latest_record
    USING NEW.fact_type, NEW.source_code, NEW.source_instrument_id,
          NEW.natural_key;

    IF NEW.predecessor_observation_id IS NULL THEN
        IF NEW.chain_sequence <> 1 OR latest_record.id IS NOT NULL THEN
            RAISE EXCEPTION
                'PIT fact chain must start once at sequence 1'
                USING ERRCODE = '23514';
        END IF;
    ELSE
        EXECUTE format(
            'SELECT id, fact_type, source_code, source_instrument_id, '
            || 'natural_key, chain_sequence, canonical_content_hash '
            || 'FROM %I.pit_market_fact_observations WHERE id = $1',
            TG_TABLE_SCHEMA
        )
        INTO STRICT predecessor_record
        USING NEW.predecessor_observation_id;

        IF predecessor_record.fact_type <> NEW.fact_type
           OR predecessor_record.source_code <> NEW.source_code
           OR predecessor_record.source_instrument_id
              <> NEW.source_instrument_id
           OR predecessor_record.natural_key <> NEW.natural_key
           OR predecessor_record.chain_sequence + 1
              <> NEW.chain_sequence
           OR latest_record.id <> predecessor_record.id THEN
            RAISE EXCEPTION
                'PIT fact predecessor must be the same-source chain tail'
                USING ERRCODE = '23514';
        END IF;
        IF predecessor_record.canonical_content_hash
           = NEW.canonical_content_hash THEN
            RAISE EXCEPTION
                'consecutive identical PIT fact is idempotent'
                USING ERRCODE = '23505';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION validate_raw_daily_bar_fact_v2()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
DECLARE envelope RECORD;
BEGIN
    EXECUTE format(
        'SELECT fact_type, natural_key, source_instrument_id, '
        || 'first_observed_at, known_at '
        || 'FROM %I.pit_market_fact_observations WHERE id = $1',
        TG_TABLE_SCHEMA
    )
    INTO STRICT envelope
    USING NEW.observation_id;
    IF envelope.fact_type <> 'RAW_DAILY_BAR'
       OR envelope.natural_key <>
          ('RAW_DAILY_BAR|' || NEW.symbol || '|' || NEW.trade_date)
       OR envelope.source_instrument_id = ''
       OR envelope.first_observed_at <
          ((NEW.trade_date::timestamp + TIME '15:00:00')
           AT TIME ZONE 'Asia/Shanghai')
       OR envelope.known_at <
          ((NEW.trade_date::timestamp + TIME '15:00:00')
           AT TIME ZONE 'Asia/Shanghai') THEN
        RAISE EXCEPTION 'raw daily bar natural key or type mismatch'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION validate_adjustment_factor_fact_v1()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
DECLARE envelope RECORD;
BEGIN
    EXECUTE format(
        'SELECT fact_type, natural_key '
        || 'FROM %I.pit_market_fact_observations WHERE id = $1',
        TG_TABLE_SCHEMA
    )
    INTO STRICT envelope
    USING NEW.observation_id;
    IF envelope.fact_type <> 'ADJUSTMENT_FACTOR'
       OR envelope.natural_key <>
          ('ADJUSTMENT_FACTOR|' || NEW.symbol || '|'
           || NEW.factor_type || '|'
           || NEW.factor_effective_trade_date) THEN
        RAISE EXCEPTION 'adjustment factor natural key or type mismatch'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION validate_trading_calendar_fact_v1()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
DECLARE envelope RECORD;
BEGIN
    EXECUTE format(
        'SELECT fact_type, natural_key, source_instrument_id '
        || 'FROM %I.pit_market_fact_observations WHERE id = $1',
        TG_TABLE_SCHEMA
    )
    INTO STRICT envelope
    USING NEW.observation_id;
    IF envelope.fact_type <> 'TRADING_CALENDAR'
       OR envelope.natural_key <>
          ('TRADING_CALENDAR|' || NEW.exchange || '|'
           || NEW.calendar_date) THEN
        RAISE EXCEPTION 'trading calendar natural key or type mismatch'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION validate_corporate_action_fact_v1()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
DECLARE envelope RECORD;
BEGIN
    EXECUTE format(
        'SELECT fact_type, natural_key '
        || 'FROM %I.pit_market_fact_observations WHERE id = $1',
        TG_TABLE_SCHEMA
    )
    INTO STRICT envelope
    USING NEW.observation_id;
    IF envelope.fact_type <> 'CORPORATE_ACTION'
       OR envelope.natural_key <>
          ('CORPORATE_ACTION|' || NEW.symbol || '|'
           || NEW.source_action_id) THEN
        RAISE EXCEPTION 'corporate action natural key or type mismatch'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_pit_market_fact_observations_validate
BEFORE INSERT ON pit_market_fact_observations
FOR EACH ROW EXECUTE FUNCTION validate_pit_market_fact_observation();

CREATE TRIGGER trg_raw_daily_bar_facts_v2_validate
BEFORE INSERT ON raw_daily_bar_facts_v2
FOR EACH ROW EXECUTE FUNCTION validate_raw_daily_bar_fact_v2();
CREATE TRIGGER trg_adjustment_factor_facts_v1_validate
BEFORE INSERT ON adjustment_factor_facts_v1
FOR EACH ROW EXECUTE FUNCTION validate_adjustment_factor_fact_v1();
CREATE TRIGGER trg_trading_calendar_facts_v1_validate
BEFORE INSERT ON trading_calendar_facts_v1
FOR EACH ROW EXECUTE FUNCTION validate_trading_calendar_fact_v1();
CREATE TRIGGER trg_corporate_action_facts_v1_validate
BEFORE INSERT ON corporate_action_facts_v1
FOR EACH ROW EXECUTE FUNCTION validate_corporate_action_fact_v1();

CREATE TRIGGER trg_pit_market_fact_batches_immutable
BEFORE UPDATE OR DELETE ON pit_market_fact_batches
FOR EACH ROW EXECUTE FUNCTION reject_pit_market_fact_mutation();
CREATE TRIGGER trg_pit_market_fact_batches_no_truncate
BEFORE TRUNCATE ON pit_market_fact_batches
FOR EACH STATEMENT EXECUTE FUNCTION reject_pit_market_fact_mutation();

CREATE TRIGGER trg_pit_market_fact_observations_immutable
BEFORE UPDATE OR DELETE ON pit_market_fact_observations
FOR EACH ROW EXECUTE FUNCTION reject_pit_market_fact_mutation();
CREATE TRIGGER trg_pit_market_fact_observations_no_truncate
BEFORE TRUNCATE ON pit_market_fact_observations
FOR EACH STATEMENT EXECUTE FUNCTION reject_pit_market_fact_mutation();

CREATE TRIGGER trg_raw_daily_bar_facts_v2_immutable
BEFORE UPDATE OR DELETE ON raw_daily_bar_facts_v2
FOR EACH ROW EXECUTE FUNCTION reject_pit_market_fact_mutation();
CREATE TRIGGER trg_raw_daily_bar_facts_v2_no_truncate
BEFORE TRUNCATE ON raw_daily_bar_facts_v2
FOR EACH STATEMENT EXECUTE FUNCTION reject_pit_market_fact_mutation();

CREATE TRIGGER trg_adjustment_factor_facts_v1_immutable
BEFORE UPDATE OR DELETE ON adjustment_factor_facts_v1
FOR EACH ROW EXECUTE FUNCTION reject_pit_market_fact_mutation();
CREATE TRIGGER trg_adjustment_factor_facts_v1_no_truncate
BEFORE TRUNCATE ON adjustment_factor_facts_v1
FOR EACH STATEMENT EXECUTE FUNCTION reject_pit_market_fact_mutation();

CREATE TRIGGER trg_trading_calendar_facts_v1_immutable
BEFORE UPDATE OR DELETE ON trading_calendar_facts_v1
FOR EACH ROW EXECUTE FUNCTION reject_pit_market_fact_mutation();
CREATE TRIGGER trg_trading_calendar_facts_v1_no_truncate
BEFORE TRUNCATE ON trading_calendar_facts_v1
FOR EACH STATEMENT EXECUTE FUNCTION reject_pit_market_fact_mutation();

CREATE TRIGGER trg_corporate_action_facts_v1_immutable
BEFORE UPDATE OR DELETE ON corporate_action_facts_v1
FOR EACH ROW EXECUTE FUNCTION reject_pit_market_fact_mutation();
CREATE TRIGGER trg_corporate_action_facts_v1_no_truncate
BEFORE TRUNCATE ON corporate_action_facts_v1
FOR EACH STATEMENT EXECUTE FUNCTION reject_pit_market_fact_mutation();

-- Production defaults remain on 2I. V13 only permits the explicit TEST/DEMO
-- V2 rule version when a caller also passes the Java configuration gate.
ALTER TABLE agent_shadow_batches
    DROP CONSTRAINT ck_agent_shadow_batches_rule;
ALTER TABLE agent_shadow_batches
    ADD CONSTRAINT ck_agent_shadow_batches_rule CHECK (
        rule_version IN (
            '1.4.0-stage-2i-chief-decision-v1',
            '1.4.0-stage-3ar3b0-agent-team-pit-v2'
        )
    );

COMMENT ON TABLE pit_market_fact_batches IS
    'Immutable V2 capture lineage reusing V6 dataset versions; local dataset versions never impersonate provider revisions.';
COMMENT ON TABLE pit_market_fact_observations IS
    'Provider-neutral append-only PIT envelope with same-source predecessor lineage and qualification.';
COMMENT ON TABLE raw_daily_bar_facts_v2 IS
    'Typed unadjusted daily OHLCV facts; V9 QFQ observations are not raw inputs.';
COMMENT ON TABLE adjustment_factor_facts_v1 IS
    'Typed DAILY_EXACT QFQ factors; carry-forward is forbidden by QFQ_AS_OF_ENGINE_V1.';
COMMENT ON TABLE trading_calendar_facts_v1 IS
    'Typed provider-neutral trading-calendar observations selected as-of knowledge cutoff.';
COMMENT ON TABLE corporate_action_facts_v1 IS
    'Typed corporate-action observations used to explain cutoff-visible factor changes.';
