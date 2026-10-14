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
A separate Docker Compose smoke step is now configured and awaiting execution; its result will be
recorded after that run. It checks image build, startup, demo profile, funding, transfer/replay and
SQL conservation on an isolated ephemeral volume. No performance/load tests have been run.

Older files under docs/test-reports belong to historical Foundation debugging and are superseded
by the executed CI evidence above. They do not describe the current Phase 2 result.
