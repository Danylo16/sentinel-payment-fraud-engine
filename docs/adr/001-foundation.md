# ADR 001: Foundation

Status: accepted for Phase 1.

- Java 21 and Maven: known target runtime, conventional lifecycle, separate unit/integration stages.
- Boot 4.1.1: stable release verified against Maven Central metadata and official release announcement.
  JUnit 5.13.4 explicitly overrides Boot's JUnit 6 BOM to keep the tests on JUnit 5.
  Spring 7's SpringExtension requires JUnit 6 and cannot be used with this override. Integration
  tests therefore start the real SpringApplication in plain JUnit 5 lifecycle methods, use HTTP
  against a random port and close the context afterward. No spring-boot-starter-test dependency
  or incompatible SpringExtension is included. This keeps the application current and JUnit 5
  usable without downgrading Spring or pretending incompatible dependencies work.
- Modular monolith: one transactional database, no messaging coordination or distributed consistency.
- No JPA: jOOQ keeps SQL visible for the eventual locking and financial invariants.
- Hand-declared typed jOOQ fields: one small table needs no code-generation database yet. SQL drift is
  caught by integration tests. Reconsider generation when ledger/payments add significant schema.
- Zero-balance accounts: financing requires ledger, therefore deferred to Phase 2.
- BIGINT cents: no floating-point amounts. Phase 2 must also prevent Java/SQL integer overflow.
- Separate Failsafe integration stage: `test` is fast; `verify` is the mandatory quality gate.
- Real PostgreSQL tests: no H2 approximation; missing Docker is a failure, not a skipped success.
- No Maven wrapper binary copied from an unverified source: Maven is explicitly required and installed by CI/build image.

Tradeoffs: more explicit SQL field declarations; Maven must be installed for non-container development;
API has no authentication and only targets a local simulation. No production security claim is made.
