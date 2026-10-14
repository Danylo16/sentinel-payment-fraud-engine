# Sentinel architecture

## Implemented boundaries

| Module | Owns | Called by |
| --- | --- | --- |
| accounts | Identity/API, guarded balances, ordered account locks | payments and HTTP |
| payments | Request claim, idempotency, lifecycle and transaction orchestration | HTTP |
| ledger | Paired immutable entries and paginated history | payments and accounts HTTP |
| audit | Append-only status events | payments and transfer HTTP |
| fraud | Not implemented yet | Planned: payments |

One PostgreSQL database, one application. All payment mutations participate in the same transaction.
`AccountFunds`, ledger writes and audit writes require an existing transaction (MANDATORY).
Services own business use cases; controllers own HTTP status/headers and input validation.

## Transfer sequence

1. Validate source/target IDs, positive long cents and key format.
2. Replay a known immutable result if present. For a new request, ensure both accounts exist and claim
   the unique key using INSERT ON CONFLICT DO NOTHING. A concurrent loser reads the winning result.
3. Write RECEIVED audit event. Lock both accounts in ascending PostgreSQL UUID order with
   FOR NO KEY UPDATE (compatible with FK KEY SHARE locks).
4. Recheck account types, available funds and numeric limits under locks.
5. REJECTED: persist reason and audit, no monetary entries. COMPLETED: guarded balances, negative
   payer entry, positive payee entry, status and audit, all in the same transaction.
6. Deferred database checks validate the final committed state. Only then can the service return.

No fraud statuses exist yet. Phase 3 must add REVIEW/DECLINE through a new migration and prohibit
ledger entries for those outcomes. Ordinary key replay must never complete a pending REVIEW.

## Data ownership and constraints

Hand-declared jOOQ fields in accounts and explicit bound SQL in payments keep locking visible.
Flyway remains schema authority. Financial guarantees are SQL constraints/triggers plus service
coordination, not HTTP checks alone. Ledger amounts use signed BIGINT; aggregate reconciliation uses
NUMERIC to avoid SUM overflow. System counter-account is included in money conservation.

See ADR 001 for Foundation and ADR 002 for decisions, tradeoffs, deadlocks and funding protection.
