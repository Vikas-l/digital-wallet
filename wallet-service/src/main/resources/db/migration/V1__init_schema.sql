CREATE TABLE users (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    full_name     VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    CONSTRAINT uk_users_email UNIQUE (email)
);

CREATE TABLE wallets (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT        NOT NULL,
    balance    DECIMAL(19,2) NOT NULL,
    currency   VARCHAR(3)    NOT NULL,
    version    BIGINT        NOT NULL,
    created_at DATETIME(6)   NOT NULL,
    updated_at DATETIME(6)   NOT NULL,
    CONSTRAINT uk_wallets_user UNIQUE (user_id),
    CONSTRAINT fk_wallets_user FOREIGN KEY (user_id) REFERENCES users (id),
    -- last line of defence: the database itself refuses a negative balance
    CONSTRAINT chk_wallets_balance_non_negative CHECK (balance >= 0)
);

CREATE TABLE transactions (
    id                VARCHAR(36)   PRIMARY KEY,
    type              VARCHAR(20)   NOT NULL,
    initiator_user_id BIGINT        NOT NULL,
    source_wallet_id  BIGINT        NULL,
    target_wallet_id  BIGINT        NOT NULL,
    amount            DECIMAL(19,2) NOT NULL,
    currency          VARCHAR(3)    NOT NULL,
    description       VARCHAR(255)  NULL,
    idempotency_key   VARCHAR(64)   NULL,
    request_hash      VARCHAR(64)   NULL,
    created_at        DATETIME(6)   NOT NULL,
    CONSTRAINT uk_transactions_idempotency UNIQUE (initiator_user_id, idempotency_key),
    CONSTRAINT fk_transactions_source FOREIGN KEY (source_wallet_id) REFERENCES wallets (id),
    CONSTRAINT fk_transactions_target FOREIGN KEY (target_wallet_id) REFERENCES wallets (id),
    CONSTRAINT chk_transactions_amount_positive CHECK (amount > 0)
);

CREATE TABLE ledger_entries (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    transaction_id VARCHAR(36)   NOT NULL,
    wallet_id      BIGINT        NOT NULL,
    direction      VARCHAR(6)    NOT NULL,
    amount         DECIMAL(19,2) NOT NULL,
    balance_after  DECIMAL(19,2) NOT NULL,
    created_at     DATETIME(6)   NOT NULL,
    CONSTRAINT fk_ledger_transaction FOREIGN KEY (transaction_id) REFERENCES transactions (id),
    CONSTRAINT fk_ledger_wallet FOREIGN KEY (wallet_id) REFERENCES wallets (id),
    INDEX idx_ledger_wallet_created (wallet_id, created_at)
);

CREATE TABLE outbox_events (
    id             VARCHAR(36)  PRIMARY KEY,
    aggregate_type VARCHAR(50)  NOT NULL,
    aggregate_id   VARCHAR(64)  NOT NULL,
    topic          VARCHAR(100) NOT NULL,
    event_key      VARCHAR(100) NOT NULL,
    payload        TEXT         NOT NULL,
    status         VARCHAR(10)  NOT NULL,
    attempts       INT          NOT NULL,
    last_error     VARCHAR(500) NULL,
    created_at     DATETIME(6)  NOT NULL,
    published_at   DATETIME(6)  NULL,
    INDEX idx_outbox_status_created (status, created_at)
);

CREATE TABLE processed_events (
    event_id     VARCHAR(36) PRIMARY KEY,
    processed_at DATETIME(6) NOT NULL
);
