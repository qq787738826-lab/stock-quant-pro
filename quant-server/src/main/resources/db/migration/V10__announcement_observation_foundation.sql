-- Stock Quant Pro 1.4.0 stage 2G: append-only research announcement observations.
-- The source is explicitly research-only and does not claim FORMAL or PIT qualification.

CREATE TABLE announcement_capture_batches (
    id BIGSERIAL PRIMARY KEY,
    batch_version VARCHAR(96) NOT NULL,
    source_code VARCHAR(128) NOT NULL,
    provider_contract_version VARCHAR(128) NOT NULL,
    symbol VARCHAR(6) NOT NULL,
    requested_start_date DATE NOT NULL,
    requested_end_date DATE NOT NULL,
    observed_at TIMESTAMPTZ(6) NOT NULL,
    complete BOOLEAN NOT NULL,
    chunk_count INTEGER NOT NULL,
    successful_chunk_count INTEGER NOT NULL,
    record_count INTEGER NOT NULL,
    appended_count INTEGER NOT NULL,
    provider_metadata_json JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_announcement_capture_batches_version UNIQUE (batch_version),
    CONSTRAINT uq_announcement_capture_batches_identity
        UNIQUE (id, batch_version, source_code, provider_contract_version, symbol),
    CONSTRAINT ck_announcement_capture_batches_source
        CHECK (
            source_code = 'AKSHARE_CNINFO_RESEARCH_V1'
            AND provider_contract_version = 'AKSHARE_CNINFO_PROVIDER_V1'
        ),
    CONSTRAINT ck_announcement_capture_batches_symbol
        CHECK (symbol ~ '^[0-9]{6}$'),
    CONSTRAINT ck_announcement_capture_batches_range
        CHECK (
            requested_end_date >= requested_start_date
            AND requested_end_date - requested_start_date <= 365
        ),
    CONSTRAINT ck_announcement_capture_batches_counts
        CHECK (
            chunk_count >= 1
            AND successful_chunk_count >= 0
            AND successful_chunk_count <= chunk_count
            AND record_count >= 0
            AND appended_count >= 0
            AND appended_count <= record_count
            AND (
                (complete AND successful_chunk_count = chunk_count)
                OR
                (NOT complete AND successful_chunk_count < chunk_count)
            )
        ),
    CONSTRAINT ck_announcement_capture_batches_time
        CHECK (created_at >= observed_at),
    CONSTRAINT ck_announcement_capture_batches_metadata
        CHECK (jsonb_typeof(provider_metadata_json) = 'object')
);

CREATE TABLE announcement_observations (
    id BIGSERIAL PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    batch_version VARCHAR(96) NOT NULL,
    source_code VARCHAR(128) NOT NULL,
    provider_contract_version VARCHAR(128) NOT NULL,
    source_announcement_id VARCHAR(192) NOT NULL,
    source_identity_strength VARCHAR(32) NOT NULL,
    symbol VARCHAR(6) NOT NULL,
    security_name VARCHAR(128) NOT NULL,
    title VARCHAR(1024) NOT NULL,
    reported_publish_date DATE NOT NULL,
    reported_publish_time_precision VARCHAR(16) NOT NULL,
    source_url TEXT NOT NULL,
    normalized_source_url TEXT NOT NULL,
    source_url_hash VARCHAR(64) NOT NULL,
    first_observed_at TIMESTAMPTZ(6) NOT NULL,
    known_at TIMESTAMPTZ(6) NOT NULL,
    recorded_at TIMESTAMPTZ(6) NOT NULL,
    canonical_content_hash VARCHAR(64) NOT NULL,
    observation_version VARCHAR(64) NOT NULL,
    assurance_level VARCHAR(32) NOT NULL,
    formal_eligible BOOLEAN NOT NULL,
    pit_verified BOOLEAN NOT NULL,
    revision_relationship_guaranteed BOOLEAN NOT NULL,
    raw_payload_json JSONB NOT NULL,
    CONSTRAINT uq_announcement_observations_version UNIQUE (observation_version),
    CONSTRAINT uq_announcement_observations_batch_source
        UNIQUE (batch_id, source_announcement_id),
    CONSTRAINT fk_announcement_observations_batch
        FOREIGN KEY (
            batch_id, batch_version, source_code, provider_contract_version, symbol
        )
        REFERENCES announcement_capture_batches (
            id, batch_version, source_code, provider_contract_version, symbol
        )
        ON DELETE RESTRICT,
    CONSTRAINT ck_announcement_observations_source
        CHECK (
            source_code = 'AKSHARE_CNINFO_RESEARCH_V1'
            AND provider_contract_version = 'AKSHARE_CNINFO_PROVIDER_V1'
        ),
    CONSTRAINT ck_announcement_observations_identity
        CHECK (
            (source_identity_strength = 'CNINFO_ID'
             AND source_announcement_id ~ '^CNINFO:[A-Za-z0-9._-]+$')
            OR
            (source_identity_strength = 'URL_DERIVED'
             AND source_announcement_id ~ '^CNINFO_URL_SHA256:[0-9a-f]{64}$')
        ),
    CONSTRAINT ck_announcement_observations_symbol
        CHECK (symbol ~ '^[0-9]{6}$'),
    CONSTRAINT ck_announcement_observations_text
        CHECK (
            btrim(security_name) <> ''
            AND btrim(title) <> ''
            AND btrim(source_url) <> ''
            AND btrim(normalized_source_url) <> ''
        ),
    CONSTRAINT ck_announcement_observations_url
        CHECK (
            source_url ~* '^https?://'
            AND normalized_source_url ~ '^https?://'
            AND source_url_hash ~ '^[0-9a-f]{64}$'
        ),
    CONSTRAINT ck_announcement_observations_date
        CHECK (
            reported_publish_date
            <= (first_observed_at AT TIME ZONE 'Asia/Shanghai')::DATE
        ),
    CONSTRAINT ck_announcement_observations_time
        CHECK (
            first_observed_at = known_at
            AND known_at <= recorded_at
        ),
    CONSTRAINT ck_announcement_observations_hashes
        CHECK (
            canonical_content_hash ~ '^[0-9a-f]{64}$'
            AND observation_version ~ '^[0-9a-f]{64}$'
        ),
    CONSTRAINT ck_announcement_observations_research_assurance
        CHECK (
            assurance_level = 'RESEARCH'
            AND NOT formal_eligible
            AND NOT pit_verified
            AND NOT revision_relationship_guaranteed
            AND reported_publish_time_precision = 'DATE_ONLY'
        ),
    CONSTRAINT ck_announcement_observations_payload
        CHECK (jsonb_typeof(raw_payload_json) = 'object')
);

CREATE INDEX idx_announcement_capture_batches_coverage
    ON announcement_capture_batches (
        source_code, provider_contract_version, symbol,
        complete, requested_start_date, requested_end_date, observed_at DESC, id DESC
    );

CREATE INDEX idx_announcement_observations_as_of
    ON announcement_observations (
        source_code, provider_contract_version, symbol,
        source_announcement_id, known_at DESC, recorded_at DESC, id DESC
    );

CREATE INDEX idx_announcement_observations_reported_date
    ON announcement_observations (
        symbol, reported_publish_date DESC, known_at DESC,
        source_announcement_id, observation_version
    );

CREATE INDEX idx_announcement_observations_batch
    ON announcement_observations (batch_id, source_announcement_id);

CREATE OR REPLACE FUNCTION reject_announcement_fact_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
BEGIN
    RAISE EXCEPTION '% is append-only; % is forbidden', TG_TABLE_NAME, TG_OP
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_announcement_capture_batches_immutable
BEFORE UPDATE OR DELETE ON announcement_capture_batches
FOR EACH ROW EXECUTE FUNCTION reject_announcement_fact_mutation();

CREATE TRIGGER trg_announcement_capture_batches_no_truncate
BEFORE TRUNCATE ON announcement_capture_batches
FOR EACH STATEMENT EXECUTE FUNCTION reject_announcement_fact_mutation();

CREATE TRIGGER trg_announcement_observations_immutable
BEFORE UPDATE OR DELETE ON announcement_observations
FOR EACH ROW EXECUTE FUNCTION reject_announcement_fact_mutation();

CREATE TRIGGER trg_announcement_observations_no_truncate
BEFORE TRUNCATE ON announcement_observations
FOR EACH STATEMENT EXECUTE FUNCTION reject_announcement_fact_mutation();

COMMENT ON TABLE announcement_capture_batches IS
    'Append-only AKShare/CNINFO research capture coverage evidence; incomplete batches do not prove absence.';
COMMENT ON TABLE announcement_observations IS
    'Append-only date-precision research announcement versions, visible only from their real local known_at.';
COMMENT ON COLUMN announcement_observations.reported_publish_date IS
    'Provider-reported date only; no source publication time is inferred.';
COMMENT ON COLUMN announcement_observations.known_at IS
    'Actual Java validation completion time, equal to first_observed_at and never historically backfilled.';
COMMENT ON COLUMN announcement_observations.canonical_content_hash IS
    'ANNOUNCEMENT_CANONICAL_V1 SHA-256 over the frozen announcement identity/content whitelist.';
