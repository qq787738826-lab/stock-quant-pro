-- Governance-only migration. Loaded explicitly by the controlled-acceptance
-- test/execution database and never by the normal application Flyway location.
CREATE TABLE tushare_controlled_acceptance_execution (
    acceptance_id VARCHAR(64) PRIMARY KEY,
    authorization_fingerprint VARCHAR(64) NOT NULL,
    execution_source VARCHAR(32) NOT NULL,
    provider_code VARCHAR(64) NOT NULL,
    source_instrument_id VARCHAR(64) NOT NULL,
    trade_date DATE NOT NULL,
    endpoints_json JSONB NOT NULL,
    code_baseline_commit VARCHAR(40) NOT NULL,
    artifact_sha256 VARCHAR(64) NOT NULL,
    database_identity VARCHAR(64) NOT NULL,
    database_user VARCHAR(64) NOT NULL,
    schema_name VARCHAR(64) NOT NULL,
    schema_version INTEGER NOT NULL,
    created_at TIMESTAMPTZ(6) NOT NULL,
    authorization_expires_at TIMESTAMPTZ(6) NOT NULL,
    reserved_at TIMESTAMPTZ(6),
    started_at TIMESTAMPTZ(6),
    finalized_at TIMESTAMPTZ(6),
    status VARCHAR(48) NOT NULL,
    failure_stage VARCHAR(48),
    safe_failure_reason VARCHAR(256),
    capture_batch_id BIGINT,
    provider_call_count INTEGER NOT NULL DEFAULT 0,
    retry_count INTEGER NOT NULL DEFAULT 0,
    evidence_summary_json JSONB,
    evidence_digest VARCHAR(64),
    executor_version VARCHAR(64) NOT NULL,
    qualification_rule_version VARCHAR(64) NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_tca_execution_source CHECK (
        execution_source IN ('TEST', 'REAL_CONTROLLED_ACCEPTANCE')
    ),
    CONSTRAINT ck_tca_status CHECK (status IN (
        'AUTHORIZED', 'RESERVED', 'RUNNING', 'SUCCEEDED_CANDIDATE',
        'PASSED', 'FAILED_PRE_PROVIDER', 'FAILED_PROVIDER',
        'FAILED_VALIDATION', 'FAILED_DATABASE_GUARD',
        'FAILED_PERSISTENCE', 'FAILED_ROLLBACK', 'FAILED_QFQ',
        'FAILED_OUTPUT_AUDIT', 'INTERRUPTED', 'STALE',
        'INCOMPATIBLE_BASELINE'
    )),
    CONSTRAINT ck_tca_hashes CHECK (
        code_baseline_commit ~ '^[0-9a-f]{40}$'
        AND artifact_sha256 ~ '^[0-9a-f]{64}$'
        AND authorization_fingerprint ~ '^[0-9a-f]{64}$'
        AND (evidence_digest IS NULL OR evidence_digest ~ '^[0-9a-f]{64}$')
    ),
    CONSTRAINT ck_tca_scope CHECK (
        provider_code = 'TUSHARE_PRO'
        AND jsonb_array_length(endpoints_json) = 3
        AND schema_version = 14
        AND provider_call_count BETWEEN 0 AND 3
        AND retry_count = 0
        AND authorization_expires_at > created_at
    )
);

CREATE TABLE tushare_controlled_acceptance_transition (
    transition_id BIGSERIAL PRIMARY KEY,
    acceptance_id VARCHAR(64) NOT NULL REFERENCES
        tushare_controlled_acceptance_execution(acceptance_id),
    from_status VARCHAR(48),
    to_status VARCHAR(48) NOT NULL,
    transition_at TIMESTAMPTZ(6) NOT NULL,
    row_version BIGINT NOT NULL,
    safe_reason_code VARCHAR(256)
);

CREATE OR REPLACE FUNCTION enforce_tca_transition()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE allowed BOOLEAN := FALSE;
BEGIN
    IF NEW.acceptance_id <> OLD.acceptance_id
       OR NEW.authorization_fingerprint <> OLD.authorization_fingerprint
       OR NEW.execution_source <> OLD.execution_source
       OR NEW.provider_code <> OLD.provider_code
       OR NEW.source_instrument_id <> OLD.source_instrument_id
       OR NEW.trade_date <> OLD.trade_date
       OR NEW.endpoints_json <> OLD.endpoints_json
       OR NEW.code_baseline_commit <> OLD.code_baseline_commit
       OR NEW.artifact_sha256 <> OLD.artifact_sha256
       OR NEW.database_identity <> OLD.database_identity
       OR NEW.database_user <> OLD.database_user
       OR NEW.schema_name <> OLD.schema_name
       OR NEW.schema_version <> OLD.schema_version
       OR NEW.created_at <> OLD.created_at
       OR NEW.authorization_expires_at <> OLD.authorization_expires_at
       OR NEW.executor_version <> OLD.executor_version
       OR NEW.qualification_rule_version <> OLD.qualification_rule_version THEN
        RAISE EXCEPTION 'controlled acceptance immutable scope changed'
            USING ERRCODE = '23514';
    END IF;

    allowed := (OLD.status = 'AUTHORIZED' AND NEW.status = 'RESERVED')
        OR (OLD.status = 'RESERVED' AND NEW.status IN (
            'RUNNING', 'FAILED_PRE_PROVIDER', 'FAILED_DATABASE_GUARD',
            'STALE', 'INCOMPATIBLE_BASELINE', 'INTERRUPTED'))
        OR (OLD.status = 'RUNNING' AND NEW.status IN (
            'SUCCEEDED_CANDIDATE', 'FAILED_PROVIDER', 'FAILED_VALIDATION',
            'FAILED_DATABASE_GUARD', 'FAILED_PERSISTENCE',
            'FAILED_ROLLBACK', 'FAILED_QFQ', 'FAILED_OUTPUT_AUDIT',
            'INTERRUPTED'))
        OR (OLD.status = 'SUCCEEDED_CANDIDATE' AND NEW.status IN (
            'PASSED', 'FAILED_OUTPUT_AUDIT', 'FAILED_VALIDATION',
            'INTERRUPTED'));
    IF NOT allowed OR NEW.row_version <> OLD.row_version + 1 THEN
        RAISE EXCEPTION 'controlled acceptance transition rejected'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_tca_transition_guard
BEFORE UPDATE ON tushare_controlled_acceptance_execution
FOR EACH ROW EXECUTE FUNCTION enforce_tca_transition();

CREATE OR REPLACE FUNCTION record_tca_transition()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO tushare_controlled_acceptance_transition (
        acceptance_id, from_status, to_status, transition_at,
        row_version, safe_reason_code
    ) VALUES (
        NEW.acceptance_id,
        CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE OLD.status END,
        NEW.status,
        clock_timestamp(),
        NEW.row_version,
        NEW.safe_failure_reason
    );
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_tca_transition_history
AFTER INSERT OR UPDATE ON tushare_controlled_acceptance_execution
FOR EACH ROW EXECUTE FUNCTION record_tca_transition();

CREATE OR REPLACE FUNCTION reject_tca_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'controlled acceptance audit data is append-only'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER trg_tca_execution_no_delete
BEFORE DELETE OR TRUNCATE ON tushare_controlled_acceptance_execution
FOR EACH STATEMENT EXECUTE FUNCTION reject_tca_mutation();

CREATE TRIGGER trg_tca_transition_immutable
BEFORE UPDATE OR DELETE OR TRUNCATE ON tushare_controlled_acceptance_transition
FOR EACH STATEMENT EXECUTE FUNCTION reject_tca_mutation();
