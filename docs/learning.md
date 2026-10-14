# Phase 2 practical task (15 minutes)

In `TransferCommandTest`, add a test that accepts amount **1 cent** and preserves it unchanged.
Then explain what would happen if `AccountFunds.move()` debited a payer, but `LedgerService.post()`
failed while inserting the recipient's entry.

Trace the code path: controller → TransferService proxy → account/ledger/audit services → commit.
Identify why one `@Transactional` boundary rolls ALL these changes back, and why the MANDATORY
annotation prevents an isolated ledger write outside a payment transaction.

Run `mvn test`. Your explanation should mention the difference between a persisted business rejection
and an unexpected transaction failure. Neither is allowed to leave half a completed payment.
