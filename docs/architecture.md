# Sentinel architecture

## Implemented boundaries

| Module | Owns | Called by |
| --- | --- | --- |
| accounts | Identity/API, guarded balances, ordered account locks | payments and HTTP |
| payments | Request claim, idempotency, lifecycle and transaction orchestration | HTTP |
| ledger | Paired immutable entries and paginated history | payments and accounts HTTP |
| audit | Append-only status events | payments and transfer HTTP |
| fraud | Independent rules, configuration, historical observations and immutable decisions | payments and transfer HTTP |

One PostgreSQL database, one application. All payment mutations participate in the same transaction.
`AccountFunds`, fraud assessment, ledger writes and audit writes require an existing transaction (MANDATORY).
Services own business use cases; controllers own HTTP status/headers and input validation.

## Transfer sequence

1. Validate source/target IDs, positive long cents and key format.
2. Replay a known immutable result if present. For a new request, ensure both accounts exist and claim
   the unique key using INSERT ON CONFLICT DO NOTHING. A concurrent loser reads the winning result.
3. Write RECEIVED audit event. Lock both accounts in ascending PostgreSQL UUID order with
   FOR NO KEY UPDATE (compatible with FK KEY SHARE locks).
4. Recheck account types, then evaluate fraud using a new READ COMMITTED snapshot after locks. Persist the decision, triggered rules and observed values. REVIEW/DECLINE commit only the request, decision and status audit; no money moves.
5. For APPROVE (or controlled funding), check available funds and numeric limits under locks.
6. REJECTED: persist reason and audit, no monetary entries. COMPLETED: guarded balances, negative
   payer entry, positive payee entry, status and audit, all in the same transaction.
7. Deferred database checks validate the final committed state. Only then can the service return.

V3 adds REVIEW and DECLINED. REVIEW remains pending without an approval route; key replay returns the original result and cannot execute it. Database constraints require a matching decision for every new customer transfer, and zero entries for unexecuted outcomes.

## Data ownership and constraints

Hand-declared jOOQ fields in accounts and explicit bound SQL in payments keep locking visible.
Flyway remains schema authority. Financial guarantees are SQL constraints/triggers plus service
coordination, not HTTP checks alone. Ledger amounts use signed BIGINT; aggregate reconciliation uses
NUMERIC to avoid SUM overflow. System counter-account is included in money conservation.

See ADR 001 for Foundation, ADR 002 for payments/deadlocks/funding, and ADR 003 for fraud semantics and concurrency.

## Browser and monitoring boundary

React in web/ uses TypeScript and Vite. Nginx serves static assets and proxies /api to Java; there is
no second business server and no permissive CORS configuration. All monetary authority remains in
PostgreSQL. GET accounts/transfers supports bounded newest-first cursors (created_at, UUID), with V4
indexes. UI sample counts are explicit; separate refresh requests are not a globally atomic snapshot.
Large integer JSON is parsed losslessly and formatted with BigInt. Draft retry intent lives in
sessionStorage, while the demo funding token stays only in memory. ADR 004 explains the choices.
