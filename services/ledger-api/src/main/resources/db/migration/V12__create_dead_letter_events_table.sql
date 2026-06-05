CREATE TABLE dead_letter_events (
    id UUID PRIMARY KEY,
    source_topic VARCHAR(120) NOT NULL,
    event_key VARCHAR(255),
    payload JSONB NOT NULL,
    error_message TEXT NOT NULL,
    status VARCHAR(40) NOT NULL,
    attempts BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    replayed_at TIMESTAMPTZ
);

CREATE INDEX idx_dead_letter_events_status_created_at
ON dead_letter_events(status, created_at);

CREATE INDEX idx_dead_letter_events_source_topic
ON dead_letter_events(source_topic);