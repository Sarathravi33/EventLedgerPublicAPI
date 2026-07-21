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
