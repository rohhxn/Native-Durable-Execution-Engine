# Native Durable Execution Engine

Temporal-inspired durable execution in plain Java 21 preview APIs + SQLite. Workflows are regular Java methods; wrap side effects in `step()` to gain replay and crash recovery. No annotations or DSLs.

## Repository

GitHub: https://github.com/rohhxn/Native-Durable-Execution-Engine

## How it works
- **Step primitive**: `<T> T step(String id, Callable<T>)` wraps any side effect. A monotonically increasing sequence builds a unique `step_key = workflowId:sequence`. `step_id` is stored for observability; the sequence guarantees uniqueness even inside loops.
- **Replay**: On re-run, the same sequence order is used. `COMPLETED` rows return cached JSON; missing rows execute; stale `IN_PROGRESS` rows are marked `FAILED` and re-run.
- **Sequence strategy**: `SequenceTracker` hands out deterministic numbers. Parallel branches reserve sequences **before** forking virtual threads so durable keys stay stable.
- **Persistence**: `SQLiteStepStore` creates table `steps` and enables WAL + `busy_timeout`. `SQLITE_BUSY` is propagated to a retry loop with backoff; transactions handle read→insert→update in one unit of work.
- **Concurrency**: Uses Java 21 preview `StructuredTaskScope` (virtual threads). Busy signals in transactions are retried instead of failing the workflow.
- **Type safety**: Jackson with `TypeReference` overloads handles generic return types.

## Project layout
```

src/
  main/java/com/example/durable/
    App.java                 # CLI
    DurableContext.java      # Workflow facade
    Workflow.java            # Workflow contract
    WorkflowRunner.java      # start/resume
    engine/
      CrashSimulator.java
      SQLiteStepStore.java
      SequenceTracker.java
      StepExecutor.java
      StepRecord.java
      StepResultSerializer.java
      StepStatus.java
    example/
      OnboardingWorkflow.java
  test/java/com/example/durable/
    StepExecutorTest.java
README.md
prompts.txt
```

## Data model

SQLite table `steps` (PK: `workflow_id`, `step_key`):
- `workflow_id` – workflow instance id
- `step_key` – `workflowId:sequence`
- `step_id` – human-friendly id
- `sequence` – reserved long
- `status` – `IN_PROGRESS | COMPLETED | FAILED`
- `output` – serialized JSON
- `updated_at` – timestamp

## Build

Requirements: Java 21, Maven.

```bash
mvn clean package
```

Compiler and Surefire are configured with `--enable-preview` for structured concurrency.

## Run

1) Copy runtime dependencies (classpath run expects them under `target/lib`):
```bash
mvn dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/lib
```

2) Run the sample onboarding workflow (Windows classpath uses `;`):
```bash
java --enable-preview -cp "target/native-durable-execution-engine-1.0.0.jar;target/lib/*" com.example.durable.App start wf-123
```

3) Resume or crash-test:
```bash
java --enable-preview -cp "target/native-durable-execution-engine-1.0.0.jar;target/lib/*" com.example.durable.App resume wf-123
java --enable-preview -cp "target/native-durable-execution-engine-1.0.0.jar;target/lib/*" com.example.durable.App crash-after wf-123 2
```

Notes:
- The shaded-jar path is not used; rely on the classpath run above unless you add a fat-jar plugin.
- `durable.db` is created in the working directory and ignored by git.
- Increase logging with `-Dorg.slf4j.simpleLogger.defaultLogLevel=debug`.

## Example workflow (Onboarding)
- Create employee record (sequential)
- Provision laptop (parallel via reserved sequence + `StructuredTaskScope`)
- Provision access (parallel)
- Send welcome email (sequential)

Each step is durable and will not repeat once `COMPLETED`.

## Design notes
- **Transactions**: Read→insert `IN_PROGRESS`→update `COMPLETED/FAILED` within one transaction to avoid races.
- **Busy retries**: `SQLITE_BUSY` triggers bounded retries with backoff (200ms, 5 attempts) and WAL + `busy_timeout=5000` pragmas.
- **Zombie handling**: Stale `IN_PROGRESS` rows are marked `FAILED` so they can be re-run; side effects should be idempotent.
- **Crash simulation**: `crash-after N` halts after marking the Nth step `IN_PROGRESS` to exercise recovery.
- **Extensibility**: Swap SQLite URL, tune retry/backoff, or implement another store behind `StepExecutor`.

## Testing

```bash
mvn test
```

`StepExecutorTest` validates replay and caching semantics.
