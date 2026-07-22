-- Stage 2D-2B-1B-1: TEST/DEMO security event materialization foundation.
-- FORMAL/PIT, history projection, calendar projection and Universe remain unavailable.

ALTER TABLE market_data_ingestion_runs
    ADD COLUMN manifest_contract_version VARCHAR(80) NOT NULL
        DEFAULT 'INGESTION_MANIFEST_V1';
ALTER TABLE market_data_ingestion_runs
    ALTER COLUMN manifest_contract_version DROP DEFAULT;
ALTER TABLE market_data_ingestion_runs
    ADD CONSTRAINT ck_market_data_ingestion_runs_manifest_contract CHECK (
        manifest_contract_version IN (
            'INGESTION_MANIFEST_V1', 'INGESTION_MANIFEST_V2_SECURITY_EVENT'
        )
    );

ALTER TABLE security_status_events
    ADD COLUMN event_logical_key VARCHAR(256),
    ADD COLUMN event_contract_version VARCHAR(120),
    ADD COLUMN record_namespace VARCHAR(16) NOT NULL DEFAULT 'DEMO',
    ADD COLUMN assurance_level VARCHAR(32) NOT NULL DEFAULT 'INFERRED_RESEARCH',
    ADD COLUMN security_logical_key VARCHAR(256);
ALTER TABLE security_status_events
    ALTER COLUMN record_namespace DROP DEFAULT,
    ALTER COLUMN assurance_level DROP DEFAULT;
ALTER TABLE security_status_events
    DROP CONSTRAINT uq_security_status_events_source_record;
ALTER TABLE security_status_events
    ADD CONSTRAINT uq_security_status_events_source_record UNIQUE (
        record_namespace, source, source_version, source_record_id, source_revision
    ),
    ADD CONSTRAINT uq_security_status_events_event_logical_key UNIQUE (event_logical_key),
    ADD CONSTRAINT ck_security_status_events_namespace CHECK (
        record_namespace IN ('FORMAL', 'TEST', 'DEMO')
    ),
    ADD CONSTRAINT ck_security_status_events_assurance CHECK (
        assurance_level IN (
            'PIT_VERIFIED', 'RECONSTRUCTED_VERIFIED', 'INFERRED_RESEARCH'
        )
    ),
    ADD CONSTRAINT ck_security_status_events_logical_identity CHECK (
        (event_logical_key IS NULL
         AND event_contract_version IS NULL
         AND security_logical_key IS NULL)
        OR
        (event_logical_key IS NOT NULL
         AND btrim(event_logical_key) <> ''
         AND event_contract_version IS NOT NULL
         AND btrim(event_contract_version) <> ''
         AND security_logical_key IS NOT NULL
         AND btrim(security_logical_key) <> '')
    );

CREATE UNIQUE INDEX uq_security_status_events_full_root
    ON security_status_events (
        record_namespace, security_logical_key, event_contract_version
    )
    WHERE event_logical_key IS NOT NULL
      AND event_type = 'FULL_STATUS_SNAPSHOT';
CREATE INDEX idx_security_status_events_security_chain
    ON security_status_events (
        record_namespace, security_logical_key, event_contract_version,
        effective_from, known_at
    )
    WHERE event_logical_key IS NOT NULL;

CREATE TABLE security_identity_registry (
    id BIGSERIAL PRIMARY KEY,
    security_logical_key VARCHAR(256) NOT NULL,
    record_namespace VARCHAR(16) NOT NULL,
    identity_authority VARCHAR(128) NOT NULL,
    identity_stable_id VARCHAR(256) NOT NULL,
    identity_contract_version VARCHAR(80) NOT NULL,
    assurance_level VARCHAR(32) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_security_identity_registry_logical_key
        UNIQUE (security_logical_key),
    CONSTRAINT uq_security_identity_registry_business_key
        UNIQUE (record_namespace, identity_authority, identity_stable_id,
                identity_contract_version),
    CONSTRAINT ck_security_identity_registry_namespace CHECK (
        record_namespace IN ('TEST', 'DEMO')
    ),
    CONSTRAINT ck_security_identity_registry_contract CHECK (
        identity_contract_version = 'SECURITY_IDENTITY_V1'
    ),
    CONSTRAINT ck_security_identity_registry_assurance CHECK (
        assurance_level IN ('RECONSTRUCTED_VERIFIED', 'INFERRED_RESEARCH')
    ),
    CONSTRAINT ck_security_identity_registry_nonblank CHECK (
        btrim(security_logical_key) <> ''
        AND btrim(identity_authority) <> ''
        AND btrim(identity_stable_id) <> ''
    )
);

CREATE TABLE source_security_identity_mappings (
    id BIGSERIAL PRIMARY KEY,
    mapping_logical_key VARCHAR(256) NOT NULL,
    record_namespace VARCHAR(16) NOT NULL,
    source VARCHAR(128) NOT NULL,
    source_version VARCHAR(128) NOT NULL,
    source_instrument_id VARCHAR(256) NOT NULL,
    security_identity_id BIGINT NOT NULL,
    security_logical_key VARCHAR(256) NOT NULL,
    mapping_contract_version VARCHAR(80) NOT NULL,
    mapping_assurance_level VARCHAR(32) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_source_security_identity_mappings_identity
        FOREIGN KEY (security_identity_id)
        REFERENCES security_identity_registry(id) ON DELETE RESTRICT,
    CONSTRAINT uq_source_security_identity_mappings_logical_key
        UNIQUE (mapping_logical_key),
    CONSTRAINT uq_source_security_identity_mappings_business_key
        UNIQUE (record_namespace, source, source_version, source_instrument_id,
                mapping_contract_version),
    CONSTRAINT ck_source_security_identity_mappings_namespace CHECK (
        record_namespace IN ('TEST', 'DEMO')
    ),
    CONSTRAINT ck_source_security_identity_mappings_contract CHECK (
        mapping_contract_version = 'SOURCE_SECURITY_IDENTITY_MAPPING_V1'
    ),
    CONSTRAINT ck_source_security_identity_mappings_assurance CHECK (
        mapping_assurance_level IN (
            'RECONSTRUCTED_VERIFIED', 'INFERRED_RESEARCH'
        )
    ),
    CONSTRAINT ck_source_security_identity_mappings_nonblank CHECK (
        btrim(mapping_logical_key) <> ''
        AND btrim(source) <> ''
        AND btrim(source_version) <> ''
        AND btrim(source_instrument_id) <> ''
        AND btrim(security_logical_key) <> ''
    )
);

CREATE INDEX idx_source_security_identity_mappings_identity
    ON source_security_identity_mappings (security_identity_id, record_namespace);

CREATE TABLE security_status_normalization_results (
    id BIGSERIAL PRIMARY KEY,
    processing_attempt_id BIGINT NOT NULL,
    attempt_logical_key VARCHAR(256) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    event_id BIGINT,
    event_logical_key VARCHAR(256),
    security_logical_key VARCHAR(256),
    predecessor_event_logical_key VARCHAR(256),
    normalizer_version VARCHAR(120) NOT NULL,
    transition_rule_version VARCHAR(120) NOT NULL,
    assurance_level VARCHAR(32) NOT NULL,
    result_hash VARCHAR(64) NOT NULL,
    error_code VARCHAR(120),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_security_status_normalization_results_attempt
        FOREIGN KEY (processing_attempt_id)
        REFERENCES security_status_processing_attempts(id) ON DELETE RESTRICT,
    CONSTRAINT fk_security_status_normalization_results_event
        FOREIGN KEY (event_id)
        REFERENCES security_status_events(id) ON DELETE RESTRICT,
    CONSTRAINT uq_security_status_normalization_results_attempt
        UNIQUE (processing_attempt_id),
    CONSTRAINT uq_security_status_normalization_results_attempt_key
        UNIQUE (attempt_logical_key),
    CONSTRAINT ck_security_status_normalization_results_outcome CHECK (
        outcome IN (
            'EVENT_MATERIALIZED', 'EVENT_REUSED', 'NO_STATE_CHANGE',
            'IDENTITY_UNRESOLVED', 'UNSUPPORTED_CONTRACT', 'CONFLICT',
            'PROJECTION_FAILED', 'REJECTED'
        )
    ),
    CONSTRAINT ck_security_status_normalization_results_event_reference CHECK (
        (outcome IN ('EVENT_MATERIALIZED', 'EVENT_REUSED')
         AND event_id IS NOT NULL AND event_logical_key IS NOT NULL)
        OR
        (outcome NOT IN ('EVENT_MATERIALIZED', 'EVENT_REUSED')
         AND event_id IS NULL AND event_logical_key IS NULL)
    ),
    CONSTRAINT ck_security_status_normalization_results_error CHECK (
        (outcome IN ('EVENT_MATERIALIZED', 'EVENT_REUSED', 'NO_STATE_CHANGE')
         AND error_code IS NULL)
        OR
        (outcome NOT IN ('EVENT_MATERIALIZED', 'EVENT_REUSED', 'NO_STATE_CHANGE')
         AND error_code IS NOT NULL AND btrim(error_code) <> '')
    ),
    CONSTRAINT ck_security_status_normalization_results_assurance CHECK (
        assurance_level IN ('RECONSTRUCTED_VERIFIED', 'INFERRED_RESEARCH')
    ),
    CONSTRAINT ck_security_status_normalization_results_hash CHECK (
        result_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_security_status_normalization_results_nonblank CHECK (
        btrim(attempt_logical_key) <> ''
        AND btrim(normalizer_version) <> ''
        AND btrim(transition_rule_version) <> ''
        AND (event_logical_key IS NULL OR btrim(event_logical_key) <> '')
        AND (security_logical_key IS NULL OR btrim(security_logical_key) <> '')
        AND (predecessor_event_logical_key IS NULL
             OR btrim(predecessor_event_logical_key) <> '')
    )
);

CREATE TABLE security_status_event_lineage (
    id BIGSERIAL PRIMARY KEY,
    event_id BIGINT NOT NULL,
    event_logical_key VARCHAR(256) NOT NULL,
    dataset_version_id BIGINT NOT NULL,
    dataset_logical_key VARCHAR(256) NOT NULL,
    raw_record_id BIGINT NOT NULL,
    raw_record_logical_key VARCHAR(256) NOT NULL,
    ingestion_run_id BIGINT NOT NULL,
    ingestion_run_logical_key VARCHAR(256) NOT NULL,
    processing_attempt_id BIGINT NOT NULL,
    attempt_logical_key VARCHAR(256) NOT NULL,
    security_identity_id BIGINT NOT NULL,
    security_logical_key VARCHAR(256) NOT NULL,
    mapping_id BIGINT NOT NULL,
    mapping_logical_key VARCHAR(256) NOT NULL,
    predecessor_event_id BIGINT,
    predecessor_event_logical_key VARCHAR(256),
    record_namespace VARCHAR(16) NOT NULL,
    event_contract_version VARCHAR(120) NOT NULL,
    normalizer_version VARCHAR(120) NOT NULL,
    transition_rule_version VARCHAR(120) NOT NULL,
    assurance_level VARCHAR(32) NOT NULL,
    lineage_hash VARCHAR(64) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_security_status_event_lineage_event
        FOREIGN KEY (event_id) REFERENCES security_status_events(id) ON DELETE RESTRICT,
    CONSTRAINT fk_security_status_event_lineage_dataset
        FOREIGN KEY (dataset_version_id)
        REFERENCES market_data_dataset_versions(id) ON DELETE RESTRICT,
    CONSTRAINT fk_security_status_event_lineage_raw
        FOREIGN KEY (raw_record_id)
        REFERENCES security_status_raw_records(id) ON DELETE RESTRICT,
    CONSTRAINT fk_security_status_event_lineage_run
        FOREIGN KEY (ingestion_run_id)
        REFERENCES market_data_ingestion_runs(id) ON DELETE RESTRICT,
    CONSTRAINT fk_security_status_event_lineage_attempt
        FOREIGN KEY (processing_attempt_id)
        REFERENCES security_status_processing_attempts(id) ON DELETE RESTRICT,
    CONSTRAINT fk_security_status_event_lineage_identity
        FOREIGN KEY (security_identity_id)
        REFERENCES security_identity_registry(id) ON DELETE RESTRICT,
    CONSTRAINT fk_security_status_event_lineage_mapping
        FOREIGN KEY (mapping_id)
        REFERENCES source_security_identity_mappings(id) ON DELETE RESTRICT,
    CONSTRAINT fk_security_status_event_lineage_predecessor
        FOREIGN KEY (predecessor_event_id)
        REFERENCES security_status_events(id) ON DELETE RESTRICT,
    CONSTRAINT uq_security_status_event_lineage_event UNIQUE (event_id),
    CONSTRAINT uq_security_status_event_lineage_event_key UNIQUE (event_logical_key),
    CONSTRAINT uq_security_status_event_lineage_attempt UNIQUE (processing_attempt_id),
    CONSTRAINT ck_security_status_event_lineage_namespace CHECK (
        record_namespace IN ('TEST', 'DEMO')
    ),
    CONSTRAINT ck_security_status_event_lineage_contract CHECK (
        event_contract_version = 'SECURITY_STATUS_EVENT_V1'
    ),
    CONSTRAINT ck_security_status_event_lineage_assurance CHECK (
        assurance_level IN ('RECONSTRUCTED_VERIFIED', 'INFERRED_RESEARCH')
    ),
    CONSTRAINT ck_security_status_event_lineage_hash CHECK (
        lineage_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_security_status_event_lineage_predecessor_pair CHECK (
        (predecessor_event_id IS NULL AND predecessor_event_logical_key IS NULL)
        OR
        (predecessor_event_id IS NOT NULL
         AND predecessor_event_logical_key IS NOT NULL
         AND btrim(predecessor_event_logical_key) <> '')
    ),
    CONSTRAINT ck_security_status_event_lineage_nonblank CHECK (
        btrim(event_logical_key) <> ''
        AND btrim(dataset_logical_key) <> ''
        AND btrim(raw_record_logical_key) <> ''
        AND btrim(ingestion_run_logical_key) <> ''
        AND btrim(attempt_logical_key) <> ''
        AND btrim(security_logical_key) <> ''
        AND btrim(mapping_logical_key) <> ''
        AND btrim(normalizer_version) <> ''
        AND btrim(transition_rule_version) <> ''
    )
);

CREATE INDEX idx_security_status_normalization_results_event
    ON security_status_normalization_results (event_id)
    WHERE event_id IS NOT NULL;
CREATE INDEX idx_security_status_event_lineage_raw
    ON security_status_event_lineage (raw_record_id, ingestion_run_id);
CREATE INDEX idx_security_status_event_lineage_identity
    ON security_status_event_lineage (security_identity_id, event_id);

CREATE OR REPLACE FUNCTION security_event_assurance_rank(value TEXT)
RETURNS INTEGER
LANGUAGE SQL
IMMUTABLE
STRICT
AS $$
    SELECT CASE value
        WHEN 'PIT_VERIFIED' THEN 2
        WHEN 'RECONSTRUCTED_VERIFIED' THEN 1
        WHEN 'INFERRED_RESEARCH' THEN 0
        ELSE -1 END
$$;

CREATE OR REPLACE FUNCTION security_event_assurance_from_rank(value INTEGER)
RETURNS VARCHAR(32)
LANGUAGE SQL
IMMUTABLE
STRICT
AS $$
    SELECT CASE value
        WHEN 2 THEN 'PIT_VERIFIED'
        WHEN 1 THEN 'RECONSTRUCTED_VERIFIED'
        ELSE 'INFERRED_RESEARCH' END::VARCHAR(32)
$$;

CREATE OR REPLACE FUNCTION security_event_trust_rank(value TEXT)
RETURNS INTEGER
LANGUAGE SQL
IMMUTABLE
STRICT
AS $$
    SELECT CASE value
        WHEN 'OBSERVED' THEN 2
        WHEN 'BACKFILLED_VERIFIED' THEN 1
        ELSE 0 END
$$;

CREATE OR REPLACE FUNCTION security_event_trust_from_rank(value INTEGER)
RETURNS VARCHAR(32)
LANGUAGE SQL
IMMUTABLE
STRICT
AS $$
    SELECT CASE value
        WHEN 2 THEN 'OBSERVED'
        WHEN 1 THEN 'BACKFILLED_VERIFIED'
        ELSE 'BACKFILLED_INFERRED' END::VARCHAR(32)
$$;

CREATE OR REPLACE FUNCTION compute_security_identity_logical_key(
    record_namespace TEXT,
    identity_authority TEXT,
    identity_stable_id TEXT,
    identity_contract_version TEXT
)
RETURNS VARCHAR(256)
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE material BYTEA := ''::BYTEA;
BEGIN
    material := ingestion_canonical_append(material, 'INGESTION_CANONICAL_V1');
    material := ingestion_canonical_append(material, 'SECURITY_IDENTITY_LOGICAL_KEY_V1');
    material := ingestion_canonical_append(material, record_namespace);
    material := ingestion_canonical_append(material, identity_authority);
    material := ingestion_canonical_append(material, identity_stable_id);
    material := ingestion_canonical_append(material, identity_contract_version);
    RETURN 'security:v1:' || ingestion_canonical_sha256(material);
END;
$$;

CREATE OR REPLACE FUNCTION compute_source_security_mapping_logical_key(
    record_namespace TEXT,
    source TEXT,
    source_version TEXT,
    source_instrument_id TEXT,
    mapping_contract_version TEXT
)
RETURNS VARCHAR(256)
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE material BYTEA := ''::BYTEA;
BEGIN
    material := ingestion_canonical_append(material, 'INGESTION_CANONICAL_V1');
    material := ingestion_canonical_append(
        material, 'SOURCE_SECURITY_IDENTITY_MAPPING_LOGICAL_KEY_V1');
    material := ingestion_canonical_append(material, record_namespace);
    material := ingestion_canonical_append(material, source);
    material := ingestion_canonical_append(material, source_version);
    material := ingestion_canonical_append(material, source_instrument_id);
    material := ingestion_canonical_append(material, mapping_contract_version);
    RETURN 'mapping:v1:' || ingestion_canonical_sha256(material);
END;
$$;

CREATE OR REPLACE FUNCTION compute_security_event_logical_key(
    raw_record_logical_key TEXT,
    event_contract_version TEXT,
    event_type TEXT
)
RETURNS VARCHAR(256)
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE material BYTEA := ''::BYTEA;
BEGIN
    material := ingestion_canonical_append(material, 'INGESTION_CANONICAL_V1');
    material := ingestion_canonical_append(
        material, 'SECURITY_STATUS_EVENT_LOGICAL_KEY_V1');
    material := ingestion_canonical_append(material, raw_record_logical_key);
    material := ingestion_canonical_append(material, event_contract_version);
    material := ingestion_canonical_append(material, event_type);
    RETURN 'event:v1:' || ingestion_canonical_sha256(material);
END;
$$;

CREATE OR REPLACE FUNCTION compute_security_normalization_result_hash(
    attempt_logical_key TEXT,
    outcome TEXT,
    event_logical_key TEXT,
    security_logical_key TEXT,
    predecessor_event_logical_key TEXT,
    normalizer_version TEXT,
    transition_rule_version TEXT,
    assurance_level TEXT,
    error_code TEXT
)
RETURNS VARCHAR(64)
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE material BYTEA := ''::BYTEA;
BEGIN
    material := ingestion_canonical_append(material, 'INGESTION_CANONICAL_V1');
    material := ingestion_canonical_append(
        material, 'SECURITY_STATUS_NORMALIZATION_RESULT_V1');
    material := ingestion_canonical_append(material, attempt_logical_key);
    material := ingestion_canonical_append(material, outcome);
    material := ingestion_canonical_append(material, event_logical_key);
    material := ingestion_canonical_append(material, security_logical_key);
    material := ingestion_canonical_append(material, predecessor_event_logical_key);
    material := ingestion_canonical_append(material, normalizer_version);
    material := ingestion_canonical_append(material, transition_rule_version);
    material := ingestion_canonical_append(material, assurance_level);
    material := ingestion_canonical_append(material, error_code);
    RETURN ingestion_canonical_sha256(material);
END;
$$;

CREATE OR REPLACE FUNCTION compute_security_event_lineage_hash(
    event_logical_key TEXT,
    dataset_logical_key TEXT,
    raw_record_logical_key TEXT,
    ingestion_run_logical_key TEXT,
    attempt_logical_key TEXT,
    mapping_logical_key TEXT,
    security_logical_key TEXT,
    predecessor_event_logical_key TEXT,
    record_namespace TEXT,
    event_contract_version TEXT,
    normalizer_version TEXT,
    transition_rule_version TEXT,
    event_payload_hash TEXT,
    assurance_level TEXT
)
RETURNS VARCHAR(64)
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE material BYTEA := ''::BYTEA;
BEGIN
    material := ingestion_canonical_append(material, 'INGESTION_CANONICAL_V1');
    material := ingestion_canonical_append(material, 'SECURITY_STATUS_EVENT_LINEAGE_V1');
    material := ingestion_canonical_append(material, event_logical_key);
    material := ingestion_canonical_append(material, dataset_logical_key);
    material := ingestion_canonical_append(material, raw_record_logical_key);
    material := ingestion_canonical_append(material, ingestion_run_logical_key);
    material := ingestion_canonical_append(material, attempt_logical_key);
    material := ingestion_canonical_append(material, mapping_logical_key);
    material := ingestion_canonical_append(material, security_logical_key);
    material := ingestion_canonical_append(material, predecessor_event_logical_key);
    material := ingestion_canonical_append(material, record_namespace);
    material := ingestion_canonical_append(material, event_contract_version);
    material := ingestion_canonical_append(material, normalizer_version);
    material := ingestion_canonical_append(material, transition_rule_version);
    material := ingestion_canonical_append(material, event_payload_hash);
    material := ingestion_canonical_append(material, assurance_level);
    RETURN ingestion_canonical_sha256(material);
END;
$$;

CREATE OR REPLACE FUNCTION security_status_raw_test_v1_is_valid(value JSONB)
RETURNS BOOLEAN
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    state JSONB;
    root_count INTEGER;
    state_count INTEGER;
    listed_value BOOLEAN;
    active_value BOOLEAN;
BEGIN
    IF value IS NULL OR jsonb_typeof(value) <> 'object' THEN RETURN FALSE; END IF;
    SELECT count(*) INTO root_count FROM jsonb_object_keys(value);
    IF root_count <> 3
       OR NOT value ?& ARRAY['schemaVersion', 'symbol', 'state']
       OR jsonb_typeof(value->'schemaVersion') <> 'string'
       OR value->>'schemaVersion' <> 'SECURITY_STATUS_RAW_TEST_V1'
       OR jsonb_typeof(value->'symbol') <> 'string'
       OR btrim(value->>'symbol') = ''
       OR length(value->>'symbol') > 12
       OR jsonb_typeof(value->'state') <> 'object' THEN
        RETURN FALSE;
    END IF;
    state := value->'state';
    SELECT count(*) INTO state_count FROM jsonb_object_keys(state);
    IF state_count <> 5
       OR NOT state ?& ARRAY['exchange', 'board', 'listed', 'active', 'isSt']
       OR jsonb_typeof(state->'exchange') <> 'string'
       OR state->>'exchange' NOT IN ('SSE', 'SZSE')
       OR jsonb_typeof(state->'board') <> 'string'
       OR btrim(state->>'board') = ''
       OR length(state->>'board') > 64
       OR jsonb_typeof(state->'listed') <> 'boolean'
       OR jsonb_typeof(state->'active') <> 'boolean'
       OR jsonb_typeof(state->'isSt') <> 'boolean' THEN
        RETURN FALSE;
    END IF;
    listed_value := (state->>'listed')::BOOLEAN;
    active_value := (state->>'active')::BOOLEAN;
    RETURN listed_value OR NOT active_value;
END;
$$;

CREATE OR REPLACE FUNCTION security_status_event_v1_is_valid(value JSONB)
RETURNS BOOLEAN
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    state JSONB;
    root_count INTEGER;
    state_count INTEGER;
    listed_value BOOLEAN;
    active_value BOOLEAN;
BEGIN
    IF value IS NULL OR jsonb_typeof(value) <> 'object' THEN RETURN FALSE; END IF;
    SELECT count(*) INTO root_count FROM jsonb_object_keys(value);
    IF root_count <> 2
       OR NOT value ?& ARRAY['schemaVersion', 'resultingState']
       OR jsonb_typeof(value->'schemaVersion') <> 'string'
       OR value->>'schemaVersion' <> 'SECURITY_STATUS_EVENT_V1'
       OR jsonb_typeof(value->'resultingState') <> 'object' THEN
        RETURN FALSE;
    END IF;
    state := value->'resultingState';
    SELECT count(*) INTO state_count FROM jsonb_object_keys(state);
    IF state_count <> 5
       OR NOT state ?& ARRAY['exchange', 'board', 'listed', 'active', 'isSt']
       OR jsonb_typeof(state->'exchange') <> 'string'
       OR state->>'exchange' NOT IN ('SSE', 'SZSE')
       OR jsonb_typeof(state->'board') <> 'string'
       OR btrim(state->>'board') = ''
       OR length(state->>'board') > 64
       OR jsonb_typeof(state->'listed') <> 'boolean'
       OR jsonb_typeof(state->'active') <> 'boolean'
       OR jsonb_typeof(state->'isSt') <> 'boolean' THEN
        RETURN FALSE;
    END IF;
    listed_value := (state->>'listed')::BOOLEAN;
    active_value := (state->>'active')::BOOLEAN;
    RETURN listed_value OR NOT active_value;
END;
$$;

CREATE OR REPLACE FUNCTION compute_security_status_event_v1_payload_hash(value JSONB)
RETURNS VARCHAR(64)
LANGUAGE plpgsql
IMMUTABLE
STRICT
AS $$
DECLARE
    state JSONB;
    canonical TEXT;
BEGIN
    IF NOT security_status_event_v1_is_valid(value) THEN
        RAISE EXCEPTION 'invalid SECURITY_STATUS_EVENT_V1 payload'
            USING ERRCODE = '23514';
    END IF;
    state := value->'resultingState';
    canonical := '{"schemaVersion":"SECURITY_STATUS_EVENT_V1","resultingState":{'
        || '"exchange":' || to_jsonb(state->>'exchange')::TEXT
        || ',"board":' || to_jsonb(state->>'board')::TEXT
        || ',"listed":' || CASE WHEN (state->>'listed')::BOOLEAN THEN 'true' ELSE 'false' END
        || ',"active":' || CASE WHEN (state->>'active')::BOOLEAN THEN 'true' ELSE 'false' END
        || ',"isSt":' || CASE WHEN (state->>'isSt')::BOOLEAN THEN 'true' ELSE 'false' END
        || '}}';
    RETURN encode(sha256(convert_to(canonical, 'UTF8')), 'hex');
END;
$$;

CREATE OR REPLACE FUNCTION security_status_event_v1_transition_valid(
    event_type TEXT,
    predecessor_payload JSONB,
    resulting_payload JSONB
)
RETURNS BOOLEAN
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE previous_state JSONB;
DECLARE next_state JSONB;
BEGIN
    IF NOT security_status_event_v1_is_valid(resulting_payload) THEN RETURN FALSE; END IF;
    next_state := resulting_payload->'resultingState';
    IF event_type = 'FULL_STATUS_SNAPSHOT' THEN RETURN predecessor_payload IS NULL; END IF;
    IF predecessor_payload IS NULL OR NOT security_status_event_v1_is_valid(predecessor_payload) THEN
        RETURN FALSE;
    END IF;
    previous_state := predecessor_payload->'resultingState';
    RETURN CASE event_type
        WHEN 'ST_CHANGE' THEN
            previous_state->>'exchange'=next_state->>'exchange'
            AND previous_state->>'board'=next_state->>'board'
            AND previous_state->>'listed'=next_state->>'listed'
            AND previous_state->>'active'=next_state->>'active'
            AND previous_state->>'isSt'<>next_state->>'isSt'
        WHEN 'BOARD_CHANGE' THEN
            previous_state->>'exchange'=next_state->>'exchange'
            AND previous_state->>'board'<>next_state->>'board'
            AND previous_state->>'listed'=next_state->>'listed'
            AND previous_state->>'active'=next_state->>'active'
            AND previous_state->>'isSt'=next_state->>'isSt'
        WHEN 'ACTIVE_CHANGE' THEN
            previous_state->>'exchange'=next_state->>'exchange'
            AND previous_state->>'board'=next_state->>'board'
            AND previous_state->>'listed'=next_state->>'listed'
            AND previous_state->>'active'<>next_state->>'active'
            AND previous_state->>'isSt'=next_state->>'isSt'
        WHEN 'EXCHANGE_CHANGE' THEN
            previous_state->>'exchange'<>next_state->>'exchange'
            AND previous_state->>'board'=next_state->>'board'
            AND previous_state->>'listed'=next_state->>'listed'
            AND previous_state->>'active'=next_state->>'active'
            AND previous_state->>'isSt'=next_state->>'isSt'
        WHEN 'LISTING' THEN
            previous_state->>'exchange'=next_state->>'exchange'
            AND previous_state->>'board'=next_state->>'board'
            AND (previous_state->>'listed')::BOOLEAN=FALSE
            AND (previous_state->>'active')::BOOLEAN=FALSE
            AND (next_state->>'listed')::BOOLEAN=TRUE
            AND (next_state->>'active')::BOOLEAN=TRUE
            AND previous_state->>'isSt'=next_state->>'isSt'
        WHEN 'DELISTING' THEN
            previous_state->>'exchange'=next_state->>'exchange'
            AND previous_state->>'board'=next_state->>'board'
            AND (previous_state->>'listed')::BOOLEAN=TRUE
            AND (next_state->>'listed')::BOOLEAN=FALSE
            AND (next_state->>'active')::BOOLEAN=FALSE
            AND previous_state->>'isSt'=next_state->>'isSt'
        ELSE FALSE
    END;
END;
$$;

CREATE OR REPLACE FUNCTION validate_security_identity_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.record_namespace NOT IN ('TEST', 'DEMO')
       OR NEW.assurance_level = 'PIT_VERIFIED'
       OR NEW.identity_contract_version <> 'SECURITY_IDENTITY_V1' THEN
        RAISE EXCEPTION 'security identity is restricted to TEST/DEMO V1 non-PIT facts'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.security_logical_key <> compute_security_identity_logical_key(
            NEW.record_namespace, NEW.identity_authority, NEW.identity_stable_id,
            NEW.identity_contract_version) THEN
        RAISE EXCEPTION 'security identity logical key is not canonical'
            USING ERRCODE = '23514';
    END IF;
    NEW.recorded_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION validate_source_security_mapping_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE identity_row security_identity_registry%ROWTYPE;
BEGIN
    SELECT * INTO STRICT identity_row
    FROM security_identity_registry WHERE id=NEW.security_identity_id FOR SHARE;
    IF NEW.record_namespace NOT IN ('TEST', 'DEMO')
       OR NEW.mapping_assurance_level = 'PIT_VERIFIED'
       OR NEW.mapping_contract_version <> 'SOURCE_SECURITY_IDENTITY_MAPPING_V1'
       OR NEW.record_namespace <> identity_row.record_namespace
       OR NEW.security_logical_key <> identity_row.security_logical_key
       OR security_event_assurance_rank(NEW.mapping_assurance_level)
          > security_event_assurance_rank(identity_row.assurance_level) THEN
        RAISE EXCEPTION 'source security mapping does not match its TEST/DEMO identity'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.mapping_logical_key <> compute_source_security_mapping_logical_key(
            NEW.record_namespace, NEW.source, NEW.source_version,
            NEW.source_instrument_id, NEW.mapping_contract_version) THEN
        RAISE EXCEPTION 'source security mapping logical key is not canonical'
            USING ERRCODE = '23514';
    END IF;
    NEW.recorded_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION validate_ingestion_manifest_contract_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE parent_version VARCHAR(80);
BEGIN
    IF NEW.manifest_contract_version = 'INGESTION_MANIFEST_V2_SECURITY_EVENT' THEN
        IF NEW.dataset_type <> 'SECURITY_STATUS'
           OR NEW.run_namespace NOT IN ('TEST', 'DEMO') THEN
            RAISE EXCEPTION 'Manifest V2 security event runs require TEST/DEMO SECURITY_STATUS'
                USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.manifest_contract_version <> 'INGESTION_MANIFEST_V1' THEN
        RAISE EXCEPTION 'unsupported ingestion manifest contract version'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.operation_type = 'RETRY' THEN
        SELECT manifest_contract_version INTO STRICT parent_version
        FROM market_data_ingestion_runs
        WHERE ingestion_run_logical_key=NEW.retry_of_run_logical_key;
        IF parent_version <> NEW.manifest_contract_version THEN
            RAISE EXCEPTION 'RETRY run must preserve its manifest contract version'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION validate_v2_security_attempt_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE run_row market_data_ingestion_runs%ROWTYPE;
BEGIN
    SELECT * INTO STRICT run_row FROM market_data_ingestion_runs
    WHERE id=NEW.ingestion_run_id FOR SHARE;
    IF run_row.manifest_contract_version = 'INGESTION_MANIFEST_V2_SECURITY_EVENT' THEN
        IF run_row.dataset_type <> 'SECURITY_STATUS'
           OR run_row.run_namespace NOT IN ('TEST', 'DEMO')
           OR run_row.status <> 'RUNNING' OR run_row.sealed_at IS NOT NULL THEN
            RAISE EXCEPTION 'Manifest V2 attempt is outside an open TEST/DEMO security run'
                USING ERRCODE = '55000';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION validate_security_status_event_insert_v8()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    raw_row security_status_raw_records%ROWTYPE;
    dataset_row market_data_dataset_versions%ROWTYPE;
    mapping_row source_security_identity_mappings%ROWTYPE;
    predecessor_row security_status_events%ROWTYPE;
    expected_trust VARCHAR(32);
    resolved_fields INTEGER;
    raw_state JSONB;
    event_state JSONB;
BEGIN
    resolved_fields := (NEW.event_logical_key IS NOT NULL)::INTEGER
        + (NEW.event_contract_version IS NOT NULL)::INTEGER
        + (NEW.security_logical_key IS NOT NULL)::INTEGER;
    IF resolved_fields = 0 THEN
        -- Explicitly isolated V6 compatibility rows can remain append-only Legacy facts.
        IF NEW.record_namespace <> 'DEMO'
           OR NEW.assurance_level <> 'INFERRED_RESEARCH' THEN
            RAISE EXCEPTION 'unresolved Legacy event must remain DEMO/INFERRED_RESEARCH'
                USING ERRCODE = '23514';
        END IF;
        IF NEW.supersedes_event_id IS NOT NULL THEN
            SELECT * INTO STRICT predecessor_row
            FROM security_status_events WHERE id=NEW.supersedes_event_id;
            IF predecessor_row.event_logical_key IS NOT NULL THEN
                RAISE EXCEPTION 'Legacy event cannot extend a resolved materialization chain'
                    USING ERRCODE = '23514';
            END IF;
        END IF;
        RETURN NEW;
    END IF;
    IF resolved_fields <> 3
       OR NEW.record_namespace NOT IN ('TEST', 'DEMO')
       OR NEW.assurance_level = 'PIT_VERIFIED'
       OR NEW.event_contract_version <> 'SECURITY_STATUS_EVENT_V1' THEN
        RAISE EXCEPTION 'resolved event requires complete TEST/DEMO V1 logical identity'
            USING ERRCODE = '23514';
    END IF;

    -- Serialize every resolved chain decision by schema and stable identity.  The
    -- schema component keeps independently migrated integration-test schemas isolated.
    PERFORM pg_advisory_xact_lock(hashtextextended(
        current_schema() || chr(31) || NEW.security_logical_key, 0));

    SELECT * INTO STRICT raw_row
    FROM security_status_raw_records
    WHERE record_namespace=NEW.record_namespace
      AND source=NEW.source AND source_version=NEW.source_version
      AND source_record_id=NEW.source_record_id
      AND source_revision=NEW.source_revision
    FOR SHARE;
    IF raw_row.dataset_version_id <> NEW.dataset_version_id
       OR raw_row.source_instrument_id IS NULL
       OR raw_row.source_effective_date IS NULL
       OR NOT security_status_raw_test_v1_is_valid(raw_row.raw_payload) THEN
        RAISE EXCEPTION 'resolved event does not have a valid TEST/DEMO raw source fact'
            USING ERRCODE = '23514';
    END IF;
    SELECT * INTO STRICT dataset_row
    FROM market_data_dataset_versions WHERE id=NEW.dataset_version_id;
    SELECT * INTO STRICT mapping_row
    FROM source_security_identity_mappings
    WHERE record_namespace=NEW.record_namespace
      AND source=NEW.source AND source_version=NEW.source_version
      AND source_instrument_id=raw_row.source_instrument_id
      AND mapping_contract_version='SOURCE_SECURITY_IDENTITY_MAPPING_V1';
    IF mapping_row.security_logical_key <> NEW.security_logical_key THEN
        RAISE EXCEPTION 'resolved event security identity does not match explicit mapping'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.event_logical_key <> compute_security_event_logical_key(
            raw_row.raw_record_logical_key, NEW.event_contract_version, NEW.event_type) THEN
        RAISE EXCEPTION 'resolved event logical key is not canonical'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.symbol <> raw_row.raw_payload->>'symbol'
       OR NEW.effective_from <> raw_row.source_effective_date
       OR NEW.effective_to IS NOT NULL
       OR NEW.published_at IS DISTINCT FROM raw_row.source_published_at
       OR NOT security_status_event_v1_is_valid(NEW.payload)
       OR NEW.payload_hash <> compute_security_status_event_v1_payload_hash(NEW.payload) THEN
        RAISE EXCEPTION 'resolved event payload/time does not match its immutable raw fact'
            USING ERRCODE = '23514';
    END IF;
    raw_state := raw_row.raw_payload->'state';
    event_state := NEW.payload->'resultingState';
    IF raw_state <> event_state THEN
        RAISE EXCEPTION 'resolved event resultingState does not match normalized raw state'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.event_type = 'FULL_STATUS_SNAPSHOT' THEN
        IF NEW.supersedes_event_id IS NOT NULL THEN
            RAISE EXCEPTION 'FULL_STATUS_SNAPSHOT must not have a predecessor'
                USING ERRCODE = '23514';
        END IF;
    ELSE
        IF NEW.supersedes_event_id IS NULL THEN
            RAISE EXCEPTION 'incremental V1 event requires a predecessor'
                USING ERRCODE = '23514';
        END IF;
        SELECT * INTO STRICT predecessor_row
        FROM security_status_events WHERE id=NEW.supersedes_event_id FOR SHARE;
        IF predecessor_row.event_logical_key IS NULL
           OR predecessor_row.record_namespace <> NEW.record_namespace
           OR predecessor_row.security_logical_key <> NEW.security_logical_key
           OR predecessor_row.event_contract_version <> NEW.event_contract_version
           OR NEW.effective_from <= predecessor_row.effective_from THEN
            RAISE EXCEPTION 'incremental V1 event predecessor chain is invalid'
                USING ERRCODE = '23514';
        END IF;
        IF EXISTS (
            SELECT 1
            FROM security_status_normalization_results result
            JOIN security_status_processing_attempts attempt
              ON attempt.id=result.processing_attempt_id
            JOIN security_status_raw_records no_change_raw
              ON no_change_raw.id=attempt.raw_record_id
            WHERE result.outcome='NO_STATE_CHANGE'
              AND result.security_logical_key=NEW.security_logical_key
              AND result.predecessor_event_logical_key=predecessor_row.event_logical_key
              AND no_change_raw.record_namespace=NEW.record_namespace
              AND no_change_raw.source_effective_date=NEW.effective_from) THEN
            RAISE EXCEPTION 'resolved event conflicts with a same-date NO_STATE_CHANGE fact'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    IF NOT security_status_event_v1_transition_valid(
            NEW.event_type,
            CASE WHEN NEW.supersedes_event_id IS NULL THEN NULL ELSE predecessor_row.payload END,
            NEW.payload) THEN
        RAISE EXCEPTION 'resolved event violates the frozen V1 transition contract'
            USING ERRCODE = '23514';
    END IF;

    expected_trust := security_event_trust_from_rank(least(
        security_event_trust_rank(dataset_row.trust_level),
        security_event_trust_rank(raw_row.source_trust_level),
        CASE WHEN NEW.supersedes_event_id IS NULL THEN 2
             ELSE security_event_trust_rank(predecessor_row.trust_level) END));
    IF NEW.trust_level <> expected_trust THEN
        RAISE EXCEPTION 'resolved event trust must be the conservative provenance minimum'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION reject_resolved_event_history_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    source_event_logical_key VARCHAR(256);
BEGIN
    SELECT event_logical_key INTO STRICT source_event_logical_key
    FROM security_status_events
    WHERE id=NEW.source_event_id
    FOR SHARE;
    IF source_event_logical_key IS NOT NULL THEN
        RAISE EXCEPTION
            'V8 resolved event must wait for stage 2D-2B-2 history projector'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION validate_security_event_lineage_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    event_row security_status_events%ROWTYPE;
    dataset_row market_data_dataset_versions%ROWTYPE;
    raw_row security_status_raw_records%ROWTYPE;
    run_row market_data_ingestion_runs%ROWTYPE;
    attempt_row security_status_processing_attempts%ROWTYPE;
    identity_row security_identity_registry%ROWTYPE;
    mapping_row source_security_identity_mappings%ROWTYPE;
    predecessor_row security_status_events%ROWTYPE;
    result_row security_status_normalization_results%ROWTYPE;
    expected_assurance VARCHAR(32);
    expected_lineage_hash VARCHAR(64);
    attached BOOLEAN;
BEGIN
    SELECT * INTO STRICT event_row FROM security_status_events
    WHERE id=NEW.event_id FOR SHARE;
    PERFORM pg_advisory_xact_lock(hashtextextended(
        current_schema() || chr(31) || event_row.security_logical_key, 0));
    SELECT * INTO STRICT dataset_row FROM market_data_dataset_versions
    WHERE id=NEW.dataset_version_id;
    SELECT * INTO STRICT raw_row FROM security_status_raw_records
    WHERE id=NEW.raw_record_id FOR SHARE;
    SELECT * INTO STRICT run_row FROM market_data_ingestion_runs
    WHERE id=NEW.ingestion_run_id FOR SHARE;
    SELECT * INTO STRICT attempt_row FROM security_status_processing_attempts
    WHERE id=NEW.processing_attempt_id FOR SHARE;
    SELECT * INTO STRICT identity_row FROM security_identity_registry
    WHERE id=NEW.security_identity_id FOR SHARE;
    SELECT * INTO STRICT mapping_row FROM source_security_identity_mappings
    WHERE id=NEW.mapping_id FOR SHARE;

    IF run_row.status <> 'RUNNING' OR run_row.sealed_at IS NOT NULL
       OR run_row.manifest_contract_version <> 'INGESTION_MANIFEST_V2_SECURITY_EVENT'
       OR run_row.dataset_type <> 'SECURITY_STATUS'
       OR run_row.run_namespace NOT IN ('TEST', 'DEMO') THEN
        RAISE EXCEPTION 'event lineage requires an open TEST/DEMO Manifest V2 run'
            USING ERRCODE = '55000';
    END IF;
    SELECT EXISTS(
        SELECT 1 FROM security_status_ingestion_run_records
        WHERE ingestion_run_id=run_row.id AND raw_record_id=raw_row.id
    ) INTO attached;
    IF NOT attached
       OR attempt_row.ingestion_run_id <> run_row.id
       OR attempt_row.raw_record_id <> raw_row.id
       OR attempt_row.status <> 'COMPLETED'
       OR run_row.dataset_version_id <> dataset_row.id
       OR raw_row.dataset_version_id <> dataset_row.id
       OR raw_row.record_namespace <> run_row.run_namespace
       OR event_row.dataset_version_id <> dataset_row.id
       OR event_row.record_namespace <> run_row.run_namespace THEN
        RAISE EXCEPTION 'event lineage run/raw/attempt/dataset chain is inconsistent'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.event_logical_key <> event_row.event_logical_key
       OR NEW.dataset_logical_key <> run_row.dataset_logical_key
       OR NEW.raw_record_logical_key <> raw_row.raw_record_logical_key
       OR NEW.ingestion_run_logical_key <> run_row.ingestion_run_logical_key
       OR NEW.attempt_logical_key <> attempt_row.attempt_logical_key
       OR NEW.security_logical_key <> identity_row.security_logical_key
       OR NEW.mapping_logical_key <> mapping_row.mapping_logical_key
       OR NEW.record_namespace <> run_row.run_namespace
       OR NEW.event_contract_version <> event_row.event_contract_version THEN
        RAISE EXCEPTION 'event lineage logical identities are inconsistent'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.normalizer_version <> 'SECURITY_STATUS_NORMALIZER_V1'
       OR NEW.transition_rule_version <> 'SECURITY_STATUS_TRANSITION_V1' THEN
        RAISE EXCEPTION 'event lineage requires the frozen V1 materialization rules'
            USING ERRCODE = '23514';
    END IF;
    IF mapping_row.security_identity_id <> identity_row.id
       OR mapping_row.security_logical_key <> identity_row.security_logical_key
       OR mapping_row.record_namespace <> run_row.run_namespace
       OR mapping_row.source <> raw_row.source
       OR mapping_row.source_version <> raw_row.source_version
       OR mapping_row.source_instrument_id <> raw_row.source_instrument_id THEN
        RAISE EXCEPTION 'event lineage identity mapping is outside the raw chain'
            USING ERRCODE = '23514';
    END IF;
    IF event_row.source <> raw_row.source
       OR event_row.source_version <> raw_row.source_version
       OR event_row.source_record_id <> raw_row.source_record_id
       OR event_row.source_revision <> raw_row.source_revision
       OR event_row.payload_hash <> compute_security_status_event_v1_payload_hash(event_row.payload)
       OR event_row.known_at <> attempt_row.derived_known_from THEN
        RAISE EXCEPTION 'event lineage source/payload/knowledge facts are inconsistent'
            USING ERRCODE = '23514';
    END IF;

    SELECT * INTO result_row FROM security_status_normalization_results
    WHERE processing_attempt_id=attempt_row.id;
    IF FOUND AND (result_row.outcome <> 'EVENT_MATERIALIZED'
                  OR result_row.event_id IS DISTINCT FROM event_row.id
                  OR result_row.event_logical_key IS DISTINCT FROM event_row.event_logical_key) THEN
        RAISE EXCEPTION 'event lineage conflicts with its attempt normalization result'
            USING ERRCODE = '23514';
    END IF;

    IF event_row.event_type = 'FULL_STATUS_SNAPSHOT' THEN
        IF NEW.predecessor_event_id IS NOT NULL
           OR NEW.predecessor_event_logical_key IS NOT NULL
           OR event_row.supersedes_event_id IS NOT NULL THEN
            RAISE EXCEPTION 'FULL event lineage must not have a predecessor'
                USING ERRCODE = '23514';
        END IF;
    ELSE
        SELECT * INTO STRICT predecessor_row FROM security_status_events
        WHERE id=NEW.predecessor_event_id FOR SHARE;
        IF event_row.supersedes_event_id <> predecessor_row.id
           OR NEW.predecessor_event_logical_key <> predecessor_row.event_logical_key
           OR predecessor_row.record_namespace <> event_row.record_namespace
           OR predecessor_row.security_logical_key <> event_row.security_logical_key
           OR predecessor_row.event_contract_version <> event_row.event_contract_version THEN
            RAISE EXCEPTION 'incremental event lineage predecessor is inconsistent'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    expected_assurance := security_event_assurance_from_rank(least(
        security_event_assurance_rank(attempt_row.assurance_level),
        security_event_assurance_rank(identity_row.assurance_level),
        security_event_assurance_rank(mapping_row.mapping_assurance_level),
        CASE WHEN event_row.supersedes_event_id IS NULL THEN 1
             ELSE security_event_assurance_rank(predecessor_row.assurance_level) END,
        1));
    IF NEW.assurance_level <> expected_assurance
       OR event_row.assurance_level <> expected_assurance THEN
        RAISE EXCEPTION 'event/lineage assurance is not the conservative chain minimum'
            USING ERRCODE = '23514';
    END IF;
    expected_lineage_hash := compute_security_event_lineage_hash(
        NEW.event_logical_key, NEW.dataset_logical_key, NEW.raw_record_logical_key,
        NEW.ingestion_run_logical_key, NEW.attempt_logical_key, NEW.mapping_logical_key,
        NEW.security_logical_key, NEW.predecessor_event_logical_key,
        NEW.record_namespace, NEW.event_contract_version, NEW.normalizer_version,
        NEW.transition_rule_version, event_row.payload_hash, NEW.assurance_level);
    IF NEW.lineage_hash <> expected_lineage_hash THEN
        RAISE EXCEPTION 'event lineage hash is not canonical'
            USING ERRCODE = '23514';
    END IF;
    NEW.recorded_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION validate_security_normalization_result_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    attempt_row security_status_processing_attempts%ROWTYPE;
    run_row market_data_ingestion_runs%ROWTYPE;
    raw_row security_status_raw_records%ROWTYPE;
    event_row security_status_events%ROWTYPE;
    predecessor_row security_status_events%ROWTYPE;
    identity_row security_identity_registry%ROWTYPE;
    mapping_row source_security_identity_mappings%ROWTYPE;
    lineage_row security_status_event_lineage%ROWTYPE;
    expected_outcome_status VARCHAR(32);
    expected_assurance VARCHAR(32);
    current_head_event_logical_key VARCHAR(256);
    mapping_found BOOLEAN := FALSE;
    predecessor_found BOOLEAN := FALSE;
BEGIN
    SELECT * INTO STRICT attempt_row FROM security_status_processing_attempts
    WHERE id=NEW.processing_attempt_id FOR SHARE;
    SELECT * INTO STRICT run_row FROM market_data_ingestion_runs
    WHERE id=attempt_row.ingestion_run_id FOR SHARE;
    SELECT * INTO STRICT raw_row FROM security_status_raw_records
    WHERE id=attempt_row.raw_record_id FOR SHARE;
    IF run_row.manifest_contract_version <> 'INGESTION_MANIFEST_V2_SECURITY_EVENT'
       OR run_row.dataset_type <> 'SECURITY_STATUS'
       OR run_row.run_namespace NOT IN ('TEST', 'DEMO')
       OR run_row.status <> 'RUNNING' OR run_row.sealed_at IS NOT NULL
       OR raw_row.record_namespace <> run_row.run_namespace
       OR NOT EXISTS (
            SELECT 1 FROM security_status_ingestion_run_records
            WHERE ingestion_run_id=run_row.id AND raw_record_id=raw_row.id) THEN
        RAISE EXCEPTION 'normalization result requires an open attached Manifest V2 security fact'
            USING ERRCODE = '55000';
    END IF;
    IF NEW.attempt_logical_key <> attempt_row.attempt_logical_key THEN
        RAISE EXCEPTION 'normalization result attempt logical identity mismatch'
            USING ERRCODE = '23514';
    END IF;
    expected_outcome_status := CASE NEW.outcome
        WHEN 'EVENT_MATERIALIZED' THEN 'COMPLETED'
        WHEN 'EVENT_REUSED' THEN 'COMPLETED'
        WHEN 'NO_STATE_CHANGE' THEN 'COMPLETED'
        WHEN 'IDENTITY_UNRESOLVED' THEN 'IDENTITY_UNRESOLVED'
        WHEN 'UNSUPPORTED_CONTRACT' THEN 'UNSUPPORTED_CONTRACT'
        WHEN 'CONFLICT' THEN 'CONFLICT'
        WHEN 'PROJECTION_FAILED' THEN 'PROJECTION_FAILED'
        WHEN 'REJECTED' THEN 'REJECTED'
        ELSE NULL END;
    IF expected_outcome_status IS NULL OR attempt_row.status <> expected_outcome_status
       OR NEW.error_code IS DISTINCT FROM attempt_row.error_code THEN
        RAISE EXCEPTION 'normalization outcome does not match terminal attempt status/error'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.outcome IN ('EVENT_MATERIALIZED', 'EVENT_REUSED', 'NO_STATE_CHANGE',
                       'IDENTITY_UNRESOLVED')
       AND (raw_row.source_instrument_id IS NULL
            OR raw_row.source_effective_date IS NULL
            OR NOT security_status_raw_test_v1_is_valid(raw_row.raw_payload)) THEN
        RAISE EXCEPTION 'normalization outcome requires a valid TEST/DEMO raw contract'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.outcome <> 'UNSUPPORTED_CONTRACT'
       AND (attempt_row.contract_version <> 'SECURITY_STATUS_RAW_TEST_V1'
            OR NEW.normalizer_version <> 'SECURITY_STATUS_NORMALIZER_V1'
            OR NEW.transition_rule_version <> 'SECURITY_STATUS_TRANSITION_V1') THEN
        RAISE EXCEPTION 'normalization requires the frozen V1 materialization contracts'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.outcome = 'IDENTITY_UNRESOLVED' THEN
        IF NEW.security_logical_key IS NOT NULL
           OR NEW.predecessor_event_logical_key IS NOT NULL
           OR EXISTS (
                SELECT 1 FROM source_security_identity_mappings mapping
                WHERE mapping.record_namespace=run_row.run_namespace
                  AND mapping.source=raw_row.source
                  AND mapping.source_version=raw_row.source_version
                  AND mapping.source_instrument_id=raw_row.source_instrument_id
                  AND mapping.mapping_contract_version=
                      'SOURCE_SECURITY_IDENTITY_MAPPING_V1') THEN
            RAISE EXCEPTION 'IDENTITY_UNRESOLVED requires an absent explicit V1 mapping'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    expected_assurance := CASE
        WHEN NEW.outcome='PROJECTION_FAILED' AND NEW.security_logical_key IS NULL
            THEN 'INFERRED_RESEARCH'
        ELSE attempt_row.assurance_level
    END;
    IF NEW.security_logical_key IS NOT NULL THEN
        PERFORM pg_advisory_xact_lock(hashtextextended(
            current_schema() || chr(31) || NEW.security_logical_key, 0));
        SELECT mapping.* INTO STRICT mapping_row
        FROM source_security_identity_mappings mapping
        WHERE mapping.record_namespace=run_row.run_namespace
          AND mapping.source=raw_row.source
          AND mapping.source_version=raw_row.source_version
          AND mapping.source_instrument_id=raw_row.source_instrument_id
          AND mapping.mapping_contract_version='SOURCE_SECURITY_IDENTITY_MAPPING_V1';
        SELECT * INTO STRICT identity_row FROM security_identity_registry
        WHERE id=mapping_row.security_identity_id;
        mapping_found := TRUE;
        IF mapping_row.security_logical_key <> NEW.security_logical_key
           OR identity_row.security_logical_key <> NEW.security_logical_key THEN
            RAISE EXCEPTION 'normalization result stable identity mismatch'
                USING ERRCODE = '23514';
        END IF;
        expected_assurance := security_event_assurance_from_rank(least(
            security_event_assurance_rank(expected_assurance),
            security_event_assurance_rank(identity_row.assurance_level),
            security_event_assurance_rank(mapping_row.mapping_assurance_level), 1));
    END IF;
    IF NEW.predecessor_event_logical_key IS NOT NULL THEN
        SELECT * INTO STRICT predecessor_row FROM security_status_events
        WHERE event_logical_key=NEW.predecessor_event_logical_key;
        predecessor_found := TRUE;
        IF predecessor_row.record_namespace <> run_row.run_namespace
           OR predecessor_row.security_logical_key <> NEW.security_logical_key
           OR predecessor_row.event_contract_version <> 'SECURITY_STATUS_EVENT_V1' THEN
            RAISE EXCEPTION 'normalization predecessor is outside the stable identity chain'
                USING ERRCODE = '23514';
        END IF;
        expected_assurance := security_event_assurance_from_rank(least(
            security_event_assurance_rank(expected_assurance),
            security_event_assurance_rank(predecessor_row.assurance_level), 1));
    END IF;

    IF NEW.outcome='PROJECTION_FAILED' AND mapping_found THEN
        SELECT candidate.event_logical_key INTO current_head_event_logical_key
        FROM security_status_events candidate
        WHERE candidate.record_namespace=run_row.run_namespace
          AND candidate.security_logical_key=NEW.security_logical_key
          AND candidate.event_contract_version='SECURITY_STATUS_EVENT_V1'
          AND NOT EXISTS (
              SELECT 1 FROM security_status_events successor
              WHERE successor.supersedes_event_id=candidate.id
                AND successor.event_logical_key IS NOT NULL);
        IF current_head_event_logical_key IS DISTINCT FROM
                NEW.predecessor_event_logical_key THEN
            RAISE EXCEPTION 'PROJECTION_FAILED predecessor must be the current resolved chain head'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    IF NEW.outcome IN ('EVENT_MATERIALIZED', 'EVENT_REUSED') THEN
        SELECT * INTO STRICT event_row FROM security_status_events WHERE id=NEW.event_id;
        SELECT * INTO STRICT lineage_row FROM security_status_event_lineage
        WHERE event_id=event_row.id;
        IF NOT mapping_found
           OR NEW.event_logical_key <> event_row.event_logical_key
           OR NEW.security_logical_key <> event_row.security_logical_key
           OR event_row.record_namespace <> run_row.run_namespace
           OR event_row.dataset_version_id <> run_row.dataset_version_id
           OR raw_row.dataset_version_id <> run_row.dataset_version_id
           OR event_row.source <> raw_row.source
           OR event_row.source_version <> raw_row.source_version
           OR event_row.source_record_id <> raw_row.source_record_id
           OR event_row.source_revision <> raw_row.source_revision
           OR event_row.event_logical_key <> compute_security_event_logical_key(
                raw_row.raw_record_logical_key,
                event_row.event_contract_version,
                event_row.event_type)
           OR event_row.security_logical_key <> mapping_row.security_logical_key
           OR lineage_row.event_id <> event_row.id
           OR lineage_row.event_logical_key <> event_row.event_logical_key
           OR lineage_row.raw_record_id <> raw_row.id
           OR lineage_row.raw_record_logical_key <> raw_row.raw_record_logical_key
           OR lineage_row.dataset_version_id <> run_row.dataset_version_id
           OR lineage_row.dataset_logical_key <> run_row.dataset_logical_key
           OR lineage_row.security_identity_id <> identity_row.id
           OR lineage_row.security_logical_key <> mapping_row.security_logical_key
           OR lineage_row.mapping_id <> mapping_row.id
           OR lineage_row.mapping_logical_key <> mapping_row.mapping_logical_key
           OR lineage_row.record_namespace <> run_row.run_namespace
           OR lineage_row.event_contract_version <> event_row.event_contract_version
           OR NEW.predecessor_event_logical_key IS DISTINCT FROM
                lineage_row.predecessor_event_logical_key
           OR NEW.normalizer_version <> lineage_row.normalizer_version
           OR NEW.transition_rule_version <> lineage_row.transition_rule_version
           OR event_row.assurance_level <> expected_assurance
           OR lineage_row.assurance_level <> expected_assurance THEN
            RAISE EXCEPTION
                'normalization event/raw/dataset lineage chain or assurance is inconsistent'
                USING ERRCODE = '23514';
        END IF;
        IF NEW.outcome = 'EVENT_MATERIALIZED'
           AND lineage_row.processing_attempt_id <> attempt_row.id THEN
            RAISE EXCEPTION 'EVENT_MATERIALIZED must own the event lineage attempt'
                USING ERRCODE = '23514';
        ELSIF NEW.outcome = 'EVENT_REUSED'
           AND lineage_row.processing_attempt_id = attempt_row.id THEN
            RAISE EXCEPTION 'EVENT_REUSED must reference a previously materialized event'
                USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.outcome = 'NO_STATE_CHANGE' THEN
        IF NOT mapping_found OR NOT predecessor_found
           OR raw_row.raw_payload->>'symbol' <> predecessor_row.symbol
           OR raw_row.raw_payload->'state' <> predecessor_row.payload->'resultingState'
           OR raw_row.source_effective_date <= predecessor_row.effective_from
           OR EXISTS (
                SELECT 1 FROM security_status_events successor
                WHERE successor.supersedes_event_id=predecessor_row.id
                  AND successor.event_logical_key IS NOT NULL) THEN
            RAISE EXCEPTION
                'NO_STATE_CHANGE requires the same later-dated current chain-head state'
            USING ERRCODE = '23514';
        END IF;
    END IF;
    IF NEW.outcome <> 'EVENT_MATERIALIZED'
       AND EXISTS (
            SELECT 1 FROM security_status_event_lineage lineage
            WHERE lineage.processing_attempt_id=attempt_row.id) THEN
        RAISE EXCEPTION 'only EVENT_MATERIALIZED may own event lineage'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.assurance_level <> expected_assurance THEN
        RAISE EXCEPTION 'normalization assurance is not the conservative chain minimum'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.result_hash <> compute_security_normalization_result_hash(
            NEW.attempt_logical_key, NEW.outcome, NEW.event_logical_key,
            NEW.security_logical_key, NEW.predecessor_event_logical_key,
            NEW.normalizer_version, NEW.transition_rule_version,
            NEW.assurance_level, NEW.error_code) THEN
        RAISE EXCEPTION 'normalization result hash is not canonical'
            USING ERRCODE = '23514';
    END IF;
    NEW.recorded_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION ensure_v2_attempt_has_result()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE contract_version VARCHAR(80);
BEGIN
    SELECT manifest_contract_version INTO STRICT contract_version
    FROM market_data_ingestion_runs WHERE id=NEW.ingestion_run_id;
    IF contract_version = 'INGESTION_MANIFEST_V2_SECURITY_EVENT'
       AND NOT EXISTS (
            SELECT 1 FROM security_status_normalization_results
            WHERE processing_attempt_id=NEW.id) THEN
        RAISE EXCEPTION 'Manifest V2 security attempt requires one normalization result'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE OR REPLACE FUNCTION ensure_resolved_event_has_lineage()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.event_logical_key IS NOT NULL
       AND NOT EXISTS (
            SELECT 1 FROM security_status_event_lineage WHERE event_id=NEW.id) THEN
        RAISE EXCEPTION 'resolved security event requires one authoritative lineage'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE OR REPLACE FUNCTION compute_security_event_manifest_v2_hash(
    run_id BIGINT,
    terminal_status TEXT,
    expected_count INTEGER,
    received_count INTEGER,
    accepted_count INTEGER,
    rejected_count INTEGER,
    assurance_level TEXT
)
RETURNS VARCHAR(64)
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    run_row market_data_ingestion_runs%ROWTYPE;
    dataset_row market_data_dataset_versions%ROWTYPE;
    entry_count INTEGER;
    entry_row RECORD;
    material BYTEA := ''::BYTEA;
BEGIN
    SELECT * INTO STRICT run_row FROM market_data_ingestion_runs WHERE id=run_id;
    SELECT * INTO STRICT dataset_row FROM market_data_dataset_versions
    WHERE id=run_row.dataset_version_id;
    IF run_row.manifest_contract_version <> 'INGESTION_MANIFEST_V2_SECURITY_EVENT'
       OR run_row.dataset_type <> 'SECURITY_STATUS' THEN
        RAISE EXCEPTION 'Manifest V2 hash requires a security event run'
            USING ERRCODE = '23514';
    END IF;
    SELECT count(*) INTO entry_count
    FROM security_status_processing_attempts WHERE ingestion_run_id=run_id;

    material := ingestion_canonical_append(material, 'INGESTION_CANONICAL_V1');
    material := ingestion_canonical_append(
        material, 'INGESTION_MANIFEST_V2_SECURITY_EVENT');
    material := ingestion_canonical_append(material, run_row.ingestion_run_logical_key);
    material := ingestion_canonical_append(material, run_row.dataset_logical_key);
    material := ingestion_canonical_append(material, run_row.dataset_type);
    material := ingestion_canonical_append(material, run_row.run_namespace);
    material := ingestion_canonical_append(material, run_row.operation_type);
    material := ingestion_canonical_append(material, run_row.request_key);
    material := ingestion_canonical_append(material, run_row.root_request_logical_key);
    material := ingestion_canonical_append(material, run_row.run_attempt_number::TEXT);
    material := ingestion_canonical_append(
        material, to_char(run_row.requested_range_start, 'YYYY-MM-DD'));
    material := ingestion_canonical_append(
        material, to_char(run_row.requested_range_end, 'YYYY-MM-DD'));
    material := ingestion_canonical_append(material, run_row.retry_of_run_logical_key);
    material := ingestion_canonical_append(material, terminal_status);
    material := ingestion_canonical_append(material, dataset_row.source);
    material := ingestion_canonical_append(material, dataset_row.source_version);
    material := ingestion_canonical_append(material, dataset_row.connector_version);
    material := ingestion_canonical_append(material, dataset_row.trust_level);
    material := ingestion_canonical_append(material, expected_count::TEXT);
    material := ingestion_canonical_append(material, received_count::TEXT);
    material := ingestion_canonical_append(material, accepted_count::TEXT);
    material := ingestion_canonical_append(material, rejected_count::TEXT);
    material := ingestion_canonical_append(material, assurance_level);
    material := ingestion_canonical_append(material, entry_count::TEXT);

    FOR entry_row IN
        SELECT raw.raw_record_logical_key,
               raw.payload_hash AS raw_payload_hash,
               raw.source_trust_level,
               raw.source_instrument_id,
               attempt.attempt_no,
               attempt.attempt_logical_key,
               attempt.status AS attempt_status,
               attempt.processor_version,
               attempt.contract_version,
               attempt.published_at_verification,
               attempt.requested_assurance_level,
               attempt.knowledge_time_policy_version,
               attempt.assurance_level AS attempt_assurance_level,
               attempt.derived_known_from,
               attempt.error_code AS attempt_error_code,
               attempt.result_hash AS attempt_result_hash,
               result.outcome,
               result.event_logical_key,
               event.event_type,
               event.payload_hash AS event_payload_hash,
               event.assurance_level AS event_assurance_level,
               result.security_logical_key,
               result.predecessor_event_logical_key,
               run_row.run_namespace AS record_namespace,
               result.normalizer_version,
               result.transition_rule_version,
               result.assurance_level AS result_assurance_level,
               result.result_hash AS normalization_result_hash,
               lineage.lineage_hash
        FROM security_status_ingestion_run_records receipt
        JOIN security_status_raw_records raw ON raw.id=receipt.raw_record_id
        JOIN security_status_processing_attempts attempt
          ON attempt.ingestion_run_id=receipt.ingestion_run_id
         AND attempt.raw_record_id=receipt.raw_record_id
        JOIN security_status_normalization_results result
          ON result.processing_attempt_id=attempt.id
        LEFT JOIN security_status_events event ON event.id=result.event_id
        LEFT JOIN security_status_event_lineage lineage ON lineage.event_id=event.id
        WHERE receipt.ingestion_run_id=run_id
        ORDER BY raw.raw_record_logical_key COLLATE "C", attempt.attempt_no,
                 attempt.attempt_logical_key COLLATE "C"
    LOOP
        material := ingestion_canonical_append(material, entry_row.raw_record_logical_key);
        material := ingestion_canonical_append(material, entry_row.raw_payload_hash);
        material := ingestion_canonical_append(material, entry_row.source_trust_level);
        material := ingestion_canonical_append(material, entry_row.source_instrument_id);
        material := ingestion_canonical_append(material, NULL); -- exchange
        material := ingestion_canonical_append(material, NULL); -- tradeDate
        material := ingestion_canonical_append(material, entry_row.attempt_no::TEXT);
        material := ingestion_canonical_append(material, entry_row.attempt_logical_key);
        material := ingestion_canonical_append(material, entry_row.attempt_status);
        material := ingestion_canonical_append(material, entry_row.processor_version);
        material := ingestion_canonical_append(material, entry_row.contract_version);
        material := ingestion_canonical_append(material, entry_row.published_at_verification);
        material := ingestion_canonical_append(material, entry_row.requested_assurance_level);
        material := ingestion_canonical_append(material, entry_row.knowledge_time_policy_version);
        material := ingestion_canonical_append(material, entry_row.attempt_assurance_level);
        material := ingestion_canonical_append(
            material, ingestion_canonical_instant(entry_row.derived_known_from));
        material := ingestion_canonical_append(material, entry_row.attempt_error_code);
        material := ingestion_canonical_append(material, entry_row.attempt_result_hash);
        material := ingestion_canonical_append(material, entry_row.outcome);
        material := ingestion_canonical_append(material, entry_row.event_logical_key);
        material := ingestion_canonical_append(material, entry_row.event_type);
        material := ingestion_canonical_append(material, entry_row.event_payload_hash);
        material := ingestion_canonical_append(material, entry_row.event_assurance_level);
        material := ingestion_canonical_append(material, entry_row.security_logical_key);
        material := ingestion_canonical_append(material, entry_row.predecessor_event_logical_key);
        material := ingestion_canonical_append(material, entry_row.record_namespace);
        material := ingestion_canonical_append(material, entry_row.normalizer_version);
        material := ingestion_canonical_append(material, entry_row.transition_rule_version);
        material := ingestion_canonical_append(material, entry_row.result_assurance_level);
        material := ingestion_canonical_append(material, entry_row.normalization_result_hash);
        material := ingestion_canonical_append(material, entry_row.lineage_hash);
    END LOOP;
    RETURN ingestion_canonical_sha256(material);
END;
$$;

CREATE OR REPLACE FUNCTION protect_ingestion_run_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    attempt_table REGCLASS;
    run_record_table REGCLASS;
    received_count INTEGER;
    attempt_count INTEGER;
    accepted_count INTEGER;
    rejected_count INTEGER;
    conservative_assurance VARCHAR(32);
    dataset_trust_level VARCHAR(32);
    expected_manifest_hash VARCHAR(64);
    invalid_count INTEGER;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'market_data_ingestion_runs is immutable; DELETE is forbidden'
            USING ERRCODE = '55000';
    END IF;
    IF OLD.status <> 'RUNNING' OR OLD.sealed_at IS NOT NULL THEN
        RAISE EXCEPTION 'sealed ingestion run is immutable' USING ERRCODE = '55000';
    END IF;
    IF NEW.status NOT IN ('COMPLETED', 'PARTIAL', 'FAILED') THEN
        RAISE EXCEPTION 'ingestion run only permits one RUNNING-to-terminal transition'
            USING ERRCODE = '55000';
    END IF;
    IF (to_jsonb(NEW) - ARRAY[
            'status', 'sealed_at', 'finished_at', 'manifest_hash',
            'final_expected_count', 'final_received_count',
            'final_accepted_count', 'final_rejected_count', 'assurance_level'
        ]) <> (to_jsonb(OLD) - ARRAY[
            'status', 'sealed_at', 'finished_at', 'manifest_hash',
            'final_expected_count', 'final_received_count',
            'final_accepted_count', 'final_rejected_count', 'assurance_level'
        ]) THEN
        RAISE EXCEPTION 'ingestion run identity fields are immutable'
            USING ERRCODE = '55000';
    END IF;

    IF OLD.dataset_type = 'SECURITY_STATUS' THEN
        attempt_table := 'security_status_processing_attempts'::REGCLASS;
        run_record_table := 'security_status_ingestion_run_records'::REGCLASS;
    ELSE
        attempt_table := 'trading_calendar_processing_attempts'::REGCLASS;
        run_record_table := 'trading_calendar_ingestion_run_records'::REGCLASS;
    END IF;
    EXECUTE format('SELECT count(*) FROM %s WHERE ingestion_run_id=$1', run_record_table)
        INTO received_count USING OLD.id;

    IF OLD.manifest_contract_version = 'INGESTION_MANIFEST_V2_SECURITY_EVENT' THEN
        IF OLD.dataset_type <> 'SECURITY_STATUS' OR OLD.run_namespace NOT IN ('TEST', 'DEMO') THEN
            RAISE EXCEPTION 'Manifest V2 sealing requires TEST/DEMO security run'
                USING ERRCODE = '23514';
        END IF;
        SELECT count(*) INTO invalid_count
        FROM security_status_ingestion_run_records receipt
        LEFT JOIN LATERAL (
            SELECT min(attempt_no) AS minimum_no, max(attempt_no) AS maximum_no,
                   count(*) AS attempt_total,
                   count(result.id) AS result_total
            FROM security_status_processing_attempts attempt
            LEFT JOIN security_status_normalization_results result
              ON result.processing_attempt_id=attempt.id
            WHERE attempt.ingestion_run_id=receipt.ingestion_run_id
              AND attempt.raw_record_id=receipt.raw_record_id
        ) audit ON TRUE
        WHERE receipt.ingestion_run_id=OLD.id
          AND (audit.minimum_no IS NULL OR audit.minimum_no<>1
               OR audit.attempt_total<>audit.maximum_no
               OR audit.result_total<>audit.attempt_total);
        IF invalid_count <> 0 THEN
            RAISE EXCEPTION 'Manifest V2 requires contiguous attempts and one result per attempt'
                USING ERRCODE = '23514';
        END IF;
        WITH final_attempts AS (
            SELECT attempt.status, result.assurance_level,
                   row_number() OVER (
                       PARTITION BY attempt.raw_record_id ORDER BY attempt.attempt_no DESC) AS rank
            FROM security_status_processing_attempts attempt
            JOIN security_status_normalization_results result
              ON result.processing_attempt_id=attempt.id
            WHERE attempt.ingestion_run_id=OLD.id
        )
        SELECT count(*), count(*) FILTER (WHERE status='COMPLETED'),
               count(*) FILTER (WHERE status<>'COMPLETED'),
               security_event_assurance_from_rank(
                   coalesce(min(security_event_assurance_rank(assurance_level)), 0))
        INTO attempt_count, accepted_count, rejected_count, conservative_assurance
        FROM final_attempts WHERE rank=1;
        expected_manifest_hash := compute_security_event_manifest_v2_hash(
            OLD.id, NEW.status, NEW.final_expected_count, NEW.final_received_count,
            NEW.final_accepted_count, NEW.final_rejected_count, NEW.assurance_level);
    ELSE
        EXECUTE format(
            'WITH final_attempts AS ('
            || 'SELECT status, assurance_level, row_number() OVER ('
            || 'PARTITION BY raw_record_id ORDER BY attempt_no DESC) AS rank '
            || 'FROM %s WHERE ingestion_run_id=$1) '
            || 'SELECT count(*), count(*) FILTER (WHERE status=''COMPLETED''), '
            || 'count(*) FILTER (WHERE status<>''COMPLETED''), '
            || 'CASE min(CASE assurance_level '
            || 'WHEN ''INFERRED_RESEARCH'' THEN 0 '
            || 'WHEN ''RECONSTRUCTED_VERIFIED'' THEN 1 ELSE 2 END) '
            || 'WHEN 2 THEN ''PIT_VERIFIED'' '
            || 'WHEN 1 THEN ''RECONSTRUCTED_VERIFIED'' '
            || 'ELSE ''INFERRED_RESEARCH'' END '
            || 'FROM final_attempts WHERE rank=1', attempt_table)
        INTO attempt_count, accepted_count, rejected_count, conservative_assurance USING OLD.id;
        IF conservative_assurance IS NULL THEN
            conservative_assurance := 'INFERRED_RESEARCH';
        END IF;
        SELECT trust_level INTO STRICT dataset_trust_level
        FROM market_data_dataset_versions WHERE id=OLD.dataset_version_id;
        IF dataset_trust_level = 'BACKFILLED_INFERRED' THEN
            conservative_assurance := 'INFERRED_RESEARCH';
        ELSIF conservative_assurance = 'PIT_VERIFIED' THEN
            conservative_assurance := 'RECONSTRUCTED_VERIFIED';
        END IF;
        expected_manifest_hash := compute_ingestion_manifest_hash(
            OLD.id, NEW.status, NEW.final_expected_count, NEW.final_received_count,
            NEW.final_accepted_count, NEW.final_rejected_count, NEW.assurance_level);
    END IF;

    IF attempt_count <> received_count
       OR NEW.final_received_count <> received_count
       OR NEW.final_accepted_count <> accepted_count
       OR NEW.final_rejected_count <> rejected_count
       OR NEW.assurance_level <> conservative_assurance THEN
        RAISE EXCEPTION 'ingestion run final counts do not match terminal facts'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.manifest_hash <> expected_manifest_hash THEN
        RAISE EXCEPTION 'ingestion run manifest hash does not match persisted facts'
            USING ERRCODE = '23514';
    END IF;
    NEW.sealed_at := clock_timestamp();
    NEW.finished_at := NEW.sealed_at;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_security_identity_registry_validate
BEFORE INSERT ON security_identity_registry
FOR EACH ROW EXECUTE FUNCTION validate_security_identity_insert();
CREATE TRIGGER trg_security_identity_registry_immutable
BEFORE UPDATE OR DELETE ON security_identity_registry
FOR EACH ROW EXECUTE FUNCTION reject_ingestion_fact_mutation();
CREATE TRIGGER trg_security_identity_registry_no_truncate
BEFORE TRUNCATE ON security_identity_registry
FOR EACH STATEMENT EXECUTE FUNCTION reject_ingestion_fact_truncate();

CREATE TRIGGER trg_source_security_identity_mappings_validate
BEFORE INSERT ON source_security_identity_mappings
FOR EACH ROW EXECUTE FUNCTION validate_source_security_mapping_insert();
CREATE TRIGGER trg_source_security_identity_mappings_immutable
BEFORE UPDATE OR DELETE ON source_security_identity_mappings
FOR EACH ROW EXECUTE FUNCTION reject_ingestion_fact_mutation();
CREATE TRIGGER trg_source_security_identity_mappings_no_truncate
BEFORE TRUNCATE ON source_security_identity_mappings
FOR EACH STATEMENT EXECUTE FUNCTION reject_ingestion_fact_truncate();

CREATE TRIGGER trg_market_data_ingestion_runs_manifest_contract
BEFORE INSERT ON market_data_ingestion_runs
FOR EACH ROW EXECUTE FUNCTION validate_ingestion_manifest_contract_insert();

CREATE TRIGGER trg_security_status_processing_attempts_v2_validate
BEFORE INSERT ON security_status_processing_attempts
FOR EACH ROW EXECUTE FUNCTION validate_v2_security_attempt_insert();
CREATE CONSTRAINT TRIGGER trg_security_status_processing_attempts_v2_result
AFTER INSERT ON security_status_processing_attempts
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION ensure_v2_attempt_has_result();

CREATE TRIGGER trg_security_status_events_v8_validate
BEFORE INSERT ON security_status_events
FOR EACH ROW EXECUTE FUNCTION validate_security_status_event_insert_v8();
CREATE CONSTRAINT TRIGGER trg_security_status_events_v8_lineage
AFTER INSERT ON security_status_events
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION ensure_resolved_event_has_lineage();

CREATE TRIGGER trg_security_status_history_reject_v8_resolved_event
BEFORE INSERT ON security_status_history
FOR EACH ROW EXECUTE FUNCTION reject_resolved_event_history_insert();

CREATE TRIGGER trg_security_status_event_lineage_validate
BEFORE INSERT ON security_status_event_lineage
FOR EACH ROW EXECUTE FUNCTION validate_security_event_lineage_insert();
CREATE TRIGGER trg_security_status_event_lineage_immutable
BEFORE UPDATE OR DELETE ON security_status_event_lineage
FOR EACH ROW EXECUTE FUNCTION reject_ingestion_fact_mutation();
CREATE TRIGGER trg_security_status_event_lineage_no_truncate
BEFORE TRUNCATE ON security_status_event_lineage
FOR EACH STATEMENT EXECUTE FUNCTION reject_ingestion_fact_truncate();

CREATE TRIGGER trg_security_status_normalization_results_validate
BEFORE INSERT ON security_status_normalization_results
FOR EACH ROW EXECUTE FUNCTION validate_security_normalization_result_insert();
CREATE TRIGGER trg_security_status_normalization_results_immutable
BEFORE UPDATE OR DELETE ON security_status_normalization_results
FOR EACH ROW EXECUTE FUNCTION reject_ingestion_fact_mutation();
CREATE TRIGGER trg_security_status_normalization_results_no_truncate
BEFORE TRUNCATE ON security_status_normalization_results
FOR EACH STATEMENT EXECUTE FUNCTION reject_ingestion_fact_truncate();

-- Resolve all V8 helpers against the schema in which Flyway installed them.
DO $$
DECLARE
    migration_schema TEXT := current_schema();
    target_function RECORD;
BEGIN
    FOR target_function IN
        SELECT namespace_record.nspname,
               catalog_function.proname,
               pg_get_function_identity_arguments(catalog_function.oid) AS identity_arguments
        FROM pg_proc catalog_function
        JOIN pg_namespace namespace_record
          ON namespace_record.oid=catalog_function.pronamespace
        WHERE namespace_record.nspname=migration_schema
          AND catalog_function.proname IN (
            'security_event_assurance_rank',
            'security_event_assurance_from_rank',
            'security_event_trust_rank',
            'security_event_trust_from_rank',
            'compute_security_identity_logical_key',
            'compute_source_security_mapping_logical_key',
            'compute_security_event_logical_key',
            'compute_security_normalization_result_hash',
            'compute_security_event_lineage_hash',
            'security_status_raw_test_v1_is_valid',
            'security_status_event_v1_is_valid',
            'compute_security_status_event_v1_payload_hash',
            'security_status_event_v1_transition_valid',
            'validate_security_identity_insert',
            'validate_source_security_mapping_insert',
            'validate_ingestion_manifest_contract_insert',
            'validate_v2_security_attempt_insert',
            'validate_security_status_event_insert_v8',
            'reject_resolved_event_history_insert',
            'validate_security_event_lineage_insert',
            'validate_security_normalization_result_insert',
            'ensure_v2_attempt_has_result',
            'ensure_resolved_event_has_lineage',
            'compute_security_event_manifest_v2_hash',
            'protect_ingestion_run_mutation'
          )
    LOOP
        EXECUTE format(
            'ALTER FUNCTION %I.%I(%s) SET search_path TO pg_catalog, %I',
            target_function.nspname, target_function.proname,
            target_function.identity_arguments, migration_schema);
    END LOOP;
END;
$$;

COMMENT ON COLUMN market_data_ingestion_runs.manifest_contract_version IS
    'Immutable run-time manifest contract chosen when the run is created.';
COMMENT ON TABLE security_identity_registry IS
    'Append-only TEST/DEMO stable identities; symbol and exchange are not identity keys.';
COMMENT ON TABLE source_security_identity_mappings IS
    'Append-only explicit source instrument to stable security mappings.';
COMMENT ON TABLE security_status_normalization_results IS
    'One append-only terminal normalization result per Manifest V2 security attempt.';
COMMENT ON TABLE security_status_event_lineage IS
    'One append-only authoritative raw-to-event lineage per resolved V1 event.';
