# FIXES.md

A running log of bugs found (and fixed) and corrections made while implementing the steps in
[Prompt.md](Prompt.md). Design decisions and assumptions belong in
[IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) §17 / [README.md](README.md); this file is
specifically for things that were *wrong* at some point during the build and had to be
corrected — so a reviewer can see what broke, how it was caught, and how it was resolved,
without having to dig through commit history. One section per `Prompt.md` step; appended to as
each subsequent step completes.

---

## Step 0 — Repository scaffolding

**Stray `.gitignore` content.** The repo's `.gitignore` (present before implementation
started) contained only `Prompt.md` and `.gitIgnore` as entries — meaning `Prompt.md` itself
would have been excluded from version control. Replaced with the intended standard Maven/IDE
ignore patterns (`target/`, `*.class`, `*.log`, `.idea/`, `*.iml`, `.vscode/`, `HELP.md`).

**Port 8080 conflict on the dev machine.** While smoke-testing that both services boot
(`mvn spring-boot:run`), the Event Gateway failed to start with "Port 8080 was already in
use" — an unrelated local Apache `httpd` process (PID 4932) was already bound to 8080 on this
machine, not a bug in the project. Verified the Gateway boots correctly on an override port
instead, and later (see below) the default was changed to 8082 project-wide so local runs
don't depend on 8080 being free.

## Step 2 — Account Service business logic (atomic, idempotent apply)

**Concurrency bug: lost updates from a lazy-account-creation race.** The first implementation
of "create the `Account` row lazily on first transaction" was:
```java
accountRepository.findById(accountId).orElseGet(() -> accountRepository.save(new Account(accountId, now)));
```
`AccountServiceConcurrencyTest` (two threads applying 25 transactions each to a brand-new
account) caught this immediately: both threads saw `findById` return empty, both attempted to
insert the same `account_id`, one insert won and the other threw a unique-constraint violation
(`23505`) that propagated out of `applyTransaction` — silently dropping that thread's
transaction entirely. Test failure: `expected: 500.00 but was: 250.0000` (only one thread's 25
transactions were actually applied).

Fix: extracted account creation into `AccountProvisioningService.createAccount(...)`, running in
its own `@Transactional(propagation = REQUIRES_NEW)` sub-transaction. `AccountService` now
checks `existsById` first, and if the sub-transaction's insert loses the creation race, it
catches the resulting `DataIntegrityViolationException` and treats it as success (the account
exists either way) — instead of letting a lost race fail the whole `applyTransaction` call.
Because the insert runs in its own transaction, a lost race only rolls back that small
sub-transaction, not the caller's in-progress work. Re-ran `AccountServiceConcurrencyTest` after
the fix: passes consistently, with the constraint-violation log line now expected/harmless
(one thread's creation attempt legitimately loses the race every time, by design).

**Missing flush/clear on the atomic balance update.** The `@Modifying` bulk update query
(`UPDATE Account a SET a.balance = a.balance + :signedAmount ...`) was initially written without
`flushAutomatically`/`clearAutomatically`. Without these, a same-transaction account creation
immediately preceding the update is not guaranteed to be flushed to the database before the
bulk update runs, and a subsequent `findById` read in the same transaction could return a stale
cached entity instead of the post-update row. Corrected to
`@Modifying(flushAutomatically = true, clearAutomatically = true)` before this caused an
observable failure — caught during implementation review, not by a failing test, so it's
recorded here for visibility rather than left implicit in the diff.

## Step 3 — Account Service REST API

**Contract/domain mismatch: `currency` had no home.** `IMPLEMENTATION_PLAN.md` §6.2 originally
documented the Account Service's `POST /accounts/{accountId}/transactions` request as including
`currency`, but the `Transaction` entity built in Step 1 has no currency column, and nothing in
the Account Service's own responsibilities (computing an order-independent sum) ever needs it.
Caught while wiring the real request DTO in this step, before writing any code around a field
that would have been silently ignored. Resolved by dropping `currency` from the Account
Service's *internal* contract entirely (it stays on the Gateway's public event payload and
`events` table, where it's actually required) rather than adding an unused column or a
misleading single-currency-per-account field. `IMPLEMENTATION_PLAN.md` §6.2 updated to match,
with the reasoning recorded inline there.

**Idempotent-replay status code needed a signal the service didn't expose.** To return `201`
for a newly-applied transaction and `200` for a replay of an already-applied `eventId` (as
`IMPLEMENTATION_PLAN.md` §6.1 specifies for the Gateway, and the same distinction is useful
here), the controller needed to know which case it was in. `TransactionApplicationResult` was
extended with a `replayed` boolean (`AccountService` already had both branches internally; it
just wasn't surfacing which one ran). Existing Step 2 tests were updated to assert `replayed()`
explicitly rather than just ignoring the new field.

## Step 6 — Event Gateway REST API + real Account Service client

**`Prompt.md` gap: the balance-proxy endpoint was never scheduled.** `IMPLEMENTATION_PLAN.md`
§6.1/§13 and `README.md` both document a Gateway-side `GET /accounts/{accountId}/balance`
pass-through (needed to make the brief's balance-query graceful-degradation requirement
reachable from a public client at all), but none of `Prompt.md`'s 13 steps actually scheduled
building it — Step 6 only listed the three `/events` endpoints. Caught while implementing this
step's controllers, before moving on. Resolved by adding `AccountProxyController` (and the
corresponding `AccountServiceClient.getBalance(...)` method, `AccountNotFoundException` for a
genuine 404 vs. `AccountServiceCallException` for a downstream-unreachable 503) as part of this
step's scope, since it belongs naturally alongside the other controllers being wired up here
and Step 7's resiliency wrapping needs to cover it too.

**Build bug: the executable jar couldn't be used as a dependency.** To let the new full
integration test boot a real Account Service Spring context in the same JVM (per this step's
own instructions), `account-service` was added as a test-scope dependency of `event-gateway`.
The build failed with "package com.eventledger.account does not exist" despite the jar
containing the class — `spring-boot-maven-plugin`'s `repackage` goal, with no classifier
configured, replaces the plain jar with the executable Spring Boot fat jar
(`BOOT-INF/classes/...` layout), which cannot be resolved as a normal classpath dependency by
another module. Fixed by adding `<classifier>exec</classifier>` to `account-service/pom.xml`'s
`spring-boot-maven-plugin` config, which keeps the plain jar as the resolvable main artifact
and moves the executable jar to `account-service-exec.jar`. `README.md`'s manual-run
instructions were updated to reference the new `-exec` jar name.

**Classpath collision: both modules ship `application.yml` at the same path.** Once
account-service was on event-gateway's test classpath (previous fix), the integration test's
manually-started Account Service context came up listening on port `8082` against
`jdbc:h2:mem:gatewaydb` — the Gateway's own settings, not its own `8081`/`accountdb`. Caught by
inspecting the context's own startup log (`Tomcat initialized with port 8082`), not by a failing
assertion — the test still passed by coincidence, since the two services' entities use
non-overlapping table names, so no data actually collided in the shared H2 instance. Root cause:
both modules' `application.yml` resolve to the identical classpath resource path
(`classpath:/application.yml`), and classpath resource lookup returns whichever the classloader
finds first; separately, the original fix attempt used
`SpringApplicationBuilder.properties("server.port=0", ...)`, which sets low-precedence "default
properties" that any `application.yml` easily overrides — so even the intended override silently
did nothing. Fixed by switching to `SpringApplicationBuilder.run("--server.port=0",
"--spring.datasource.url=...", ...)` — command-line-style args, which take precedence over any
classpath `application.yml` — and pointing the test's Account Service context at a distinctly
named `accountdb-it` in-memory database. Re-verified via the startup log: the context now binds
a random port against `accountdb-it`, fully isolated from the Gateway's own `gatewaydb`.

## Step 7 — Resiliency (circuit breaker, timeout, bounded retry)

**Plan gap: `minimum-number-of-calls` would have silently defeated the sliding window.**
While writing the WireMock test asserting the circuit opens after enough failures, tracing
through exactly *when* Resilience4j evaluates the failure rate surfaced that its own default
for `minimum-number-of-calls` is 100, independent of `sliding-window-size` — so the original
`IMPLEMENTATION_PLAN.md` §12 sketch (`sliding-window-size: 10` with no `minimum-number-of-calls`)
would never have opened the breaker until 100 calls were recorded, regardless of how many of
the most recent 10 failed. Caught by reasoning through the test design before writing any code
that would have exhibited it at runtime. Fixed by explicitly setting
`minimum-number-of-calls: 10` to match the window size, in both `application.yml` and the
updated `IMPLEMENTATION_PLAN.md` §12 snippet.

**Design refinement: `AccountNotFoundException` needed to be excluded from Retry/CircuitBreaker.**
A 404 from the Account Service (a genuinely nonexistent account) is a business outcome, not an
infrastructure failure — retrying it wastes the retry budget on an answer that will never
change, and counting it against the circuit breaker's failure rate would open the breaker for
public clients innocently querying unknown account IDs, not for actual Account Service health
problems. Added `ignore-exceptions: [com.eventledger.gateway.client.AccountNotFoundException]`
to both the `retry` and `circuitbreaker` instances before this could manifest as a real
incident (a burst of "balance for an account that was never created" lookups tripping the
breaker for everyone else).

**Build bug: `wiremock:3.3.1` fails at server startup with `IncompatibleClassChangeError`.**
The first resiliency test run failed immediately on WireMock server startup:
`class org.eclipse.jetty.http2.server.HttpChannelOverHTTP2 has interface
org.eclipse.jetty.server.HttpChannel as super class`. `mvn dependency:tree` showed the plain
`org.wiremock:wiremock:3.3.1` artifact transitively pulling **both** Jetty 12.0.8
(`jetty-server`, `jetty-io`) and Jetty 11.0.18 (`jetty-servlet`, `jetty-servlets`,
`jetty-webapp`, `http2-server`) — a packaging bug in that release. Fixed by switching to
`org.wiremock:wiremock-standalone:3.3.1`, which shades its dependencies and avoids the
transitive conflict entirely; re-ran the full resiliency suite afterward to confirm.

## Step 8 — Distributed tracing

This step involved the longest debugging trail so far — the "no extra code should be required"
assumption in the original plan turned out to be wrong for this exact dependency combination,
and it took several rounds of empirical isolation to find out why.

**Plan gap: no application-level log statements existed yet.** Neither service had a single
`log.info(...)` call anywhere before this step — Steps 1–7 only produced framework-level log
noise. `Prompt.md`'s Step 9 prompt ("confirm log lines are valid JSON containing... message")
implicitly assumes meaningful log lines already exist by then; nothing in Steps 1–8 had actually
scheduled adding them. Caught while designing this step's trace-propagation test, which needs a
real application log line to assert a traceId against. Fixed by adding `log.info`/`log.warn`
calls at the meaningful lifecycle points in both `EventService` (Gateway) and `AccountService`
(Account Service) — received/duplicate/retry/applied/failed — which also directly serves
requirement #3 ("both services must log the trace ID") and requirement #4 (structured logging
needs something to structure).

**Bug: the `RestClient` bean bypassed Spring's own instrumented builder.** `AccountServiceClientConfig`
originally called the static `RestClient.builder()` factory method rather than injecting the
`RestClient.Builder` bean Spring Boot auto-configures (and decorates with observation/tracing
customizers). Fixed by taking `RestClient.Builder` as a constructor/method parameter instead.
This was necessary but, as the next two findings show, not sufficient on its own.

**Investigated and ruled out: thread-hop losing the trace context.** `ResilientAccountServiceClient`
runs the real HTTP call inside a `CompletableFuture` on a separate executor thread (required by
`TimeLimiter`), and thread-locals don't cross that boundary automatically. This is a real
concern in general — fixed by capturing `Context.current()` (OpenTelemetry's own context) before
the hop and re-activating it (`context.makeCurrent()`) inside the async task — but it was **not
the actual root cause** of the missing `traceparent` header: a diagnostic call to the raw
(unwrapped) client on the *same* thread, wrapped in a manually-started `Observation`, still
produced no header. This ruled the thread-hop theory out and pointed at something more
fundamental. (The fix is kept regardless — it is independently correct and necessary once the
real root cause below is also fixed, since the interceptor still needs a current span to read.)

**Root cause: the auto-configured Micrometer `Propagator` bean had zero registered header
fields.** Every bean Spring Boot's tracing auto-configuration is supposed to create was
genuinely present — `ObservationRestClientCustomizer`, `PropagatingSenderTracingObservationHandler`,
a `Propagator` bean, `DefaultTracingObservationHandler`, all confirmed by listing bean
definitions in a diagnostic test. But calling `propagator.fields()` directly on the injected
`Propagator` returned an empty list — i.e., it had no W3C (or any) format configured, so
`propagator.inject(...)` had nothing to set regardless of how correct the surrounding wiring
was. Tried the documented property `management.tracing.propagation.type: W3C` on both services;
confirmed via the same diagnostic that it had **no effect** on this object — either the property
doesn't apply the way expected on this exact Spring Boot 3.2.5 / Micrometer / OTel version
combination, or something else about the auto-configuration path used here (Boot 3.2's
`OpenTelemetryPropagationConfigurations` offers both a `PropagationWithBaggage` and a
`NoPropagation` variant — both existed as beans in the context, and it's not fully clear from
outside which one actually won). Removed the ineffective property from both `application.yml`s
rather than leave dead config with a misleading comment.

**Fix: bypass the Micrometer/Spring Boot layer for this one interceptor.** Added
`TracingPropagationInterceptor`, registered on the Gateway's `RestClient`, which reads
`io.opentelemetry.context.Context.current()` directly and injects the header via
`io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator.getInstance()` — OpenTelemetry's
own, most fundamental propagation API, with no dependency on whichever Spring Boot/Micrometer
auto-configuration path did or didn't wire correctly. Verified working via `TracePropagationTest`
(traceId extracted from the captured `traceparent` header matches the Gateway's own captured log
output) and, more strongly, via `CrossServiceTracePropagationTest` (boots a real Account Service
in the same JVM; the identical traceId appears in *both* services' logs for one request — e.g.
`15dedae53ae6b5b7595fc90cb9437657` showed up in both the Gateway's "Received event" log line and
the Account Service's "Applied CREDIT transaction" log line, with different span IDs as
expected).

## Step 9 — Structured logging

**Redundant `serviceName` field leaking into every log line.** `logback-spring.xml` declares
`<springProperty scope="context" name="serviceName" source="spring.application.name"/>` purely
as a substitution variable for the `customFields` JSON (`{"service":"${serviceName}"}`).
Caught immediately during the manual-run visual check this step's `Prompt.md` instructions
explicitly ask for: real log output showed both `"service":"account-service"` (intentional) and
a separate `"serviceName":"account-service"` (unintentional) on every line —
`LogstashEncoder` emits all Logback *context* properties as top-level fields by default, and
`scope="context"` puts the variable there for exactly that reason (it needs to be readable at
config-parse time). Fixed by adding `<includeContext>false</includeContext>` to both services'
encoder config, then re-verified by re-running both services and confirming the field was
gone. Coverage tests wouldn't have caught this — they construct the encoder directly rather
than parsing real Logback context properties — which is why the manual-run check the step
calls for still matters even with an automated test in place.

## Step 10 — Metrics and health endpoints

No real bugs this step — the one thing worth recording is a plan correction caught before
writing unnecessary code. `IMPLEMENTATION_PLAN.md` §11 originally sketched a custom
`gateway.circuit_breaker.state` gauge "via Resilience4j Micrometer binder." While implementing
it, a quick `curl http://localhost:8082/metrics` against a running instance (after just adding
`resilience4j-spring-boot3` back in Step 7 — no new code) showed `resilience4j.circuitbreaker.state`,
`.calls`, `.failure.rate`, `.not.permitted.calls`, and the retry/time-limiter equivalents were
*already* populated. `resilience4j-spring-boot3` auto-binds all of these to any `MeterRegistry`
present on the classpath — writing a custom gauge would have been a redundant reimplementation.
Updated §11 to reference the real metric name instead. All five required/recommended metrics,
plus both health scenarios (Account Service reachable and unreachable), were verified against
real running services via `curl`, not just the automated tests.

## Step 11 — Dockerization

**Design decision, made proactively: each Dockerfile is self-contained despite the multi-module
reactor.** `event-gateway` declares a test-scope dependency on `account-service` (Step 6), which
means a naive `event-gateway/Dockerfile` that only copies `event-gateway/src` would fail —
Maven resolves that test-scope dependency during the build regardless of whether tests actually
run, and with no `account-service` artifact reachable (fresh container, no remote repo hosting
a private artifact), the build breaks unless `account-service` is buildable within the *same*
reactor invocation. Rather than rely on `docker compose build`'s (unordered, effectively
parallel) build sequence to have already produced an `account-service` image or published
artifact, `event-gateway/Dockerfile` copies both modules' full sources and uses `mvn -pl
event-gateway -am package` — Maven builds `account-service` first within this one self-contained
build stage, and only `event-gateway`'s jar is copied into the final runtime image. Slightly
heavier (rebuilds `account-service` as a byproduct) but correct regardless of build ordering
assumptions. `-Dmaven.test.skip=true` (not just `-DskipTests`) is used in both Dockerfiles so
test *compilation* is skipped too, not just execution.

**Verified live in Docker Compose, not just in unit tests**: full happy-path flow (submit →
201 → balance reflects it), then `docker compose stop account-service` while `event-gateway`
kept running — `POST /events` returned `503` with the event still recorded (status `FAILED`,
retrievable), `GET /events/{id}` and `GET /events?account=` were completely unaffected, the
balance proxy returned `503`, and `/health` stayed `UP` overall with
`accountService.reachable: false` — reproducing exactly the behavior the Step 7/10 unit tests
already proved, this time with two real, separately-orchestrated Docker containers rather than
mocks.

**Observed (not a bug): balance came back `-10.00` instead of the expected `65.00` after
restarting Account Service and retrying the failed event.** `docker compose stop`/`start`
restarts the Account Service's JVM process entirely (confirmed via its logs — a full fresh
Spring Boot startup sequence), and H2 in-memory data does not survive a process restart — this
is the documented consequence of choosing in-memory H2 (`IMPLEMENTATION_PLAN.md` §17, README
assumptions), not a resiliency defect. The retried `DEBIT` event applied correctly against a
freshly-recreated (previously nonexistent, balance-zero) account, landing at exactly
`0 - 10 = -10`. Worth recording here specifically because a from-scratch container restart
during manual testing is a harsher failure mode than the "still running but erroring/slow"
scenario the WireMock-based tests simulate, and the resulting number could otherwise look like
a bug to someone reproducing this smoke test without this context.

## Step 12 — Coverage enforcement, full test sweep, README/plan reconciliation

**JaCoCo enforcement passed on the first run — no code changes needed.** Added the plugin
(`prepare-agent`/`report`/`check`, 80% line minimum, bound to `test`/`verify`) to both module
POMs. Both modules cleared the threshold immediately: account-service 92.1%, event-gateway
95.3%, on the strength of the test suite already built across Steps 1–11 — no coverage-driven
scrambling was needed at the end.

**One small real gap closed anyway: `MetadataConverter`'s error-handling branches were
untested.** Checking JaCoCo's per-class breakdown (not just the passing aggregate) turned up
`MetadataConverter` at 69.2% — its two `catch` blocks (wrapping JSON (de)serialization
failures as `IllegalArgumentException`) had never been exercised. Added
`MetadataConverterTest`, including a case that feeds malformed JSON through
`convertToEntityAttribute` to hit the deserialization-failure branch. (The
serialization-failure branch in `convertToDatabaseColumn` was left untested — triggering it
would require a deliberately non-serializable object, which is a contrived, near-unreachable
case in practice; chased the branch that's actually plausible in production, not the one that
isn't.) Final numbers after this addition: **account-service 92.9%, event-gateway 96.1%**,
66 tests total (18 + 48).

**README reconciliation found several real staleness issues, not just cosmetic ones:**
- The "Status" callout at the top still described the system as in-progress ("target design...
  as implementation proceeds") — stale now that all 12 steps are complete.
- Prerequisites mentioned "the included `mvnw` wrapper, once added" — that wrapper was never
  actually added at any point; the parenthetical was a stale forward-looking promise. Removed
  rather than fulfilled, since plain `mvn` already satisfies "runnable via a standard command."
- **`mvn test` vs `mvn verify` was described inaccurately**: README claimed `mvn test` ran only
  "unit + slice tests" and `mvn verify` additionally ran "WireMock resiliency/tracing tests,
  integration tests." This is wrong — no Failsafe plugin or separate IT-suffix convention was
  ever configured, so Surefire's default `test` phase runs the *entire* suite (WireMock,
  tracing, integration tests included) under plain `mvn test` already; `mvn verify` only adds
  the JaCoCo `check` goal on top of the same test run. Corrected the description.
- `IMPLEMENTATION_PLAN.md` §18's configuration table claimed JSON logging was configured via
  `logging.pattern.*` properties — it isn't; it's a separate `logback-spring.xml` per service
  configuring `LogstashEncoder` directly (Step 9), since the field-name remapping and
  custom-field injection used there aren't expressible through `application.yml` alone.
  Corrected, and added the `management.endpoint.health.show-details` and
  `management.tracing.sampling.probability` keys that were missing from that table despite
  being real, load-bearing config added in Steps 8 and 10.
- `IMPLEMENTATION_PLAN.md` §2's requirement traceability matrix still listed the *planned*
  test class names from before implementation (`EventIdempotencyTest`, `ResiliencyIT`,
  `HealthEndpointIT`, etc.) — none of which are the actual class names that got built
  (`EventServiceTest`, `AccountServiceResiliencyTest`, `HealthAndMetricsTest`, ...). Updated
  every row to reference the real test classes, so the matrix is now a genuinely useful map
  from requirement to passing test rather than a stale planning artifact.

**Final requirement gap-check (brief requirements #1–#9, re-read against the as-built code):
no gaps found.** Idempotency, out-of-order tolerance, balance computation, and validation are
each covered by multiple tests across both modules; service separation holds with the one
documented, non-runtime-affecting exception (§17); tracing, structured logging, health, and
metrics are all implemented and verified against real running services, not just mocks;
exactly one resiliency pattern (circuit breaker + timeout, with retry layered in) is
implemented and its degradation behavior is proven both in unit tests and in real Docker
Compose (Step 11); Docker Compose is complete and manually verified; the automated test suite
covers every category the brief asks for, including a full Gateway → Account Service
integration test, and runs via the standard `mvn test`/`mvn verify` commands; and this README
has now been reconciled against the actual, final behavior of the system.

## Post-Step-12 — Swagger/OpenAPI documentation + Postman collection

**Adding springdoc-openapi caused a real, reproducible test regression.**
`AccountServiceResiliencyTest.postEvents_whenCircuitIsOpen_returns503NotHangingOrErroring`
started failing consistently (not flaking) at ~1.15-1.2s against a `<1000ms` bound, immediately
after adding `springdoc-openapi-starter-webmvc-ui` to `event-gateway` — reproduced in isolation,
not just under full-suite load. Root cause: this test's timed assertion happened to run on the
*first* real HTTP request dispatched into that test class's Spring context, and that one-time
`DispatcherServlet`/`HandlerMapping` initialization cost (larger now that springdoc registers
additional request mappings for `/v3/api-docs` and the Swagger UI) was being counted as part of
"how fast does an open circuit fail" — a timing assertion inadvertently measuring cold-start
overhead instead of the actual behavior under test. Fixed by issuing one untimed warm-up request
(`GET /health`) before starting the timer, so the measured window reflects only the real request.
Re-ran in isolation and under the full suite afterward — passes consistently both ways.

## Post-Step-12 — README manual-run instructions were missing a required install step

**Writing the step-by-step "How to Run" guide surfaced a real gap in the existing manual-run
instructions.** The old "Running Manually" section jumped straight to `mvn -pl event-gateway
spring-boot:run` without ever installing `account-service` first. Root cause: `event-gateway`
declares a **test-scope** Maven dependency on `account-service`'s artifact (see Step 6), and
`spring-boot:run` forks the Maven lifecycle through `test-compile` — not just `compile` — so even
running (not just building) `event-gateway` on its own requires `account-service` to already be
resolvable from the local `~/.m2` repository. On a fresh checkout, or any machine that hasn't built
the reactor at least once, following the old instructions literally would fail.

Verified empirically rather than assumed: moved the already-built `account-service` artifact aside
in the local Maven repository, then ran `mvn -pl event-gateway spring-boot:run` from the repo
root — it failed immediately with

```
[WARNING] The POM for com.eventledger:account-service:jar:0.1.0-SNAPSHOT is missing, no dependency information available
[ERROR] Failed to execute goal on project event-gateway: Could not resolve dependencies for project com.eventledger:event-gateway:jar:0.1.0-SNAPSHOT: The following artifacts could not be resolved: com.eventledger:account-service:jar:0.1.0-SNAPSHOT (absent)
```

confirming the fork-through-`test-compile` behavior. Restored the artifact, then re-ran the
corrected sequence — `mvn clean install -DskipTests` from the repo root, followed by
`mvn -pl event-gateway spring-boot:run` — and this time it started cleanly
(`Started EventGatewayApplication in 6.526 seconds`, Tomcat on port 8082).

**Fix:** README.md's manual-run instructions (Option B) now require `mvn clean install -DskipTests`
from the repository root as an explicit, called-out step before running either service
individually, with the reason (the test-scope dependency) stated inline so it isn't skipped as
boilerplate.

## Cross-cutting: Event Gateway default port changed 8080 → 8082

Following on from the Step 0 port conflict above, the Event Gateway's default port was changed
from `8080` to `8082` everywhere it's referenced — `event-gateway/src/main/resources/application.yml`,
and every mention in `README.md`, `IMPLEMENTATION_PLAN.md`, and `Prompt.md` — so a local run
doesn't depend on port 8080 being free on the developer's machine. Verified by booting the
Gateway and confirming Tomcat starts on 8082. Account Service's port (`8081`) is unchanged.

---

<!--
Add one section per completed Prompt.md step, in step order, following the pattern above:
what was wrong, how it was caught (test failure / manual run / review), what changed, how it
was re-verified. Skip a step entirely if nothing needed fixing during it.
-->
