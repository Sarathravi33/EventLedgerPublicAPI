# Prompt.md — Step-by-Step Build Sequence

This file is a set of self-contained, ordered prompts for implementing the Event Ledger
system described in [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md). Each step is scoped to
be independently implementable and testable, produces a working increment, and lists exactly
which assignment requirements, code coverage, and test coverage it must satisfy before moving
on. Feed each prompt (to yourself, a teammate, or an AI coding assistant) one at a time, in
order — later steps assume earlier ones are done and passing.

Conventions used below:
- **Req refs** point at requirement numbers from the assignment brief and section numbers
  from `IMPLEMENTATION_PLAN.md`.
- **Coverage gate** is the JaCoCo threshold that must hold for the module(s) touched in that
  step, checked via `mvn verify`.
- **Definition of done** must be true before starting the next step.

---

## Step 0 — Repository scaffolding

**Prompt:**
> Create a multi-module Maven project rooted at the repo root: a parent `pom.xml`
> (packaging `pom`, Java 21, Spring Boot 3.2.5 BOM imported via
> `spring-boot-dependencies`, modules `account-service` and `event-gateway`), and two child
> modules each with a minimal `pom.xml` and a `@SpringBootApplication` main class that boots
> with no endpoints yet. Account Service listens on port `8081`, Event Gateway on `8082`
> (set via `application.yml`, not hardcoded). Add `.gitignore` for `target/`. Verify both
> apps start with `mvn spring-boot:run` in their respective module directories.

**Req refs:** #2 (service separation), IMPLEMENTATION_PLAN.md §4
**Coverage gate:** none yet (no logic to cover)
**Definition of done:** `mvn -pl account-service spring-boot:run` and
`mvn -pl event-gateway spring-boot:run` both boot cleanly on their assigned ports with no
errors.

---

## Step 1 — Account Service domain model + persistence

**Prompt:**
> In `account-service`, add Spring Data JPA + H2 (in-memory, `DB_CLOSE_DELAY=-1`). Create the
> `Account` entity (`accountId` PK, `balance` as `BigDecimal`, `createdAt`, `updatedAt`) and
> `Transaction` entity (`id`, unique `eventId`, `accountId` FK, `type` enum `CREDIT`/`DEBIT`,
> `amount` `BigDecimal`, `eventTimestamp`, `appliedAt`), per IMPLEMENTATION_PLAN.md §5.2.
> Add Spring Data repositories. Write `@DataJpaTest` tests proving: the `eventId` unique
> constraint is enforced, and a repository query can compute balance as `ΣCREDIT − ΣDEBIT`
> for a set of transactions inserted in a randomized (non-chronological) order — assert the
> sum is correct regardless of insertion order.

**Req refs:** #1b, #1c, IMPLEMENTATION_PLAN.md §5.2, §8
**Coverage gate:** ≥80% line coverage on the entity/repository package
**Definition of done:** `mvn -pl account-service test` green; the randomized-order test
explicitly demonstrates order-independence, not just a single fixed-order case.

---

## Step 2 — Account Service business logic: atomic, idempotent transaction application

**Prompt:**
> Implement `AccountService.applyTransaction(accountId, eventId, type, amount,
> eventTimestamp)`:
> - If a `Transaction` with this `eventId` already exists, return the existing result
>   unchanged (idempotent no-op) — do not re-apply.
> - Otherwise, lazily create the `Account` row if it doesn't exist (starting balance 0), apply
>   the signed amount via an atomic update (`balance = balance + :signedAmount`, not a
>   read-modify-write in application code, to avoid lost updates under concurrent calls), and
>   insert the `Transaction` row.
> - Wrap the whole thing in a single `@Transactional` boundary.
> Write unit tests (mocked repositories) for: first application, idempotent replay of the same
> `eventId` (result unchanged, no duplicate row, balance not double-counted), CREDIT vs DEBIT
> sign handling, and a concurrency-flavored test (e.g. two threads applying different
> `eventId`s concurrently to the same account) proving the final balance is correct — this
> validates the atomic-update approach, not a mocked assumption.

**Req refs:** #1a (idempotency at the Account Service layer, per IMPLEMENTATION_PLAN.md §7),
#1c, IMPLEMENTATION_PLAN.md §2
**Coverage gate:** ≥80% line coverage on `service` package
**Definition of done:** idempotent-replay test and concurrent-application test both pass
reliably (no flakiness from a race condition in the update path).

---

## Step 3 — Account Service REST API

**Prompt:**
> Add controllers for `POST /accounts/{accountId}/transactions`, `GET
> /accounts/{accountId}/balance`, `GET /accounts/{accountId}` per IMPLEMENTATION_PLAN.md §6.2.
> Add request validation (Jakarta Bean Validation) and a `GlobalExceptionHandler` returning
> RFC 7807 `ProblemDetail` for validation errors and 404s for unknown accounts. Write
> `@WebMvcTest` (or full `@SpringBootTest` with `MockMvc`) tests for every status code path:
> success, validation failure, unknown account, idempotent-replay response shape.

**Req refs:** #1d, IMPLEMENTATION_PLAN.md §6.2, §6.3
**Coverage gate:** ≥80% line coverage on `api` package; module-wide ≥80%
**Definition of done:** `mvn -pl account-service verify` green with coverage gate passing;
manual `curl` smoke test against the running service matches the documented contract.

---

## Step 4 — Event Gateway domain model + persistence

**Prompt:**
> In `event-gateway`, mirror Step 1's setup: H2, JPA. Create the `Event` entity per
> IMPLEMENTATION_PLAN.md §5.1 (`eventId` unique, `accountId`, `type`, `amount`, `currency`,
> `eventTimestamp`, `metadata` via a Jackson `AttributeConverter<Map<String,Object>,String>`,
> `status` enum `RECEIVED`/`APPLIED`/`FAILED`, `failureReason`, `traceId`, `createdAt`). Add
> the repository with a method to find by `eventId` and a method to list by `accountId`
> ordered by `eventTimestamp ASC`. Write `@DataJpaTest` tests for the unique constraint, the
> ordering query (insert events out of chronological order, assert the query returns them
> sorted), and the metadata JSON round-trip.

**Req refs:** #1b, IMPLEMENTATION_PLAN.md §5.1, §8
**Coverage gate:** ≥80% on entity/repository package
**Definition of done:** ordering test inserts events in scrambled order and asserts the
returned list is chronological — this is the concrete proof of out-of-order tolerance for
reads.

---

## Step 5 — Event Gateway validation + idempotency/replay orchestration (Account Service call stubbed)

**Prompt:**
> Implement `EventService.submit(EventRequest)` with a stubbed/interface-only
> `AccountServiceClient` (real HTTP wiring comes in Step 6 — for now, inject a fake/mock
> implementation so this step's logic can be tested without a live dependency). Behavior per
> IMPLEMENTATION_PLAN.md §6.1/§7:
> - Validate the payload (Bean Validation: required fields, `amount > 0`, `type` in
>   `{CREDIT,DEBIT}`, parseable ISO-8601 `eventTimestamp`) → 400 with field-level
>   `ProblemDetail` on failure.
> - If `eventId` exists with status `APPLIED` → return 200 with the existing record, do not
>   call the client.
> - If `eventId` exists with status `FAILED` → treat as a retry: call the client again, update
>   the same row in place.
> - If `eventId` is new → persist as `RECEIVED`, call the client, update to `APPLIED` or
>   `FAILED` based on the outcome.
> - On `APPLIED` → 201. On `FAILED` (client threw/returned failure) → 503, but the row must
>   still exist and be fetchable.
> Write unit tests covering every branch above, plus the full validation matrix (missing
> field, zero amount, negative amount, unknown type, malformed timestamp).

**Req refs:** #1a, #1d, #6 (partially — degradation behavior for POST), IMPLEMENTATION_PLAN.md
§7, §13
**Coverage gate:** ≥80% on `service` package
**Definition of done:** every branch in the prompt has a dedicated passing test; no branch is
exercised only incidentally by another test.

---

## Step 6 — Event Gateway REST API + real Account Service client (happy path)

**Prompt:**
> Add the `POST /events`, `GET /events/{id}`, `GET /events?account=` controllers wired to
> `EventService` from Step 5. Implement the real `AccountServiceClient` using Spring's
> `RestClient`, calling the contract from IMPLEMENTATION_PLAN.md §6.2, with the base URL
> externalized as `account-service.base-url` (default `http://localhost:8081`). No
> resiliency wrapping yet — that's Step 7; this step is happy-path wiring only. Add a
> `@SpringBootTest(webEnvironment = RANDOM_PORT)` integration test that starts the Account
> Service's Spring context in the same JVM on a random port (or points at a separately
> `mvn -pl account-service spring-boot:run`'d instance, documented either way) and exercises:
> submit → 201 → balance reflects the transaction → duplicate submit → 200, balance unchanged
> → submit two events out of chronological order → `GET /events?account=` returns them sorted.

**Req refs:** #1a, #1b, #1c, #2, #8 (integration test requirement), IMPLEMENTATION_PLAN.md §6,
§15
**Coverage gate:** ≥80% module-wide on `event-gateway`
**Definition of done:** this is the first test that proves the two real services talking to
each other over REST behave correctly — treat it as the project's core acceptance test; it
must pass before touching resiliency, since resiliency changes should not need to alter this
test's happy-path assertions.

---

## Step 7 — Resiliency: circuit breaker, timeout, bounded retry

**Prompt:**
> Add Resilience4j (`resilience4j-spring-boot3`) to `event-gateway`. Wrap
> `AccountServiceClient`'s call with `TimeLimiter` → `Retry` → `CircuitBreaker` per the
> config in IMPLEMENTATION_PLAN.md §12. On any failure that exhausts retry/timeout, or when
> the circuit is open, `EventService` must treat it as a downstream failure (per Step 5's
> `FAILED` branch) and the controller must return `503`, never `500`, never hang past the
> configured timeout budget. Write tests using WireMock standing in for the Account Service:
> (a) a stubbed 500ms delay beyond the timeout → verify the call fails fast at the timeout
> boundary, not at some longer default; (b) a stub that always errors → after enough failures
> to cross the configured failure-rate threshold, verify the circuit breaker's state is
> `OPEN` and subsequent calls fail immediately without hitting WireMock at all (assert
> WireMock's request count stops increasing); (c) a stub that fails once then succeeds →
> verify the bounded retry recovers without surfacing an error to the caller. Also verify
> `GET /events/{id}` and `GET /events?account=` still return `200` while the circuit is open
> (they must not depend on the Account Service at all).

**Req refs:** #5, #6, #8 (resiliency test requirement), IMPLEMENTATION_PLAN.md §12, §13
**Coverage gate:** ≥80% on `client`/`config` packages touched
**Definition of done:** all three WireMock scenarios pass deterministically (no reliance on
wall-clock sleeps beyond what the configured timeouts require); the circuit-open scenario
concretely proves no call reached the stub after opening.

---

## Step 8 — Distributed tracing

**Prompt:**
> Add `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-logging` to both modules.
> Confirm (no extra code should be required beyond dependencies + minimal config, since Spring
> Boot auto-instruments the servlet filter chain and `RestClient`) that: (a) each inbound
> Gateway request gets a trace ID, (b) the outbound call to the Account Service carries a
> `traceparent` header, (c) both services' log lines for that request include the same
> `traceId`. Write an integration test using WireMock (capturing the outbound request's
> headers) plus a Logback `ListAppender` attached in the test (capturing the Gateway's own log
> output) that asserts the `traceId` extracted from the captured `traceparent` header matches
> the `traceId` field in the captured log line for the same request.

**Req refs:** #3, #8 (trace propagation test requirement), IMPLEMENTATION_PLAN.md §9
**Coverage gate:** no new production logic beyond config — gate stays at prior threshold
**Definition of done:** the trace-ID-match assertion in the test is a real equality check
between two independently captured values, not just "a traceId field exists somewhere."

---

## Step 9 — Structured logging

**Prompt:**
> Add `logstash-logback-encoder`; replace the default console appender in
> `logback-spring.xml` (both services) with `LogstashEncoder`, adding a constant `service`
> field (`event-gateway` / `account-service`) and confirming `traceId`/`spanId` from Step 8
> appear automatically via MDC. Manually run both services and visually confirm log lines are
> valid single-line JSON containing `timestamp`, `level`, `service`, `traceId`, `spanId`,
> `logger`, `message`. Add a lightweight test that parses a captured log line as JSON and
> asserts the required fields are present.

**Req refs:** #4 (structured logging), IMPLEMENTATION_PLAN.md §10
**Coverage gate:** stays at prior threshold
**Definition of done:** log output is machine-parseable JSON, confirmed by both manual
inspection and the parsing test.

---

## Step 10 — Metrics and health endpoints

**Prompt:**
> Add the metrics from IMPLEMENTATION_PLAN.md §11 via Micrometer (`gateway.events.received`
> tagged by type/outcome at minimum; the rest are optional but recommended since they make
> Step 7's resiliency behavior observable). Remap Actuator so `GET /health` is served at the
> literal path `/health` on both services (not `/actuator/health`), reporting H2 connectivity.
> Add the Gateway's non-fatal `accountService` health component (§11) — reachable/unreachable
> status reported, but never flips the Gateway's own overall status to `DOWN`. Write tests:
> `/health` returns `200` with a `status: UP` body on both services under normal conditions;
> a test that stops/mocks the Account Service and confirms the Gateway's `/health` still
> returns `200` overall while the `accountService` sub-component reports the outage; a test
> that asserts the `gateway.events.received` counter increments after a `POST /events` call.

**Req refs:** #4 (health checks + custom metric), #6 (health must reflect degradation without
itself failing), IMPLEMENTATION_PLAN.md §11
**Coverage gate:** ≥80% module-wide, both modules
**Definition of done:** `/health` path is literally `/health`, not `/actuator/health`, on both
running services (verify with `curl`).

---

## Step 11 — Dockerization

**Prompt:**
> Add a `Dockerfile` to each module (multi-stage: Maven build stage → slim JRE 21 runtime
> stage running the packaged jar). Add a root `docker-compose.yml` per
> IMPLEMENTATION_PLAN.md §14: both services on one bridge network, `account-service` healthcheck
> hitting `/health`, `event-gateway` with `depends_on: account-service: condition:
> service_healthy` and `ACCOUNT_SERVICE_BASE_URL=http://account-service:8081` overriding the
> local default. Run `docker compose up --build` and manually smoke-test the full flow (submit
> an event, check balance, check health) against the composed stack, then stop one container
> and confirm the Gateway degrades per IMPLEMENTATION_PLAN.md §13 while running in Compose too
> (not just in unit tests).

**Req refs:** #7, IMPLEMENTATION_PLAN.md §14
**Coverage gate:** n/a (infra, not application code)
**Definition of done:** `docker compose up --build` brings up both services healthy; killing
the `account-service` container and re-running the smoke test reproduces the same
degradation behavior verified in Step 7/Step 10's tests, confirming the resiliency
configuration isn't accidentally environment-specific.

---

## Step 12 — Coverage enforcement, full test sweep, README/plan reconciliation

**Prompt:**
> Add the JaCoCo Maven plugin to both module POMs with `prepare-agent` bound to `test` and
> `check` (minimum 80% line coverage, per IMPLEMENTATION_PLAN.md §15) bound to `verify`. Run
> `mvn verify` at the root and fix any module under threshold — prefer adding a missing test
> over deleting/weakening the check. Re-read the full requirement list (#1–#9) against the
> current codebase and produce a short gap list if anything is missing; close any gaps found.
> Update README.md if any actual behavior diverged from what it currently documents (ports,
> config keys, endpoint shapes, health path, etc.) — the README must describe the system as
> it actually behaves, not as originally planned, if the two have diverged.

**Req refs:** #8, #9, and a final pass over #1–#7
**Coverage gate:** ≥80% line coverage, both modules, enforced (build fails under threshold)
**Definition of done:** `mvn verify` green at the root; README accurately describes the
as-built system; every row in IMPLEMENTATION_PLAN.md §2's traceability matrix has a
corresponding passing test.

---

## Quick reference: requirement → step

| Requirement | Steps |
|---|---|
| 1. Core functionality (idempotency, out-of-order, balance, validation) | 1, 2, 3, 4, 5, 6 |
| 2. Service separation | 0, 6 |
| 3. Distributed tracing | 8 |
| 4. Observability (logging, health, metrics) | 9, 10 |
| 5. Resiliency | 7 |
| 6. Graceful degradation | 5, 7, 10, 11 |
| 7. Docker Compose | 11 |
| 8. Automated tests | every step (each has its own test scope) |
| 9. README | 12 (final reconciliation); drafted upfront in README.md |
