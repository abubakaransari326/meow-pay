CREATE TABLE cats (
    id UUID PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE transfers (
    id UUID PRIMARY KEY,
    sender_id UUID NOT NULL REFERENCES cats (id),
    recipient_id UUID NOT NULL REFERENCES cats (id),
    amount INTEGER NOT NULL CHECK (amount > 0),
    status VARCHAR(20) NOT NULL CHECK (status IN ('COMPLETED', 'REJECTED')),
    idempotency_key VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (sender_id, idempotency_key)
);

CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY,
    cat_id UUID NOT NULL REFERENCES cats (id),
    amount BIGINT NOT NULL CHECK (amount <> 0),
    type VARCHAR(32) NOT NULL CHECK (type IN ('SIGNUP_BONUS', 'TRANSFER_DEBIT', 'TRANSFER_CREDIT')),
    transfer_id UUID REFERENCES transfers (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (
        (type = 'SIGNUP_BONUS' AND transfer_id IS NULL)
        OR (type IN ('TRANSFER_DEBIT', 'TRANSFER_CREDIT') AND transfer_id IS NOT NULL)
    ),
    CHECK (
        (type = 'TRANSFER_DEBIT' AND amount < 0)
        OR (type IN ('SIGNUP_BONUS', 'TRANSFER_CREDIT') AND amount > 0)
    )
);

CREATE INDEX idx_ledger_entries_cat_id ON ledger_entries (cat_id);
CREATE INDEX idx_transfers_recipient_id ON transfers (recipient_id);
CREATE UNIQUE INDEX idx_ledger_entries_one_type_per_transfer
    ON ledger_entries (transfer_id, type)
    WHERE transfer_id IS NOT NULL;
