# Phase 2 practical task (15 minutes)

In `TransferCommandTest`, add a test that accepts amount **1 cent** and preserves it unchanged.
Then explain what would happen if `AccountFunds.move()` debited a payer, but `LedgerService.post()`
failed while inserting the recipient's entry.

Trace the code path: controller → TransferService proxy → account/ledger/audit services → commit.
Identify why one `@Transactional` boundary rolls ALL these changes back, and why the MANDATORY
annotation prevents an isolated ledger write outside a payment transaction.

Run `mvn test`. Your explanation should mention the difference between a persisted business rejection
and an unexpected transaction failure. Neither is allowed to leave half a completed payment.

# Phase 3 practical task (15 minutes)

Add a unit test to FraudEngineTest proving that a new recipient alone produces score 20 and APPROVE
with default settings. Then change the configured new-recipient contribution to 45 in the test and
verify REVIEW. Use a separate configuration object; do not modify application.yaml.

Explain why a REVIEW response must not call AccountFunds.move(), and why repeating its idempotency
key must return the stored decision instead of running the rules again. Run mvn test.

# Phase 4 practical task (15 minutes)

In web/test/money.test.ts add a test that `centsFromEuros('123.45')` gives `12345n` and formats back
as `€123.45`. Then identify why JSON.parse of Long.MAX_VALUE cents would lose information and why a
client retry after an unknown response must keep BOTH the original key and the original request body.
Run `npm --prefix web test`. Explain the boundary between display state and database money guarantees.
