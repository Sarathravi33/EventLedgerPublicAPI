# Event Ledger API — Implementation Plan

Status: **Planning artifact — no application code has been written yet.**
This document is the engineering plan for building the two-service Event Ledger system
described in the assignment brief. It is the source of truth for design decisions;
[Prompt.md](Prompt.md) breaks it into an ordered, executable build sequence, and
[README.md](README.md) documents the system as it will exist once built.

---

## 1. Objective & Scope

Build two independently runnable Spring Boot services:

- **Event Gateway API** (public) — accepts transaction events, validates them, enforces
  idempotency, persists an audit record, and forwards the transaction to the Account
  Service.
- **Account Service** (internal) — owns account balances and transaction history, applies
  transactions atomically and idempotently.

Both services must run as separate processes with separate embedded H2 databases, talk only
over synchronous REST, propagate a trace ID across the call, emit structured JSON logs and at
least one custom metric, expose `/health`, and have the Gateway degrade gracefully (no hangs,
no bare 500s) when the Account Service is down. At least one resiliency pattern is required on
the Gateway → Account Service call.

## 2. Requirement Traceability Matrix

| # | Requirement | Design Decision | Component | Verified By |
|---|---|---|---|---|
| 1a | Idempotency | Unique `eventId` constraint + status-aware replay logic (§7) | Gateway `EventService` | `EventIdempotencyTest`, `EventControllerIT` |
| 1b | Out-of-order tolerance | Balance = SQL `SUM`/atomic increment (order-independent); listings `ORDER BY event_timestamp` (§8) | Gateway `EventRepository`, Account `AccountService` | `BalanceOrderIndependenceTest`, `EventOrderingTest` |
| 1c | Balance computation | CREDIT increments, DEBIT decrements, atomic `UPDATE ... SET balance = balance ± ?` | Account Service | `AccountServiceTest` |
| 1d | Validation | Jakarta Bean Validation + custom enum validator + `GlobalExceptionHandler` → RFC 7807 `ProblemDetail` | Gateway `EventRequest` DTO | `EventValidationTest` |
| 2 | Service separation | Two Maven modules, two Spring Boot processes, two H2 instances, REST-only contract, no shared runtime code | Root project layout (§4) | `docker-compose up` smoke test |
| 3 | Distributed tracing | Micrometer Tracing + OTel bridge; W3C `traceparent` header auto-propagated by instrumented `RestClient`; trace/span IDs in MDC | Both services | `TracePropagationIT` |
| 4a | Structured logging | Logback + `logstash-logback-encoder`, JSON fields: timestamp, level, service, traceId, spanId, message | Both services | Manual log inspection + `LoggingConfigTest` |
| 4b | Health checks | `/health` (Actuator remapped), custom indicator reports own DB + downstream reachability (Gateway only) | Both services | `HealthEndpointIT` |
| 4c | Custom metric | Micrometer counters/timers, exposed at `/actuator/prometheus` (§11) | Both services | `MetricsTest` |
| 5 | Resiliency pattern | Resilience4j: Timeout + Circuit Breaker (primary), bounded Retry w/ backoff layered underneath (§12) | Gateway `AccountServiceClient` | `ResiliencyIT` (WireMock) |
| 6 | Graceful degradation | Event persisted locally before calling downstream; `POST /events` → 503 on downstream failure but data retained; `GET` endpoints never touch Account Service (§13) | Gateway | `DegradationIT` |
| 7 | Docker Compose | `docker-compose.yml` with both services, healthchecks, explicit start order | Root | `docker-compose config` + manual run |
| 8 | Automated tests | Unit + slice + WireMock resiliency + full integration test (§15) | Both modules | `mvn test` / `mvn verify` |
| 9 | README | Architecture, setup, run, test, resiliency rationale | Root | This repo |

## 3. High-Level Architecture

```
                          ┌──────────────────────────┐
Browser / Client ───────▶ │  Event Gateway API :8082  │
                          │  - validation             │
                          │  - idempotency            │
                          │  - H2 (gateway-db)        │
                          │  - Resilience4j client    │
                          └──────────┬────────────────┘
                                     │ REST (traceparent propagated)
                                     ▼
                          ┌──────────────────────────┐
                          │  Account Service :8081    │
                          │  - balance engine         │
                          │  - H2 (account-db)        │
                          │  - idempotent apply       │
                          └──────────────────────────┘
```

Both services are independent Spring Boot JVM processes. There is no shared database, no
shared in-process state, and no shared library code — only a documented REST contract (§6).

## 4. Repository / Module Layout

Multi-module Maven build so `mvn -pl <module> ... ` and `mvn test` at the root both work, while
each module still produces its own independently runnable jar and remains logically separate
(no runtime coupling, no shared DTO/library module — each service defines its own DTOs matching
the documented contract in §6).

```
EventLedgerAPI/
├── pom.xml                        (parent: packaging=pom, dependency management only)
├── event-gateway/
│   ├── pom.xml
│   └── src/main/java/com/eventledger/gateway/
│       ├── EventGatewayApplication.java
│       ├── api/            (controllers, DTOs, ProblemDetail mapping)
│       ├── domain/         (Event entity, EventStatus enum)
│       ├── repository/     (Spring Data JPA repository)
│       ├── service/        (EventService — validation, idempotency orchestration)
│       ├── client/         (AccountServiceClient — Resilience4j-wrapped RestClient)
│       ├── config/         (Resilience4j, tracing, logging, metrics config)
│       └── health/         (AccountServiceHealthIndicator)
│   └── src/test/java/...   (unit, slice, WireMock resiliency, IT)
├── account-service/
│   ├── pom.xml
│   └── src/main/java/com/eventledger/account/
│       ├── AccountServiceApplication.java
│       ├── api/
│       ├── domain/         (Account entity, Transaction entity)
│       ├── repository/
│       ├── service/        (AccountService — atomic balance update, idempotent apply)
│       └── config/
│   └── src/test/java/...
├── docker-compose.yml
├── IMPLEMENTATION_PLAN.md
├── Prompt.md
└── README.md
```

## 5. Domain Model

### 5.1 Event Gateway — `events` table

| Column | Type | Notes |
|---|---|---|
| `id` | UUID / bigint PK | internal surrogate key |
| `event_id` | varchar, **unique** | client-supplied idempotency key |
| `account_id` | varchar, indexed | |
| `type` | varchar | `CREDIT` \| `DEBIT` |
| `amount` | decimal(19,4) | validated `> 0` |
| `currency` | varchar(3) | stored as-is, not converted |
| `event_timestamp` | timestamp, indexed | used for chronological ordering |
| `metadata` | JSON/CLOB via Jackson `AttributeConverter` | optional, freeform |
| `status` | varchar | `RECEIVED` → `APPLIED` \| `FAILED` (see §7) |
| `failure_reason` | varchar, nullable | populated when `status=FAILED` |
| `trace_id` | varchar | for correlating stored records to logs |
| `created_at` | timestamp | server-side receipt time (audit only, not used for ordering) |

### 5.2 Account Service

**`accounts`**

| Column | Type | Notes |
|---|---|---|
| `account_id` | varchar PK | lazily created on first transaction |
| `balance` | decimal(19,4) | updated via atomic `balance = balance ± amount` |
| `created_at` / `updated_at` | timestamp | |

**`transactions`**

| Column | Type | Notes |
|---|---|---|
| `id` | UUID/bigint PK | |
| `event_id` | varchar, **unique** | idempotency key, same value the Gateway sent |
| `account_id` | varchar, FK | |
| `type` | varchar | `CREDIT` \| `DEBIT` |
| `amount` | decimal(19,4) | |
| `event_timestamp` | timestamp | echoed back for audit |
| `applied_at` | timestamp | server-side apply time |

The Account Service re-validates `event_id` uniqueness independently of the Gateway. This is
required, not redundant: the Gateway's Resilience4j retry policy (§12) can re-send the same
apply request after a timeout whose original call actually succeeded server-side, so the
Account Service must be idempotent on its own terms.

## 6. API Contracts

### 6.1 Event Gateway (public)

**`POST /events`**
Request body — see assignment payload spec.
Responses:
- `201 Created` — new event, successfully applied downstream. Body = stored event record.
- `200 OK` — duplicate `event_id` whose prior attempt already reached `APPLIED`. Body = the
  original stored record, unchanged.
- `400 Bad Request` — validation failure. Body = `ProblemDetail` with a `errors[]` array
  (field, message).
- `503 Service Unavailable` — Account Service unreachable/circuit open. The event **is still
  persisted** (status `FAILED`) so it is retrievable and auditable; body = `ProblemDetail`
  including the stored `eventId`.

Note on retried-after-failure semantics: if an `event_id` already exists with status `FAILED`
(a previous attempt never reached the Account Service), a resubmission is treated as a genuine
retry, not a no-op duplicate — the Gateway attempts to apply it again and updates the same
record in place, rather than creating a second row. This keeps "idempotent" and "recoverable"
compatible instead of letting idempotency permanently strand a failed event.

**`GET /events/{id}`** → `200` with stored record, or `404`. Never calls the Account Service.

**`GET /events?account={accountId}`** → `200` with array ordered by `eventTimestamp` ascending.
Never calls the Account Service.

**`GET /health`** → service status + H2 connectivity + (informational, non-fatal) Account
Service reachability.

Extension beyond the literal endpoint table (documented explicitly, not implied): a
pass-through **`GET /accounts/{accountId}/balance`** is added on the Gateway. The brief's
graceful-degradation section (#6) explicitly calls out balance-query behavior when the Account
Service is down, but the endpoint table only lists it on the internal Account Service, which is
not reachable by external clients. Without a Gateway-side proxy, "return a clear error for
balance queries when Account Service is unreachable" would be untestable from the public API.
This endpoint proxies to Account Service with the same Resilience4j policy as the transaction
call, returning `503` with a clear message on failure.

### 6.2 Account Service (internal)

**`POST /accounts/{accountId}/transactions`**
Request: `{ "eventId": "...", "type": "CREDIT|DEBIT", "amount": 150.00, "eventTimestamp": "..." }`
Response: `201` (newly applied) or `200` (idempotent replay of an already-applied `eventId`) with
`{ accountId, balance, transaction }`. Idempotent on `eventId` (§5.2) — replays return the same
result without re-applying.

Note: `currency` is deliberately **not** part of this internal contract, even though it's
present on the public Gateway payload. The Account Service's balance is a unit-agnostic sum
(§8) and never needs currency to compute or validate anything; the Gateway's `events` table is
already the record of what currency each event was submitted in. Carrying an unused field
across the internal boundary would suggest a consistency guarantee (e.g. per-account currency
uniformity) that this system deliberately does not enforce — see §17.

**`GET /accounts/{accountId}/balance`** → `{ accountId, balance }`, `404` if unknown.

**`GET /accounts/{accountId}`** → account details + recent transactions (paged/limited).

**`GET /health`** → service status + H2 connectivity.

### 6.3 Error format

RFC 7807 `ProblemDetail` for every non-2xx response, with a consistent shape across both
services:
```json
{
  "type": "about:blank",
  "title": "Validation Failed",
  "status": 400,
  "detail": "amount must be greater than 0",
  "instance": "/events",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "errors": [{"field": "amount", "message": "must be greater than 0"}]
}
```

## 7. Idempotency Design

Two independent idempotency layers, because two independent failure modes exist:

1. **Client → Gateway** (duplicate delivery of the same event): enforced by the `event_id`
   unique constraint on `events`. Lookup-before-insert inside a single transaction; on
   conflict, branch on stored `status` (§6.1).
2. **Gateway → Account Service** (retry after timeout): enforced by the `event_id` unique
   constraint on `transactions`. If the Gateway's Resilience4j retry re-sends an apply request
   whose first attempt actually succeeded, the Account Service returns the existing result
   instead of double-applying.

This two-layer design is what makes retries *safe to use at all* as part of the resiliency
pattern in §12 — without idempotency at the Account Service, a Gateway-side retry after a slow
success would double-credit or double-debit an account.

## 8. Out-of-Order & Balance Correctness Design

- **Balance correctness under any arrival order**: balance is *not* a "last write wins" field.
  It is maintained as a running total via an atomic `UPDATE accounts SET balance = balance +
  :signedAmount WHERE account_id = :id` (or equivalently derivable via `SUM` over
  `transactions`). Addition is commutative, so the final balance is identical no matter which
  order events physically arrive in — correctness here falls directly out of using an additive
  accumulator instead of a positional/sequence-dependent structure.
- **Chronological listings under any arrival order**: `event_timestamp` (the business time the
  event claims to have occurred) is a stored, indexed column, distinct from `created_at`
  (server receipt time). `GET /events?account=` sorts by `event_timestamp ASC`, so a
  late-arriving event with an earlier timestamp is correctly inserted into its right place in
  the ordering, regardless of when it was physically received.
- **What is explicitly out of scope**: reordering *within* a single account's business logic
  (e.g., rejecting a DEBIT that would overdraw based on "as-of" balance at its event time). The
  brief defines balance purely as `ΣCREDIT − ΣDEBIT`; no overdraft/insufficient-funds rule is
  specified, so none is implemented (see §17).

## 9. Distributed Tracing Design

- Dependencies (both services): `spring-boot-starter-actuator` (required, not optional — the
  observation-based auto-instrumentation for the MVC filter chain and RestClient lives in
  `spring-boot-actuator-autoconfigure`; without it `micrometer-tracing-bridge-otel` alone
  provides only the tracer, nothing that creates/propagates spans), `micrometer-tracing-bridge-otel`,
  `opentelemetry-exporter-logging` (console/log exporter — no external collector required to
  satisfy the brief; wired via an explicit `SpanExporter` bean, since Spring Boot does not
  auto-configure one just from the dependency being present).
- The Gateway generates a trace on each inbound HTTP request automatically (Spring Boot's
  observation-based MVC filter, active once actuator + the tracing bridge are present).
  `management.tracing.sampling.probability: 1.0` is set on both services so every request is
  traced, not Spring Boot's 10%-sampled default.
- **The outbound `traceparent` header is attached explicitly**, not by Spring Boot's automatic
  RestClient instrumentation. That auto-instrumentation registers correctly (every expected bean
  — `ObservationRestClientCustomizer`, `PropagatingSenderTracingObservationHandler`, a
  `Propagator`, etc. — is present in the context) but on this project's exact dependency
  combination, the autoconfigured Micrometer `Propagator` bean resolved with zero registered
  header fields (verified directly via `propagator.fields()` returning empty), so it silently
  injected nothing regardless of `management.tracing.propagation.type`. `RestAccountServiceClient`'s
  `RestClient` bean instead registers `TracingPropagationInterceptor`, which reads the current
  span from OpenTelemetry's own `Context.current()` and injects the W3C header directly via
  `io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator` — bypassing the
  Micrometer/Spring Boot auto-wiring layer entirely. See FIXES.md (Step 8) for the full
  debugging trail.
- A second subtlety specific to this codebase: the actual downstream HTTP call happens inside
  `ResilientAccountServiceClient`'s `TimeLimiter`-driven `CompletableFuture`, i.e. on a
  different thread than the one holding the active span. `execute()` explicitly captures
  `Context.current()` before the thread hop and re-activates it (`context.makeCurrent()`)
  inside the async task, otherwise the interceptor above would find no current span to inject
  at all.
- Both services bridge the current trace/span IDs into SLF4J's MDC automatically
  (`traceId`/`spanId`), which the JSON log encoder (§10) picks up — no manual code needed for
  this part, confirmed empirically.
- Verified by two tests: `TracePropagationTest` (Gateway calls a WireMock-stubbed Account
  Service; asserts the recorded request carried a `traceparent` header, and that the traceId
  extracted from it matches the Gateway's own captured log output for that request) and
  `CrossServiceTracePropagationTest` (boots a *real* Account Service in the same JVM and asserts
  the identical traceId appears in both services' own logs for one request — the stronger,
  genuinely end-to-end proof that a single client request produces a traceable path across both
  services, per requirement #3).

## 10. Structured Logging Design

- `logstash-logback-encoder` `LogstashEncoder` on the console appender in `logback-spring.xml`
  for both services.
- Fields on every log line: `@timestamp`, `level`, `service` (constant custom field per
  service, e.g. `event-gateway` / `account-service`), `traceId`, `spanId`, `logger`, `message`,
  plus MDC extras where relevant (`eventId`, `accountId`).
- No secrets/PII beyond what's already in the domain (account/event IDs) are logged.

## 11. Metrics & Health Design

**Metrics** (Micrometer, exposed at `/metrics` and `/prometheus` — see the health note below
on why these paths lack the usual `/actuator` prefix):

| Metric | Type | Tags | Service |
|---|---|---|---|
| `gateway.events.received` | counter | `type`, `outcome` (created/duplicate/rejected/failed) | Gateway |
| `gateway.account_service.call.duration` | timer | `outcome` (success/failure/circuit_open) | Gateway |
| `resilience4j.circuitbreaker.state` | gauge | `state`, `name` | Gateway |
| `account.transactions.applied` | counter | `type` | Account Service |
| `account.transaction.apply.duration` | timer | — | Account Service |

The circuit breaker state gauge needed no custom code at all — `resilience4j-spring-boot3`
(already a dependency for §12) auto-binds it (and `resilience4j.circuitbreaker.calls`,
`.failure.rate`, `.not.permitted.calls`, plus retry/time-limiter equivalents) to the
`MeterRegistry` the moment one is present on the classpath; the original sketch of a custom
`gateway.circuit_breaker.state` gauge would have been a redundant reimplementation of something
already provided. All five metrics above were verified populated via `curl` against real
running services after exercising each code path at least once.

At minimum one metric (`gateway.events.received`) satisfies requirement 4's "at least one
custom metric"; the rest are included because they're needed anyway to observe the resiliency
pattern in §12.

**Health**: Actuator remapped so `/health` (not `/actuator/health`) is the literal path, via
`management.endpoints.web.base-path: /` (which, as a side effect, also moves every other
exposed actuator endpoint to root — `/metrics`, `/prometheus` — rather than only remapping
`health` specifically; simpler than trying to remap one endpoint while leaving the rest under
`/actuator`, and the brief only requires the `/health` path literally). Each service reports
its own H2 connectivity via the standard `DataSourceHealthIndicator` (`show-details: always`
is required for this to actually appear in the response body). The Gateway additionally
exposes a non-fatal `accountService` component (`AccountServiceHealthIndicator`, pinging the
Account Service's `/health` via the plain, non-resilience-wrapped `RestClient` bean — a health
probe shouldn't share a failure budget with business calls or be blocked by a circuit it didn't
open) — implemented by **always** returning `Health.up()` for this component's own status
(Spring Boot's health aggregation has no built-in "informational, doesn't affect the aggregate"
toggle for components within the same group/endpoint) while varying only the `reachable`
detail field based on the actual ping outcome. This keeps `/health` on the Gateway truthful
about the Gateway's own liveness even while correctly surfacing the downstream outage.
Verified live: with the Account Service running, `details.reachable: true`; with it stopped,
`details.reachable: false` and overall `status` stays `UP` either way.

## 12. Resiliency Design (Gateway → Account Service)

**Chosen pattern: Circuit Breaker + Timeout, with a bounded Retry-with-backoff layered inside
the breaker.** Implemented with Resilience4j (`resilience4j-spring-boot3`), composed as
`TimeLimiter → Retry → CircuitBreaker` around the `AccountServiceClient` call.

Rationale (for the "be prepared to explain your choice" ask):
- A **bare retry** alone is wrong here: if the Account Service is genuinely down (not just
  blipping), unbounded/naive retries amplify load into a dead dependency and make the Gateway's
  own request latency unpredictable — exactly what requirement 6 says not to do ("rather than
  hanging").
- A **timeout** is necessary but not sufficient on its own: it bounds a single call's latency,
  but without a circuit breaker every subsequent request still pays that same timeout cost
  one-by-one while the dependency is down, which is a slow, resource-wasting way to fail.
- A **circuit breaker** is the piece that actually stops calling a service that's "repeatedly
  failing" (the brief's own phrasing) and fails fast — the correct behavior for an internal
  dependency the Gateway cannot function without for the write path.
- A **small bounded retry with backoff** is added *underneath* the breaker (not instead of it)
  purely to absorb transient blips (a single dropped connection, a GC pause) without forcing a
  user-visible 503 for something that would have succeeded on the second try — but it is capped
  (e.g. 2 attempts, exponential backoff with jitter, ~200ms base) specifically so it cannot
  itself cause the "hanging" behavior the brief warns against. This composition is presented as
  satisfying "at least one" resiliency pattern (circuit breaker) while explaining why the two
  supporting mechanisms are included rather than treated as separate independent options.
- A **bulkhead** was considered but not chosen as primary: the Gateway's downstream is a single
  internal dependency called synchronously per request, so thread-pool isolation matters most
  once the Gateway also serves other traffic that shouldn't stall — the circuit breaker's
  fail-fast behavior already prevents thread exhaustion for *this* call by refusing to hold a
  thread waiting on a call that has already proven itself dead, which is the primary risk
  bulkheads defend against here. It is called out in §17 as a reasonable follow-up if the
  Gateway acquires other downstream calls in the future.

As-implemented configuration (`event-gateway/src/main/resources/application.yml`):

```yaml
resilience4j:
  timelimiter:
    instances:
      accountService:
        timeout-duration: 1500ms
  retry:
    instances:
      accountService:
        max-attempts: 2
        wait-duration: 200ms
        enable-exponential-backoff: true
        exponential-backoff-multiplier: 2
        ignore-exceptions:
          - com.eventledger.gateway.client.AccountNotFoundException
  circuitbreaker:
    instances:
      accountService:
        sliding-window-size: 10
        minimum-number-of-calls: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 3
        ignore-exceptions:
          - com.eventledger.gateway.client.AccountNotFoundException
```

Two additions beyond the original indicative sketch, both worth calling out:
- `minimum-number-of-calls` must be set explicitly to match `sliding-window-size` — Resilience4j's
  own default for this value is 100 regardless of window size, so without this the breaker would
  never evaluate/open until 100 calls had been recorded, silently defeating a window size of 10.
- `ignore-exceptions` on both Retry and CircuitBreaker excludes `AccountNotFoundException`: a 404
  from the Account Service is a legitimate business outcome (the account genuinely doesn't
  exist), not an infrastructure failure — it must not be retried, and it must not count against
  the breaker's failure rate the way a timeout or connection failure does.

Composition (`ResilientAccountServiceClient`): `CircuitBreaker(Retry(TimeLimiter(call)))`,
built via Resilience4j's `Callable`-based decorators rather than `@CircuitBreaker`/`@Retry`/
`@TimeLimiter` annotations — `@TimeLimiter` requires the underlying method to return a
`CompletableFuture`, which would leak async/reactive style into an otherwise synchronous
client for no benefit here, and the programmatic composition is what makes the WireMock-backed
tests (below) deterministic. CircuitBreaker is outermost so it evaluates one outcome per
logical call (after retries are exhausted or succeed), not one outcome per HTTP attempt —
otherwise a single flaky-but-recovering call would count as multiple failures and could trip
the breaker prematurely.

## 13. Graceful Degradation Behavior Matrix

| Endpoint | Account Service down | Behavior |
|---|---|---|
| `POST /events` | down/circuit open | Event persisted locally with `status=FAILED`; respond `503` with `ProblemDetail` (not a hang, not a 500) |
| `GET /events/{id}` | down | Unaffected — pure Gateway-local read |
| `GET /events?account=` | down | Unaffected — pure Gateway-local read |
| `GET /accounts/{id}/balance` (Gateway proxy) | down | `503` with clear message; no partial/stale balance is fabricated |
| `GET /health` (Gateway) | down | Still `200`, Gateway's own status stays `UP`; `accountService` component reports `DOWN`/unreachable |

## 14. Docker Compose Plan

- Two services: `account-service` (built from `account-service/Dockerfile`, port `8081`),
  `event-gateway` (built from `event-gateway/Dockerfile`, port `8082`, env var
  `ACCOUNT_SERVICE_URL=http://account-service:8081`).
- `account-service` exposes a Compose `healthcheck` hitting `/health`; `event-gateway`'s
  `depends_on: account-service: condition: service_healthy` so the Gateway doesn't race the
  Account Service on cold start (this is a startup-ordering convenience, not a substitute for
  the runtime resiliency behavior in §12/§13, which must hold even if the Account Service goes
  down *after* both are up).
- Single bridge network; no shared volumes (each H2 instance is process-local/in-memory).

## 15. Testing Strategy & Coverage Targets

| Layer | Tool | What it covers |
|---|---|---|
| Unit | JUnit 5 + Mockito | validation rules, balance arithmetic, idempotency branching logic, DTO mapping |
| Repository slice | `@DataJpaTest` (H2) | unique constraints, ordering queries, atomic balance update |
| Web slice | `@WebMvcTest` / MockMvc | controller status codes, `ProblemDetail` shape |
| Resiliency | WireMock standing in for Account Service (delay/500/connection-reset stubs) | circuit breaker opens after threshold, timeout fires, retry is bounded, Gateway returns 503 not 500, never hangs past the configured timeout budget |
| Tracing | WireMock request capture + log capture (`ListAppender`) | `traceparent` header present on outbound call; same trace ID appears in Gateway log line for that request |
| Full integration | `@SpringBootTest(webEnvironment=RANDOM_PORT)` for both modules running together in one JVM (Gateway pointed at the real Account Service's random port) | end-to-end POST → applied balance → GET listing order → duplicate replay |

**Coverage tooling**: JaCoCo Maven plugin in both module POMs, aggregated at the parent via
`jacoco:report-aggregate`. Target: **≥ 80% line coverage** per module, enforced via
`jacoco:check` bound to `verify` (build fails under threshold) — chosen as a reasonable bar
that's enforceable in CI without chasing 100% on generated/boilerplate code (entities, DTOs).
Run with `mvn verify`; HTML report at `target/site/jacoco/index.html` per module.

## 16. Build & Delivery Milestones

1. Parent POM + module skeletons, both apps boot and answer `/health`.
2. Account Service: domain model, repository, atomic apply, idempotency, balance/detail
   endpoints, unit + slice tests.
3. Gateway: domain model, repository, validation, `EventService` idempotency/replay logic, unit
   + slice tests (Account Service call still stubbed).
4. `AccountServiceClient`: real REST call wiring, DTOs matching §6 contract, happy-path
   integration between the two running services.
5. Resilience4j composition (§12) + WireMock resiliency tests + graceful degradation (§13).
6. Tracing (§9) + structured logging (§10) + tracing test.
7. Metrics + health remapping (§11) + metrics/health tests.
8. Dockerfiles + `docker-compose.yml`, manual smoke test of the composed stack.
9. Full integration test suite, JaCoCo thresholds green, README finalized against the as-built
   system.

This ordering matches the step-by-step prompts in [Prompt.md](Prompt.md).

## 17. Assumptions & Out-of-Scope Items

- No overdraft/insufficient-funds rule — balance can go negative; the brief only defines
  balance as `ΣCREDIT − ΣDEBIT`, not an authorization rule.
- No cross-event currency-consistency validation per account (e.g. mixing `USD` and `EUR`
  events on the same account is accepted as-is); flagged here rather than silently added,
  since the brief doesn't ask for it.
- No automatic background reconciliation/retry of `FAILED` events after the initial request
  returns 503 — the record is retained and a client resubmission is treated as a real retry
  (§7), but there is no scheduled job. Worth calling out as a natural extension, not built by
  default to avoid scope creep.
- No authentication/authorization on either API — not mentioned in the brief.
- Bulkhead pattern not implemented (see §12 rationale); could be added if the Gateway grows more
  downstream dependencies.
- OpenTelemetry is used via the Micrometer Tracing bridge with a logging exporter rather than a
  full OTel Collector/Jaeger/Zipkin backend, since the brief only requires trace IDs to be
  generated, propagated, and logged — no visualization backend is required and adding one would
  be infrastructure beyond what's asked.

## 18. Configuration Reference (planned `application.yml` keys)

| Key | Service | Purpose |
|---|---|---|
| `server.port` | both | `8082` gateway / `8081` account |
| `account-service.base-url` | gateway | e.g. `http://localhost:8081` (Compose overrides via env) |
| `resilience4j.*` | gateway | see §12 |
| `management.endpoints.web.base-path` | both | `/` so Actuator health serves at `/health` |
| `management.endpoints.web.exposure.include` | both | `health,prometheus,metrics` |
| `logging.pattern.*` / logback JSON encoder config | both | §10 |
| `spring.datasource.url` | both | `jdbc:h2:mem:<service>db;DB_CLOSE_DELAY=-1` |
