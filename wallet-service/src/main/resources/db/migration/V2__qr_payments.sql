-- A single-use QR code can be paid at most once: the database guarantees it,
-- even if the Redis claim is lost.
ALTER TABLE transactions
    ADD COLUMN single_use_qr_id VARCHAR(36) NULL,
    ADD CONSTRAINT uk_transactions_single_use_qr UNIQUE (single_use_qr_id);
