-- Outbox retries with exponential backoff: a failed event waits until next_attempt_at,
-- and is marked FAILED (and alerted on) after the maximum number of attempts.
ALTER TABLE outbox_events
    ADD COLUMN next_attempt_at DATETIME(6) NULL;