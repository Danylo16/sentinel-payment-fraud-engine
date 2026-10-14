-- Foundation has no funding. Refuse unexplained balances instead of silently inventing ledger history.
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM accounts WHERE balance_cents <> 0) THEN
        RAISE EXCEPTION 'Cannot introduce ledger over nonzero unexplained balances';
    END IF;
END $$;

ALTER TABLE accounts ADD COLUMN account_type VARCHAR(20) NOT NULL DEFAULT 'CUSTOMER';
ALTER TABLE accounts ADD CONSTRAINT accounts_valid_type CHECK (account_type IN ('CUSTOMER', 'SYSTEM_CONTRA'));
ALTER TABLE accounts DROP CONSTRAINT accounts_nonnegative_balance;
ALTER TABLE accounts ADD CONSTRAINT accounts_nonnegative_balance
    CHECK (account_type = 'SYSTEM_CONTRA' OR balance_cents >= 0);
CREATE UNIQUE INDEX accounts_one_system_contra ON accounts(account_type) WHERE account_type = 'SYSTEM_CONTRA';
INSERT INTO accounts(id, name, account_type) VALUES
    ('00000000-0000-0000-0000-000000000001', 'Demo funding contra account', 'SYSTEM_CONTRA');

CREATE TABLE transfers (
    id UUID PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL UNIQUE CHECK (idempotency_key ~ '^[!-~]{1,128}$'),
    operation VARCHAR(10) NOT NULL CHECK (operation IN ('TRANSFER', 'FUNDING')),
    source_account_id UUID NOT NULL REFERENCES accounts(id),
    target_account_id UUID NOT NULL REFERENCES accounts(id),
    amount_cents BIGINT NOT NULL CHECK (amount_cents > 0),
    currency VARCHAR(3) NOT NULL DEFAULT 'EUR' CHECK (currency = 'EUR'),
    status VARCHAR(12) NOT NULL CHECK (status IN ('RECEIVED', 'COMPLETED', 'REJECTED')),
    failure_reason VARCHAR(40),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (source_account_id <> target_account_id),
    CHECK ((status = 'REJECTED' AND failure_reason IS NOT NULL AND failure_reason IN ('INSUFFICIENT_FUNDS', 'BALANCE_LIMIT'))
        OR (status <> 'REJECTED' AND failure_reason IS NULL))
);
CREATE INDEX transfers_source_created ON transfers(source_account_id, created_at DESC);
CREATE INDEX transfers_target_created ON transfers(target_account_id, created_at DESC);

CREATE TABLE ledger_entries (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id UUID NOT NULL REFERENCES transfers(id),
    account_id UUID NOT NULL REFERENCES accounts(id),
    signed_cents BIGINT NOT NULL CHECK (signed_cents <> 0),
    currency VARCHAR(3) NOT NULL DEFAULT 'EUR' CHECK (currency = 'EUR'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (transfer_id, account_id)
);
CREATE INDEX ledger_account_history ON ledger_entries(account_id, id DESC);

CREATE TABLE audit_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id UUID NOT NULL REFERENCES transfers(id),
    event_type VARCHAR(30) NOT NULL CHECK (event_type IN ('RECEIVED', 'COMPLETED', 'REJECTED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (transfer_id, event_type)
);

CREATE FUNCTION forbid_accounting_mutation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Accounting history is append-only' USING ERRCODE = '23514';
END $$;
CREATE TRIGGER ledger_immutable BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_accounting_mutation();
CREATE TRIGGER ledger_no_truncate BEFORE TRUNCATE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_accounting_mutation();
CREATE TRIGGER audit_immutable BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION forbid_accounting_mutation();
CREATE TRIGGER audit_no_truncate BEFORE TRUNCATE ON audit_events
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_accounting_mutation();
CREATE TRIGGER transfers_no_delete BEFORE DELETE ON transfers
    FOR EACH ROW EXECUTE FUNCTION forbid_accounting_mutation();
CREATE TRIGGER transfers_no_truncate BEFORE TRUNCATE ON transfers
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_accounting_mutation();

CREATE FUNCTION guard_transfer_transition() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id, NEW.idempotency_key, NEW.operation, NEW.source_account_id, NEW.target_account_id,
           NEW.amount_cents, NEW.currency, NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.id, OLD.idempotency_key, OLD.operation, OLD.source_account_id, OLD.target_account_id,
           OLD.amount_cents, OLD.currency, OLD.created_at) THEN
        RAISE EXCEPTION 'Transfer request is immutable' USING ERRCODE = '23514';
    END IF;
    IF OLD.status <> 'RECEIVED' OR NEW.status NOT IN ('COMPLETED', 'REJECTED') THEN
        RAISE EXCEPTION 'Illegal transfer status transition' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER transfers_lifecycle BEFORE UPDATE ON transfers
    FOR EACH ROW EXECUTE FUNCTION guard_transfer_transition();

CREATE FUNCTION guard_account_identity() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id, NEW.account_type, NEW.currency) IS DISTINCT FROM ROW(OLD.id, OLD.account_type, OLD.currency) THEN
        RAISE EXCEPTION 'Account identity, type and currency are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER accounts_identity BEFORE UPDATE ON accounts
    FOR EACH ROW EXECUTE FUNCTION guard_account_identity();

-- Deferred checks inspect FINAL transaction state, after both entries and both balances are written.
CREATE FUNCTION assert_transfer_accounting() RETURNS TRIGGER LANGUAGE plpgsql AS $$
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
        RAISE EXCEPTION 'Rejected transfer must not have ledger entries' USING ERRCODE = '23514';
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
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER transfers_accounting AFTER INSERT OR UPDATE ON transfers
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION assert_transfer_accounting();
CREATE CONSTRAINT TRIGGER ledger_accounting AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION assert_transfer_accounting();
CREATE CONSTRAINT TRIGGER audit_accounting AFTER INSERT ON audit_events
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION assert_transfer_accounting();

CREATE FUNCTION assert_account_projection() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE aid UUID; b BIGINT; total NUMERIC;
BEGIN
    IF TG_TABLE_NAME = 'accounts' THEN aid := NEW.id; ELSE aid := NEW.account_id; END IF;
    SELECT balance_cents INTO STRICT b FROM accounts WHERE id = aid;
    SELECT COALESCE(sum(signed_cents), 0) INTO total FROM ledger_entries WHERE account_id = aid;
    IF b::NUMERIC <> total THEN
        RAISE EXCEPTION 'Account balance must equal its ledger projection' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER accounts_projection AFTER INSERT OR UPDATE ON accounts
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION assert_account_projection();
CREATE CONSTRAINT TRIGGER ledger_projection AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION assert_account_projection();
