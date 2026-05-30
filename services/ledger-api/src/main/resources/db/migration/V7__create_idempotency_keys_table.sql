CREATE TABLE idempotency_keys (
    key VARCHAR(100) PRIMARY KEY,
    owner_user_id UUID NOT NULL REFERENCES users(id),
    request_hash CHAR(64) NOT NULL,
    transaction_id UUID REFERENCES transactions(id),
    response_status VARCHAR(20) NOT NULL,
    response_body JSONB NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_idempotency_keys_owner_user_id ON idempotency_keys(owner_user_id);
CREATE INDEX idx_idempotency_keys_expires_at ON idempotency_keys(expires_at);
