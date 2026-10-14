# Final engineering review

## Reviewed behavior

- Payment request, fraud decision, guarded balances, paired ledger and outcome audit share one
  READ COMMITTED transaction. Existing business rejections remain stable; unexpected failures roll back.
- Account locks are ordered UUID FOR NO KEY UPDATE, compatible with FK KEY SHARE. Fraud observations
  happen after those locks; observation time uses database clock_timestamp, not transaction start.
- Unique persistent keys handle concurrent duplicates; request changes conflict. REVIEW/DECLINED
  cannot execute by replay or terminal status mutation. Funding is demo-profile/token protected.
- Deferred PostgreSQL checks require matching balanced entries and balance projections; history is
  append-only. A database administrator/owner can bypass triggers, so this is not tamper-proof custody.
- V1–V3 checksums/history stay unchanged in Phase 4. V4 adds only cursor indexes. Account monitoring
  excludes SYSTEM_CONTRA; aggregate customer funds are explicitly not global money conservation.
- Numeric API values are exact long cents. Browser conversion uses BigInt and lossless JSON, never
  Number for money. Retry intent survives browser reload; its token does not enter browser storage.
- Charts state their sample boundaries. No fake location, customer identity, latency, daily totals,
  fraud effectiveness or production capacity is displayed. Optional refresh is honest 5-second polling.
- HTTP outcomes and GET fraud/list contracts are documented in OpenAPI/README. A created DECLINED
  resource returns 201, so clients inspect status rather than assuming settlement from the HTTP code.

## Fixes made during review

Removed external font loading, preserved retry fields across reload, retained funding request keys,
and kept a same-origin proxy without automatic POST retries. Added API pagination/cursor checks,
large-integer tests, browser scenarios and post-load financial reconciliation. Query parameters remain
bound values; tokens and payloads are not emitted in application logs.

## Remaining limitations

No user authentication/authorization, authenticated reviewer workflow, rate limiting, production
roles, secrets manager, KYC/AML, real settlements, fees, FX, reservations, reversals, backups/PITR,
HA, disaster recovery or monitoring alerts. The app database role owns development tables/triggers.
Fraud weights are illustrative and uncalibrated; history averages can have false positives and drift.
All completed transfers/history are retained; data retention, key expiration and reconciliation jobs
need design. Cursor views are request snapshots; separate refreshes can legitimately show changing
balances. UI is a local demo, not an internet-facing banking dashboard.

Database projection checks rescan ledger history, and fraud rules query growing histories while
holding account locks. Improve only from measured plans and cardinalities; weakening accounting
checks to increase a benchmark number would be a correctness regression. See performance evidence.
