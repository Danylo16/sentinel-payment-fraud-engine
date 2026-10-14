# Portfolio description and interview preparation

## GitHub technical description

Sentinel is an educational EUR payment and fraud-detection simulator built as a Java 21 modular
monolith with Spring Boot, PostgreSQL, jOOQ and Flyway. It implements atomic account transfers,
double-entry movement accounting, persistent request idempotency, ordered row locks, rule-based
fraud scoring and immutable ledger/decision/status history. A React/TypeScript console displays real
balances and decisions and preserves large integer cents through the JSON boundary. JUnit 5,
PostgreSQL Testcontainers, Playwright, Docker Compose and GitHub Actions verify the system. It does
not connect to real banks or process real money.

## Three CV bullets

- Built a Java 21/Spring Boot payment simulator with PostgreSQL transactions, double-entry accounting,
  persistent idempotency and ordered row locking to prevent concurrent overspending.
- Implemented four configurable fraud rules with bounded risk scoring and immutable decision audit;
  REVIEW and DECLINE outcomes preserve funds and cannot execute through request replay.
- Verified financial invariants, concurrent requests and injected failures using JUnit 5/Testcontainers
  and CI; added a React/TypeScript console with exact monetary parsing and Playwright API-backed tests.

## Five technical interview questions

1. Two requests each want to spend 80 EUR from a 100 EUR account. Where does the lock occur, what does
   the second request read, and why do Java checks alone not guarantee correctness?
2. The response is lost after commit. How does a retry with the same key behave, and what happens when
   another concurrent request claimed that key with different parameters?
3. Why do transfers lock with FOR NO KEY UPDATE rather than FOR UPDATE after inserting FK references?
   Which deadlock can occur with incompatible lock conversion, and how are lock failures retried safely?
4. How do deferred constraints distinguish an unfinished write sequence from a broken committed
   ledger, and what happens when writing the second entry or fraud decision fails?
5. Why must velocity history be read after source locking? How are funding, rejected attempts,
   REVIEW, replay and observation time treated, and why is this score not a fraud probability?

## Honest limits and next improvements

The simulation has no production customer auth/reviewer permissions or custody security, no real
settlement/KYC/AML, no FX/fees/reversals/reservations, and no availability/backup guarantees. REVIEW
has no approval endpoint. Fraud weights are uncalibrated, use only payment history and can misclassify.
Measurements are small closed-loop experiments, not capacity or business effectiveness claims.

Next: authenticated ownership and reviewer roles, settlement/reversal semantics within a simulation,
reconciliation tooling, calibrated synthetic fraud cases, larger repeated/open-loop load with database
profiling, retention/idempotency expiry design and stricter runtime/database privileges. Redis has no
justified role yet. Do not list unfinished future features as implemented on a CV.
