CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'EUR',
    balance_cents BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT accounts_valid_name CHECK (char_length(name) BETWEEN 1 AND 100 AND name ~ '[^[:space:]]'),
    CONSTRAINT accounts_eur_only CHECK (currency = 'EUR'),
    CONSTRAINT accounts_nonnegative_balance CHECK (balance_cents >= 0)
);
-- No seed balance, funding endpoint, or public balance update exists in Foundation.
