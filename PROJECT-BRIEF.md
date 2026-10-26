# FlowGuard: Problem, Solution, and Product Positioning

## Executive summary

FlowGuard is a small Java 17 application for analyzing operational event data exported as CSV. It identifies events whose measured value exceeds a supplied threshold, rejects malformed or duplicate records, and produces downloadable reports.

Its strongest fit is a low-cost, easy-to-run batch-processing demonstration or prototype. It is not a replacement for a production observability platform or a durable real-time streaming system. Those products provide broader integrations, persistent storage, alert delivery, access control, and operational support that FlowGuard does not currently provide.

## Problem statement 

Teams often need to inspect operational records from services or business processes—for example, checkout errors, payment failures, latency, delayed shipments, and notification queue depth. When this data is exported to a file, a person may have to inspect it manually or write a one-off script to:

- Identify measurements that exceed their acceptable limits.
- Spot repeated event IDs.
- Find malformed records without losing the rest of the file.
- Group and share the important results.

Manual inspection is slow and inconsistent as files grow. A quick script may be difficult to reuse, tune, or understand, and may stop entirely when it encounters a bad row. For a small offline workflow, a focused tool can make these checks repeatable without requiring a large platform deployment.

## Proposed solution

FlowGuard processes a CSV file containing an event ID, timestamp, source, metric, value, and threshold. Its current behavior is:

1. Read records from the input file one line at a time.
2. Reject repeated non-empty event IDs within that run.
3. Validate and parse each unique record.
4. Mark a valid event as an alert when `value > threshold`.
5. Process work with a configurable worker count and bounded queue.
6. Write alerts to `alerts.csv`, rejected rows and reasons to `dead-letter.csv`, and print a run summary.

The same processor is available through a simple local web page: a user can paste or upload CSV and download the resulting reports. The web demo accepts requests up to 2 MB and limits concurrent analysis jobs.

### Example

For the included sample, FlowGuard reads eight data rows: six valid unique events, four alerts, one duplicate, and one invalid timestamp. This illustrates that a bad or repeated record is reported separately rather than preventing the other rows from being analyzed.

## Why FlowGuard is a strong choice for its intended use

“Best” depends on the requirements. For a small, offline CSV analysis task, FlowGuard has useful practical advantages:

- **Low setup cost:** Java 17 is the only runtime requirement; there is no database, broker, framework, or third-party library to configure.
- **Accessible outputs:** the reports are ordinary CSV files that can be inspected, archived, or opened in spreadsheet software.
- **Visible failure handling:** duplicate and invalid rows are recorded with a reason instead of being silently ignored.
- **Bounded queued work:** the worker pool uses a bounded queue and backpressure, avoiding an unbounded backlog of pending tasks.
- **Two ways to use it:** a command-line runner suits repeatable local jobs; a small browser interface suits a manual demonstration.
- **Understandable implementation:** the focused Java code and dependency-free tests are suitable for learning, demonstrations, and experimentation.

These are fit-for-purpose strengths, not evidence that FlowGuard is faster, more reliable, or more capable than established commercial platforms. A meaningful performance comparison would require representative datasets, repeatable benchmark methodology, and like-for-like configurations.

## Comparison with common alternatives

The table compares solution categories, not specific vendor products or benchmark results.

| Option | Where it is usually stronger | FlowGuard's relative advantage | Trade-off |
|---|---|---|---|
| Manual spreadsheet review | Ad hoc exploration and visual analysis | Repeatable threshold checks, duplicate detection, and a separate rejection report | FlowGuard is less flexible for free-form exploration |
| One-off script | Fast, highly customized automation | A reusable CLI and web demo, explicit output files, worker controls, and included tests | A short script may be simpler for a one-time, very small file |
| Observability / monitoring platform | Live telemetry integrations, dashboards, alert routing, retention, access controls, and operational support | Easier to run locally for a small CSV task, with no platform account or service configuration | FlowGuard does not provide those production platform capabilities |
| Stream-processing framework | Continuous event ingestion, distributed processing, scalable state, and richer processing pipelines | Much smaller operational footprint for finite local files | Frameworks are a better fit for continuous or high-volume workloads |
| Custom batch data pipeline | Integration with a team's storage, schedulers, databases, and governance | Ready-to-run sample workflow with a concise set of outputs | A tailored pipeline can address production-specific requirements FlowGuard does not handle |

FlowGuard is most compelling when the input is a finite local file, the rule is a straightforward per-row threshold, and simplicity matters more than integrations or long-term data management. If the need is continuous monitoring, on-call alert delivery, durable deduplication, large-scale processing, or compliance controls, choose or build a production platform designed for those needs.

## Current limitations

The current application should be treated as a learning project or batch prototype, not as a production service:

- The parser supports plain comma-separated fields, not full quoted CSV syntax.
- Event IDs, alerts, and rejected records are retained in memory; total memory use therefore grows with the data.
- Duplicate detection is limited to one run and is not persisted.
- The app reads local or uploaded CSV; it does not ingest live events or send alerts to external systems.
- Thresholds are provided on each input row; there is no central threshold management.
- The rules are stateless per event; there are no rolling windows, baselines, or trend analysis.
- The web interface has no authentication and is intended only for a safe local demo. Do not submit confidential, personal, payment-card, or production data.
- The local benchmark is an experiment, not a production capacity or performance guarantee.

## Recommended improvement roadmap

Priorities depend on the intended audience. For a more robust prototype, the following order reduces correctness and operational risks before adding broader features.

### 1. Improve input correctness

- Use a standards-compliant CSV parser that handles quoting, escaped quotes, and commas inside fields.
- Add tests for malformed quoting, empty fields, line endings, encoding, and large individual records.
- Make the accepted timestamp formats and validation rules explicit and consistent.

### 2. Bound memory and support larger files

- Write alerts and rejected records incrementally instead of retaining every result in memory.
- Define and test a memory/resource policy for duplicate tracking; consider a persistent key store when cross-run deduplication is required.
- Add configurable limits and clear error reporting for oversized inputs and outputs.

### 3. Make rules manageable

- Support centrally configured thresholds, with validation and documented precedence if row-level thresholds remain available.
- Add optional rule types only when there is a clear use case, such as ranges or rolling windows.
- Keep rule behavior covered by tests and explain each alert with its matched rule.

### 4. Secure and operate the web interface

- Add authentication and authorization before exposing the web app beyond a trusted local environment.
- Add request-rate controls, safe upload handling, and explicit retention policies.
- Add structured logs, health/readiness checks, and operational metrics.
- Use a production-grade web framework and deployment configuration if this becomes a maintained service.

### 5. Connect to real workflows

- Add integrations only for confirmed requirements, such as object storage input, a scheduler, a message broker, or notification destinations.
- Define delivery guarantees, retries, idempotency, and failure recovery before connecting to live sources.
- Compare the additional operational cost against an existing observability or streaming platform before building overlapping capabilities.

### 6. Measure performance responsibly

- Benchmark representative datasets at several sizes and worker counts.
- Run repeated trials and report medians, hardware, Java version, and configuration.
- Track memory use and error rates as well as throughput; do not optimize based only on a tiny sample.

## Success criteria for a next version

A future release should be considered successful when it:

- Correctly parses the documented CSV format, including quoted fields.
- Produces complete alert and rejection reports without requiring all outputs to remain in memory.
- Has tested, documented behavior for duplicate IDs and threshold rules.
- Protects uploaded data and access if the web application is deployed for multiple users.
- Has repeatable performance and resource measurements on representative workloads.
- Clearly documents whether it is a local utility, an internal service, or a production system—and implements the controls required for that chosen role.

## Conclusion

FlowGuard solves a narrow, useful problem: turning an operational CSV export into threshold alerts and a transparent report of rejected records. Its simplicity, low setup cost, and inspectable outputs make it a strong choice for local batch analysis, education, and prototyping. It is not categorically better than market platforms; it is a better fit when those platforms would be unnecessary overhead and the workflow does not require their production capabilities. The next step should be to choose the target use case, then prioritize CSV correctness, bounded memory, and security before adding live integrations or expanding the feature set.
