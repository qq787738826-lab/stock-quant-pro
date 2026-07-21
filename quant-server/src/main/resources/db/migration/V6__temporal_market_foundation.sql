-- Stock Quant Pro 1.4.0 stage 2D-2A: temporal market-data foundation.
-- Authoritative history is intentionally independent from the mutable securities projection.

CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE market_data_dataset_versions (
    id BIGSERIAL PRIMARY KEY,
    dataset_type VARCHAR(64) NOT NULL,
    source VARCHAR(128) NOT NULL,
    source_version VARCHAR(128) NOT NULL,
    connector_version VARCHAR(128) NOT NULL,
    range_start DATE NOT NULL,
    range_end DATE NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    payload_hash VARCHAR(64) NOT NULL,
    trust_level VARCHAR(32) NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::JSONB,
    CONSTRAINT ck_market_dataset_type_nonblank CHECK (btrim(dataset_type) <> ''),
    CONSTRAINT ck_market_dataset_source_nonblank CHECK (btrim(source) <> ''),
    CONSTRAINT ck_market_dataset_source_version_nonblank CHECK (btrim(source_version) <> ''),
    CONSTRAINT ck_market_dataset_connector_version_nonblank CHECK (btrim(connector_version) <> ''),
    CONSTRAINT ck_market_dataset_date_range CHECK (range_end >= range_start),
    CONSTRAINT ck_market_dataset_recorded_after_fetch CHECK (recorded_at >= fetched_at),
    CONSTRAINT ck_market_dataset_payload_hash CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_market_dataset_trust_level CHECK (trust_level IN (
        'OBSERVED', 'BACKFILLED_VERIFIED', 'BACKFILLED_INFERRED'
    )),
    CONSTRAINT ck_market_dataset_metadata_object CHECK (jsonb_typeof(metadata) = 'object'),
    CONSTRAINT uq_market_dataset_source_payload UNIQUE (
        dataset_type, source, source_version, connector_version,
        range_start, range_end, payload_hash
    )
);

CREATE FUNCTION reject_temporal_immutable_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% is immutable; % is forbidden', TG_TABLE_NAME, TG_OP
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_market_dataset_versions_immutable_rows
    BEFORE UPDATE OR DELETE ON market_data_dataset_versions
    FOR EACH ROW EXECUTE FUNCTION reject_temporal_immutable_mutation();
CREATE TRIGGER trg_market_dataset_versions_no_truncate
    BEFORE TRUNCATE ON market_data_dataset_versions
    FOR EACH STATEMENT EXECUTE FUNCTION reject_temporal_immutable_mutation();

CREATE INDEX idx_market_dataset_type_range
    ON market_data_dataset_versions (dataset_type, range_start, range_end, fetched_at DESC);
CREATE INDEX idx_market_dataset_source_version
    ON market_data_dataset_versions (source, source_version, fetched_at DESC);

CREATE TABLE security_status_events (
    id BIGSERIAL PRIMARY KEY,
    dataset_version_id BIGINT NOT NULL,
    symbol VARCHAR(12) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE,
    published_at TIMESTAMPTZ,
    known_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    source VARCHAR(128) NOT NULL,
    source_version VARCHAR(128) NOT NULL,
    source_record_id VARCHAR(256) NOT NULL,
    source_revision VARCHAR(128) NOT NULL,
    trust_level VARCHAR(32) NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::JSONB,
    payload_hash VARCHAR(64) NOT NULL,
    supersedes_event_id BIGINT,
    CONSTRAINT fk_security_status_events_dataset FOREIGN KEY (dataset_version_id)
        REFERENCES market_data_dataset_versions (id) ON DELETE RESTRICT,
    CONSTRAINT fk_security_status_events_supersedes FOREIGN KEY (supersedes_event_id)
        REFERENCES security_status_events (id) ON DELETE RESTRICT,
    CONSTRAINT ck_security_status_events_symbol_nonblank CHECK (btrim(symbol) <> ''),
    CONSTRAINT ck_security_status_events_type CHECK (event_type IN (
        'LISTING', 'DELISTING', 'ST_CHANGE', 'ACTIVE_CHANGE', 'BOARD_CHANGE',
        'EXCHANGE_CHANGE', 'FULL_STATUS_SNAPSHOT'
    )),
    CONSTRAINT ck_security_status_events_effective_range CHECK (
        effective_to IS NULL OR effective_to > effective_from
    ),
    CONSTRAINT ck_security_status_events_published_known CHECK (
        published_at IS NULL OR known_at >= published_at
    ),
    CONSTRAINT ck_security_status_events_recorded_known CHECK (recorded_at >= known_at),
    CONSTRAINT ck_security_status_events_source_nonblank CHECK (btrim(source) <> ''),
    CONSTRAINT ck_security_status_events_source_version_nonblank CHECK (btrim(source_version) <> ''),
    CONSTRAINT ck_security_status_events_record_id_nonblank CHECK (btrim(source_record_id) <> ''),
    CONSTRAINT ck_security_status_events_revision_nonblank CHECK (btrim(source_revision) <> ''),
    CONSTRAINT ck_security_status_events_trust_level CHECK (trust_level IN (
        'OBSERVED', 'BACKFILLED_VERIFIED', 'BACKFILLED_INFERRED'
    )),
    CONSTRAINT ck_security_status_events_payload_object CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_security_status_events_payload_hash CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_security_status_events_not_self_superseding CHECK (
        supersedes_event_id IS NULL OR supersedes_event_id <> id
    ),
    CONSTRAINT uq_security_status_events_source_record UNIQUE (
        source, source_version, source_record_id, source_revision
    )
);

CREATE INDEX idx_security_status_events_dataset
    ON security_status_events (dataset_version_id);
CREATE INDEX idx_security_status_events_symbol_effective
    ON security_status_events (symbol, effective_from, effective_to);
CREATE INDEX idx_security_status_events_symbol_known
    ON security_status_events (symbol, known_at DESC, recorded_at DESC);
CREATE INDEX idx_security_status_events_type_effective
    ON security_status_events (event_type, effective_from);
CREATE UNIQUE INDEX uq_security_status_events_superseded_once
    ON security_status_events (supersedes_event_id)
    WHERE supersedes_event_id IS NOT NULL;

CREATE TRIGGER trg_security_status_events_immutable_rows
    BEFORE UPDATE OR DELETE ON security_status_events
    FOR EACH ROW EXECUTE FUNCTION reject_temporal_immutable_mutation();
CREATE TRIGGER trg_security_status_events_no_truncate
    BEFORE TRUNCATE ON security_status_events
    FOR EACH STATEMENT EXECUTE FUNCTION reject_temporal_immutable_mutation();

CREATE TABLE security_status_history (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(12) NOT NULL,
    exchange VARCHAR(8) NOT NULL,
    board VARCHAR(64) NOT NULL,
    listed BOOLEAN NOT NULL,
    active BOOLEAN NOT NULL,
    is_st BOOLEAN NOT NULL,
    valid_from DATE NOT NULL,
    valid_to DATE,
    known_from TIMESTAMPTZ NOT NULL,
    known_to TIMESTAMPTZ,
    source_event_id BIGINT NOT NULL,
    dataset_version_id BIGINT NOT NULL,
    source VARCHAR(128) NOT NULL,
    source_version VARCHAR(128) NOT NULL,
    trust_level VARCHAR(32) NOT NULL,
    status_hash VARCHAR(64) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_security_status_history_event FOREIGN KEY (source_event_id)
        REFERENCES security_status_events (id) ON DELETE RESTRICT,
    CONSTRAINT fk_security_status_history_dataset FOREIGN KEY (dataset_version_id)
        REFERENCES market_data_dataset_versions (id) ON DELETE RESTRICT,
    CONSTRAINT ck_security_status_history_symbol_nonblank CHECK (btrim(symbol) <> ''),
    CONSTRAINT ck_security_status_history_exchange CHECK (exchange IN ('SSE', 'SZSE')),
    CONSTRAINT ck_security_status_history_board_nonblank CHECK (btrim(board) <> ''),
    CONSTRAINT ck_security_status_history_valid_range CHECK (
        valid_to IS NULL OR valid_to > valid_from
    ),
    CONSTRAINT ck_security_status_history_known_range CHECK (
        known_to IS NULL OR known_to > known_from
    ),
    CONSTRAINT ck_security_status_history_recorded_known CHECK (recorded_at >= known_from),
    CONSTRAINT ck_security_status_history_source_nonblank CHECK (btrim(source) <> ''),
    CONSTRAINT ck_security_status_history_source_version_nonblank CHECK (btrim(source_version) <> ''),
    CONSTRAINT ck_security_status_history_trust_level CHECK (trust_level IN (
        'OBSERVED', 'BACKFILLED_VERIFIED', 'BACKFILLED_INFERRED'
    )),
    CONSTRAINT ck_security_status_history_status_hash CHECK (status_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT uq_security_status_history_event_projection UNIQUE (
        source_event_id, valid_from, known_from
    ),
    CONSTRAINT ex_security_status_history_bitemporal_overlap EXCLUDE USING gist (
        symbol WITH =,
        daterange(valid_from, valid_to, '[)') WITH &&,
        tstzrange(known_from, known_to, '[)') WITH &&
    ) DEFERRABLE INITIALLY IMMEDIATE
);

CREATE FUNCTION allow_only_temporal_knowledge_close()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE'
       AND OLD.known_to IS NULL
       AND NEW.known_to IS NOT NULL
       AND NEW.known_to > OLD.known_from
       AND (to_jsonb(NEW) - 'known_to') = (to_jsonb(OLD) - 'known_to') THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION '% only permits one NULL-to-value known_to close; % is forbidden',
        TG_TABLE_NAME, TG_OP USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_security_status_history_guard_rows
    BEFORE UPDATE OR DELETE ON security_status_history
    FOR EACH ROW EXECUTE FUNCTION allow_only_temporal_knowledge_close();
CREATE TRIGGER trg_security_status_history_no_truncate
    BEFORE TRUNCATE ON security_status_history
    FOR EACH STATEMENT EXECUTE FUNCTION reject_temporal_immutable_mutation();

CREATE INDEX idx_security_status_history_dataset
    ON security_status_history (dataset_version_id);
CREATE INDEX idx_security_status_history_event
    ON security_status_history (source_event_id);
CREATE INDEX idx_security_status_history_as_of
    ON security_status_history (
        symbol, valid_from, valid_to, known_from DESC, known_to
    );
CREATE INDEX idx_security_status_history_current_knowledge
    ON security_status_history (symbol, valid_from, valid_to)
    WHERE known_to IS NULL;
CREATE INDEX idx_security_status_history_eligible_as_of
    ON security_status_history (
        exchange, board, valid_from, valid_to, known_from DESC, known_to, symbol
    )
    WHERE listed = TRUE AND active = TRUE AND is_st = FALSE;

CREATE TABLE trading_calendar_revisions (
    id BIGSERIAL PRIMARY KEY,
    dataset_version_id BIGINT NOT NULL,
    exchange VARCHAR(8) NOT NULL,
    trade_date DATE NOT NULL,
    is_open BOOLEAN NOT NULL,
    session_type VARCHAR(32) NOT NULL,
    session_open_at TIMESTAMPTZ,
    session_close_at TIMESTAMPTZ,
    known_from TIMESTAMPTZ NOT NULL,
    known_to TIMESTAMPTZ,
    source VARCHAR(128) NOT NULL,
    source_version VARCHAR(128) NOT NULL,
    source_record_id VARCHAR(256) NOT NULL,
    source_revision VARCHAR(128) NOT NULL,
    trust_level VARCHAR(32) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_trading_calendar_revisions_dataset FOREIGN KEY (dataset_version_id)
        REFERENCES market_data_dataset_versions (id) ON DELETE RESTRICT,
    CONSTRAINT ck_trading_calendar_revisions_exchange CHECK (exchange IN ('SSE', 'SZSE')),
    CONSTRAINT ck_trading_calendar_revisions_session_type CHECK (session_type IN (
        'REGULAR', 'HALF_DAY', 'HOLIDAY', 'TEMPORARY_CLOSURE'
    )),
    CONSTRAINT ck_trading_calendar_revisions_session CHECK (
        (
            is_open = TRUE
            AND session_type IN ('REGULAR', 'HALF_DAY')
            AND session_open_at IS NOT NULL
            AND session_close_at IS NOT NULL
            AND session_close_at > session_open_at
        )
        OR (
            is_open = FALSE
            AND session_type IN ('HOLIDAY', 'TEMPORARY_CLOSURE')
            AND session_open_at IS NULL
            AND session_close_at IS NULL
        )
    ),
    CONSTRAINT ck_trading_calendar_revisions_known_range CHECK (
        known_to IS NULL OR known_to > known_from
    ),
    CONSTRAINT ck_trading_calendar_revisions_recorded_known CHECK (recorded_at >= known_from),
    CONSTRAINT ck_trading_calendar_revisions_source_nonblank CHECK (btrim(source) <> ''),
    CONSTRAINT ck_trading_calendar_revisions_source_version_nonblank CHECK (btrim(source_version) <> ''),
    CONSTRAINT ck_trading_calendar_revisions_record_id_nonblank CHECK (btrim(source_record_id) <> ''),
    CONSTRAINT ck_trading_calendar_revisions_revision_nonblank CHECK (btrim(source_revision) <> ''),
    CONSTRAINT ck_trading_calendar_revisions_trust_level CHECK (trust_level IN (
        'OBSERVED', 'BACKFILLED_VERIFIED', 'BACKFILLED_INFERRED'
    )),
    CONSTRAINT ck_trading_calendar_revisions_payload_hash CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT uq_trading_calendar_revisions_source_record UNIQUE (
        source, source_version, source_record_id, source_revision, exchange, trade_date
    ),
    CONSTRAINT ex_trading_calendar_revisions_knowledge_overlap EXCLUDE USING gist (
        exchange WITH =,
        trade_date WITH =,
        tstzrange(known_from, known_to, '[)') WITH &&
    ) DEFERRABLE INITIALLY IMMEDIATE
);

CREATE TRIGGER trg_trading_calendar_revisions_guard_rows
    BEFORE UPDATE OR DELETE ON trading_calendar_revisions
    FOR EACH ROW EXECUTE FUNCTION allow_only_temporal_knowledge_close();
CREATE TRIGGER trg_trading_calendar_revisions_no_truncate
    BEFORE TRUNCATE ON trading_calendar_revisions
    FOR EACH STATEMENT EXECUTE FUNCTION reject_temporal_immutable_mutation();

CREATE INDEX idx_trading_calendar_revisions_dataset
    ON trading_calendar_revisions (dataset_version_id);
CREATE INDEX idx_trading_calendar_revisions_as_of
    ON trading_calendar_revisions (
        exchange, trade_date, known_from DESC, known_to
    );
CREATE UNIQUE INDEX uq_trading_calendar_revisions_current_knowledge
    ON trading_calendar_revisions (exchange, trade_date)
    WHERE known_to IS NULL;
CREATE INDEX idx_trading_calendar_revisions_open_dates
    ON trading_calendar_revisions (exchange, trade_date, known_from DESC)
    WHERE is_open = TRUE;

COMMENT ON TABLE market_data_dataset_versions IS
    'Immutable provenance and trust envelope for one ingested or backfilled market dataset version.';
COMMENT ON TABLE security_status_events IS
    'Append-only authoritative security-status facts; corrections reference the superseded event.';
COMMENT ON COLUMN security_status_events.effective_to IS
    'Exclusive valid-time upper bound; NULL means unbounded.';
COMMENT ON TABLE security_status_history IS
    'Bitemporal normalized security status derived from authoritative status events.';
COMMENT ON COLUMN security_status_history.valid_to IS
    'Exclusive valid-time upper bound; NULL means unbounded.';
COMMENT ON COLUMN security_status_history.known_to IS
    'Exclusive knowledge-time upper bound; NULL means the version is currently known.';
COMMENT ON TABLE trading_calendar_revisions IS
    'Versioned SSE/SZSE trading-calendar facts selected by an explicit knowledge-time cutoff.';
COMMENT ON COLUMN trading_calendar_revisions.known_to IS
    'Exclusive knowledge-time upper bound; NULL means the revision is currently known.';
