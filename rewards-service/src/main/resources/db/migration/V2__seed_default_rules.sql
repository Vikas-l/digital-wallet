INSERT INTO reward_rules (code, name, description, transaction_type, min_amount, cashback_percent, max_reward,
                          daily_limit_per_user, priority, active, valid_from, valid_to, version, created_at, updated_at)
VALUES ('TRANSFER_CASHBACK_2', '2% cashback on transfers',
        '2% cashback on transfers of 500 or more, capped at 100, up to 3 times per day',
        'TRANSFER', 500.00, 2.00, 100.00, 3, 100, TRUE, NULL, NULL, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
       ('TOPUP_BONUS_1', '1% top-up bonus',
        '1% bonus on top-ups of 1000 or more, capped at 50, once per day',
        'TOP_UP', 1000.00, 1.00, 50.00, 1, 50, TRUE, NULL, NULL, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));
