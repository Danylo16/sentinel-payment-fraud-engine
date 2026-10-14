# ADR 003 — Transactional rule-based fraud assessment

Status: implemented; execution evidence is maintained in docs/verification.md.

## Decision

Four small FraudRule implementations operate on an immutable FraudContext. They return only triggered
RuleHit values (ID, integer risk contribution, reason and observed evidence). FraudEngine sums with a
100 cap, sorts IDs for deterministic output and applies configured review/decline thresholds.
FraudService owns SQL observations and persistence; it participates in the payment transaction.
No external service calls, ML, Redis or process-local counters.

## Concurrency and time

Acquire source and recipient locks in UUID order using FOR NO KEY UPDATE, then query history at
READ COMMITTED. All normal transfers follow this protocol, including ones that end in REVIEW or
DECLINE and make no balance update. A second request waits and its later history queries see the
previous request's committed decision. Counting before the lock would let a burst bypass velocity.

Use clock_timestamp() after lock acquisition, not transaction-start CURRENT_TIMESTAMP, for the
observation window. Prior attempts are fraud evaluations in the inclusive [now-window, now] interval;
current attempt is not inserted yet. Replays never evaluate again. Funding is excluded. Approved
attempts rejected for insufficient funds still count, as do reviewed and declined attempts. This is
risk policy rather than request rate limiting: invalid requests and rollbacks are not counted.

New recipient means no completed historical transfer to this recipient (all-time). Typical activity
is the arithmetic average of completed outbound transfers in the previous 30 days, with at least five
samples. NUMERIC/BigDecimal avoids rounding and overflow. Rules-v1 is the code version; each decision
stores thresholds and each triggered rule records its contribution and effective observation settings.
This is decision evidence, not a full reproducibility snapshot of every non-triggered rule.

## Lifecycle and database guarantees

RECEIVED transitions once to COMPLETED, REJECTED, REVIEW or DECLINED. REVIEW has no reserve and no
approval route yet; this deliberately prevents execution without an authenticated review workflow.
A future approval feature requires a new migration, authorization, another locked balance check and
an additional immutable audit event. DECLINED is permanent. Idempotent replay returns all outcomes.

APPROVE permits accounting, not a guarantee of sufficient funds. V3 deferred triggers require a
matching fraud decision for new customer transfers: APPROVE for COMPLETED/REJECTED, REVIEW for
REVIEW, DECLINE for DECLINED. Only COMPLETED permits two balanced ledger entries. Fraud rows are
append-only, require bounded risk/thresholds, consistent classification and a score matching the JSON
rule contributions. The application still owns rule evaluation: these checks do not independently
reconstruct business rules from historical observations or prevent an administrator forging facts.

## Compatibility and HTTP

V3 preserves existing transfers and balances without inventing historical risk decisions. A BEFORE
INSERT trigger makes every new TRANSFER require fraud; callers cannot turn this flag off. Legacy
requests replay unchanged. Funding bypasses fraud and keeps the protected demo profile/token.

New REVIEW returns 202; new DECLINED returns 201 because an immutable declined transfer resource was
created. Inspect status rather than interpreting 201 as settlement. Both replay with 200. GET fraud
returns 404 for funding/legacy requests. REJECTED balance failures keep their stable 409 error.

## Limits

Weights are illustrative and uncalibrated, not probabilities. First-transfer and frequency signals
can produce false positives. No identity/device/IP features, manual review, trained models, policy
version registry, real payment integrations or production authorization. History queries can become
expensive with large accounts; measure before choosing counters or denormalized aggregates.
