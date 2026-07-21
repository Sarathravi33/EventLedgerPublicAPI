# Event Ledger API

A two-service system for ingesting financial transaction events that may arrive **out of
order** or be **delivered more than once**, while keeping account balances correct and
degrading gracefully when the internal Account Service is unavailable.

> **Status**: this README describes the target design as specified in
> [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) and built step-by-step per
> [Prompt.md](Prompt.md). Sections below will be kept in sync as implementation proceeds; if
> anything here ever disagrees with the actual code, the code wins.

## Architecture

```
                          ┌──────────────────────────┐
Browser / Client ───────▶ │  Event Gateway API :8082  │
                          │  (public-facing)          │
                          └──────────┬────────────────┘
                                     │ REST (sync, traceparent propagated)
                                     ▼
                          ┌──────────────────────────┐
                          │  Account Service :8081    │
                          │  (internal)               │
                          └──────────────────────────┘
```

- **Event Gateway API** — the only externally-facing service. Validates incoming events,
  enforces idempotency on `eventId`, stores every event as a local audit record (so
  read endpoints work even if the Account Service is down), and calls the Account Service to
  apply the transaction.
- **Account Service** — owns account balances and transaction history. Applies transactions
  idempotently (keyed on the same `eventId`) and computes balance as an order-independent
  running total (`ΣCREDIT − ΣDEBIT`).

Each service is an independent Spring Boot process with its own in-memory H2 database. They
share no database and no in-process state — only the REST contract described below.

## Tech Stack

- Java 21, Spring Boot 3.2.5
- Spring Web, Spring Data JPA, H2 (embedded/in-memory)
- Resilience4j (circuit breaker + timeout + retry) on the Gateway's downstream call
- Micrometer Tracing + OpenTelemetry bridge for trace propagation
- Logback + `logstash-logback-encoder` for structured JSON logs
- Micrometer + Actuator for metrics and health
- JUnit 5, Mockito, WireMock, JaCoCo for testing and coverage
- Maven (multi-module), Docker / Docker Compose

## Repository Structure

```
EventLedgerAPI/
├── pom.xml                  # parent POM (dependency management only)
├── event-gateway/           # public-facing service
├── account-service/         # internal service
├── docker-compose.yml
├── IMPLEMENTATION_PLAN.md   # detailed design & requirement traceability
├── Prompt.md                # step-by-step build sequence
└── README.md                # this file
```

## Prerequisites

- JDK 21
- Maven 3.9+ (or the included `mvnw` wrapper, once added)
- Docker + Docker Compose (optional, but preferred for running the full stack)

## Configuration

Key settings (see [IMPLEMENTATION_PLAN.md §18](IMPLEMENTATION_PLAN.md#18-configuration-reference-planned-applicationyml-keys)
for the full list):

| Setting | Default | Notes |
|---|---|---|
| Gateway port | `8082` | `server.port` |
| Account Service port | `8081` | `server.port` |
| Account Service URL (as seen by Gateway) | `http://localhost:8081` | overridden to `http://account-service:8081` in Docker Compose |
| Health endpoint | `/health` on both services | remapped from Actuator's default `/actuator/health` — this remap moves all actuator endpoints to root, so metrics/prometheus lose the `/actuator` prefix too |
| Metrics endpoint | `/metrics`, `/prometheus` | both services |

## Running with Docker Compose (preferred)

```bash
docker compose up --build
```

This starts `account-service` on `localhost:8081` and `event-gateway` on `localhost:8082`. The
Gateway waits for the Account Service's healthcheck before starting. Stop with `docker compose
down`.

## Running Manually (without Docker)

From the repository root, in two separate terminals:

```bash
# Terminal 1 — Account Service
mvn -pl account-service spring-boot:run

# Terminal 2 — Event Gateway (after Account Service is up)
mvn -pl event-gateway spring-boot:run
```

Or run the packaged jars:

```bash
mvn -pl account-service package -DskipTests
mvn -pl event-gateway package -DskipTests
java -jar account-service/target/account-service-exec.jar
java -jar event-gateway/target/event-gateway.jar
```

(Account Service's executable jar is suffixed `-exec` — its plain jar is kept as the resolvable
Maven artifact so `event-gateway`'s integration test can depend on it in test scope; see the
`spring-boot-maven-plugin` config in `account-service/pom.xml`.)

## API Usage Examples

Submit an event:

```bash
curl -i -X POST http://localhost:8082/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-001",
    "accountId": "acct-123",
    "type": "CREDIT",
    "amount": 150.00,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T14:02:11Z",
    "metadata": {"source": "mainframe-batch", "batchId": "B-9042"}
  }'
```

Resubmitting the same body returns the same stored record (idempotent replay) rather than
creating a second event or double-applying the balance.

Fetch a single event:

```bash
curl http://localhost:8082/events/evt-001
```

List events for an account, chronologically ordered by `eventTimestamp`:

```bash
curl "http://localhost:8082/events?account=acct-123"
```

Check a balance (proxied through the Gateway; returns `503` if the Account Service is down):

```bash
curl http://localhost:8082/accounts/acct-123/balance
```

Health checks:

```bash
curl http://localhost:8082/health
curl http://localhost:8081/health
```

## Resiliency Pattern & Rationale

The Gateway wraps every call to the Account Service with **Circuit Breaker + Timeout**
(Resilience4j), with a small bounded **Retry with backoff** layered inside the breaker for
transient blips.

- **Timeout** bounds how long a single call can take, so the Gateway never hangs waiting on a
  stuck dependency.
- **Circuit breaker** is the primary pattern: once the Account Service is *repeatedly* failing,
  the breaker opens and the Gateway fails fast with `503` instead of continuing to pay
  timeout-latency on every request while the dependency is dead.
- **Bounded retry with backoff** (2 attempts, exponential backoff) sits underneath the breaker
  purely to absorb one-off transient errors without surfacing a user-visible failure for
  something that would have succeeded on a second try — it's capped tightly enough that it
  cannot itself reintroduce the "hanging" behavior the breaker/timeout exist to prevent.

A bulkhead was considered but not chosen as the primary mechanism: the Gateway has a single
synchronous downstream dependency today, and the circuit breaker's fail-fast behavior already
prevents thread exhaustion on that call path by refusing to hold a thread on a call that has
already proven the dependency is down. See
[IMPLEMENTATION_PLAN.md §12](IMPLEMENTATION_PLAN.md#12-resiliency-design-gateway--account-service)
for the full rationale.

**Graceful degradation when the Account Service is unavailable:**

| Endpoint | Behavior |
|---|---|
| `POST /events` | Event is still persisted locally (status `FAILED`, retrievable/auditable); responds `503`, never hangs, never `500`s |
| `GET /events/{id}` | Unaffected — served entirely from the Gateway's own database |
| `GET /events?account=` | Unaffected — served entirely from the Gateway's own database |
| `GET /accounts/{id}/balance` | `503` with a clear message — no fabricated/stale balance is returned |

## Observability

- **Tracing**: a trace is created per inbound Gateway request and the W3C `traceparent` header
  is automatically propagated to the Account Service via the instrumented REST client. Both
  services log the active `traceId`/`spanId` on every log line via MDC.
- **Structured logs**: JSON on stdout, fields include `timestamp`, `level`, `service`,
  `traceId`, `spanId`, `logger`, `message`.
- **Metrics**: `gateway.events.received` (by type/outcome), `gateway.account_service.call.duration`,
  `resilience4j.circuitbreaker.state` (auto-bound by Resilience4j — no custom code needed),
  `account.transactions.applied`, `account.transaction.apply.duration` — exposed at `/metrics`
  and `/prometheus` on both services (e.g. `curl http://localhost:8082/metrics/gateway.events.received`).
- **Health**: `GET /health` on both services reports DB connectivity (`show-details: always`);
  the Gateway additionally reports, as a non-fatal `accountService` sub-component, whether the
  Account Service is currently reachable — that component's own status is always `UP` (only its
  `reachable` detail varies), so a downstream outage is visible without ever flipping the
  Gateway's overall health to `DOWN`.

## Running Tests & Coverage

```bash
mvn test      # unit + slice tests, both modules
mvn verify    # adds WireMock resiliency/tracing tests, integration tests, and JaCoCo enforcement
```

- JaCoCo is configured in both modules with an enforced minimum of **80% line coverage**
  (`mvn verify` fails the build under that threshold).
- HTML coverage reports: `event-gateway/target/site/jacoco/index.html` and
  `account-service/target/site/jacoco/index.html`.
- Test categories: idempotency, out-of-order/balance correctness, validation, resiliency
  (simulated Account Service failure via WireMock — asserts the circuit breaker opens and the
  Gateway responds correctly), trace propagation, and at least one full Gateway → Account
  Service integration test. See
  [IMPLEMENTATION_PLAN.md §15](IMPLEMENTATION_PLAN.md#15-testing-strategy--coverage-targets)
  for the complete breakdown.

## Assumptions & Known Limitations

The brief leaves a number of details unspecified. Where a choice had to be made, it's recorded
here rather than made silently. (Full rationale for the design-heavy ones is in
[IMPLEMENTATION_PLAN.md §17](IMPLEMENTATION_PLAN.md#17-assumptions--out-of-scope-items).)

**Business logic**
- Balance has no overdraft/insufficient-funds rule — it is purely `ΣCREDIT − ΣDEBIT` as
  specified in the brief; it is allowed to go negative.
- No cross-event currency-consistency validation per account — an account can end up with
  mixed-currency transactions (e.g. a `USD` event followed by a `EUR` event) without rejection;
  no FX conversion is performed anywhere.
- An `accountId` is not pre-registered — the Account Service lazily creates an `Account` row
  (starting balance `0`) the first time it sees a transaction for that ID, rather than
  requiring accounts to be provisioned up front.
- `eventTimestamp` (business time, used for ordering) and the server-side receipt time
  (`createdAt`) are treated as distinct fields; only `eventTimestamp` drives chronological
  ordering in `GET /events?account=`.

**Idempotency & retries**
- "Return the original event with an appropriate status code" is interpreted as: `200 OK` for
  a duplicate of an already-successfully-applied event, `201 Created` for a new event applied
  successfully, and `503` (with the event still persisted) when the downstream call fails —
  rather than a single fixed status code for every duplicate case.
- A resubmission of an `eventId` that previously ended in `FAILED` (Account Service was
  unreachable at the time) is treated as a genuine client retry and is re-attempted against the
  Account Service, updating the same stored record in place — it is not treated as a pure
  no-op duplicate, and no second row is ever created for the same `eventId`.
- No automatic background reconciliation/retry job for `FAILED` events — recovery relies on the
  upstream system resubmitting, which the idempotency design above supports.
- The Account Service independently deduplicates on `eventId` (not just the Gateway), because
  the Gateway's own retry policy could otherwise re-apply a transaction whose first attempt
  actually succeeded but timed out on the response.

**API surface**
- `GET /accounts/{accountId}/balance` is added on the Gateway as a pass-through to the Account
  Service. It isn't in the brief's endpoint table (only listed internally on the Account
  Service), but requirement #6 explicitly describes balance-query degradation behavior for
  public clients, which is only testable/reachable if the Gateway exposes it.
- No API versioning, no authentication/authorization on either service, and no rate limiting —
  none are mentioned in the brief.
- `metadata` is stored as freeform, schema-less JSON with no validation beyond being valid
  JSON.

**Infrastructure & non-functional choices**
- Each service uses H2 in-memory (`DB_CLOSE_DELAY=-1`) rather than file-backed H2 — data does
  not survive a process restart, which matches "in-memory" in the brief's own DB constraint.
- Default ports `8082` (Gateway) and `8081` (Account Service), overridable via config/env —
  the brief doesn't mandate specific ports.
- Trace propagation uses the standard W3C `traceparent` header (via Micrometer Tracing/OTel
  auto-instrumentation) rather than a custom header — chosen because it's the OpenTelemetry-
  idiomatic default the brief says is "preferred," and it means propagation requires no
  hand-written header-forwarding code.
- OpenTelemetry is wired via the Micrometer Tracing bridge with a logging exporter, not a full
  OTel Collector + Jaeger/Zipkin backend — the brief only requires trace IDs to be generated,
  propagated, and logged, not visualized, so no collector infrastructure is stood up.
- Docker Compose's `depends_on: condition: service_healthy` is a startup-ordering convenience
  only; it does not substitute for the Gateway's runtime resiliency behavior, which must (and
  is tested to) hold even if the Account Service goes down after both services are already up.
- The two services share no library/DTO code, even though they're built from one multi-module
  Maven repo for developer convenience — this is a deliberate reading of "must not share
  database or in-process state" as also ruling out compile-time coupling that would make them
  harder to version/deploy independently.
- 80% line coverage (JaCoCo, enforced on `mvn verify`) was chosen as a reasonable, enforceable
  bar — the brief asks for test coverage of specific behaviors (§ below) but doesn't specify a
  numeric target.

## Further Reading

- [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) — full design, requirement traceability
  matrix, API contracts, and configuration reference.
- [Prompt.md](Prompt.md) — ordered, step-by-step build prompts with acceptance criteria for
  requirement/code/test coverage at each step.
- [FIXES.md](FIXES.md) — running log of bugs found and corrected during implementation (e.g.
  the account-creation race condition caught by the concurrency test), per build step.
