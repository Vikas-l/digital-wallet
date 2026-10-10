# Performance and load test report

**Date:** 10 Oct 2026 · **Tool:** k6 1.8.1 · **Script:** [`load-test/transfers.js`](../load-test/transfers.js) · **Runner:** [`scripts/load-test.ps1`](../scripts/load-test.ps1)

## What was tested

Real-user behaviour, not a synthetic benchmark:

- **200 users** registered, each topped up with ₹5,000.
- Each virtual user **pauses 0.5–1.5 s** between actions, like a person using an app.
- **Action mix:** 60% transfers, 30% balance and statement reads, 10% top-ups.
- **Hot spots:** 15% of transfers go to 3 popular "merchant" accounts, so their wallet rows are locked by many payments at once.
- **Real client behaviour:**
  - 3% of transfers are **double-clicks** (the same request twice at the same moment).
  - 10% of successful transfers are **retried after a "timeout"** with the same `Idempotency-Key`.
  - On `503 busy` the client waits for `Retry-After` and retries with the **same key** (up to 3 tries).
- **Staircase:** 10 → 25 → 50 → 100 → 200 concurrent users, 45 s per level, in an isolated copy of the system.

## Environment

A developer laptop, with Docker Desktop limited to **2 CPU cores and 3 GB RAM**. Those 2 cores are shared by **everything**: MySQL, Kafka, Redis, Mailpit, both services and the k6 load generator itself. So the absolute numbers are modest; the point is how the system **behaves** as load grows.

## Results

| Concurrent users | Requests/s | Transfers/s | Transfer p50 | Transfer p95 | Transfer p99 | Read p95 | Busy retries | Gave up | Errors | Double charges | Peak CPU wallet-service | Peak CPU MySQL |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 10 | 9.6 | 2.3 | 118 ms | 1,221 ms* | 1,833 ms* | 344 ms | 0 | 0 | 0 | 0 | 137% | 18% |
| 25 | 22.7 | 7.5 | 135 ms | 835 ms | 1,250 ms | 225 ms | 0 | 0 | 0 | 0 | 148% | 24% |
| **50** | **33.2** | **12.7** | **396 ms** | **1,689 ms** | 4,586 ms | 629 ms | **0** | **0** | **0** | **0** | 166% | 41% |
| 100 | 21.0 | 6.3 | 2,831 ms | 8,759 ms | 11,726 ms | 3,560 ms | 147 | 2 | 0 | 0 | 168% | 60% |
| 200 | 36.8 | 11.9 | 3,154 ms | 12,524 ms | 14,064 ms | 10,086 ms | 243 | 1 | 0 | 0 | 150% | 37% |

\* The first level includes JVM warm-up (the JIT compiler is still optimising hot code); the median is 118 ms.
CPU: 100% = one full core; the maximum available is 200%.

**Data consistency after all levels** (3,120 transfers, 9,251 ledger entries, 1,517 cashback rewards):

| Check | Problems |
|---|---|
| Wallets with a negative balance | 0 |
| Wallets whose ledger does not add up to the balance | 0 |
| Transfers without exactly one debit and one credit | 0 |
| Money created or lost (all balances vs top-ups + cashback) | 0 paise |
| Outbox events not published (both services) | 0 |
| Transaction events not processed exactly once by rewards-service | 0 |
| Rewards granted vs cashback credited (count and amount) | 0 |
| Users over a daily cashback limit | 0 |
| Duplicate idempotency keys | 0 |

An accidental extra run with **1,050 concurrent users** (a typo in the level list) also finished with **0 errors, 0 double charges and 0 consistency problems**. Responses were around 20 s, and 12 of about 1,400 requests gave up after 3 "busy" retries.

## What the numbers mean

1. **Comfortable capacity on this laptop: about 50 concurrent active users.** That's ~33 requests/s, a median transfer of 0.4 s, no retries. (50 *active* users, each acting every 1–2 s, is the activity of a much larger number of people simply having the app open.)
2. **The bottleneck is CPU, specifically wallet-service**, which peaks at about 165% of the 200% available. MySQL stays at 40–60%. Throughput stops growing after ~50 users and latency rises instead: the classic saturation curve.
3. **Under overload the system degrades safely.** Requests that can't get a database connection within 3 s, or wait more than 5 s for a locked wallet row, get **`503 SERVICE_BUSY` + `Retry-After: 1`**. Nothing was committed, so the client retries with the same idempotency key and is never charged twice.
4. **Correctness never depends on load.** Row locks taken in a fixed order, the ledger, idempotency keys, the outbox and idempotent consumers kept every check at 0, from 10 to 1,050 users.

## A problem the load test found, and the fix

The first run (100 users with no pauses) returned **HTTP 500 errors** for some transfers.

- **Diagnosis:** the service logs showed `HikariPool ... SQLTransientConnectionException`, meaning **connection pool exhaustion**. The pool had 10 connections. Payments queuing for a hot merchant's row lock each **held a connection while waiting**, so the pool emptied and new requests timed out after 5 s.
- **Fix:**
  - Overload is now answered with **503 + Retry-After** instead of 500, and safe retries rely on idempotency keys.
  - **Lock waits are capped** at 5 s (`innodb_lock_wait_timeout`, set per connection).
  - Connection waits are capped at 3 s (fail fast instead of piling up).
  - The pool grew from 10 to 20 connections.
- **Result:** 0 errors at every level since.

## How it would scale in production

- **More instances of each service.** They're stateless; shared state lives in MySQL and Redis, and the outbox relay uses `FOR UPDATE SKIP LOCKED`, so instances don't step on each other. Kafka consumers scale up to the number of partitions (3 now, easy to raise).
- **More CPU per instance.** wallet-service is CPU-bound here. Java virtual threads would also help, since they let a thread waiting on I/O not tie up an OS thread.
- **Hot merchants:** a very popular merchant's single wallet row serialises its payments. Real systems split such a balance into several sub-wallets (sharded counters) or credit merchants asynchronously.
- **Database:** read replicas for statements and balance reads; partition or shard wallets by user id at a much larger scale.

## Run it yourself

```powershell
# from the project root (Windows PowerShell, Docker running)
powershell -ExecutionPolicy Bypass -File scripts\load-test.ps1                      # full staircase, ~8 minutes
powershell -ExecutionPolicy Bypass -File scripts\load-test.ps1 -Levels "10 50" -Duration 30s   # quick run
```

The runner pauses your normal stack (keeping its data), starts an isolated copy, runs k6 at each level while sampling CPU, waits until Kafka has processed every event, runs [`load-test/verify-consistency.sql`](../load-test/verify-consistency.sql), writes `load-test/results/report.md`, deletes only the isolated copy, and restarts your stack.
