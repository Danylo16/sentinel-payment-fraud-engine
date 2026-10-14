# ADR 004 — Demo console and reproducible verification

React + TypeScript + Vite provides one static operations page. Next.js would add a server without an
SSR, SEO or routing requirement. Java remains the only business backend; Nginx serves the UI and
proxies /api. No CORS allowance, Redux, chart package or component framework is necessary. CSS charts
show actual counts in the loaded sample. Two cursor endpoints expose persisted data rather than
keeping a fake transaction feed in the browser; V4 adds matching indexes.

Java long/BIGINT money exceeds JavaScript's exact number range. Use BigInt for input, arithmetic and
formatting, and lossless-json for the numeric JSON boundary. API money fields remain JSON integer
numbers for compatibility. Scores are bounded and may be converted to number for graphical display.
Tests explicitly cover Long.MAX_VALUE cents, one cent, decimal fractions and overflow rejection.

A client timeout does not tell a user whether a payment committed. Store the exact request and key
in sessionStorage before POST, freeze that intent, offer Retry same request, and require an explicit
New request to create another key. This convenience does not replace database-backed idempotency.
Demo tokens are never stored, embedded in a build or logged. Browser data is local simulation data.

Playwright exercises the actual Compose deployment (Nginx, Java, PostgreSQL), including API-backed
account creation, protected funding, transfer replay, REVIEW with no spend, browser reload and a
mobile overflow check. No API mocks are used for correctness claims. GitHub Actions retains traces,
screenshots and test reports. Local scratch has no Docker daemon; CI supplies the deployment tests.

A Python standard-library driver measures fixed-concurrency closed-loop HTTP requests to localhost.
It refuses external hosts, records real latencies/statuses, and fails on an unexpected result.
Post-run SQL checks money conservation, account projections and balanced paired entries. These small
experiments demonstrate a measurement workflow, not production throughput or fraud effectiveness.
