# FlowGuard

FlowGuard is a **Java 17 event-processing project** with a command-line runner and an interactive website. It reads operational event records from CSV, checks each event against its threshold, and produces alerts and rejected-record reports.

The example data represents software and business operations: checkout error rate and latency, payment failures, and notification queue depth. Similar data could come from application logs, monitoring exports, payment systems, or order-processing services. FlowGuard does not connect to those systems itself; its input is a CSV export.

This project intentionally uses core Java rather than Spring or third-party libraries. Its web interface uses the JDK's built-in HTTP server. It demonstrates Java records, file I/O, collections, exception handling, executors, bounded queues, futures, concurrent maps, and atomic counters.

## Contents

- [Requirements](#requirements)
- [Run the project](#run-the-project)
- [Run the website locally](#run-the-website-locally)
- [Deploy the website free on Render](#deploy-the-website-free-on-render)
- [What happens during a run](#what-happens-during-a-run)
- [Input CSV format](#input-csv-format)
- [Understanding the output](#understanding-the-output)
- [How the code is organized](#how-the-code-is-organized)
- [System design and processing flow](#system-design-and-processing-flow)
- [Method-by-method responsibilities](#method-by-method-responsibilities)
- [Patterns and principles used](#patterns-and-principles-used)
- [Algorithm and complexity](#algorithm-and-complexity)
- [How multithreading works](#how-multithreading-works)
- [Design choices and tradeoffs](#design-choices-and-tradeoffs)
- [Tests](#tests)
- [Benchmark and performance metrics](#benchmark-and-performance-metrics)
- [Current limitations](#current-limitations)

## Requirements

- JDK 17 or later, including `java` and `javac` on `PATH`.
- Windows PowerShell for the included `.ps1` commands.
- No Maven, Gradle, database, external service, Spring, or extra dependencies are needed.

Check Java is available:

```powershell
java -version
javac -version
```

Open PowerShell in the project directory:

```powershell
cd "C:\Users\DELL\Desktop\Java Project"
```

## Run the project

Run the tests first:

```powershell
.\test.ps1
```

Run the sample:

```powershell
.\run.ps1
```

The run script compiles Java source into `build\classes`, processes `sample-events.csv`, and creates or replaces these files in `output`:

- `output\alerts.csv`
- `output\dead-letter.csv`

Set the number of worker threads and the bounded queue capacity:

```powershell
.\run.ps1 -InputFile .\sample-events.csv -OutputDirectory .\output -Workers 4 -QueueCapacity 128
```

The input and output paths can be changed. Paths supplied to `run.ps1` are resolved relative to the project directory.

You can also invoke the compiled Java program directly:

```powershell
.\build.ps1
java -cp .\build\classes com.flowguard.FlowGuard .\sample-events.csv .\output 4 128
```

The command-line argument order is:

```text
<input.csv> <output-directory> [workers] [queue-capacity]
```

The default worker count in `run.ps1` is the number of processors reported by Java/.NET on the machine; its default queue capacity is 64. When invoking the Java class directly, omitted values default to the Java runtime's available processor count and four times that count (at least 16), respectively.

## Run the website locally

Start the JDK HTTP server:

```powershell
.\run-web.ps1
```

Open [http://localhost:8080](http://localhost:8080). Choose a CSV file or paste CSV text into the form, then select **Analyze events**. The result page shows processing counts and throughput, with links to download alert and dead-letter CSV files.

The web app accepts URL-encoded CSV requests up to 2 MB and allows at most two simultaneous analysis jobs. Result downloads are held in memory and expire after 15 minutes (up to 20 recent results). Temporary uploaded files are removed after processing. Restarting the app clears the download results.

## Deploy the website free on Render

The repository includes a [Dockerfile](./Dockerfile) and [Render Blueprint](./render.yaml). To publish it:

1. Push the project to a GitHub repository.
2. Sign in to Render and choose **New → Blueprint**.
3. Connect the GitHub repository and confirm the `flowguard-demo` web service from `render.yaml`.
4. Wait for the Docker build and deployment, then open the Render URL.
5. Check that `<your-render-url>/health` returns `ok`.

Render supplies the `PORT` environment variable, which `WebServer` uses. The free web service can sleep when idle and take time to wake on the next visit. Check Render's current free-plan limits before deploying, since hosting terms may change.

**Public-demo safety:** this demo has no login or access control, so anyone with its URL can submit requests. Do not upload confidential, personal, payment-card, or production data. Uploaded CSV is processed by the app and its temporary file is deleted, but result data is temporarily held in application memory. Do not treat this learning project as a production service.

## What happens during a run

1. The program checks that the first input row is the exact expected header.
2. It reads the file one line at a time instead of loading all input rows at once.
3. It rejects a repeated non-empty event ID after its first appearance.
4. It submits each remaining row to the bounded worker pool.
5. Workers validate and parse rows concurrently. A valid event becomes an alert if `value` is greater than `threshold`.
6. The main thread collects worker results, writes alert and dead-letter CSV files, and prints a run summary.

For the included sample, the key counts are:

```text
Rows: 8 | accepted: 6 | alerts: 4 | duplicates: 1 | invalid: 1
```

That means eight data rows were read. Six unique rows were valid and accepted, four of those exceeded their threshold, one row repeated an event ID, and one row had an invalid timestamp. Duplicate rows are included in `dead-letter.csv`, but the `invalid` summary count specifically excludes duplicates.

## Input CSV format

The input must be UTF-8 CSV with this exact header:

```text
event_id,timestamp,source,metric,value,threshold
```

Each data row has six columns:

| Column | Meaning | Example |
|---|---|---|
| `event_id` | Unique ID for suppressing repeat deliveries | `evt-1001` |
| `timestamp` | Event time as ISO-8601 UTC | `2026-10-23T08:00:00Z` |
| `source` | Application or business component that emitted it | `checkout-api` |
| `metric` | Name of the measured value | `error_rate` |
| `value` | Numeric observation | `7.2` |
| `threshold` | Numeric alert threshold for this event | `5.0` |

Rules:

- The timestamp must parse as an ISO-8601 instant, such as `2026-10-23T08:00:00Z`.
- `event_id`, `timestamp`, `source`, and `metric` must not be blank.
- `value` and `threshold` must be finite numbers. `NaN` and infinity are rejected.
- The alert condition is strictly `value > threshold`; equality is not an alert.
- Input fields are plain, unquoted comma-separated values. Commas inside a field and quoted CSV fields are not supported by the input parser.
- The first occurrence of a non-empty event ID wins. A later row with that ID is recorded as a duplicate, even if its other columns are invalid.
- A blank event ID cannot be deduplicated, but the row is rejected by validation.

`sample-events.csv` includes normal rows, alerting rows, a duplicate, and an invalid timestamp so each outcome is visible in one run.

## Understanding the output

### Console summary

The console reports elapsed processing time, rows read, accepted rows, alerts, duplicates, invalid records, approximate accepted events per second, worker count, queue capacity, and alert totals grouped by source and metric.

Throughput is:

```text
accepted events / elapsed processing time in seconds
```

It is a local measurement for that run, not a guaranteed production rate.

### `alerts.csv`

Contains the original event fields for each valid event whose value exceeded its threshold. Alerts are sorted by event timestamp. The sample produces four alerts, including:

```csv
event_id,timestamp,source,metric,value,threshold
evt-1001,2026-10-23T08:00:00Z,checkout-api,error_rate,7.2,5.0
```

### `dead-letter.csv`

Contains the one-based source line number, rejection reason, and original row for duplicate or invalid records. The header line is line 1. CSV fields in the output are escaped when needed.

Example reasons include `duplicate event_id: evt-1001` and `timestamp must be ISO-8601 UTC`.

## How the code is organized

```text
.
├── src
│   ├── main
│   │   └── java/com/flowguard
│   │       ├── FlowGuard.java                 # CLI, output files, console summary
│   │       ├── WebServer.java                 # JDK HTTP server and browser interface
│   │       ├── engine
│   │       │   ├── ProcessingReport.java      # Immutable run result and throughput
│   │       │   └── SignalProcessor.java       # Streaming, concurrency, validation flow
│   │       ├── io
│   │       │   └── CsvCodec.java              # Input parsing and output field escaping
│   │       └── model
│   │           ├── ProcessOutcome.java        # Accepted or rejected row result
│   │           └── SignalEvent.java           # Immutable event record
│   └── test
│       └── java/com/flowguard
│           └── SignalProcessorTest.java       # Dependency-free executable tests
├── sample-events.csv
├── build.ps1
├── test.ps1
├── run.ps1
├── run-web.ps1
├── benchmark.ps1
├── Dockerfile
└── render.yaml
```

### Main Java concepts shown

- **Records:** `SignalEvent`, `ProcessOutcome`, and `ProcessingReport` are concise data carriers.
- **Encapsulation:** `FlowGuard` coordinates the CLI while parsing and processing live in separate classes.
- **Exceptions:** malformed rows are converted into rejection outcomes; file/header failures are reported as command errors.
- **Collections:** `List` stores report rows, `Set` tracks IDs already seen, and `Map` groups alert totals.
- **Concurrency:** executors and completion services process rows with a controlled number of worker threads.
- **Thread-safe state:** `ConcurrentHashMap` and `LongAdder` count alert groups across workers.
- **Resource handling:** try-with-resources closes readers and writers.
- **Immutability:** Java records and copied report lists provide stable results to the output stage.

## System design and processing flow

FlowGuard is a small **batch-processing system**, not a continuously running event service. Its main components are separated by responsibility:

```text
CSV file
   |
   v
FlowGuard CLI ---- validates arguments, creates output directory
   |
   v
SignalProcessor (reader / coordinator)
   |-- checks header and suppresses duplicate IDs
   |-- bounded task queue
   v
Fixed worker pool ---- parse + validate each row
   |                         |
   |                         +--> thread-safe alert-group counters
   v
CompletionService ---- collects completed outcomes
   |
   +--> accepted alert events --> alerts.csv
   +--> rejected rows ---------> dead-letter.csv
   +--> counts/timing ---------> console summary
```

The processing responsibilities are split into three logical stages:

1. **Ingest:** read one CSV line, track its line number, and reject duplicate IDs.
2. **Process:** worker tasks parse/validate a row and contribute to thread-safe alert-group counters.
3. **Collect and report:** the coordinator gathers task outcomes, builds a report, and the CLI writes the two output files.

The input file and output files are local to one run. The reader also acts as the producer; worker threads are consumers of the submitted row tasks. This makes the data flow easy to follow and keeps the example runnable without a broker or database.

## Method-by-method responsibilities

| Class / method | Responsibility |
|---|---|
| `FlowGuard.main` | Parses command-line arguments, creates the output directory, invokes processing, handles reported I/O/interruption errors, and coordinates output. |
| `FlowGuard.writeAlerts` | Writes accepted events that crossed their thresholds to `alerts.csv`. |
| `FlowGuard.writeDeadLetters` | Writes rejected rows and their reasons to `dead-letter.csv`. |
| `FlowGuard.printSummary` | Prints counts, elapsed time, throughput, worker/queue settings, and alert-group counts. |
| `SignalProcessor.process` | Validates the worker/queue settings and header, streams rows, suppresses duplicate IDs, submits bounded tasks, collects results, and returns a `ProcessingReport`. |
| `SignalProcessor.processLine` | Worker operation: parses one row, updates the concurrent alert-group count when it crosses the threshold, and returns an accepted or rejected outcome. |
| `SignalProcessor.takeCompleted` | Waits for the next completed worker result and turns an unexpected worker failure into an `IOException`. |
| `SignalProcessor.collect` | Adds a worker result to the alert or dead-letter collection and updates the accepted count. |
| `CsvCodec.eventIdIfPresent` | Reads the first CSV column for duplicate detection before full row parsing. |
| `CsvCodec.parseEvent` | Validates the six input columns and creates a `SignalEvent`. |
| `CsvCodec.escape` | Escapes commas, quotes, and line breaks when writing output CSV fields. |
| `ProcessingReport.eventsPerSecond` | Calculates accepted events divided by elapsed processing time. |
| `WebServer.main` / `WebServer.createServer` | Starts the JDK HTTP server and registers the home page, health check, and download routes. |
| `WebServer.home` | Serves the interactive form and processes submitted CSV with input-size and active-job limits. |
| `WebServer.download` | Serves generated alert or dead-letter CSV files while the in-memory result is available. |
| `ProcessOutcome.accepted` / `ProcessOutcome.rejected` | Creates the result representation for a successfully parsed row or a rejected row. |

The names above refer to methods in the source files; use the [source tree](#how-the-code-is-organized) to locate their classes.

## Patterns and principles used

These are patterns and design principles visible in the code. Some are formal design patterns; others are concurrency or architecture techniques.

| Pattern / principle | Where it appears | Why it is used |
|---|---|---|
| **Producer–consumer** | `SignalProcessor.process` submits rows; the executor workers process them | Separates file reading from parallel row processing. |
| **Bounded work queue / backpressure** | `ArrayBlockingQueue` and `CallerRunsPolicy` in `SignalProcessor.process` | Prevents task backlog from growing without bound; a full queue slows the producer by making it run work. |
| **Pipeline / staged processing** | Ingest → parse/process → collect/output | Makes each stage's responsibility clear and makes later replacement of file input/output easier. |
| **Task-based concurrency** | Each submitted lambda calls `processLine` for one row | Gives the executor independent work units without manually managing thread lifecycles. |
| **Completion-based result collection** | `ExecutorCompletionService` and `takeCompleted` | Allows collection in completion order instead of waiting for the oldest submitted task. |
| **Immutable value objects** | Java records: `SignalEvent`, `ProcessOutcome`, `ProcessingReport` | Makes data passed between stages concise and less prone to accidental mutation. |
| **Strategy via JDK rejection handler** | `CallerRunsPolicy` is supplied as the executor's rejection policy | Chooses how saturation is handled. This is a JDK-provided policy; the project does not define a custom Strategy class. |
| **Separation of concerns** | CLI, processor, CSV codec, and model types are separate | Keeps argument handling, business processing, parsing, and data representation from becoming one large class. |

There is no need to claim that FlowGuard implements every named pattern. For example, it does not implement a full Factory, Repository, Observer, or a custom Strategy hierarchy.

## Algorithm and complexity

The main algorithm makes one streaming pass over the input:

1. Read a row.
2. Check whether its event ID has already been seen.
3. If it is a duplicate, record the rejection. Otherwise, submit it for parsing and validation.
4. For a valid event, compare `value > threshold`; if true, add an alert and increment its source/metric count.
5. Collect completed work and write the resulting output files.

For `n` input rows, the expected processing work is **O(n)**. Hash-based duplicate lookup and insertion are expected **O(1)** per row, and the threshold comparison is **O(1)**. Sorting `a` alert events by timestamp adds **O(a log a)** time. The duplicate-ID set uses **O(u)** memory for `u` distinct non-empty IDs. Results and dead letters are also retained in memory until writing, so total memory is not constant.

Parallel workers can reduce elapsed processing time when parsing/validation has enough independent work and the machine has spare CPU capacity. They do not improve the algorithm's asymptotic complexity, and the reader, file system, output collection, sorting, and available cores can limit speedup.

**Why this algorithm fits this project:** the input is a finite file, each row's validation and threshold check is independent, and the goal is a simple demonstrable batch pipeline. A single pass avoids loading all input up front; a bounded queue controls pending task growth; a set provides straightforward per-run duplicate suppression.

**It is not universally the best algorithm.** For small files, a single-threaded loop may be simpler and faster because thread scheduling adds overhead. For millions of distinct event IDs or huge output sets, this implementation's in-memory IDs and results may use too much memory. For continuous ingestion, durable cross-run idempotency, rolling time windows, or exactly-once effects, use a message broker and persistent state with an architecture designed for those requirements.

## How multithreading works

`SignalProcessor` owns a single reader thread and creates a `ThreadPoolExecutor` with a fixed number of workers:

1. The reader checks duplicate IDs and submits unique rows as tasks.
2. The bounded `ArrayBlockingQueue` holds at most the configured queue capacity while workers are busy.
3. `CallerRunsPolicy` makes the submitting reader run a task if the queue is full. This slows input submission and creates backpressure instead of accumulating an unbounded queue.
4. `ExecutorCompletionService` lets the main processing thread collect whichever task finishes first, rather than waiting for results in input order.
5. A bounded in-flight window limits queued tasks and retained future results.
6. Worker threads share only the alert-group counters, which use a concurrent map and `LongAdder`. The main reader thread alone owns duplicate detection and collects results into output lists.

More threads do not always mean higher throughput. Parsing this sample is lightweight and can be limited by CPU, disk speed, scheduling overhead, or the input size. Benchmark the worker counts on representative data.

## Design choices and tradeoffs

| Design choice | Why it was chosen | Tradeoff |
|---|---|---|
| Plain Java, no framework | Keeps focus on Java fundamentals and makes the example easy to compile | JDK HTTP server has fewer features than a mature web framework |
| Stream input one row at a time | Avoids holding the entire input file in memory | Duplicate IDs are retained in memory for the duration of the run |
| Fixed worker count | Predictable concurrency and a clear tuning parameter | The right count depends on workload and machine |
| Bounded queue plus caller-runs backpressure | Puts a ceiling on queued work and slows the producer when saturated | The reader occasionally performs worker tasks itself |
| Per-row threshold | Supports different metric thresholds in one input | Threshold configuration is supplied in the CSV, not managed centrally |
| Dead-letter bad rows | One bad record does not discard otherwise valid input | Dead letters and alerts are accumulated in memory before output |
| CSV file input/output | Easy to inspect and run locally | No live ingestion, persistence, retry service, or dashboard |

The queue capacity limits pending tasks, not total process memory: the ID set, alert list, and dead-letter list still grow with the data. This implementation is intended as a learning project and batch prototype, not as an unlimited-volume production service.

## Tests

Run:

```powershell
.\test.ps1
```

The script compiles production and test source with `javac`, then runs `SignalProcessorTest`. Tests check:

- Concurrent processing of valid rows.
- Alerts only when the value is above the threshold.
- Accepted, alert, duplicate, and invalid counts.
- Grouping alerts by source and metric.
- Dead-letter content count.
- Clear failure when the header is incorrect.
- Local HTTP home page, CSV submission, and alert download.

The test runner is deliberately dependency-free. It uses assertions implemented in Java rather than a third-party test framework.

## Benchmark and performance metrics

Run a generated workload through one worker and then multiple workers:

```powershell
.\benchmark.ps1 -Rows 100000 -Workers 4
```

The benchmark generates data and keeps it under the ignored `build\benchmark` directory. It prints both event rates and the percentage change:

```text
throughput change (%) =
  (parallel events/sec - single-worker events/sec)
  / single-worker events/sec * 100
```

Use the result as an experiment, not a promise. Run it several times on the same machine, compare medians, and test different row counts and worker settings. The first run may include warm-up effects. Record the exact row count, worker count, Java version, and machine when reporting a metric. Do not use the tiny sample run as a benchmark.

## Current limitations

- CSV input parsing supports plain comma-separated fields only; it is not a full quoted-CSV parser.
- Event IDs, alerts, and rejected rows are held in memory, so very large feeds need bounded/durable storage changes.
- Duplicate suppression is scoped to one file run and is not persisted across runs.
- The application processes local files; it does not receive live events or send alerts to external systems.
- The website is an unauthenticated public demo; it should not receive confidential data or be treated as production infrastructure.
- Thresholds arrive on each row; there is no configuration UI or threshold-management service.
- Alerting is stateless: no rolling windows, rate-of-change rules, or historical baselines.
- The benchmark is a simple local comparison, not a statistically rigorous load-testing framework.
