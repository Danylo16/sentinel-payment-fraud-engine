# ADR 002: Atomic payments and accounting

Status: implemented; execution evidence is recorded in docs/verification.md.

## Database transaction boundary

A transfer, its two balances, paired ledger entries and two status audit events share one Spring
transaction at PostgreSQL READ COMMITTED. No Java mutex or in-memory idempotency registry exists.
`AccountFunds`, ledger writes and audit writes use MANDATORY propagation so callers cannot accidentally
run individual money-changing operations without an enclosing transaction.

The request is claimed using a unique global idempotency key and `INSERT ... ON CONFLICT DO NOTHING`.
An insert blocked on a competing transaction resumes after its commit/rollback; a separate SELECT
under READ COMMITTED then sees the committed result. Immutable persisted parameters are compared
without a hash. Identical replays return the original result; changed operation/source/target/amount
return 409. Keys apply across ordinary transfers and demo funding. Global scope is a deliberate local
simulation limitation; an authenticated application would scope keys to a principal/tenant.

Rejected payments commit their request and audit result. Raising an insufficient-funds exception out
of the transaction would roll back the idempotency record and permit a later replay to become a new
payment, so insufficient funds is instead persisted as REJECTED.

## Locking and deadlocks

Accounts are locked in PostgreSQL UUID ascending order using FOR NO KEY UPDATE. This protects balance
writes just as required but is compatible with the KEY SHARE locks acquired by foreign key checks.
Using FOR UPDATE after inserting a transfer can create lock-conversion deadlocks even with ordered
account IDs. No account key/type/currency is updated during a transfer.

The common A-to-B/B-to-A deadlock is avoided. Future database changes can introduce other deadlocks;
PostgreSQL remains the final detector. SQLSTATE 40P01/40001/55P03/57014 produces a rollback and HTTP 503,
with Retry-After: 1 and an instruction to retry the SAME key. Lock timeout is 3 seconds; transaction
timeout is 10 seconds. No unbounded automatic retry is hidden in the service.

## Double-entry simulation

Each completed movement has one negative source entry and one positive destination entry. This is
an immutable balanced movement ledger, not a complete general-purpose chart of accounting accounts.
Deferred constraint triggers enforce exactly two entries, matching accounts and amount, sum zero,
valid operation/account types, and the received/terminal audit pair at transaction commit. Additional
deferred triggers enforce account balance == sum(ledger entries), even when application checks are
bypassed. SUM(BIGINT) uses PostgreSQL NUMERIC, avoiding overflow when reconciling many entries.

Ledger and audit reject UPDATE, DELETE and TRUNCATE. Transfer request parameters cannot change and
terminal statuses cannot transition. The database owner/superuser can still disable triggers or
alter schema. Compose uses a development owner role; this is not production-grade tamper resistance.

## Funding and numeric bounds

The SYSTEM_CONTRA account is created at zero by migration; only it may be negative. Demo funding is
an ordinary paired movement from this counter-account to a customer. Thus total balances including
the counter-account stay zero; customers are never funded by modifying balances directly.

The HTTP funding controller exists only in the demo profile and requires a configured >=16-character
token. Startup fails if demo is enabled without a token. Compose binds to loopback. This is protection
for a local simulation, not authentication for a financial product.

Positive amounts can use the full BIGINT range. Customer debit checks, recipient MAX_VALUE bounds,
and SYSTEM_CONTRA MIN_VALUE bounds precede guarded SQL updates. Faults after either balance update or
first ledger write roll back the entire transaction. Extreme-value tests use a separate database so
they do not exhaust the counter-account of other tests.

## Phase 3 boundary

There is no fraud engine or REVIEW/DECLINE in Phase 2. A later migration must extend the legal status
set and enforce no ledger entries while pending/rejected. Retrying an existing key never re-executes
its original persisted business result.
