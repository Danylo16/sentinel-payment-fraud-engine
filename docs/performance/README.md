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

## Executed result — 2026-10-14 UTC

[CI run 38643977680](https://github.com/Danylo16/sentinel-payment-fraud-engine/actions/runs/38643977680)
tested head `519967b31768da13e6a3cd541f67b8f12824482f` as PR merge ref
`6b59cd3cf3c14d4b7c9604e2f5cdbabef3a69c99`. Host reported 4 CPUs, Python 3.12.3,
Linux Azure; PostgreSQL 17.6 ran in Compose. Raw results and plans are stored alongside this document.

| Scenario | Requests | Workers | Requests/s | p50 ms | p95 ms | p99 ms |
| --- | --- | --- | --- | --- | --- | --- |
| Shared source, one pair | 500 | 8 | 55.71 | 86.58 | 374.50 | 580.69 |
| Independent sources, eight pairs | 500 | 8 | 183.18 | 39.52 | 66.78 | 103.47 |

Every measured response was 201/COMPLETED. Post-run money conservation, account/ledger projections
and paired zero-sum entries all passed. Setup/warmup requests are outside these 1000 measurements.

## What the evidence supports

The shared-source scenario took longer. This is consistent with serialized account locks, but the
experiment does not isolate lock waiting from history cardinality, HTTP overhead or scheduler effects.
It is not proof of a particular optimization or a maximum throughput bound.

The source projection plan read 503 ledger entries through ledger_account_history and took 0.267 ms
in the post-run sample. It rescans history; repeated deferred projection checks will grow with account
history. The velocity plan processed 501 transfers and did 501 fraud-decision primary-key lookups,
with 1527 shared block hits and 0.927 ms execution time. Its estimate was only 3 joined rows. Fresh
benchmark tables had not had a controlled ANALYZE cycle; future comparisons must control statistics
and cardinalities. These tiny cached read plans are not the same as per-transfer end-to-end latency.

Next useful experiment: repeated runs with identical fixture histories, controlled ANALYZE, larger
history sizes, lock/wait profiling, and a separate open-loop arrival test. Preserve accounting checks
while investigating history aggregation or batched validation. No Redis/Kafka addition is justified
by this small experiment. See docs/verification.md for executed proof.

A second successful execution (run 38644637428, head 49ece07) measured 56.17 requests/s shared and
193.20 independent, with p95 319.71 ms and 62.42 ms respectively. Every request again completed and
all post-load invariants passed. Raw repeated observations are in results-repeat.json. Two small
runs are useful reproducibility evidence, not enough for statistical or maximum-capacity conclusions.
