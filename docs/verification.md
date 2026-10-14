# Executed verification — 2026-10-14

This is an execution record, not a claim that Phase 1 has passed its gate.

| Check | Result | Evidence / limitation |
| --- | --- | --- |
| Java 21 compiler / Maven Enforcer | PASS | JDK 21.0.12.1, Maven 3.9.11 |
| `mvn test` | PASS | 5 unit tests; zero failures, errors or skips |
| `mvn clean package` | PASS | All sources and integration test sources compiled; 5 unit tests passed |
| Packaged JAR integrity | PASS | Zip CRC check; 71 nested dependencies |
| Packaged application HTTP smoke | PASS | Real process startup, OpenAPI 200, five expected 400/404 responses |
| PostgreSQL Testcontainers suite | BLOCKED / command FAILED | No valid Docker environment, no `/var/run/docker.sock` |
| `mvn verify` | FAILED | Integration container initialization failed before database cases ran |
| Docker Compose startup | NOT RUN | Docker unavailable |
| GitHub Actions execution | NOT RUN | Workflow prepared; no remote repository was created |

The HTTP smoke disabled Flyway and used no live database. It checked unknown route, invalid UUID,
malformed JSON, blank name and a forbidden initial balance property, plus generated OpenAPI paths.
It does not validate migrations, successful account creation, SQL constraints or persistence.

One earlier packaged JAR was found truncated during direct launch. A clean rebuild replaced it;
CRC validation and launching the rebuilt JAR then succeeded. The delivered source excludes build output.

The first integration attempt also exposed a Spring 7/JUnit 5 extension incompatibility. It was fixed
by removing Spring's JUnit extension and starting/stopping SpringApplication with plain JUnit 5
lifecycle methods. The subsequent run reported only the Docker initialization failure.

The integration suite has 13 authored scenarios. A Failsafe report counting one class initialization
error does **not** mean those 13 scenarios executed. They remain unverified.

Next gate: run `mvn verify` with Java 21 and a running Docker daemon. Then run `docker compose up --build`
and create/read an account over HTTP. Fix any discovered issues before Phase 2.

## GitHub status

Authenticated account confirmed as `Danylo16`. The available connector has no repository creation
operation. Looking up `Danylo16/sentinel-payment-fraud-engine` returned 404; that alone is not proof
that no private inaccessible repository exists. No other repositories were changed.

This archive includes a local Git repository and a Foundation commit. Commit metadata uses a local
automation identity rather than inventing the user's email address. It has no configured remote.

After creating an empty repository under your own account, from the extracted project directory:

```sh
git remote add origin https://github.com/Danylo16/sentinel-payment-fraud-engine.git
git push -u origin main
```

Create it without an initial README/gitignore to avoid unrelated merge history. If a repository already
exists, inspect it first; do not force push. GitHub authentication is handled by your credential manager.
