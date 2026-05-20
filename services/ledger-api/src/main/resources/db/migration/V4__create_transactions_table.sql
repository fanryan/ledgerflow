CREATE TABLE transactions (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id),
    owner_user_id UUID NOT NULL REFERENCES users(id),
    idempotency_key VARCHAR(100) NOT NULL,
    type VARCHAR(20) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    description VARCHAR(255),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT transactions_type_valid CHECK (type IN ('DEPOSIT', 'WITHDRAWAL')),
    CONSTRAINT transactions_status_valid CHECK (status IN ('PENDING', 'POSTED', 'FAILED')),
    CONSTRAINT transactions_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT transactions_currency_uppercase CHECK (currency = upper(currency)),
    CONSTRAINT transactions_currency_length CHECK (char_length(currency) = 3),
    CONSTRAINT transactions_idempotency_unique_per_owner UNIQUE (owner_user_id, idempotency_key)
);

CREATE INDEX idx_transactions_account_id ON transactions(account_id);
CREATE INDEX idx_transactions_owner_created_at ON transactions(owner_user_id, created_at DESC);