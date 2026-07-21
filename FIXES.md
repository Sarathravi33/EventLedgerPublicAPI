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
