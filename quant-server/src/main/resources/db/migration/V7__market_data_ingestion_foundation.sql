-- Stage 2D-2B-1A: source-neutral ingestion runs, immutable raw facts and terminal attempts.
-- This migration deliberately does not publish security/calendar projections or Universe snapshots.

CREATE TABLE market_data_ingestion_runs (
    id BIGSERIAL PRIMARY KEY,
    ingestion_run_logical_key VARCHAR(256) NOT NULL,
    dataset_version_id BIGINT NOT NULL,
    dataset_logical_key VARCHAR(256) NOT NULL,
    dataset_type VARCHAR(32) NOT NULL,
    run_namespace VARCHAR(16) NOT NULL,
    operation_type VARCHAR(16) NOT NULL,
    request_key VARCHAR(200) NOT NULL,
    retry_of_run_logical_key VARCHAR(256),
    root_request_logical_key VARCHAR(256) NOT NULL,
    run_attempt_number INTEGER NOT NULL,
    requested_range_start DATE NOT NULL,
    requested_range_end DATE NOT NULL,
    status VARCHAR(16) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    sealed_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    manifest_hash VARCHAR(64),
    final_expected_count INTEGER,
    final_received_count INTEGER,
    final_accepted_count INTEGER,
    final_rejected_count INTEGER,
    assurance_level VARCHAR(32),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_market_data_ingestion_runs_dataset
        FOREIGN KEY (dataset_version_id)
        REFERENCES market_data_dataset_versions(id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_market_data_ingestion_runs_retry_parent
        FOREIGN KEY (retry_of_run_logical_key)
        REFERENCES market_data_ingestion_runs(ingestion_run_logical_key)
        ON DELETE RESTRICT,
    CONSTRAINT uq_market_data_ingestion_runs_logical_key
        UNIQUE (ingestion_run_logical_key),
    CONSTRAINT uq_market_data_ingestion_runs_root_attempt
        UNIQUE (root_request_logical_key, run_attempt_number),
    CONSTRAINT ck_market_data_ingestion_runs_dataset_type CHECK (
        dataset_type IN ('SECURITY_STATUS', 'TRADING_CALENDAR')
    ),
    CONSTRAINT ck_market_data_ingestion_runs_namespace CHECK (
        run_namespace IN ('FORMAL', 'TEST', 'DEMO')
    ),
    CONSTRAINT ck_market_data_ingestion_runs_operation CHECK (
        operation_type IN ('INGEST', 'BACKFILL', 'REBUILD', 'RETRY')
    ),
    CONSTRAINT ck_market_data_ingestion_runs_status CHECK (
        status IN ('RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED')
    ),
    CONSTRAINT ck_market_data_ingestion_runs_manifest_hash CHECK (
        manifest_hash IS NULL OR manifest_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_market_data_ingestion_runs_text_nonblank CHECK (
        btrim(ingestion_run_logical_key) <> ''
        AND btrim(dataset_logical_key) <> ''
        AND btrim(root_request_logical_key) <> ''
        AND btrim(request_key) <> ''
        AND (retry_of_run_logical_key IS NULL OR btrim(retry_of_run_logical_key) <> '')
    ),
    CONSTRAINT ck_market_data_ingestion_runs_retry_chain CHECK (
        requested_range_end >= requested_range_start
        AND (
            (operation_type = 'RETRY'
             AND retry_of_run_logical_key IS NOT NULL
             AND run_attempt_number > 1)
            OR
            (operation_type <> 'RETRY'
             AND retry_of_run_logical_key IS NULL
             AND run_attempt_number = 1)
        )
    ),
    CONSTRAINT ck_market_data_ingestion_runs_assurance CHECK (
        assurance_level IS NULL OR assurance_level IN (
            'PIT_VERIFIED', 'RECONSTRUCTED_VERIFIED', 'INFERRED_RESEARCH'
        )
    ),
    CONSTRAINT ck_market_data_ingestion_runs_time_order CHECK (
        created_at >= started_at
        AND (sealed_at IS NULL OR sealed_at >= started_at)
        AND (finished_at IS NULL OR finished_at >= started_at)
    ),
    CONSTRAINT ck_market_data_ingestion_runs_lifecycle CHECK (
        (
            status = 'RUNNING'
            AND sealed_at IS NULL
            AND finished_at IS NULL
            AND manifest_hash IS NULL
            AND final_expected_count IS NULL
            AND final_received_count IS NULL
            AND final_accepted_count IS NULL
            AND final_rejected_count IS NULL
            AND assurance_level IS NULL
        ) OR (
            status IN ('COMPLETED', 'PARTIAL', 'FAILED')
            AND sealed_at IS NOT NULL
            AND finished_at IS NOT NULL
            AND finished_at >= sealed_at
            AND manifest_hash IS NOT NULL
            AND final_expected_count IS NOT NULL
            AND final_received_count IS NOT NULL
            AND final_accepted_count IS NOT NULL
            AND final_rejected_count IS NOT NULL
            AND assurance_level IS NOT NULL
            AND final_expected_count >= 0
            AND final_received_count >= 0
            AND final_accepted_count >= 0
            AND final_rejected_count >= 0
            AND final_received_count <= final_expected_count
            AND final_accepted_count + final_rejected_count = final_received_count
            AND (
                status <> 'COMPLETED'
                OR (
                    final_expected_count > 0
                    AND final_received_count = final_expected_count
                    AND final_accepted_count = final_received_count
                    AND final_rejected_count = 0
                )
            )
            AND (
                status <> 'PARTIAL'
                OR final_received_count < final_expected_count
                OR final_rejected_count > 0
            )
        )
    )
);

CREATE INDEX idx_market_data_ingestion_runs_status
    ON market_data_ingestion_runs (run_namespace, dataset_type, status, started_at DESC);
CREATE INDEX idx_market_data_ingestion_runs_dataset
    ON market_data_ingestion_runs (dataset_version_id, started_at DESC);
CREATE INDEX idx_market_data_ingestion_runs_retry_parent
    ON market_data_ingestion_runs (retry_of_run_logical_key)
    WHERE retry_of_run_logical_key IS NOT NULL;

CREATE TABLE security_status_raw_records (
    id BIGSERIAL PRIMARY KEY,
    first_ingestion_run_id BIGINT NOT NULL,
    dataset_version_id BIGINT NOT NULL,
    raw_record_logical_key VARCHAR(256) NOT NULL,
    record_namespace VARCHAR(16) NOT NULL,
    source VARCHAR(128) NOT NULL,
    source_version VARCHAR(128) NOT NULL,
    source_record_id VARCHAR(256) NOT NULL,
    source_revision VARCHAR(128) NOT NULL,
    source_instrument_id VARCHAR(256),
    source_published_at TIMESTAMPTZ,
    source_effective_date DATE,
    source_effective_at TIMESTAMPTZ,
    system_first_observed_at TIMESTAMPTZ NOT NULL,
    system_recorded_at TIMESTAMPTZ NOT NULL,
    source_trust_level VARCHAR(32) NOT NULL,
    raw_payload JSONB NOT NULL DEFAULT '{}'::JSONB,
    payload_hash VARCHAR(64) NOT NULL,
    CONSTRAINT fk_security_status_raw_records_first_run
        FOREIGN KEY (first_ingestion_run_id)
        REFERENCES market_data_ingestion_runs(id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_security_status_raw_records_dataset
        FOREIGN KEY (dataset_version_id)
        REFERENCES market_data_dataset_versions(id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_security_status_raw_records_logical_key
        UNIQUE (raw_record_logical_key),
    CONSTRAINT uq_security_status_raw_records_source_revision
        UNIQUE (record_namespace, source, source_version, source_record_id, source_revision),
    CONSTRAINT ck_security_status_raw_records_namespace CHECK (
        record_namespace IN ('FORMAL', 'TEST', 'DEMO')
    ),
    CONSTRAINT ck_security_status_raw_records_trust CHECK (
        source_trust_level IN ('OBSERVED', 'BACKFILLED_VERIFIED', 'BACKFILLED_INFERRED')
    ),
    CONSTRAINT ck_security_status_raw_records_payload CHECK (
        jsonb_typeof(raw_payload) = 'object'
        AND payload_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_security_status_raw_records_text_nonblank CHECK (
        btrim(raw_record_logical_key) <> ''
        AND btrim(source) <> ''
        AND btrim(source_version) <> ''
        AND btrim(source_record_id) <> ''
        AND btrim(source_revision) <> ''
        AND (source_instrument_id IS NULL OR btrim(source_instrument_id) <> '')
    ),
    CONSTRAINT ck_security_status_raw_records_time_order CHECK (
        system_recorded_at >= system_first_observed_at
        AND (source_published_at IS NULL OR source_published_at <= system_first_observed_at)
    ),
    CONSTRAINT ck_security_status_raw_records_effective_consistency CHECK (
        source_effective_date IS NULL
        OR source_effective_at IS NULL
        OR (source_effective_at AT TIME ZONE 'Asia/Shanghai')::DATE = source_effective_date
    )
);

CREATE INDEX idx_security_status_raw_records_first_run
    ON security_status_raw_records (first_ingestion_run_id, id);
CREATE INDEX idx_security_status_raw_records_dataset
    ON security_status_raw_records (dataset_version_id, id);
CREATE INDEX idx_security_status_raw_records_instrument
    ON security_status_raw_records (record_namespace, source, source_instrument_id)
    WHERE source_instrument_id IS NOT NULL;

CREATE TABLE trading_calendar_raw_records (
    id BIGSERIAL PRIMARY KEY,
    first_ingestion_run_id BIGINT NOT NULL,
    dataset_version_id BIGINT NOT NULL,
    raw_record_logical_key VARCHAR(256) NOT NULL,
    record_namespace VARCHAR(16) NOT NULL,
    source VARCHAR(128) NOT NULL,
    source_version VARCHAR(128) NOT NULL,
    source_record_id VARCHAR(256) NOT NULL,
    source_revision VARCHAR(128) NOT NULL,
    exchange VARCHAR(16) NOT NULL,
    trade_date DATE NOT NULL,
    source_published_at TIMESTAMPTZ,
    source_effective_date DATE,
    source_effective_at TIMESTAMPTZ,
    system_first_observed_at TIMESTAMPTZ NOT NULL,
    system_recorded_at TIMESTAMPTZ NOT NULL,
    source_trust_level VARCHAR(32) NOT NULL,
    raw_payload JSONB NOT NULL DEFAULT '{}'::JSONB,
    payload_hash VARCHAR(64) NOT NULL,
    CONSTRAINT fk_trading_calendar_raw_records_first_run
        FOREIGN KEY (first_ingestion_run_id)
        REFERENCES market_data_ingestion_runs(id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_trading_calendar_raw_records_dataset
        FOREIGN KEY (dataset_version_id)
        REFERENCES market_data_dataset_versions(id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_trading_calendar_raw_records_logical_key
        UNIQUE (raw_record_logical_key),
    CONSTRAINT uq_trading_calendar_raw_records_source_revision
        UNIQUE (record_namespace, source, source_version, source_record_id, source_revision),
    CONSTRAINT ck_trading_calendar_raw_records_namespace CHECK (
        record_namespace IN ('FORMAL', 'TEST', 'DEMO')
    ),
    CONSTRAINT ck_trading_calendar_raw_records_trust CHECK (
        source_trust_level IN ('OBSERVED', 'BACKFILLED_VERIFIED', 'BACKFILLED_INFERRED')
    ),
    CONSTRAINT ck_trading_calendar_raw_records_exchange CHECK (
        exchange IN ('SSE', 'SZSE')
    ),
    CONSTRAINT ck_trading_calendar_raw_records_payload CHECK (
        jsonb_typeof(raw_payload) = 'object'
        AND payload_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_trading_calendar_raw_records_text_nonblank CHECK (
        btrim(raw_record_logical_key) <> ''
        AND btrim(source) <> ''
        AND btrim(source_version) <> ''
        AND btrim(source_record_id) <> ''
        AND btrim(source_revision) <> ''
    ),
    CONSTRAINT ck_trading_calendar_raw_records_time_order CHECK (
        system_recorded_at >= system_first_observed_at
        AND (source_published_at IS NULL OR source_published_at <= system_first_observed_at)
    ),
    CONSTRAINT ck_trading_calendar_raw_records_effective_consistency CHECK (
        source_effective_date IS NULL
        OR source_effective_at IS NULL
        OR (source_effective_at AT TIME ZONE 'Asia/Shanghai')::DATE = source_effective_date
    )
);

CREATE INDEX idx_trading_calendar_raw_records_first_run
    ON trading_calendar_raw_records (first_ingestion_run_id, id);
CREATE INDEX idx_trading_calendar_raw_records_dataset
    ON trading_calendar_raw_records (dataset_version_id, id);
CREATE INDEX idx_trading_calendar_raw_records_exchange_date
    ON trading_calendar_raw_records (exchange, trade_date, system_first_observed_at);

CREATE TABLE security_status_ingestion_run_records (
    ingestion_run_id BIGINT NOT NULL,
    raw_record_id BIGINT NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (ingestion_run_id, raw_record_id),
    CONSTRAINT fk_security_status_ingestion_run_records_run
        FOREIGN KEY (ingestion_run_id)
        REFERENCES market_data_ingestion_runs(id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_security_status_ingestion_run_records_raw
        FOREIGN KEY (raw_record_id)
        REFERENCES security_status_raw_records(id)
        ON DELETE RESTRICT
);

CREATE INDEX idx_security_status_ingestion_run_records_raw
    ON security_status_ingestion_run_records (raw_record_id, ingestion_run_id);

CREATE TABLE trading_calendar_ingestion_run_records (
    ingestion_run_id BIGINT NOT NULL,
    raw_record_id BIGINT NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (ingestion_run_id, raw_record_id),
    CONSTRAINT fk_trading_calendar_ingestion_run_records_run
        FOREIGN KEY (ingestion_run_id)
        REFERENCES market_data_ingestion_runs(id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_trading_calendar_ingestion_run_records_raw
        FOREIGN KEY (raw_record_id)
        REFERENCES trading_calendar_raw_records(id)
        ON DELETE RESTRICT
);

CREATE INDEX idx_trading_calendar_ingestion_run_records_raw
    ON trading_calendar_ingestion_run_records (raw_record_id, ingestion_run_id);

CREATE TABLE security_status_processing_attempts (
    id BIGSERIAL PRIMARY KEY,
    ingestion_run_id BIGINT NOT NULL,
    raw_record_id BIGINT NOT NULL,
    attempt_no INTEGER NOT NULL,
    attempt_logical_key VARCHAR(256) NOT NULL,
    status VARCHAR(32) NOT NULL,
    processor_version VARCHAR(120) NOT NULL,
    contract_version VARCHAR(120) NOT NULL,
    published_at_verification VARCHAR(24) NOT NULL,
    requested_assurance_level VARCHAR(32) NOT NULL,
    derived_known_from TIMESTAMPTZ NOT NULL,
    knowledge_time_policy_version VARCHAR(80) NOT NULL,
    assurance_level VARCHAR(32) NOT NULL,
    error_code VARCHAR(120),
    result_metadata JSONB NOT NULL DEFAULT '{}'::JSONB,
    result_hash VARCHAR(64) NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    system_recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_security_status_processing_attempts_run
        FOREIGN KEY (ingestion_run_id)
        REFERENCES market_data_ingestion_runs(id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_security_status_processing_attempts_raw
        FOREIGN KEY (raw_record_id)
        REFERENCES security_status_raw_records(id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_security_status_processing_attempts_logical_key
        UNIQUE (attempt_logical_key),
    CONSTRAINT uq_security_status_processing_attempts_run_raw_no
        UNIQUE (ingestion_run_id, raw_record_id, attempt_no),
    CONSTRAINT ck_security_status_processing_attempts_no CHECK (attempt_no > 0),
    CONSTRAINT ck_security_status_processing_attempts_status CHECK (
        status IN ('COMPLETED', 'REJECTED', 'CONFLICT',
                   'UNSUPPORTED_CONTRACT', 'IDENTITY_UNRESOLVED', 'PROJECTION_FAILED')
    ),
    CONSTRAINT ck_security_status_processing_attempts_publication CHECK (
        published_at_verification IN ('VERIFIED', 'UNVERIFIED', 'NOT_PROVIDED')
    ),
    CONSTRAINT ck_security_status_processing_attempts_policy CHECK (
        knowledge_time_policy_version = 'KNOWLEDGE_TIME_POLICY_V1'
    ),
    CONSTRAINT ck_security_status_processing_attempts_assurance CHECK (
        requested_assurance_level IN (
            'PIT_VERIFIED', 'RECONSTRUCTED_VERIFIED', 'INFERRED_RESEARCH'
        )
        AND assurance_level IN (
            'PIT_VERIFIED', 'RECONSTRUCTED_VERIFIED', 'INFERRED_RESEARCH'
        )
    ),
    CONSTRAINT ck_security_status_processing_attempts_result CHECK (
        result_metadata = '{}'::JSONB
        AND result_hash = '3e0be30d9c55a4c203bff3bcdce0842e1cd7054bf46f9c24f476e51adf2cf34b'
        AND ((status = 'COMPLETED' AND error_code IS NULL)
             OR (status <> 'COMPLETED' AND error_code IS NOT NULL))
    ),
    CONSTRAINT ck_security_status_processing_attempts_text_nonblank CHECK (
        btrim(attempt_logical_key) <> ''
        AND btrim(processor_version) <> ''
        AND btrim(contract_version) <> ''
        AND (error_code IS NULL OR btrim(error_code) <> '')
    ),
    CONSTRAINT ck_security_status_processing_attempts_time_order CHECK (
        system_recorded_at >= completed_at
    )
);

CREATE INDEX idx_security_status_processing_attempts_run
    ON security_status_processing_attempts (
        ingestion_run_id, raw_record_id, attempt_no DESC, status
    );
CREATE UNIQUE INDEX uq_security_status_processing_attempts_completed
    ON security_status_processing_attempts (ingestion_run_id, raw_record_id)
    WHERE status = 'COMPLETED';

CREATE TABLE trading_calendar_processing_attempts (
    id BIGSERIAL PRIMARY KEY,
    ingestion_run_id BIGINT NOT NULL,
    raw_record_id BIGINT NOT NULL,
    attempt_no INTEGER NOT NULL,
    attempt_logical_key VARCHAR(256) NOT NULL,
    status VARCHAR(32) NOT NULL,
    processor_version VARCHAR(120) NOT NULL,
    contract_version VARCHAR(120) NOT NULL,
    published_at_verification VARCHAR(24) NOT NULL,
    requested_assurance_level VARCHAR(32) NOT NULL,
    derived_known_from TIMESTAMPTZ NOT NULL,
    knowledge_time_policy_version VARCHAR(80) NOT NULL,
    assurance_level VARCHAR(32) NOT NULL,
    error_code VARCHAR(120),
    result_metadata JSONB NOT NULL DEFAULT '{}'::JSONB,
    result_hash VARCHAR(64) NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    system_recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_trading_calendar_processing_attempts_run
        FOREIGN KEY (ingestion_run_id)
        REFERENCES market_data_ingestion_runs(id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_trading_calendar_processing_attempts_raw
        FOREIGN KEY (raw_record_id)
        REFERENCES trading_calendar_raw_records(id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_trading_calendar_processing_attempts_logical_key
        UNIQUE (attempt_logical_key),
    CONSTRAINT uq_trading_calendar_processing_attempts_run_raw_no
        UNIQUE (ingestion_run_id, raw_record_id, attempt_no),
    CONSTRAINT ck_trading_calendar_processing_attempts_no CHECK (attempt_no > 0),
    CONSTRAINT ck_trading_calendar_processing_attempts_status CHECK (
        status IN ('COMPLETED', 'REJECTED', 'CONFLICT',
                   'UNSUPPORTED_CONTRACT', 'IDENTITY_UNRESOLVED', 'PROJECTION_FAILED')
    ),
    CONSTRAINT ck_trading_calendar_processing_attempts_publication CHECK (
        published_at_verification IN ('VERIFIED', 'UNVERIFIED', 'NOT_PROVIDED')
    ),
    CONSTRAINT ck_trading_calendar_processing_attempts_policy CHECK (
        knowledge_time_policy_version = 'KNOWLEDGE_TIME_POLICY_V1'
    ),
    CONSTRAINT ck_trading_calendar_processing_attempts_assurance CHECK (
        requested_assurance_level IN (
            'PIT_VERIFIED', 'RECONSTRUCTED_VERIFIED', 'INFERRED_RESEARCH'
        )
        AND assurance_level IN (
            'PIT_VERIFIED', 'RECONSTRUCTED_VERIFIED', 'INFERRED_RESEARCH'
        )
    ),
    CONSTRAINT ck_trading_calendar_processing_attempts_result CHECK (
        result_metadata = '{}'::JSONB
        AND result_hash = '3e0be30d9c55a4c203bff3bcdce0842e1cd7054bf46f9c24f476e51adf2cf34b'
        AND ((status = 'COMPLETED' AND error_code IS NULL)
             OR (status <> 'COMPLETED' AND error_code IS NOT NULL))
    ),
    CONSTRAINT ck_trading_calendar_processing_attempts_text_nonblank CHECK (
        btrim(attempt_logical_key) <> ''
        AND btrim(processor_version) <> ''
        AND btrim(contract_version) <> ''
        AND (error_code IS NULL OR btrim(error_code) <> '')
    ),
    CONSTRAINT ck_trading_calendar_processing_attempts_time_order CHECK (
        system_recorded_at >= completed_at
    )
);

CREATE INDEX idx_trading_calendar_processing_attempts_run
    ON trading_calendar_processing_attempts (
        ingestion_run_id, raw_record_id, attempt_no DESC, status
    );
CREATE UNIQUE INDEX uq_trading_calendar_processing_attempts_completed
    ON trading_calendar_processing_attempts (ingestion_run_id, raw_record_id)
    WHERE status = 'COMPLETED';

-- TEMPORAL_CANONICAL_V1 uses signed network-order int32 byte lengths followed by UTF-8.
-- NULL is encoded as the int32 value -1 and contributes no value bytes.
CREATE OR REPLACE FUNCTION ingestion_canonical_append(material BYTEA, value TEXT)
RETURNS BYTEA
LANGUAGE SQL
IMMUTABLE
AS $$
    SELECT material
           || pg_catalog.int4send(
                  CASE WHEN value IS NULL THEN -1
                       ELSE octet_length(convert_to(value, 'UTF8')) END)
           || CASE WHEN value IS NULL THEN ''::BYTEA
                   ELSE convert_to(value, 'UTF8') END
$$;

CREATE OR REPLACE FUNCTION ingestion_canonical_sha256(material BYTEA)
RETURNS VARCHAR(64)
LANGUAGE SQL
IMMUTABLE
STRICT
AS $$
    SELECT encode(sha256(material), 'hex')
$$;

CREATE OR REPLACE FUNCTION ingestion_canonical_instant(value TIMESTAMPTZ)
RETURNS TEXT
LANGUAGE SQL
IMMUTABLE
STRICT
AS $$
    SELECT to_char(value AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
$$;

CREATE OR REPLACE FUNCTION ingestion_canonical_json_append(material BYTEA, value JSONB)
RETURNS BYTEA
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    result BYTEA := material;
    member RECORD;
    scalar_text TEXT;
BEGIN
    CASE jsonb_typeof(value)
        WHEN 'null' THEN
            result := ingestion_canonical_append(result, 'NULL');
        WHEN 'object' THEN
            result := ingestion_canonical_append(result, 'OBJECT');
            result := ingestion_canonical_append(
                result, (SELECT count(*)::TEXT FROM jsonb_each(value)));
            FOR member IN
                SELECT key, member_value
                FROM jsonb_each(value) AS object_member(key, member_value)
                ORDER BY key COLLATE "C"
            LOOP
                result := ingestion_canonical_append(result, member.key);
                result := ingestion_canonical_json_append(result, member.member_value);
            END LOOP;
        WHEN 'array' THEN
            result := ingestion_canonical_append(result, 'ARRAY');
            result := ingestion_canonical_append(result, jsonb_array_length(value)::TEXT);
            FOR member IN
                SELECT member_value
                FROM jsonb_array_elements(value) WITH ORDINALITY
                     AS array_member(member_value, ordinal)
                ORDER BY ordinal
            LOOP
                result := ingestion_canonical_json_append(result, member.member_value);
            END LOOP;
        WHEN 'boolean' THEN
            result := ingestion_canonical_append(result, 'BOOLEAN');
            result := ingestion_canonical_append(
                result, CASE WHEN value::TEXT = 'true' THEN '1' ELSE '0' END);
        WHEN 'number' THEN
            result := ingestion_canonical_append(result, 'NUMBER');
            scalar_text := trim_scale((value #>> '{}')::NUMERIC)::TEXT;
            IF scalar_text = '-0' THEN scalar_text := '0'; END IF;
            result := ingestion_canonical_append(result, scalar_text);
        WHEN 'string' THEN
            result := ingestion_canonical_append(result, 'STRING');
            result := ingestion_canonical_append(result, value #>> '{}');
        ELSE
            RAISE EXCEPTION 'unsupported JSON type for ingestion canonical hash'
                USING ERRCODE = '22023';
    END CASE;
    RETURN result;
END;
$$;

CREATE OR REPLACE FUNCTION compute_ingestion_json_hash(value JSONB)
RETURNS VARCHAR(64)
LANGUAGE SQL
IMMUTABLE
STRICT
AS $$
    SELECT ingestion_canonical_sha256(
        ingestion_canonical_json_append(
            ingestion_canonical_append(''::BYTEA, 'INGESTION_CANONICAL_V1'), value))
$$;

CREATE OR REPLACE FUNCTION compute_ingestion_dataset_logical_key(dataset_id BIGINT)
RETURNS VARCHAR(256)
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    dataset_row market_data_dataset_versions%ROWTYPE;
    material BYTEA := ''::BYTEA;
BEGIN
    SELECT * INTO STRICT dataset_row
    FROM market_data_dataset_versions
    WHERE id = dataset_id;

    material := ingestion_canonical_append(material, 'INGESTION_CANONICAL_V1');
    material := ingestion_canonical_append(material, 'DATASET_LOGICAL_KEY_V1');
    material := ingestion_canonical_append(material, dataset_row.dataset_type);
    material := ingestion_canonical_append(material, dataset_row.source);
    material := ingestion_canonical_append(material, dataset_row.source_version);
    material := ingestion_canonical_append(material, dataset_row.connector_version);
    material := ingestion_canonical_append(
        material, to_char(dataset_row.range_start, 'YYYY-MM-DD'));
    material := ingestion_canonical_append(
        material, to_char(dataset_row.range_end, 'YYYY-MM-DD'));
    material := ingestion_canonical_append(material, dataset_row.payload_hash);
    RETURN 'dataset:v1:' || ingestion_canonical_sha256(material);
END;
$$;

CREATE OR REPLACE FUNCTION compute_ingestion_root_request_logical_key(
    dataset_logical_key TEXT,
    dataset_type TEXT,
    run_namespace TEXT,
    operation_type TEXT,
    request_key TEXT,
    requested_range_start DATE,
    requested_range_end DATE
)
RETURNS VARCHAR(256)
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    material BYTEA := ''::BYTEA;
BEGIN
    material := ingestion_canonical_append(material, 'INGESTION_CANONICAL_V1');
    material := ingestion_canonical_append(material, 'INGESTION_ROOT_REQUEST_LOGICAL_KEY_V1');
    material := ingestion_canonical_append(material, dataset_logical_key);
    material := ingestion_canonical_append(material, dataset_type);
    material := ingestion_canonical_append(material, run_namespace);
    material := ingestion_canonical_append(material, operation_type);
    material := ingestion_canonical_append(material, request_key);
    material := ingestion_canonical_append(
        material, to_char(requested_range_start, 'YYYY-MM-DD'));
    material := ingestion_canonical_append(
        material, to_char(requested_range_end, 'YYYY-MM-DD'));
    RETURN 'root-request:v1:' || ingestion_canonical_sha256(material);
END;
$$;

CREATE OR REPLACE FUNCTION compute_ingestion_run_logical_key(
    dataset_logical_key TEXT,
    dataset_type TEXT,
    run_namespace TEXT,
    operation_type TEXT,
    request_key TEXT,
    root_request_logical_key TEXT,
    run_attempt_number INTEGER,
    requested_range_start DATE,
    requested_range_end DATE,
    retry_of_run_logical_key TEXT
)
RETURNS VARCHAR(256)
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    material BYTEA := ''::BYTEA;
BEGIN
    material := ingestion_canonical_append(material, 'INGESTION_CANONICAL_V1');
    material := ingestion_canonical_append(material, 'INGESTION_RUN_LOGICAL_KEY_V1');
    material := ingestion_canonical_append(material, dataset_logical_key);
    material := ingestion_canonical_append(material, dataset_type);
    material := ingestion_canonical_append(material, run_namespace);
    material := ingestion_canonical_append(material, operation_type);
    material := ingestion_canonical_append(material, request_key);
    material := ingestion_canonical_append(material, root_request_logical_key);
    material := ingestion_canonical_append(material, run_attempt_number::TEXT);
    material := ingestion_canonical_append(
        material, to_char(requested_range_start, 'YYYY-MM-DD'));
    material := ingestion_canonical_append(
        material, to_char(requested_range_end, 'YYYY-MM-DD'));
    material := ingestion_canonical_append(material, retry_of_run_logical_key);
    RETURN 'run:v1:' || ingestion_canonical_sha256(material);
END;
$$;

CREATE OR REPLACE FUNCTION compute_ingestion_raw_record_logical_key(
    dataset_type TEXT,
    record_namespace TEXT,
    source TEXT,
    source_version TEXT,
    source_record_id TEXT,
    source_revision TEXT
)
RETURNS VARCHAR(256)
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    material BYTEA := ''::BYTEA;
BEGIN
    material := ingestion_canonical_append(material, 'INGESTION_CANONICAL_V1');
    material := ingestion_canonical_append(material, 'RAW_RECORD_LOGICAL_KEY_V1');
    material := ingestion_canonical_append(material, dataset_type);
    material := ingestion_canonical_append(material, record_namespace);
    material := ingestion_canonical_append(material, source);
    material := ingestion_canonical_append(material, source_version);
    material := ingestion_canonical_append(material, source_record_id);
    material := ingestion_canonical_append(material, source_revision);
    RETURN 'raw:v1:' || ingestion_canonical_sha256(material);
END;
$$;

CREATE OR REPLACE FUNCTION compute_ingestion_attempt_logical_key(
    run_logical_key TEXT,
    raw_record_logical_key TEXT,
    attempt_no INTEGER,
    processor_version TEXT,
    contract_version TEXT
)
RETURNS VARCHAR(256)
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    material BYTEA := ''::BYTEA;
BEGIN
    material := ingestion_canonical_append(material, 'INGESTION_CANONICAL_V1');
    material := ingestion_canonical_append(material, 'PROCESSING_ATTEMPT_LOGICAL_KEY_V1');
    material := ingestion_canonical_append(material, run_logical_key);
    material := ingestion_canonical_append(material, raw_record_logical_key);
    material := ingestion_canonical_append(material, attempt_no::TEXT);
    material := ingestion_canonical_append(material, processor_version);
    material := ingestion_canonical_append(material, contract_version);
    RETURN 'attempt:v1:' || ingestion_canonical_sha256(material);
END;
$$;

CREATE OR REPLACE FUNCTION compute_ingestion_manifest_hash(
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
    raw_table REGCLASS;
    attempt_table REGCLASS;
    run_record_table REGCLASS;
    entry_count INTEGER;
    entry_row RECORD;
    material BYTEA := ''::BYTEA;
BEGIN
    SELECT * INTO STRICT run_row
    FROM market_data_ingestion_runs
    WHERE id = run_id;
    SELECT * INTO STRICT dataset_row
    FROM market_data_dataset_versions
    WHERE id = run_row.dataset_version_id;

    IF run_row.dataset_type = 'SECURITY_STATUS' THEN
        raw_table := 'security_status_raw_records'::REGCLASS;
        attempt_table := 'security_status_processing_attempts'::REGCLASS;
        run_record_table := 'security_status_ingestion_run_records'::REGCLASS;
    ELSE
        raw_table := 'trading_calendar_raw_records'::REGCLASS;
        attempt_table := 'trading_calendar_processing_attempts'::REGCLASS;
        run_record_table := 'trading_calendar_ingestion_run_records'::REGCLASS;
    END IF;

    EXECUTE format(
        'SELECT count(*) FROM %s WHERE ingestion_run_id=$1', attempt_table
    ) INTO entry_count USING run_id;

    material := ingestion_canonical_append(material, 'INGESTION_CANONICAL_V1');
    material := ingestion_canonical_append(material, 'INGESTION_MANIFEST_V1');
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

    FOR entry_row IN EXECUTE format(
        'SELECT raw.raw_record_logical_key, raw.payload_hash AS raw_payload_hash, '
        || 'raw.source_trust_level, '
        || CASE WHEN run_row.dataset_type = 'SECURITY_STATUS'
             THEN 'raw.source_instrument_id, NULL::VARCHAR AS exchange, NULL::DATE AS trade_date, '
             ELSE 'NULL::VARCHAR AS source_instrument_id, raw.exchange, raw.trade_date, ' END
        || 'attempt.attempt_no, attempt.attempt_logical_key, '
        || 'attempt.status, attempt.processor_version, attempt.contract_version, '
        || 'attempt.published_at_verification, attempt.requested_assurance_level, '
        || 'attempt.knowledge_time_policy_version, '
        || 'attempt.assurance_level, attempt.derived_known_from, attempt.error_code, '
        || 'attempt.result_hash '
        || 'FROM %s receipt '
        || 'JOIN %s attempt ON attempt.ingestion_run_id=receipt.ingestion_run_id '
        || 'AND attempt.raw_record_id=receipt.raw_record_id '
        || 'JOIN %s raw ON raw.id=attempt.raw_record_id '
        || 'WHERE receipt.ingestion_run_id=$1 '
        || 'ORDER BY raw.raw_record_logical_key COLLATE "C", attempt.attempt_no, '
        || 'attempt.attempt_logical_key COLLATE "C"',
        run_record_table, attempt_table, raw_table
    ) USING run_id LOOP
        material := ingestion_canonical_append(material, entry_row.raw_record_logical_key);
        material := ingestion_canonical_append(material, entry_row.raw_payload_hash);
        material := ingestion_canonical_append(material, entry_row.source_trust_level);
        material := ingestion_canonical_append(material, entry_row.source_instrument_id);
        material := ingestion_canonical_append(material, entry_row.exchange);
        material := ingestion_canonical_append(
            material, CASE WHEN entry_row.trade_date IS NULL THEN NULL
                           ELSE to_char(entry_row.trade_date, 'YYYY-MM-DD') END);
        material := ingestion_canonical_append(material, entry_row.attempt_no::TEXT);
        material := ingestion_canonical_append(material, entry_row.attempt_logical_key);
        material := ingestion_canonical_append(material, entry_row.status);
        material := ingestion_canonical_append(material, entry_row.processor_version);
        material := ingestion_canonical_append(material, entry_row.contract_version);
        material := ingestion_canonical_append(material, entry_row.published_at_verification);
        material := ingestion_canonical_append(material, entry_row.requested_assurance_level);
        material := ingestion_canonical_append(material, entry_row.knowledge_time_policy_version);
        material := ingestion_canonical_append(material, entry_row.assurance_level);
        material := ingestion_canonical_append(
            material, ingestion_canonical_instant(entry_row.derived_known_from));
        material := ingestion_canonical_append(material, entry_row.error_code);
        material := ingestion_canonical_append(material, entry_row.result_hash);
    END LOOP;

    RETURN ingestion_canonical_sha256(material);
END;
$$;

CREATE OR REPLACE FUNCTION reject_ingestion_fact_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% is append-only; % is forbidden', TG_TABLE_NAME, TG_OP
        USING ERRCODE = '55000';
END;
$$;

CREATE OR REPLACE FUNCTION reject_ingestion_fact_truncate()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% is append-only; TRUNCATE is forbidden', TG_TABLE_NAME
        USING ERRCODE = '55000';
END;
$$;

CREATE OR REPLACE FUNCTION validate_ingestion_raw_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    expected_type VARCHAR(32) := TG_ARGV[0];
    run_row market_data_ingestion_runs%ROWTYPE;
    dataset_row market_data_dataset_versions%ROWTYPE;
BEGIN
    SELECT * INTO STRICT run_row
    FROM market_data_ingestion_runs
    WHERE id = NEW.first_ingestion_run_id
    FOR SHARE;

    IF run_row.status <> 'RUNNING' OR run_row.sealed_at IS NOT NULL THEN
        RAISE EXCEPTION 'raw record cannot be appended to a sealed ingestion run'
            USING ERRCODE = '55000';
    END IF;

    -- A raw revision's first observation is the database's first durable receipt,
    -- never a caller-controlled source timestamp. ON CONFLICT retries retain the winner.
    NEW.system_first_observed_at := clock_timestamp();
    NEW.system_recorded_at := NEW.system_first_observed_at;
    IF run_row.dataset_type <> expected_type
       OR run_row.dataset_version_id <> NEW.dataset_version_id
       OR run_row.run_namespace <> NEW.record_namespace THEN
        RAISE EXCEPTION 'raw record does not match its ingestion run'
            USING ERRCODE = '23514';
    END IF;

    SELECT * INTO STRICT dataset_row
    FROM market_data_dataset_versions
    WHERE id = NEW.dataset_version_id;
    IF dataset_row.dataset_type <> expected_type
       OR dataset_row.source <> NEW.source
       OR dataset_row.source_version <> NEW.source_version THEN
        RAISE EXCEPTION 'raw record does not match its dataset version'
            USING ERRCODE = '23514';
    END IF;
    IF expected_type = 'TRADING_CALENDAR' THEN
        IF NEW.exchange NOT IN ('SSE', 'SZSE') THEN
            RAISE EXCEPTION 'trading calendar exchange is unsupported'
                USING ERRCODE = '23514';
        END IF;
        IF NEW.trade_date < dataset_row.range_start
           OR NEW.trade_date > dataset_row.range_end THEN
            RAISE EXCEPTION 'trading calendar trade date is outside its dataset contract'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    IF NEW.raw_record_logical_key <> compute_ingestion_raw_record_logical_key(
            expected_type, NEW.record_namespace, NEW.source, NEW.source_version,
            NEW.source_record_id, NEW.source_revision) THEN
        RAISE EXCEPTION 'raw record logical key does not match canonical source identity'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.payload_hash <> compute_ingestion_json_hash(NEW.raw_payload) THEN
        RAISE EXCEPTION 'raw payload hash does not match canonical payload'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION validate_ingestion_run_record_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    expected_type VARCHAR(32) := TG_ARGV[0];
    raw_table REGCLASS := TG_ARGV[1]::REGCLASS;
    run_row market_data_ingestion_runs%ROWTYPE;
    raw_dataset_id BIGINT;
    raw_namespace VARCHAR(16);
BEGIN
    SELECT * INTO STRICT run_row
    FROM market_data_ingestion_runs
    WHERE id = NEW.ingestion_run_id
    FOR SHARE;
    IF run_row.status <> 'RUNNING' OR run_row.sealed_at IS NOT NULL THEN
        RAISE EXCEPTION 'raw record cannot be attached to a sealed ingestion run'
            USING ERRCODE = '55000';
    END IF;
    IF run_row.dataset_type <> expected_type THEN
        RAISE EXCEPTION 'run-record type does not match its ingestion run'
            USING ERRCODE = '23514';
    END IF;

    EXECUTE format(
        'SELECT dataset_version_id, record_namespace FROM %s WHERE id=$1',
        raw_table
    ) INTO STRICT raw_dataset_id, raw_namespace USING NEW.raw_record_id;
    IF raw_dataset_id <> run_row.dataset_version_id
       OR raw_namespace <> run_row.run_namespace THEN
        RAISE EXCEPTION 'raw record does not belong to the ingestion run dataset and namespace'
            USING ERRCODE = '23514';
    END IF;

    NEW.received_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION ensure_first_ingestion_run_record()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    run_record_table REGCLASS := TG_ARGV[0]::REGCLASS;
    attached BOOLEAN;
BEGIN
    EXECUTE format(
        'SELECT EXISTS (SELECT 1 FROM %s WHERE ingestion_run_id=$1 AND raw_record_id=$2)',
        run_record_table
    ) INTO STRICT attached USING NEW.first_ingestion_run_id, NEW.id;
    IF NOT attached THEN
        RAISE EXCEPTION 'raw record first ingestion run association is required'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE OR REPLACE FUNCTION validate_ingestion_attempt_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    expected_type VARCHAR(32) := TG_ARGV[0];
    raw_table REGCLASS := TG_ARGV[1]::REGCLASS;
    run_record_table REGCLASS := TG_ARGV[2]::REGCLASS;
    attempt_table REGCLASS := TG_RELID;
    run_row market_data_ingestion_runs%ROWTYPE;
    raw_dataset_id BIGINT;
    raw_namespace VARCHAR(16);
    raw_published_at TIMESTAMPTZ;
    raw_first_observed_at TIMESTAMPTZ;
    raw_trust_level VARCHAR(32);
    raw_logical_key VARCHAR(256);
    dataset_trust_level VARCHAR(32);
    attached_to_run BOOLEAN;
    existing_attempt BOOLEAN;
    latest_attempt_no INTEGER;
    completed_exists BOOLEAN;
    effective_assurance_rank INTEGER;
    expected_assurance_level VARCHAR(32);
BEGIN
    SELECT * INTO STRICT run_row
    FROM market_data_ingestion_runs
    WHERE id = NEW.ingestion_run_id
    FOR SHARE;
    IF run_row.status <> 'RUNNING' OR run_row.sealed_at IS NOT NULL THEN
        RAISE EXCEPTION 'processing attempt cannot be appended to a sealed ingestion run'
            USING ERRCODE = '55000';
    END IF;
    IF run_row.dataset_type <> expected_type THEN
        RAISE EXCEPTION 'processing attempt type does not match its ingestion run'
            USING ERRCODE = '23514';
    END IF;

    EXECUTE format(
        'SELECT dataset_version_id, record_namespace, '
        || 'source_published_at, system_first_observed_at, source_trust_level, '
        || 'raw_record_logical_key '
        || 'FROM %s WHERE id=$1',
        raw_table
    ) INTO STRICT raw_dataset_id, raw_namespace,
        raw_published_at, raw_first_observed_at, raw_trust_level, raw_logical_key
        USING NEW.raw_record_id;

    IF raw_dataset_id <> run_row.dataset_version_id
       OR raw_namespace <> run_row.run_namespace THEN
        RAISE EXCEPTION 'processing attempt raw record does not match its ingestion run'
            USING ERRCODE = '23514';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_class c
        WHERE c.oid = run_record_table
    ) THEN
        RAISE EXCEPTION 'ingestion run-record table is unavailable'
            USING ERRCODE = '55000';
    END IF;
    EXECUTE format(
        'SELECT EXISTS (SELECT 1 FROM %s WHERE ingestion_run_id=$1 AND raw_record_id=$2)',
        run_record_table
    ) INTO STRICT attached_to_run USING NEW.ingestion_run_id, NEW.raw_record_id;
    IF NOT attached_to_run THEN
        RAISE EXCEPTION 'processing attempt requires an attached run raw record'
            USING ERRCODE = '23514';
    END IF;
    -- Lock the receipt row so concurrent attempt numbers are serialized per run/raw fact.
    EXECUTE format(
        'SELECT TRUE FROM %s WHERE ingestion_run_id=$1 AND raw_record_id=$2 FOR UPDATE',
        run_record_table
    ) INTO STRICT attached_to_run USING NEW.ingestion_run_id, NEW.raw_record_id;

    EXECUTE format(
        'SELECT EXISTS(SELECT 1 FROM %s WHERE ingestion_run_id=$1 '
        || 'AND raw_record_id=$2 AND attempt_no=$3), max(attempt_no), '
        || 'coalesce(bool_or(status=''COMPLETED''), FALSE) '
        || 'FROM %s WHERE ingestion_run_id=$1 AND raw_record_id=$2',
        attempt_table, attempt_table
    ) INTO existing_attempt, latest_attempt_no, completed_exists
        USING NEW.ingestion_run_id, NEW.raw_record_id, NEW.attempt_no;

    IF NOT existing_attempt THEN
        IF completed_exists THEN
            RAISE EXCEPTION 'processing attempts cannot continue after a completed attempt'
                USING ERRCODE = '55000';
        END IF;
        IF NEW.attempt_no <> coalesce(latest_attempt_no, 0) + 1 THEN
            RAISE EXCEPTION 'processing attempt number must be the next contiguous value'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    IF NEW.attempt_logical_key <> compute_ingestion_attempt_logical_key(
            run_row.ingestion_run_logical_key, raw_logical_key, NEW.attempt_no,
            NEW.processor_version, NEW.contract_version) THEN
        RAISE EXCEPTION 'processing attempt logical key does not match canonical identity'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.published_at_verification = 'VERIFIED' THEN
        IF raw_published_at IS NULL OR NEW.derived_known_from <> raw_published_at THEN
            RAISE EXCEPTION 'verified publication time must determine derived_known_from'
                USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.published_at_verification = 'UNVERIFIED' THEN
        IF raw_published_at IS NULL OR NEW.derived_known_from <> raw_first_observed_at THEN
            RAISE EXCEPTION 'unverified publication time must use first observation'
                USING ERRCODE = '23514';
        END IF;
    ELSE
        IF raw_published_at IS NOT NULL OR NEW.derived_known_from <> raw_first_observed_at THEN
            RAISE EXCEPTION 'missing publication time must use first observation'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    SELECT trust_level INTO STRICT dataset_trust_level
    FROM market_data_dataset_versions
    WHERE id = run_row.dataset_version_id;
    -- The effective value is an equality, not merely an upper-bound check. The final
    -- rank is the conservative minimum of caller request, dataset trust, raw trust,
    -- publication verification and the source-neutral 2D-2B-1A ceiling.
    effective_assurance_rank := least(
        CASE NEW.requested_assurance_level
            WHEN 'PIT_VERIFIED' THEN 2
            WHEN 'RECONSTRUCTED_VERIFIED' THEN 1
            ELSE 0
        END,
        CASE dataset_trust_level
            WHEN 'OBSERVED' THEN 2
            WHEN 'BACKFILLED_VERIFIED' THEN 1
            ELSE 0
        END,
        CASE raw_trust_level
            WHEN 'OBSERVED' THEN 2
            WHEN 'BACKFILLED_VERIFIED' THEN 1
            ELSE 0
        END,
        CASE NEW.published_at_verification WHEN 'VERIFIED' THEN 2 ELSE 1 END,
        1 -- Stage 2D-2B-1A ceiling: RECONSTRUCTED_VERIFIED.
    );
    expected_assurance_level := CASE effective_assurance_rank
        WHEN 2 THEN 'PIT_VERIFIED'
        WHEN 1 THEN 'RECONSTRUCTED_VERIFIED'
        ELSE 'INFERRED_RESEARCH'
    END;
    IF NEW.assurance_level <> expected_assurance_level THEN
        RAISE EXCEPTION
            'processing attempt assurance must equal requested/source/publication/stage policy minimum'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.result_metadata <> '{}'::JSONB THEN
        RAISE EXCEPTION 'source-neutral processing result_metadata must be empty'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.result_hash <> compute_ingestion_json_hash(NEW.result_metadata) THEN
        RAISE EXCEPTION 'processing result hash does not match canonical metadata'
            USING ERRCODE = '23514';
    END IF;
    NEW.completed_at := greatest(clock_timestamp(), raw_first_observed_at);
    NEW.system_recorded_at := NEW.completed_at;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION validate_ingestion_run_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    dataset_row market_data_dataset_versions%ROWTYPE;
    parent_row market_data_ingestion_runs%ROWTYPE;
    expected_root_request_logical_key VARCHAR(256);
BEGIN
    SELECT * INTO STRICT dataset_row
    FROM market_data_dataset_versions
    WHERE id = NEW.dataset_version_id;
    IF dataset_row.dataset_type <> NEW.dataset_type THEN
        RAISE EXCEPTION 'ingestion run dataset type does not match its dataset version'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.requested_range_end < NEW.requested_range_start
       OR NEW.requested_range_start < dataset_row.range_start
       OR NEW.requested_range_end > dataset_row.range_end THEN
        RAISE EXCEPTION 'ingestion requested range must be within the dataset range'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.operation_type = 'RETRY' THEN
        IF NEW.retry_of_run_logical_key IS NULL THEN
            RAISE EXCEPTION 'RETRY ingestion requires a parent logical key'
                USING ERRCODE = '23514';
        END IF;
        SELECT * INTO STRICT parent_row
        FROM market_data_ingestion_runs
        WHERE ingestion_run_logical_key = NEW.retry_of_run_logical_key
        FOR SHARE;
        IF parent_row.status = 'RUNNING' OR parent_row.sealed_at IS NULL THEN
            RAISE EXCEPTION 'RETRY parent ingestion run must be sealed'
                USING ERRCODE = '55000';
        END IF;
        IF parent_row.dataset_version_id <> NEW.dataset_version_id
           OR parent_row.dataset_logical_key <> NEW.dataset_logical_key
           OR parent_row.dataset_type <> NEW.dataset_type
           OR parent_row.run_namespace <> NEW.run_namespace
           OR parent_row.request_key <> NEW.request_key
           OR parent_row.requested_range_start <> NEW.requested_range_start
           OR parent_row.requested_range_end <> NEW.requested_range_end
           OR parent_row.root_request_logical_key <> NEW.root_request_logical_key
           OR NEW.run_attempt_number <> parent_row.run_attempt_number + 1 THEN
            RAISE EXCEPTION 'RETRY run does not match its sealed parent request chain'
                USING ERRCODE = '23514';
        END IF;
    ELSE
        IF NEW.retry_of_run_logical_key IS NOT NULL OR NEW.run_attempt_number <> 1 THEN
            RAISE EXCEPTION 'non-RETRY run must be the first root request attempt'
                USING ERRCODE = '23514';
        END IF;
        expected_root_request_logical_key := compute_ingestion_root_request_logical_key(
            NEW.dataset_logical_key, NEW.dataset_type, NEW.run_namespace,
            NEW.operation_type, NEW.request_key, NEW.requested_range_start,
            NEW.requested_range_end);
        IF NEW.root_request_logical_key <> expected_root_request_logical_key THEN
            RAISE EXCEPTION 'ingestion root request logical key does not match canonical request'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    IF NEW.dataset_logical_key <> compute_ingestion_dataset_logical_key(NEW.dataset_version_id)
       OR NEW.ingestion_run_logical_key <> compute_ingestion_run_logical_key(
            NEW.dataset_logical_key, NEW.dataset_type, NEW.run_namespace,
            NEW.operation_type, NEW.request_key, NEW.root_request_logical_key,
            NEW.run_attempt_number, NEW.requested_range_start, NEW.requested_range_end,
            NEW.retry_of_run_logical_key) THEN
        RAISE EXCEPTION 'ingestion run logical identity does not match canonical dataset and request'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.run_namespace = 'FORMAL' THEN
        RAISE EXCEPTION 'FORMAL ingestion is unavailable before an approved source adapter exists'
            USING ERRCODE = '55000';
    END IF;
    NEW.started_at := clock_timestamp();
    NEW.created_at := NEW.started_at;
    RETURN NEW;
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
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'market_data_ingestion_runs is immutable; DELETE is forbidden'
            USING ERRCODE = '55000';
    END IF;

    IF OLD.status <> 'RUNNING' OR OLD.sealed_at IS NOT NULL THEN
        RAISE EXCEPTION 'sealed ingestion run is immutable'
            USING ERRCODE = '55000';
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

    EXECUTE format(
        'SELECT count(*) FROM %s WHERE ingestion_run_id=$1', run_record_table
    ) INTO received_count USING OLD.id;

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
        || 'FROM final_attempts WHERE rank=1',
        attempt_table
    ) INTO attempt_count, accepted_count, rejected_count, conservative_assurance USING OLD.id;

    IF conservative_assurance IS NULL THEN
        conservative_assurance := 'INFERRED_RESEARCH';
    END IF;
    SELECT trust_level INTO STRICT dataset_trust_level
    FROM market_data_dataset_versions
    WHERE id = OLD.dataset_version_id;
    IF dataset_trust_level = 'BACKFILLED_INFERRED' THEN
        conservative_assurance := 'INFERRED_RESEARCH';
    ELSIF conservative_assurance = 'PIT_VERIFIED' THEN
        -- 1A cannot seal a formal PIT run before a source adapter is approved.
        conservative_assurance := 'RECONSTRUCTED_VERIFIED';
    END IF;

    IF attempt_count <> received_count
       OR NEW.final_received_count <> received_count
       OR NEW.final_accepted_count <> accepted_count
       OR NEW.final_rejected_count <> rejected_count
       OR NEW.assurance_level <> conservative_assurance THEN
        RAISE EXCEPTION 'ingestion run final counts do not match terminal attempts'
            USING ERRCODE = '23514';
    END IF;
    expected_manifest_hash := compute_ingestion_manifest_hash(
        OLD.id, NEW.status, NEW.final_expected_count, NEW.final_received_count,
        NEW.final_accepted_count, NEW.final_rejected_count, NEW.assurance_level);
    IF NEW.manifest_hash <> expected_manifest_hash THEN
        RAISE EXCEPTION 'ingestion run manifest hash does not match its raw and attempt facts'
            USING ERRCODE = '23514';
    END IF;
    NEW.sealed_at := clock_timestamp();
    NEW.finished_at := NEW.sealed_at;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_market_data_ingestion_runs_protect
BEFORE UPDATE OR DELETE ON market_data_ingestion_runs
FOR EACH ROW EXECUTE FUNCTION protect_ingestion_run_mutation();

CREATE TRIGGER trg_market_data_ingestion_runs_validate_insert
BEFORE INSERT ON market_data_ingestion_runs
FOR EACH ROW EXECUTE FUNCTION validate_ingestion_run_insert();

CREATE TRIGGER trg_market_data_ingestion_runs_no_truncate
BEFORE TRUNCATE ON market_data_ingestion_runs
FOR EACH STATEMENT EXECUTE FUNCTION reject_ingestion_fact_truncate();

CREATE TRIGGER trg_security_status_raw_records_validate
BEFORE INSERT ON security_status_raw_records
FOR EACH ROW EXECUTE FUNCTION validate_ingestion_raw_insert('SECURITY_STATUS');
CREATE TRIGGER trg_trading_calendar_raw_records_validate
BEFORE INSERT ON trading_calendar_raw_records
FOR EACH ROW EXECUTE FUNCTION validate_ingestion_raw_insert('TRADING_CALENDAR');
CREATE CONSTRAINT TRIGGER trg_security_status_raw_records_first_run_receipt
AFTER INSERT ON security_status_raw_records
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION ensure_first_ingestion_run_record(
    'security_status_ingestion_run_records'
);
CREATE CONSTRAINT TRIGGER trg_trading_calendar_raw_records_first_run_receipt
AFTER INSERT ON trading_calendar_raw_records
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION ensure_first_ingestion_run_record(
    'trading_calendar_ingestion_run_records'
);

CREATE TRIGGER trg_security_status_raw_records_immutable
BEFORE UPDATE OR DELETE ON security_status_raw_records
FOR EACH ROW EXECUTE FUNCTION reject_ingestion_fact_mutation();
CREATE TRIGGER trg_security_status_raw_records_no_truncate
BEFORE TRUNCATE ON security_status_raw_records
FOR EACH STATEMENT EXECUTE FUNCTION reject_ingestion_fact_truncate();
CREATE TRIGGER trg_trading_calendar_raw_records_immutable
BEFORE UPDATE OR DELETE ON trading_calendar_raw_records
FOR EACH ROW EXECUTE FUNCTION reject_ingestion_fact_mutation();
CREATE TRIGGER trg_trading_calendar_raw_records_no_truncate
BEFORE TRUNCATE ON trading_calendar_raw_records
FOR EACH STATEMENT EXECUTE FUNCTION reject_ingestion_fact_truncate();

CREATE TRIGGER trg_security_status_ingestion_run_records_validate
BEFORE INSERT ON security_status_ingestion_run_records
FOR EACH ROW EXECUTE FUNCTION validate_ingestion_run_record_insert(
    'SECURITY_STATUS', 'security_status_raw_records'
);
CREATE TRIGGER trg_trading_calendar_ingestion_run_records_validate
BEFORE INSERT ON trading_calendar_ingestion_run_records
FOR EACH ROW EXECUTE FUNCTION validate_ingestion_run_record_insert(
    'TRADING_CALENDAR', 'trading_calendar_raw_records'
);
CREATE TRIGGER trg_security_status_ingestion_run_records_immutable
BEFORE UPDATE OR DELETE ON security_status_ingestion_run_records
FOR EACH ROW EXECUTE FUNCTION reject_ingestion_fact_mutation();
CREATE TRIGGER trg_security_status_ingestion_run_records_no_truncate
BEFORE TRUNCATE ON security_status_ingestion_run_records
FOR EACH STATEMENT EXECUTE FUNCTION reject_ingestion_fact_truncate();
CREATE TRIGGER trg_trading_calendar_ingestion_run_records_immutable
BEFORE UPDATE OR DELETE ON trading_calendar_ingestion_run_records
FOR EACH ROW EXECUTE FUNCTION reject_ingestion_fact_mutation();
CREATE TRIGGER trg_trading_calendar_ingestion_run_records_no_truncate
BEFORE TRUNCATE ON trading_calendar_ingestion_run_records
FOR EACH STATEMENT EXECUTE FUNCTION reject_ingestion_fact_truncate();

CREATE TRIGGER trg_security_status_processing_attempts_validate
BEFORE INSERT ON security_status_processing_attempts
FOR EACH ROW EXECUTE FUNCTION validate_ingestion_attempt_insert(
    'SECURITY_STATUS', 'security_status_raw_records', 'security_status_ingestion_run_records'
);
CREATE TRIGGER trg_trading_calendar_processing_attempts_validate
BEFORE INSERT ON trading_calendar_processing_attempts
FOR EACH ROW EXECUTE FUNCTION validate_ingestion_attempt_insert(
    'TRADING_CALENDAR', 'trading_calendar_raw_records', 'trading_calendar_ingestion_run_records'
);

CREATE TRIGGER trg_security_status_processing_attempts_immutable
BEFORE UPDATE OR DELETE ON security_status_processing_attempts
FOR EACH ROW EXECUTE FUNCTION reject_ingestion_fact_mutation();
CREATE TRIGGER trg_security_status_processing_attempts_no_truncate
BEFORE TRUNCATE ON security_status_processing_attempts
FOR EACH STATEMENT EXECUTE FUNCTION reject_ingestion_fact_truncate();
CREATE TRIGGER trg_trading_calendar_processing_attempts_immutable
BEFORE UPDATE OR DELETE ON trading_calendar_processing_attempts
FOR EACH ROW EXECUTE FUNCTION reject_ingestion_fact_mutation();
CREATE TRIGGER trg_trading_calendar_processing_attempts_no_truncate
BEFORE TRUNCATE ON trading_calendar_processing_attempts
FOR EACH STATEMENT EXECUTE FUNCTION reject_ingestion_fact_truncate();

-- Trigger and hash functions must resolve V7 relations and helper functions in the
-- schema where Flyway installed them, regardless of a caller-controlled search_path.
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
          ON namespace_record.oid = catalog_function.pronamespace
        WHERE namespace_record.nspname = migration_schema
          AND catalog_function.proname IN (
              'ingestion_canonical_append',
              'ingestion_canonical_sha256',
              'ingestion_canonical_instant',
              'ingestion_canonical_json_append',
              'compute_ingestion_json_hash',
              'compute_ingestion_dataset_logical_key',
              'compute_ingestion_root_request_logical_key',
              'compute_ingestion_run_logical_key',
              'compute_ingestion_raw_record_logical_key',
              'compute_ingestion_attempt_logical_key',
              'compute_ingestion_manifest_hash',
              'reject_ingestion_fact_mutation',
              'reject_ingestion_fact_truncate',
              'validate_ingestion_raw_insert',
              'validate_ingestion_run_record_insert',
              'ensure_first_ingestion_run_record',
              'validate_ingestion_attempt_insert',
              'validate_ingestion_run_insert',
              'protect_ingestion_run_mutation'
          )
    LOOP
        EXECUTE format(
            'ALTER FUNCTION %I.%I(%s) SET search_path TO pg_catalog, %I',
            target_function.nspname,
            target_function.proname,
            target_function.identity_arguments,
            migration_schema
        );
    END LOOP;
END;
$$;

COMMENT ON TABLE market_data_ingestion_runs IS
    'Stage 2D-2B-1A source-neutral ingestion lifecycle; sealed runs are immutable.';
COMMENT ON TABLE security_status_raw_records IS
    'Immutable raw security-status source revisions; no normalized assurance is stored here.';
COMMENT ON TABLE trading_calendar_raw_records IS
    'Immutable raw trading-calendar source revisions; no normalized assurance is stored here.';
COMMENT ON TABLE security_status_ingestion_run_records IS
    'Append-only receipt membership linking each security-status ingestion run to its raw facts.';
COMMENT ON TABLE trading_calendar_ingestion_run_records IS
    'Append-only receipt membership linking each trading-calendar ingestion run to its raw facts.';
COMMENT ON TABLE security_status_processing_attempts IS
    'Append-only terminal processing outcomes for security-status raw revisions.';
COMMENT ON TABLE trading_calendar_processing_attempts IS
    'Append-only terminal processing outcomes for trading-calendar raw revisions.';
