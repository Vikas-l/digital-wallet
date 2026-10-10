-- Data consistency checks after a load test. Every "problems" value must be 0.

SELECT 'Wallets with a negative balance' AS check_name,
       COUNT(*) AS problems
FROM wallet_db.wallets WHERE balance < 0

UNION ALL
SELECT 'Wallets whose ledger does not add up to the balance',
       COUNT(*)
FROM wallet_db.wallets w
LEFT JOIN (SELECT wallet_id, SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END) AS net
           FROM wallet_db.ledger_entries GROUP BY wallet_id) l ON l.wallet_id = w.id
WHERE w.balance <> COALESCE(l.net, 0)

UNION ALL
SELECT 'Transfers without exactly one debit and one credit',
       COUNT(*)
FROM (SELECT t.id FROM wallet_db.transactions t
      JOIN wallet_db.ledger_entries e ON e.transaction_id = t.id
      WHERE t.type = 'TRANSFER'
      GROUP BY t.id
      HAVING SUM(e.direction = 'DEBIT') <> 1 OR SUM(e.direction = 'CREDIT') <> 1) x

UNION ALL
SELECT 'Money created or lost, in paise (all balances vs top-ups + cashback)',
       ABS(ROUND(((SELECT SUM(balance) FROM wallet_db.wallets)
                - (SELECT COALESCE(SUM(amount), 0) FROM wallet_db.transactions WHERE type IN ('TOP_UP', 'REWARD'))) * 100))

UNION ALL
SELECT 'Outbox events not yet published (wallet-service)',
       COUNT(*) FROM wallet_db.outbox_events WHERE status <> 'PUBLISHED'

UNION ALL
SELECT 'Outbox events not yet published (rewards-service)',
       COUNT(*) FROM rewards_db.outbox_events WHERE status <> 'PUBLISHED'

UNION ALL
SELECT 'Transaction events not processed exactly once by rewards-service',
       ABS((SELECT COUNT(*) FROM wallet_db.outbox_events WHERE topic = 'digitalwallet.transaction-completed')
         - (SELECT COUNT(*) FROM rewards_db.processed_events))

UNION ALL
SELECT 'Rewards granted vs cashback credits in wallets (count difference)',
       ABS((SELECT COUNT(*) FROM rewards_db.rewards)
         - (SELECT COUNT(*) FROM wallet_db.transactions WHERE type = 'REWARD'))

UNION ALL
SELECT 'Rewards granted vs cashback credited, in paise',
       ABS(ROUND(((SELECT COALESCE(SUM(reward_amount), 0) FROM rewards_db.rewards)
                - (SELECT COALESCE(SUM(amount), 0) FROM wallet_db.transactions WHERE type = 'REWARD')) * 100))

UNION ALL
SELECT 'Users over a daily cashback limit',
       COUNT(*)
FROM (SELECT r.user_id, r.rule_code, DATE(CONVERT_TZ(r.created_at, '+00:00', '+05:30')) AS day,
             rr.daily_limit_per_user AS daily_limit, COUNT(*) AS n
      FROM rewards_db.rewards r
      JOIN rewards_db.reward_rules rr ON rr.code = r.rule_code
      WHERE rr.daily_limit_per_user IS NOT NULL
      GROUP BY r.user_id, r.rule_code, day, rr.daily_limit_per_user
      HAVING n > daily_limit) x

UNION ALL
SELECT 'Transactions with a duplicate idempotency key',
       COUNT(*)
FROM (SELECT initiator_user_id, idempotency_key FROM wallet_db.transactions
      WHERE idempotency_key IS NOT NULL
      GROUP BY initiator_user_id, idempotency_key HAVING COUNT(*) > 1) x;

-- Volume of data the checks ran over
SELECT (SELECT COUNT(*) FROM wallet_db.users) AS users,
       (SELECT COUNT(*) FROM wallet_db.transactions WHERE type = 'TRANSFER') AS transfers,
       (SELECT COUNT(*) FROM wallet_db.ledger_entries) AS ledger_entries,
       (SELECT COUNT(*) FROM rewards_db.rewards) AS rewards,
       (SELECT SUM(balance) FROM wallet_db.wallets) AS total_money;
