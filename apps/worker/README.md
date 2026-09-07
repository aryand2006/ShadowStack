# ShadowStack Worker

Asynchronous task executor for verified language modernization.

## Job ownership

In **prod/docker**, this process owns `ss_jobs` **VERIFY** dequeue:

1. API enqueues VERIFY jobs with a rich `payload_json` (sources + patch metadata) and does **not** poll (`shadowstack.jobs.poller-enabled=false`).
2. `JobClaimPoller` claims the next `PENDING` VERIFY row with Postgres `FOR UPDATE SKIP LOCKED`.
3. `VerificationTask` runs the full 7-layer Java pipeline, then updates `ss_patches` (`PENDING_REVIEW` / `VERIFICATION_FAILED`) and the job status.

Demo profile on the API stays synchronous/in-memory and does not use this poller.

### Re-enable API poller (single-process)

```bash
export SHADOWSTACK_JOBS_POLLER_ENABLED=true
```

## Run

```bash
mvn -pl apps/worker -am package -DskipTests
java -jar apps/worker/target/worker-*.jar
```

Compose: `docker compose up worker` (profile `docker,worker`).
