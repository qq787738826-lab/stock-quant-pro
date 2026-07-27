-- Stock Quant Pro 1.4.0 stage 3A-R1: forward-only temporal foundation hardening.
-- V6 is restored to the exact migration already applied to the existing public lineage.

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

DROP TRIGGER trg_security_status_events_append_only
    ON security_status_events;
DROP FUNCTION reject_security_status_event_update();

CREATE TRIGGER trg_security_status_events_immutable_rows
    BEFORE UPDATE OR DELETE ON security_status_events
    FOR EACH ROW EXECUTE FUNCTION reject_temporal_immutable_mutation();
CREATE TRIGGER trg_security_status_events_no_truncate
    BEFORE TRUNCATE ON security_status_events
    FOR EACH STATEMENT EXECUTE FUNCTION reject_temporal_immutable_mutation();

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

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM trading_calendar_revisions
        WHERE previous_open_date IS NOT NULL
           OR next_open_date IS NOT NULL
    ) THEN
        RAISE EXCEPTION
            'V12 cannot remove populated legacy trading-calendar navigation columns'
            USING ERRCODE = '55000';
    END IF;
END;
$$;

ALTER TABLE trading_calendar_revisions
    DROP CONSTRAINT ck_trading_calendar_revisions_previous_date,
    DROP CONSTRAINT ck_trading_calendar_revisions_next_date,
    DROP COLUMN previous_open_date,
    DROP COLUMN next_open_date;

CREATE TRIGGER trg_trading_calendar_revisions_guard_rows
    BEFORE UPDATE OR DELETE ON trading_calendar_revisions
    FOR EACH ROW EXECUTE FUNCTION allow_only_temporal_knowledge_close();
CREATE TRIGGER trg_trading_calendar_revisions_no_truncate
    BEFORE TRUNCATE ON trading_calendar_revisions
    FOR EACH STATEMENT EXECUTE FUNCTION reject_temporal_immutable_mutation();
