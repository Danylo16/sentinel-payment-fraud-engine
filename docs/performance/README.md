# Reproducible local measurement

CI runs `scripts/load-test.py` against its isolated Compose deployment, after the HTTP/browser smoke.
The standard-library driver creates ledger-backed demo funding and warmed recipient relations.
Default fraud policy remains active. 500 new one-cent transfers are sent with eight fixed workers in
each of two sequential scenarios: one shared source, then eight independent sources. Setup is excluded
from timing. Every key is unique; automatic retries are disabled. Unexpected HTTP/status outcomes fail
verification instead of being counted as successful throughput.

Reports contain elapsed time, HTTP/status counts, throughput, p50/p95/p99/mean HTTP latency and runner
context. The driver is closed-loop: workers wait for each response. This is not an open-loop saturation
study, and throughput is not maximum capacity. There is no warmup exclusion after fixture setup.
Fixtures/history cardinalities differ, scenarios share the same database, and GitHub runner resources
are not controlled. Do not compare these numbers to production payments or claim an optimization win.

After load, SQL verifies global conservation (including contra), every account projection, and two
zero-sum entries per ledger-backed transfer. Representative EXPLAIN ANALYZE BUFFERS plans for source
ledger projection and velocity reads are retained with results under target/performance in the CI
artifact. They are post-run read plans, not a complete profile of transfer writes/deferred triggers.

To reproduce manually, start Compose under the demo profile, set DEMO_FUNDING_TOKEN to the configured
token, and run (Python 3.11+):

```sh
python scripts/load-test.py --requests 500 --workers 8
```

This driver creates additional test accounts/payments in the target database. Prefer an isolated
Compose project for measurements. It refuses non-local hosts. CI uses its own project/volume and
removes that isolated data after verification. The normal local application data is preserved.

Executed numbers will be recorded with their commit/run IDs after CI completes; never invent a
latency target or fill a chart with placeholders. See docs/verification.md for executed proof.
