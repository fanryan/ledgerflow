CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type TEXT NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type TEXT NOT NULL,
    payload JSONB NOT NULL,
    status TEXT NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    claimed_by TEXT,
    locked_until TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_outbox_events_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED')),

    CONSTRAINT chk_outbox_events_attempts
        CHECK (attempts >= 0)
);

CREATE INDEX idx_outbox_events_publishable
ON outbox_events(status, next_attempt_at, created_at)
WHERE status IN ('PENDING', 'FAILED');

CREATE INDEX idx_outbox_events_stale_claims
ON outbox_events(locked_until)
WHERE status = 'PROCESSING';

CREATE INDEX idx_outbox_events_aggregate
ON outbox_events(aggregate_type, aggregate_id);