CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL REFERENCES users(id),
    currency CHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    balance_minor BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT accounts_currency_uppercase CHECK (currency = upper(currency)),
    CONSTRAINT accounts_currency_length CHECK (char_length(currency) = 3),
    CONSTRAINT accounts_status_valid CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    CONSTRAINT accounts_balance_non_negative CHECK (balance_minor >= 0)
);

CREATE INDEX idx_accounts_owner_user_id ON accounts(owner_user_id);