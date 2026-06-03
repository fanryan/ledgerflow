CREATE TABLE reconciliation_reports (
    id UUID PRIMARY KEY,
    report_type TEXT NOT NULL,
    status TEXT NOT NULL,
    checked_transactions BIGINT NOT NULL,
    imbalance_count BIGINT NOT NULL,
    details JSONB NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT chk_reconciliation_reports_status
        CHECK (status IN ('PASSED', 'FAILED'))
);

CREATE INDEX idx_reconciliation_reports_completed_at
ON reconciliation_reports(completed_at DESC);

CREATE INDEX idx_reconciliation_reports_type_completed_at
ON reconciliation_reports(report_type, completed_at DESC);