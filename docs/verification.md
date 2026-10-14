# Executed verification — Foundation, payments, fraud and demo console

Starting Foundation commit: `79a8d06`. Phase 2 payment commit: `dcebaa28d5a103d790665d4b2f2e4c1ce6304829`.

GitHub Actions [run 38630008919](https://github.com/Danylo16/sentinel-payment-fraud-engine/actions/runs/38630008919)
executed `mvn verify` on Java 21 with real PostgreSQL 17.6 Testcontainers and finished successfully:

| Suite | Passed | Failures / errors / skips |
| --- | --- | --- |
| AccountNameTest | 6 | 0 / 0 / 0 |
| TransferCommandTest | 5 | 0 / 0 / 0 |
| AccountsIT | 14 | 0 / 0 / 0 |
| PaymentsIT | 18 | 0 / 0 / 0 |
| BalanceLimitsIT | 1 | 0 / 0 / 0 |
| Total | 44 | 0 / 0 / 0 |

The suite verifies concurrency, idempotency, rollback fault injection, SQL constraints/immutability,
lock timeout, stable rejected replays and BIGINT bounds. Three independent PostgreSQL containers
were started, and Flyway successfully applied V1 and V2. CI retains Surefire/Failsafe reports.

The local workspace has no Docker daemon; local compilation and 11 unit tests also passed.
Final executable-code commit: `96d68ef37126c454a0af6fb7beeb43ffe192e778`.
[CI run 38630582461](https://github.com/Danylo16/sentinel-payment-fraud-engine/actions/runs/38630582461)
repeated all 44 tests successfully and also passed the Docker Compose payment smoke check.
The smoke built the real application image, started the Compose database/application, applied V1/V2,
used the protected demo funding endpoint, transferred 100 cents, replayed the same key and verified
balances 900/100, total balance zero and exactly two zero-sum entries for the transfer.
Its volume was isolated and removed after the check. No performance/load tests have been run.

Older files under docs/test-reports belong to historical Foundation debugging and are superseded
by the executed CI evidence above. They do not describe the current Phase 2 result.

## Phase 3 — executed fraud verification

Executable commit: `eee97382c8fcbf7548f5036e094f89ce8483560d`.
[GitHub Actions run 38637803402](https://github.com/Danylo16/sentinel-payment-fraud-engine/actions/runs/38637803402)
completed successfully on 2026-10-14. The test step intentionally inherited SPRING_PROFILES_ACTIVE=demo;
individual test applications explicitly chose their own profile.

| Suite | Passed | Failures / errors / skips |
| --- | --- | --- |
| AccountNameTest | 6 | 0 / 0 / 0 |
| TransferCommandTest (including the user's one-cent test) | 6 | 0 / 0 / 0 |
| FraudEngineTest | 9 | 0 / 0 / 0 |
| AccountsIT | 14 | 0 / 0 / 0 |
| PaymentsIT | 18 | 0 / 0 / 0 |
| BalanceLimitsIT | 1 | 0 / 0 / 0 |
| FraudIT | 15 | 0 / 0 / 0 |
| FraudMigrationIT | 1 | 0 / 0 / 0 |
| Total | 70 | 0 / 0 / 0 |

21 unit and 49 integration cases passed. PostgreSQL containers exercised V1/V2/V3, fraud SQL
constraints, concurrent velocity decisions, duplicate REVIEW requests, immutable decisions, score
forgery rejection, decision-write rollback and safe retry, expired observations, and database time
captured after an actually blocked payment acquired its account lock. The upgrade test populated V2
with ledger-backed funding, then migrated to V3 and verified preserved history/balances plus a new
assessed transfer. Phase 2 accounting/concurrency tests remained passing; their velocity threshold
is explicitly raised to isolate accounting stress from the separate default-policy fraud tests.

The Compose smoke built the real image and checked HTTP APPROVE (20), REVIEW (55), DECLINE (100),
review replay, balances unchanged by unexecuted decisions, zero ledger entries for REVIEW/DECLINED,
and global conservation. The isolated test volume was removed after completion.

The local workspace compiled every test class and ran all 21 unit cases successfully. It has no
Docker daemon, so integration and Compose results above come from actual GitHub Actions execution.
Earlier failed CI runs exposed missing explicit TIMESTAMPTZ/JSONB parameter casts; those bindings were
fixed before the successful run. No load/performance measurements have been made. Phase 4 remains.

## Phase 4 — actual UI, build and load verification

[CI run 38643977680](https://github.com/Danylo16/sentinel-payment-fraud-engine/actions/runs/38643977680)
successfully tested executable head `519967b31768da13e6a3cd541f67b8f12824482f`:

- 22 Java unit + 50 PostgreSQL integration tests = 72, with zero failures/errors/skips.
- Three browser-money tests passed, including Long.MAX_VALUE lossless JSON round-trip.
- TypeScript typecheck and Vite production bundle passed.
- Two Playwright cases passed against the actual Compose Nginx/Java/PostgreSQL deployment: account
  creation, token-protected funding, transfer, replay, ledger display, mobile overflow, REVIEW,
  unchanged funds and session-preserved replay after browser reload. No API mocks were used.
- Docker Compose built Java and UI images and applied V1–V4 successfully.
- Two 500-request, eight-worker closed-loop scenarios completed every request as 201/COMPLETED.
  Post-load global conservation, account projections and paired balanced entries passed.

See docs/performance/README.md, raw results.json and EXPLAIN plans for measured conditions and limits.
Artifacts include reports, screenshot and benchmark evidence. Local compilation, 22 Java unit tests,
three UI tests and TypeScript/Vite build also passed; actual deployment tests ran in CI because the
scratch workspace has no Docker daemon. Earlier browser failures exposed ambiguous select labels;
explicit accessible names fixed them before the successful run.

This is a demonstrable local simulation, not a production financial service. Demo readiness still
requires the developer to understand and explain the locking, accounting and replay decisions.

Final executable console head `49ece07a1b02cb96396fcbb9df6050b6720247f3` also passed the full workflow in
[run 38644637428](https://github.com/Danylo16/sentinel-payment-fraud-engine/actions/runs/38644637428):
72 Java tests, three money tests, two browser tests, Compose and all 1000 measured transfers plus
post-load invariants. Desktop/mobile screenshots were visually inspected; source images are in
docs/images. The repeated measurement is preserved as docs/performance/results-repeat.json.
Subsequent screenshot/evidence documentation does not alter the tested executable behavior.
