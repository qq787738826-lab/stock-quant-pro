-- M5 agent evaluation: immutable versions/reports and append-only decisions.
CREATE TABLE agent_evaluation_versions (
    version_key VARCHAR(96) PRIMARY KEY,
    version_kind VARCHAR(16) NOT NULL,
    parent_version_key VARCHAR(96)
        REFERENCES agent_evaluation_versions(version_key) ON DELETE RESTRICT,
    runtime_version VARCHAR(64) NOT NULL,
    tool_version VARCHAR(64) NOT NULL,
    strategy_version VARCHAR(64) NOT NULL,
    model_provider VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL,
    prompt_versions_json JSONB NOT NULL,
    evaluation_rule_version VARCHAR(64) NOT NULL,
    version_fingerprint VARCHAR(64) NOT NULL UNIQUE,
    registered_at TIMESTAMPTZ(6) NOT NULL,
    CONSTRAINT ck_agent_evaluation_versions_kind CHECK (
        version_kind IN ('CHAMPION', 'CHALLENGER')
    ),
    CONSTRAINT ck_agent_evaluation_versions_parent CHECK (
        version_kind='CHAMPION' OR parent_version_key IS NOT NULL
    ),
    CONSTRAINT ck_agent_evaluation_versions_json CHECK (
        jsonb_typeof(prompt_versions_json)='object'
    ),
    CONSTRAINT ck_agent_evaluation_versions_hash CHECK (
        version_fingerprint ~ '^[0-9a-f]{64}$'
    )
);

CREATE TABLE agent_evaluation_reports (
    id BIGSERIAL PRIMARY KEY,
    report_version VARCHAR(64) NOT NULL,
    champion_version_key VARCHAR(96) NOT NULL
        REFERENCES agent_evaluation_versions(version_key) ON DELETE RESTRICT,
    report_json JSONB NOT NULL,
    report_fingerprint VARCHAR(64) NOT NULL UNIQUE,
    generated_at TIMESTAMPTZ(6) NOT NULL,
    CONSTRAINT ck_agent_evaluation_reports_version CHECK (
        report_version='RESEARCH_PERFORMANCE_REPORT_V1'
    ),
    CONSTRAINT ck_agent_evaluation_reports_json CHECK (
        jsonb_typeof(report_json)='object'
    ),
    CONSTRAINT ck_agent_evaluation_reports_hash CHECK (
        report_fingerprint ~ '^[0-9a-f]{64}$'
    )
);

CREATE TABLE agent_evaluation_decisions (
    id BIGSERIAL PRIMARY KEY,
    comparison_version VARCHAR(64) NOT NULL,
    champion_version_key VARCHAR(96) NOT NULL
        REFERENCES agent_evaluation_versions(version_key) ON DELETE RESTRICT,
    challenger_version_key VARCHAR(96) NOT NULL
        REFERENCES agent_evaluation_versions(version_key) ON DELETE RESTRICT,
    decision VARCHAR(32) NOT NULL,
    comparison_fingerprint VARCHAR(64) NOT NULL UNIQUE,
    decided_at TIMESTAMPTZ(6) NOT NULL,
    CONSTRAINT ck_agent_evaluation_decisions_version CHECK (
        comparison_version='CHAMPION_CHALLENGER_V1'
    ),
    CONSTRAINT ck_agent_evaluation_decisions_identity CHECK (
        champion_version_key<>challenger_version_key
    ),
    CONSTRAINT ck_agent_evaluation_decisions_value CHECK (decision IN (
        'RETAIN_CHAMPION', 'WATCH_CHALLENGER',
        'PROMOTE_CHALLENGER', 'REJECT_CHALLENGER'
    )),
    CONSTRAINT ck_agent_evaluation_decisions_hash CHECK (
        comparison_fingerprint ~ '^[0-9a-f]{64}$'
    )
);

CREATE TABLE external_api_monthly_usage_ledger (
    id BIGSERIAL PRIMARY KEY,
    usage_key VARCHAR(128) NOT NULL UNIQUE,
    calendar_month CHAR(7) NOT NULL,
    budget_scope VARCHAR(64) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    request_count INTEGER NOT NULL,
    model_call_count INTEGER NOT NULL,
    input_units INTEGER NOT NULL,
    output_units INTEGER NOT NULL,
    reasoning_units INTEGER NOT NULL,
    total_units INTEGER NOT NULL,
    accounted_cost_cny NUMERIC(18,8) NOT NULL,
    telemetry_fingerprint VARCHAR(64) NOT NULL UNIQUE,
    recorded_at TIMESTAMPTZ(6) NOT NULL,
    CONSTRAINT ck_external_usage_month CHECK (
        calendar_month ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'
    ),
    CONSTRAINT ck_external_usage_provider CHECK (
        provider IN ('BAILIAN', 'TUSHARE')
    ),
    CONSTRAINT ck_external_usage_values CHECK (
        request_count>=0 AND model_call_count>=0 AND input_units>=0
        AND output_units>=0 AND reasoning_units>=0 AND total_units>=0
        AND reasoning_units<=output_units
        AND total_units=input_units+output_units
        AND accounted_cost_cny>=0
    ),
    CONSTRAINT ck_external_usage_hash CHECK (
        telemetry_fingerprint ~ '^[0-9a-f]{64}$'
    )
);

CREATE FUNCTION reject_m5_immutable_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only and cannot be %',
        TG_TABLE_NAME, lower(TG_OP);
END;
$$;

CREATE TRIGGER trg_agent_evaluation_versions_immutable
BEFORE UPDATE OR DELETE ON agent_evaluation_versions
FOR EACH ROW EXECUTE FUNCTION reject_m5_immutable_mutation();
CREATE TRIGGER trg_agent_evaluation_reports_immutable
BEFORE UPDATE OR DELETE ON agent_evaluation_reports
FOR EACH ROW EXECUTE FUNCTION reject_m5_immutable_mutation();
CREATE TRIGGER trg_agent_evaluation_decisions_immutable
BEFORE UPDATE OR DELETE ON agent_evaluation_decisions
FOR EACH ROW EXECUTE FUNCTION reject_m5_immutable_mutation();
CREATE TRIGGER trg_external_api_monthly_usage_immutable
BEFORE UPDATE OR DELETE ON external_api_monthly_usage_ledger
FOR EACH ROW EXECUTE FUNCTION reject_m5_immutable_mutation();

CREATE TRIGGER trg_agent_evaluation_versions_no_truncate
BEFORE TRUNCATE ON agent_evaluation_versions
FOR EACH STATEMENT EXECUTE FUNCTION reject_m5_immutable_mutation();
CREATE TRIGGER trg_agent_evaluation_reports_no_truncate
BEFORE TRUNCATE ON agent_evaluation_reports
FOR EACH STATEMENT EXECUTE FUNCTION reject_m5_immutable_mutation();
CREATE TRIGGER trg_agent_evaluation_decisions_no_truncate
BEFORE TRUNCATE ON agent_evaluation_decisions
FOR EACH STATEMENT EXECUTE FUNCTION reject_m5_immutable_mutation();
CREATE TRIGGER trg_external_api_monthly_usage_no_truncate
BEFORE TRUNCATE ON external_api_monthly_usage_ledger
FOR EACH STATEMENT EXECUTE FUNCTION reject_m5_immutable_mutation();

COMMENT ON TABLE agent_evaluation_versions IS
    'Immutable M5 champion/challenger identities; old Shadow remains bound to old versions.';
COMMENT ON TABLE agent_evaluation_reports IS
    'Append-only explainable scorecards and version comparisons.';
COMMENT ON TABLE external_api_monthly_usage_ledger IS
    'Sanitized monthly API telemetry; never stores credentials or request payloads.';
