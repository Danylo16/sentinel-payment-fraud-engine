# Architecture and phase gates

## Current implementation

`accounts` owns its API, service and package-private repository. The service is the public module boundary.
`shared` owns HTTP errors, not business state. Spring constructor injection is used for real dependencies.
jOOQ typed fields are explicitly declared for the single table. All values are bound parameters.
Flyway is the source of truth for SQL schema. jOOQ code generation is deferred until schema complexity justifies it.

## Planned modules and tables — not implemented

| Module | Responsibility | Planned tables |
| --- | --- | --- |
| accounts | Account identity and transactional balance projection | accounts |
| payments | Idempotency, lifecycle and transfer orchestration | transfers |
| ledger | Balanced immutable accounting | ledger_entries |
| fraud | Rule evaluation and persisted decisions | fraud_decisions, fraud_rule_results |
| audit | Append-only status and decision history | audit_events |

Use one database transaction for payment decisions and monetary changes. No network calls inside it.
Each module owns its writes; payments orchestrates synchronous calls into ledger/accounts/fraud/audit.
Avoid public generic repositories and services that only relay method calls.

## Planned payment algorithm

1. Validate amount > 0, distinct accounts, EUR, key length and request structure.
2. Insert an idempotency record with unique key. Concurrent duplicate inserts synchronize through PostgreSQL;
   compare persisted request parameters before returning a result. Initially keys are globally scoped because
   this local demo has no authenticated principal. Document that limitation.
3. Lock both account rows using ascending UUID order with `FOR UPDATE`. Every money-moving use case,
   including demo funding and future manual review, must use the same order.
4. Evaluate fraud under the source account lock so simultaneous attempts cannot evade velocity counters.
   Specify which persisted attempts count, use database time, and avoid counting an idempotent replay.
5. APPROVE: guarded debit (`balance >= amount`), credit with overflow protection, paired ledger writes,
   status and audit in the same transaction. REVIEW: persist pending state and decision, with no debit.
   DECLINE: persist rejected state and decision, with no ledger.
6. Commit before reporting success. Database lock timeouts/deadlock failures roll back and return a retryable
   error; clients retry with the same key. Any server-side retry must encompass the entire transaction,
   be bounded and happen outside the transactional method.

Ordered locks remove the common A→B/B→A deadlock; they do not prove absence of all future deadlocks.
Insufficient funds is a persisted business rejection, not an uncaught exception that deletes its idempotency record.
An unexpected database failure rolls everything back and may leave no result; retry remains safe.

## Ledger and funding plan

Customer accounts cannot be negative. A separate explicit SYSTEM_CONTRA account may be negative, with a
constraint enforcing this exception only for its type. Starting sum is zero: demo funding credits a customer
and debits SYSTEM_CONTRA by the same amount. This represents external funding through an accounting
counterpart, not a money creation operation hidden in a customer balance update.

Funding will only be present in an explicitly enabled demo profile and protected by local-only operation.
An unlimited negative customer balance is never permitted. There is no funding in Phase 1.

Ledger must forbid UPDATE/DELETE at the database level. A deferred constraint trigger will check both
zero-sum and exactly two entries per completed transfer at commit. Tests must prove these constraints even
when Java services are bypassed. A database ownership role can still alter constraints, so immutability
claims are relative to the application role, not a malicious database administrator.

## Lifecycle plan

`RECEIVED` is internal to creation. Persisted terminal outcomes: `COMPLETED`, `DECLINED`, `REJECTED`
(for invalid business conditions such as insufficient balance); `REVIEW` is pending. There is no manual
review endpoint in the initial fraud MVP. REVIEW cannot be completed by an ordinary retry. If manual
approval is added, re-lock accounts and recheck funds/conditions in a fresh transaction; do not reuse an
old balance or treat REVIEW as pre-authorized payment.

## Gates

Phase 1: Java 21 build, unit tests, PostgreSQL migration/constraint/API tests pass.
Phase 2: ledger invariants, rollback fault injection, identical concurrent requests and overspending tests pass.
Phase 3: rule boundaries, scoring, serialized velocity behavior and REVIEW isolation tests pass.
Phase 4: Docker smoke run, remote CI, measured load and documentation reproduction succeed.

Do not claim the project ready for CV until all four gates are supported by executed checks.
