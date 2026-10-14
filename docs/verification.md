# Executed verification — Phase 2

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
