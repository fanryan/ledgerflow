CREATE TABLE consumed_ledger_events (
    id UUID PRIMARY KEY,
    event_type TEXT NOT NULL,
    transaction_id UUID NOT NULL,
    payload JSONB NOT NULL,
    consumed_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT consumed_ledger_events_transaction_unique
        UNIQUE (transaction_id, event_type)
);

CREATE INDEX idx_consumed_ledger_events_consumed_at
ON consumed_ledger_events(consumed_at DESC);