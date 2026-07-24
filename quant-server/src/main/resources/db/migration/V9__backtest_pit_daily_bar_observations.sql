-- Stock Quant Pro 1.4.0 stage 2F: append-only PIT daily-bar observations.
-- daily_bars remains the mutable current-state compatibility projection.

CREATE TABLE market_data_observation_batches (
    id BIGSERIAL PRIMARY KEY,
    batch_version VARCHAR(96) NOT NULL,
    source_code VARCHAR(128) NOT NULL,
    dataset_version VARCHAR(128) NOT NULL,
    capture_type VARCHAR(32) NOT NULL,
    observed_at TIMESTAMPTZ(6) NOT NULL,
    recorded_at TIMESTAMPTZ(6) NOT NULL DEFAULT clock_timestamp(),
    record_count INTEGER NOT NULL,
    source_metadata JSONB NOT NULL DEFAULT '{}'::JSONB,
    CONSTRAINT uq_market_data_observation_batches_version UNIQUE (batch_version),
    CONSTRAINT uq_market_data_observation_batches_identity
        UNIQUE (id, source_code, dataset_version),
    CONSTRAINT ck_market_data_observation_batches_version
        CHECK (btrim(batch_version) <> ''),
    CONSTRAINT ck_market_data_observation_batches_source
        CHECK (btrim(source_code) <> ''),
    CONSTRAINT ck_market_data_observation_batches_dataset
        CHECK (btrim(dataset_version) <> ''),
    CONSTRAINT ck_market_data_observation_batches_capture_type
        CHECK (capture_type IN (
            'MARKET_DATA_PERSISTENCE',
            'BOOTSTRAP_CURRENT_STATE',
            'TEST_FIXTURE'
        )),
    CONSTRAINT ck_market_data_observation_batches_time
        CHECK (recorded_at >= observed_at),
    CONSTRAINT ck_market_data_observation_batches_count
        CHECK (record_count >= 0),
    CONSTRAINT ck_market_data_observation_batches_metadata
        CHECK (jsonb_typeof(source_metadata) = 'object')
);

CREATE TABLE daily_bar_observations (
    id BIGSERIAL PRIMARY KEY,
    observation_version VARCHAR(64) NOT NULL,
    batch_id BIGINT NOT NULL,
    symbol VARCHAR(12) NOT NULL,
    trade_date DATE NOT NULL,
    adjust_type VARCHAR(8) NOT NULL,
    open NUMERIC(18,4) NOT NULL,
    high NUMERIC(18,4) NOT NULL,
    low NUMERIC(18,4) NOT NULL,
    close NUMERIC(18,4) NOT NULL,
    volume BIGINT NOT NULL,
    amount NUMERIC(24,4),
    turnover_rate NUMERIC(10,4),
    source_code VARCHAR(128) NOT NULL,
    source_revision VARCHAR(128),
    dataset_version VARCHAR(128) NOT NULL,
    first_observed_at TIMESTAMPTZ(6) NOT NULL,
    known_at TIMESTAMPTZ(6) NOT NULL,
    recorded_at TIMESTAMPTZ(6) NOT NULL DEFAULT clock_timestamp(),
    canonical_content_hash VARCHAR(64) NOT NULL,
    CONSTRAINT uq_daily_bar_observations_version UNIQUE (observation_version),
    CONSTRAINT fk_daily_bar_observations_batch
        FOREIGN KEY (batch_id, source_code, dataset_version)
        REFERENCES market_data_observation_batches (id, source_code, dataset_version)
        ON DELETE RESTRICT,
    CONSTRAINT ck_daily_bar_observations_version
        CHECK (observation_version ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_daily_bar_observations_symbol
        CHECK (symbol ~ '^[0-9]{6}$'),
    CONSTRAINT ck_daily_bar_observations_adjust_type
        CHECK (adjust_type = 'QFQ'),
    CONSTRAINT ck_daily_bar_observations_ohlc_positive
        CHECK (open > 0 AND high > 0 AND low > 0 AND close > 0),
    CONSTRAINT ck_daily_bar_observations_ohlc_consistent
        CHECK (
            high >= open AND high >= close AND high >= low
            AND low <= open AND low <= close AND low <= high
        ),
    CONSTRAINT ck_daily_bar_observations_volume
        CHECK (volume >= 0),
    CONSTRAINT ck_daily_bar_observations_amount
        CHECK (amount IS NULL OR amount >= 0),
    CONSTRAINT ck_daily_bar_observations_turnover
        CHECK (turnover_rate IS NULL OR turnover_rate >= 0),
    CONSTRAINT ck_daily_bar_observations_source
        CHECK (btrim(source_code) <> ''),
    CONSTRAINT ck_daily_bar_observations_revision
        CHECK (source_revision IS NULL OR btrim(source_revision) <> ''),
    CONSTRAINT ck_daily_bar_observations_dataset
        CHECK (btrim(dataset_version) <> ''),
    CONSTRAINT ck_daily_bar_observations_time
        CHECK (
            first_observed_at <= known_at
            AND known_at <= recorded_at
        ),
    CONSTRAINT ck_daily_bar_observations_content_hash
        CHECK (canonical_content_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_market_data_observation_batches_source_time
    ON market_data_observation_batches (source_code, observed_at DESC, batch_version);

CREATE INDEX idx_daily_bar_observations_as_of
    ON daily_bar_observations (
        symbol, adjust_type, trade_date, known_at DESC,
        recorded_at DESC, id DESC, observation_version DESC
    );

CREATE INDEX idx_daily_bar_observations_source_date
    ON daily_bar_observations (
        source_code, symbol, adjust_type, trade_date, known_at DESC
    );

CREATE INDEX idx_daily_bar_observations_batch
    ON daily_bar_observations (batch_id, trade_date, observation_version);

CREATE OR REPLACE FUNCTION reject_backtest_pit_fact_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
BEGIN
    RAISE EXCEPTION '% is append-only; % is forbidden', TG_TABLE_NAME, TG_OP
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_market_data_observation_batches_immutable
BEFORE UPDATE OR DELETE ON market_data_observation_batches
FOR EACH ROW EXECUTE FUNCTION reject_backtest_pit_fact_mutation();

CREATE TRIGGER trg_market_data_observation_batches_no_truncate
BEFORE TRUNCATE ON market_data_observation_batches
FOR EACH STATEMENT EXECUTE FUNCTION reject_backtest_pit_fact_mutation();

CREATE TRIGGER trg_daily_bar_observations_immutable
BEFORE UPDATE OR DELETE ON daily_bar_observations
FOR EACH ROW EXECUTE FUNCTION reject_backtest_pit_fact_mutation();

CREATE TRIGGER trg_daily_bar_observations_no_truncate
BEFORE TRUNCATE ON daily_bar_observations
FOR EACH STATEMENT EXECUTE FUNCTION reject_backtest_pit_fact_mutation();

COMMENT ON TABLE market_data_observation_batches IS
    'Append-only local observation batches; dataset_version is a local capture version, not a claimed provider revision.';
COMMENT ON COLUMN market_data_observation_batches.observed_at IS
    'Actual local capture time; never backdated to a historical trade date.';
COMMENT ON TABLE daily_bar_observations IS
    'Append-only QFQ daily-bar versions selected as-of known_at for reliable stage-2F research.';
COMMENT ON COLUMN daily_bar_observations.source_revision IS
    'Provider revision when explicitly supplied; NULL means the provider supplied no revision identifier.';
COMMENT ON COLUMN daily_bar_observations.known_at IS
    'Earliest locally evidenced availability time for this observation occurrence.';
COMMENT ON COLUMN daily_bar_observations.canonical_content_hash IS
    'BACKTEST_CANONICAL_V1 content hash excluding physical IDs and capture timestamps.';
