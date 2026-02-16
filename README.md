# Native Durable Execution Engine

Temporal-inspired durable execution in plain Java 21 + SQLite. Workflows are normal Java methods; wrap side effects in `step()` to gain replay and crash recovery. No annotations or DSLs.

## How it works
- **Step primitive**: `<T> T step(String id, Callable<T>)` wraps any side effect. Engine uses a monotonically increasing sequence number to build a unique `step_key = workflowId:sequence`. The `step_id` is stored for observability, but the sequence guarantees uniqueness even inside loops/conditionals.
- **Replay**: On re-run, each call to `step()` reuses the same sequence order. If the record is `COMPLETED`, the cached JSON result is returned. If missing, it is executed and recorded. If `IN_PROGRESS` is stale, it is marked `FAILED` then re-run.
- **Sequence strategy**: A per-workflow `SequenceTracker` hands out numbers deterministically in call order. For parallel branches, sequences are reserved **before** spawning virtual threads to ensure deterministic keys across runs.
- **Persistence**: SQLite table `steps` is created automatically. WAL mode, busy timeout, and retry/backoff handle `SQLITE_BUSY`. All step operations run inside a single transaction for read→insert→update.
- **Zombie handling**: A crash after side effects but before `COMPLETED` leaves an `IN_PROGRESS` row. On resume, rows older than `stale_timeout` are flipped to `FAILED`, allowing re-execution. Side effects should be idempotent or implement their own two-phase semantics; the sample uses benign operations.
- **Concurrency**: Uses Java 21 `StructuredTaskScope` (virtual threads). Parallel branches reserve their sequence numbers first, then execute `stepWithReservedSequence` so durable keys stay stable.
- **Type safety**: Jackson (with polymorphic typing) serializes arbitrary return types. `TypeReference` overload is available when needed.

## Project layout
```
pom.xml
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

## Running
```bash
mvn clean package
java -jar target/native-durable-execution-engine-1.0.0.jar start wf-123
java -jar target/native-durable-execution-engine-1.0.0.jar resume wf-123
java -jar target/native-durable-execution-engine-1.0.0.jar crash-after wf-123 2
```
`durable.db` is created in the working directory.

## Example workflow (Onboarding)
- Create employee record (sequential)
- Provision laptop (parallel)
- Provision access (parallel)
- Send welcome email (sequential)

Each step is durable and will not repeat on resume once `COMPLETED`.

## Design notes
- **Transactions**: The read→insert `IN_PROGRESS`→update `COMPLETED/FAILED` occurs in one transaction to avoid races.
- **Busy retries**: `SQLITE_BUSY` triggers bounded retries with backoff.
- **Crash simulation**: `crash-after N` halts after marking the Nth step `IN_PROGRESS`, letting you exercise zombie handling.
- **Extensibility**: Swap SQLite URL to point at another file/location; add index/metrics without changing the core API.

## Testing
`mvn test` executes JUnit 5 tests including replay/idempotency validation for the step cache.
