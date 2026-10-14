# Phase 1 practical task

Read `AccountName`, `AccountService`, `AccountRepository` and `V1__create_accounts.sql`.

**Task (10–15 minutes):** add a test rejecting a 101-code-point name made entirely of emoji,
and an integration test accepting and round-tripping exactly 100 emoji. Explain why `String.length()`
would reject a valid account name but PostgreSQL `char_length()` would accept it.

Run `mvn test` and `mvn verify`. Do not change the 100-character policy to make a failing test pass.

Interview checkpoint: explain why HTTP validation alone cannot guarantee valid database state;
identify the role of SQL CHECK constraints and why there is no balance-writing endpoint yet.
