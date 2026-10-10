CREATE TABLE reward_rules (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    code                 VARCHAR(50)   NOT NULL,
    name                 VARCHAR(100)  NOT NULL,
    description          VARCHAR(500)  NULL,
    transaction_type     VARCHAR(20)   NOT NULL,
    min_amount           DECIMAL(19,2) NOT NULL,
    cashback_percent     DECIMAL(5,2)  NOT NULL,
    max_reward           DECIMAL(19,2) NOT NULL,
    daily_limit_per_user INT           NULL,
    priority             INT           NOT NULL,
    active               BOOLEAN       NOT NULL,
    valid_from           DATETIME(6)   NULL,
    valid_to             DATETIME(6)   NULL,
    version              BIGINT        NOT NULL,
    created_at           DATETIME(6)   NOT NULL,
    updated_at           DATETIME(6)   NOT NULL,
    CONSTRAINT uk_reward_rules_code UNIQUE (code),
    INDEX idx_reward_rules_active_type (active, transaction_type)
);

CREATE TABLE rewards (
    id                    VARCHAR(36)   PRIMARY KEY,
    user_id               BIGINT        NOT NULL,
    source_transaction_id VARCHAR(36)   NOT NULL,
    rule_code             VARCHAR(50)   NOT NULL,
    transaction_amount    DECIMAL(19,2) NOT NULL,
    reward_amount         DECIMAL(19,2) NOT NULL,
    created_at            DATETIME(6)   NOT NULL,
    -- at most one reward per wallet transaction, even if the event is redelivered
    CONSTRAINT uk_rewards_source_transaction UNIQUE (source_transaction_id),
    INDEX idx_rewards_user_rule_created (user_id, rule_code, created_at),
    INDEX idx_rewards_user_created (user_id, created_at)
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
