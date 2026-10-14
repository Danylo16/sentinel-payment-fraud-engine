-- Existing financial history stays unchanged. Every NEW customer transfer requires fraud assessment.
ALTER TABLE transfers ADD COLUMN fraud_required BOOLEAN NOT NULL DEFAULT false;
DO $$ DECLARE c RECORD; BEGIN
    FOR c IN SELECT conname FROM pg_constraint WHERE conrelid='transfers'::regclass AND contype='c'
             AND pg_get_constraintdef(oid) LIKE '%status%' LOOP
        EXECUTE format('ALTER TABLE transfers DROP CONSTRAINT %I', c.conname);
    END LOOP;
END $$;
ALTER TABLE transfers ADD CONSTRAINT transfers_valid_status
    CHECK (status IN ('RECEIVED','COMPLETED','REJECTED','REVIEW','DECLINED'));
ALTER TABLE transfers ADD CONSTRAINT transfers_failure_status
    CHECK ((status='REJECTED' AND failure_reason IS NOT NULL AND failure_reason IN ('INSUFFICIENT_FUNDS','BALANCE_LIMIT'))
        OR (status<>'REJECTED' AND failure_reason IS NULL));
ALTER TABLE audit_events DROP CONSTRAINT audit_events_event_type_check;
ALTER TABLE audit_events ADD CONSTRAINT audit_valid_event
    CHECK (event_type IN ('RECEIVED','COMPLETED','REJECTED','REVIEW','DECLINED'));

CREATE FUNCTION initialize_fraud_requirement() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status <> 'RECEIVED' THEN
        RAISE EXCEPTION 'New transfer must start at RECEIVED' USING ERRCODE='23514';
    END IF;
    NEW.fraud_required := NEW.operation = 'TRANSFER';
    RETURN NEW;
END $$;
CREATE TRIGGER transfers_fraud_requirement BEFORE INSERT ON transfers
    FOR EACH ROW EXECUTE FUNCTION initialize_fraud_requirement();

CREATE OR REPLACE FUNCTION guard_transfer_transition() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id, NEW.idempotency_key, NEW.operation, NEW.source_account_id, NEW.target_account_id,
           NEW.amount_cents, NEW.currency, NEW.created_at, NEW.fraud_required)
       IS DISTINCT FROM
       ROW(OLD.id, OLD.idempotency_key, OLD.operation, OLD.source_account_id, OLD.target_account_id,
           OLD.amount_cents, OLD.currency, OLD.created_at, OLD.fraud_required) THEN
        RAISE EXCEPTION 'Transfer request is immutable' USING ERRCODE='23514';
    END IF;
    IF OLD.status <> 'RECEIVED' OR NEW.status NOT IN ('COMPLETED','REJECTED','REVIEW','DECLINED') THEN
        RAISE EXCEPTION 'Illegal transfer status transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

-- Validate hit structure and aggregate contribution without trusting the Java scorer.
CREATE FUNCTION valid_fraud_rules(hits JSONB, risk INTEGER) RETURNS BOOLEAN
LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE hit JSONB; total BIGINT := 0; contribution INTEGER; ids TEXT[] := '{}';
BEGIN
    IF jsonb_typeof(hits) <> 'array' THEN RETURN false; END IF;
    FOR hit IN SELECT value FROM jsonb_array_elements(hits) LOOP
        IF jsonb_typeof(hit)<>'object' OR jsonb_typeof(hit->'ruleId') IS DISTINCT FROM 'string'
           OR length(hit->>'ruleId')=0 OR jsonb_typeof(hit->'reason') IS DISTINCT FROM 'string'
           OR length(hit->>'reason')=0 OR jsonb_typeof(hit->'evidence') IS DISTINCT FROM 'object'
           OR jsonb_typeof(hit->'contribution') IS DISTINCT FROM 'number' THEN RETURN false; END IF;
        IF (hit->>'contribution') !~ '^[0-9]+$' THEN RETURN false; END IF;
        contribution := (hit->>'contribution')::INTEGER;
        IF contribution NOT BETWEEN 1 AND 100 OR (hit->>'ruleId') = ANY(ids) THEN RETURN false; END IF;
        ids := array_append(ids, hit->>'ruleId');
        total := total + contribution;
    END LOOP;
    RETURN risk = LEAST(100,total);
EXCEPTION WHEN numeric_value_out_of_range THEN RETURN false;
END $$;
CREATE TABLE fraud_decisions (
    transfer_id UUID PRIMARY KEY REFERENCES transfers(id),
    engine_version VARCHAR(30) NOT NULL,
    evaluated_at TIMESTAMPTZ NOT NULL,
    score INTEGER NOT NULL CHECK (score BETWEEN 0 AND 100),
    decision VARCHAR(10) NOT NULL CHECK (decision IN ('APPROVE','REVIEW','DECLINE')),
    review_threshold INTEGER NOT NULL,
    decline_threshold INTEGER NOT NULL,
    rules JSONB NOT NULL,
    CHECK (review_threshold >= 1 AND review_threshold < decline_threshold AND decline_threshold <= 100),
    CHECK ((decision='APPROVE' AND score < review_threshold)
        OR (decision='REVIEW' AND score >= review_threshold AND score < decline_threshold)
        OR (decision='DECLINE' AND score >= decline_threshold)),
    CHECK (valid_fraud_rules(rules,score))
);
CREATE INDEX fraud_evaluation_time ON fraud_decisions(evaluated_at);
CREATE TRIGGER fraud_immutable BEFORE UPDATE OR DELETE ON fraud_decisions
    FOR EACH ROW EXECUTE FUNCTION forbid_accounting_mutation();
CREATE TRIGGER fraud_no_truncate BEFORE TRUNCATE ON fraud_decisions
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_accounting_mutation();

CREATE OR REPLACE FUNCTION assert_transfer_accounting() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    tid UUID;
    t transfers%ROWTYPE;
    n BIGINT;
    total NUMERIC;
    source_type VARCHAR(20);
    target_type VARCHAR(20);
BEGIN
    IF TG_TABLE_NAME = 'transfers' THEN tid := NEW.id; ELSE tid := NEW.transfer_id; END IF;
    SELECT * INTO STRICT t FROM transfers WHERE id = tid;
    SELECT count(*), COALESCE(sum(signed_cents), 0) INTO n, total FROM ledger_entries WHERE transfer_id = tid;
    IF t.status = 'RECEIVED' THEN
        RAISE EXCEPTION 'An unfinished transfer cannot commit' USING ERRCODE = '23514';
    ELSIF t.status = 'COMPLETED' THEN
        IF n <> 2 OR total <> 0 OR
           NOT EXISTS (SELECT 1 FROM ledger_entries WHERE transfer_id = tid
               AND account_id = t.source_account_id AND signed_cents = -t.amount_cents) OR
           NOT EXISTS (SELECT 1 FROM ledger_entries WHERE transfer_id = tid
               AND account_id = t.target_account_id AND signed_cents = t.amount_cents) THEN
            RAISE EXCEPTION 'Completed transfer requires exactly two matching balanced entries' USING ERRCODE = '23514';
        END IF;
    ELSIF n <> 0 THEN
        RAISE EXCEPTION 'Unexecuted transfer must not have ledger entries' USING ERRCODE = '23514';
    END IF;
    SELECT account_type INTO source_type FROM accounts WHERE id = t.source_account_id;
    SELECT account_type INTO target_type FROM accounts WHERE id = t.target_account_id;
    IF target_type <> 'CUSTOMER' OR
       (t.operation = 'TRANSFER' AND source_type <> 'CUSTOMER') OR
       (t.operation = 'FUNDING' AND source_type <> 'SYSTEM_CONTRA') THEN
        RAISE EXCEPTION 'Invalid operation/account types' USING ERRCODE = '23514';
    END IF;
    IF (SELECT count(*) FROM audit_events WHERE transfer_id = tid) <> 2 OR
       NOT EXISTS (SELECT 1 FROM audit_events WHERE transfer_id = tid AND event_type = 'RECEIVED') OR
       NOT EXISTS (SELECT 1 FROM audit_events WHERE transfer_id = tid AND event_type = t.status) THEN
        RAISE EXCEPTION 'Transfer requires received and terminal audit events' USING ERRCODE = '23514';
    END IF;
    IF t.fraud_required THEN
        IF NOT EXISTS (SELECT 1 FROM fraud_decisions f WHERE f.transfer_id = tid AND
              ((f.decision = 'APPROVE' AND t.status IN ('COMPLETED','REJECTED')) OR
               (f.decision = 'REVIEW' AND t.status = 'REVIEW') OR
               (f.decision = 'DECLINE' AND t.status = 'DECLINED'))) THEN
            RAISE EXCEPTION 'Transfer status requires a matching fraud decision' USING ERRCODE = '23514';
        END IF;
    ELSIF EXISTS (SELECT 1 FROM fraud_decisions WHERE transfer_id = tid) THEN
        RAISE EXCEPTION 'Funding and legacy transfers cannot acquire fraud decisions' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER fraud_accounting AFTER INSERT ON fraud_decisions
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION assert_transfer_accounting();
