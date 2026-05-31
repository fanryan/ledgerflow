ALTER TABLE transactions
ADD COLUMN reversal_of_transaction_id UUID REFERENCES transactions(id),
ADD COLUMN reversed_at TIMESTAMPTZ;

CREATE INDEX idx_transactions_reversal_of_transaction_id
ON transactions(reversal_of_transaction_id);