# Project review — cqrs-bank-account

Date: 2026-11-11 · Reviewed at commit `21aff51`

Every source, test and config file was read, and `bash ./gradlew test` was run. 10 tests passed. The 5 `MongoPersistenceTests` were skipped because Docker wasn't running, so the transaction and idempotency code wasn't tested in that run.

## Overall

This is a good sample. The command side is properly event-sourced. Events are versioned and protected by a unique `(aggregateIdentifier, version)` index plus an expected-version check. Events and the idempotency receipt are written in one MongoDB transaction. The read model records which event version it has applied, so it skips repeats and gaps. Money uses `BigDecimal` / `Decimal128`, never floats. The README is clear about the read model lagging behind writes.

The main problems are how concurrent writes fail, how domain errors map to HTTP status codes, how replaying events is coupled to today's business rules, and a lot of unused code.

## High priority

### 1. Concurrent writes probably return 500 instead of 409 or the original result

See `MongoEventStore.kt:35-81`.

> **Status: done (2026-11-25, uncommitted).** Confirmed first on a real replica set: 7 of 8 concurrent deposits failed with `DataIntegrityViolationException` (WriteConflict 112), and a conflict at commit time surfaces as `TransactionSystemException`. `MongoEventStore` now treats any MongoDB write conflict, duplicate key, or transient/unknown-commit error as a conflict; `AccountCommandHandler` retries the whole command with jittered back-off up to `app.commands.max-attempts` (default 5), then returns 409. Tests: `MongoPersistenceTests` (8 concurrent deposits; 8 concurrent same-key opens) and `AccountCommandHandlerTests`.

- Inside a MongoDB transaction, two writers inserting the same key usually get a `WriteConflict` error (code 112, marked `TransientTransactionError`), not `E11000`. Spring Data doesn't turn that into `DuplicateKeyException`. So two concurrent deposits on one account, or a client retrying with the same `Idempotency-Key` while the first request is still running, will likely miss the `catch` block and `ApiExceptionHandler`, and come back as 500.
- `TransactionTemplate` also doesn't retry transient transaction errors.
- The fix: catch the transient case and retry the whole command (reload, rehydrate, reapply), or at least return 409. After that, read the receipt again so an idempotent replay returns the original result.
- Add a Testcontainers test that fires two commands in parallel with a `CountDownLatch`. None of the current tests do this. This behaviour is inferred from how MongoDB transactions work, so that test is how to confirm it.

### 2. Replaying events re-checks business rules

See `AccountAggregate.kt:52-86`.

> **Status: done (2026-11-25, uncommitted).** `apply()` checks only the aggregate ID, version sequence, and a single opening event first. Tests: `AccountAggregateTests`.

- `apply()` re-runs `requirePositive`, `balance >= amount`, the not-blank checks and so on. Events are facts that already happened.
- If a rule changes later (a minimum balance, an overdraft limit, a stricter name rule), replaying older events will throw, and those accounts can never be loaded again.
- Keep the checks in the command methods (`deposit`, `withdraw`, `close`, `open`). In `apply()`, only change state, plus the aggregate-ID and version checks.

### 3. Mapping `IllegalStateException`/`IllegalArgumentException` to 409/400 everywhere is too broad

See `ApiExceptionHandler.kt:15-21`.

> **Status: done (2026-11-25, uncommitted).** Sealed `DomainException` hierarchy in `core/exceptions/Exceptions.kt`; `ApiExceptionHandler` returns `ProblemDetail` and extends `ResponseEntityExceptionHandler`; unexpected exceptions return a generic 500. A reused idempotency key returns 422 (per the IETF Idempotency-Key draft), not 409. Tests: `ApiExceptionHandlerTests`.

- Every `check`, `require` and `requireNotNull` in the codebase is caught by this, along with any `IllegalStateException` thrown by Spring or the MongoDB driver. Infrastructure failures then come back as 409/400, and internal messages are sent to the client. Examples: "The command channel did not accept the command." and messages containing version numbers.
- Instead, create a sealed `DomainException` hierarchy (`InsufficientFunds`, `AccountClosed`, `ConcurrencyConflict`, `IdempotencyKeyReused`) and map only those. Return `ProblemDetail` (RFC 9457), which Spring supports out of the box, rather than `BaseResponse`.
- Many APIs use 422, not 409, for "insufficient funds". Use 409 for real conflicts: concurrency and a reused key.

### 4. Results are passed back by changing the command object

See `OpenAccountController.kt:24-31` and `AccountCommandHandler.kt:27-32, 47, 54`.

> **Status: done for commands (2026-11-25, uncommitted).** Commands are immutable data classes with an `idempotencyKey`; the handler returns a `CommandResult` as a Spring Integration reply (`MessagingTemplate.sendAndReceive`). `RestoreReadDbCommand` was deleted (unreachable, and a no-op on an existing read model; see item 6). Events still have `var version`/`aggregateId`, since the aggregate and event store assign them.

- The controller reads `command.accountId` after `send()`. This only works because `DirectChannel` runs the handler on the same thread. Switching to an executor or queue channel would break it with no error.
- `BaseCommand.id`, `BaseEvent.version` and `BaseEvent.aggregateId` are all `var` for the same reason.
- Use a `@MessagingGateway` with a reply (or have `handle` return a result) so commands and events can be immutable.

## Medium priority (CQRS / event-sourcing design)

### 5. The projection worker doesn't scale

See `AccountProjectionWorker.kt:22-40`.

> **Status: done (2026-11-25, uncommitted), except leader election.** The worker follows a MongoDB change stream on `eventStore` and stores its resume token in `projectionCheckpoints`. A global counter document was rejected because every write would contend on it, and a timestamp cursor because it can skip events that commit out of order. Each change triggers a catch-up of that account from its stored version. Failed accounts go to `projectionRetries` and are retried on `app.projection.retry-interval`. With no checkpoint, or when the oplog no longer covers it, the worker opens the stream and then catches up every account. There is no leader lock (Spring Integration has no MongoDB lock registry); run the projection on one instance. Tests: `AccountProjectionWorkerTests`.

- Every second it runs `distinct` over the whole event store (the result has a 16 MB limit). Then, for each account, it reads the account and queries its events: about 2N+1 queries per tick, even when nothing has changed.
- Use one global checkpoint instead: a monotonically increasing global position or a timestamp-plus-ID cursor on `eventStore`, or a MongoDB change stream with a resume token. Store it in a `projectionCheckpoints` document.
- Every instance also runs the worker. The optimistic `version` guard prevents corruption but wastes work; add a leader lock (e.g. ShedLock) or partition the work.

### 6. `RestoreReadDbCommand` doesn't rebuild anything

See `AccountCommandHandler.kt:109-115`.

> **Status: done (2026-11-25, uncommitted).** The command was deleted while doing item 4. With item 5's checkpoint, a rebuild is: stop the application, drop `accounts`, `projectionCheckpoints` and `projectionRetries`, and start it again (documented in the README). There is no admin endpoint, since the API has no authentication.

- It replays events into a projection that skips versions it has already applied, so on an existing read model it does nothing. Nothing exposes the command either.
- It also lives in the command handler, but rebuilding a projection isn't a domain command.
- Either delete it, or make it an admin/projection operation that drops (or swaps out) the `accounts` collection and resets the checkpoint.

### 7. Stored events depend on Kotlin class names

> **Status: done (2026-11-25, uncommitted).** Each event has a `@TypeAlias` (`AccountOpened`, `FundsDeposited`, `FundsWithdrawn`, `AccountClosed`), and the classes are registered through a `MongoManagedTypes` bean so aliases resolve at startup. Events already stored with full class names still load (tested). Rewriting them to aliases is part of item 15. The evolution rules are documented on `AccountEvent`.

- `eventData: BaseEvent` is saved polymorphically with Spring Data's `_class` = full class name. Renaming a package or class makes old events impossible to read.
- `eventType` is stored but never used to deserialize.
- Register explicit type aliases (`@TypeAlias("AccountOpened.v1")`) or use your own serializer keyed on `eventType`, and plan for upcasting events to newer versions.

### 8. Use sealed hierarchies instead of `when … else -> throw`

> **Status: done (2026-11-25, uncommitted).** Added sealed `AccountCommand` and `AccountEvent`; the handler, aggregate and projection have no `else` branches. **Correction:** the `AccountOpenedEvent` branch in `AccountProjection` is reachable. It handles read-model rows saved before versions were stored (`version = -1`), and an existing test covers it, so it stays.

- `AccountCommandHandler.kt:36-42, 84-103`, `AccountAggregate.kt:56-83` and `AccountProjection.kt:52-68` all end with a runtime `else` branch.
- A `sealed interface AccountCommand` and a `sealed interface AccountEvent` would make the compiler check that every case is handled.
- `AccountProjection.kt:53-63` (`AccountOpenedEvent` handled as an update) can never run: a row only exists once version 0 has been applied.

### 9. Indexes

See `MongoEventStore.kt:120-133` and `EventModel.kt:8-12`.

> **Status: done (2026-11-25, uncommitted).** `MongoIndexInitializer` creates the indexes when the application starts, before the web server and projection worker; it recreates an index whose options changed. The lazy `ensureIndexes` and the unused `@CompoundIndex` were removed. Item 15 would replace this with versioned migrations.

- `@CompoundIndex` does nothing unless `auto-index-creation` is enabled, which is off by default. The unique index is created lazily on the first `saveEvents` call.
- Reads that run before any write (the projection worker, rebuilds) have no index until then.
- Create indexes at startup with an `ApplicationRunner` or a migration (e.g. Mongock). Also index `accounts.createdAt`, which `listAccounts` sorts on.

### 10. Idempotency semantics

> **Status: done (2026-11-25, uncommitted), with one correction.** Receipts gained `createdAt` and a TTL index (`app.idempotency.receipt-ttl`, default 24h). **Correction:** receipts for requests without a key are still written. Since item 1, a retry after an unknown commit result depends on them to avoid applying a command twice, and the TTL now limits their growth. Receipts written before `createdAt` existed never expire until item 15 backfills the field. Per-client keys wait for authentication. The decision not to cache failures is documented in the README.

- When there's no key, `AccountCommandHandler.kt:27` generates a random UUID and saves a receipt for every command. The receipts collection grows forever for no benefit, and receipts have no expiry. Only save a receipt when the client sent a key, and add a TTL index.
- Keys are global, not tied to a client, so one caller can collide with another's key. This matters once authentication is added.
- Failed commands save no receipt, so retrying after "insufficient funds" can succeed if a deposit happens in between. That may be fine, but write the decision down; Stripe-style APIs cache errors too.

### 15. Manage MongoDB schema changes with versioned migrations (Mongock / Flamingock)

Added 2026-11-25 at the user's request.

> **Status: done (2026-11-25, uncommitted).** Flamingock 1.4.5 Community Edition. Mongock was ruled out: its last release (5.5.1, March 2025) targets Spring Boot 3 and Spring Data 4 at most. The setup uses Flamingock's MongoDB sync-driver target system and audit store (its Spring Data target documents support only up to Spring Data 4.x), on the application's own `MongoClient` and database. Dependencies come from the `io.flamingock` Gradle plugin (`community()`, `springboot()`, `mongodb()`), as Flamingock's docs recommend; it also wires the annotation processor through kapt. Only the audit store (`flamingock-mongodb-sync-auditstore`) is declared by hand, because the plugin doesn't add one. `flamingock.management-mode=INITIALIZING_BEAN` runs migrations before the web server and projection worker; the docs' `runnerType` property does not exist in 1.4.5. Change units: `_0001__CreateIndexes`, `_0002__BackfillReceiptCreatedAt` and `_0003__UseEventTypeAliases`, all idempotent and non-transactional. `MongoIndexInitializer` and the `app.idempotency.receipt-ttl` property were removed: the TTL is now fixed by a change unit, and changing it means adding another. Verified with `MigrationTests` (applied once per database even when a second application starts, plus legacy-data upgrades) and by running the packaged jar against an empty database.

- Today, `MongoIndexInitializer` recreates indexes on every startup, and there is no record of which changes a database has had. Anything beyond indexes (backfilling a field, renaming a collection, upcasting stored events) has nowhere to live.
- Adopt a migration tool that applies ordered, versioned change units once per database and records them in an audit collection, with a lock so only one instance migrates at a time:
  - **Flamingock** is the successor to Mongock from the same team and is the one to evaluate first.
  - **Mongock** is the established option. Check that it supports Spring Boot 4 and the current MongoDB driver before choosing it.
- Initial change units:
  1. Create the event store's unique `(aggregateIdentifier, version)` index, `accounts.createdAt`, and the `commandReceipts.createdAt` TTL index (replacing `MongoIndexInitializer`).
  2. Backfill `createdAt` on receipts written before it existed, so the TTL index can expire them.
  3. Optionally rewrite stored events' `_class` from full class names to the `@TypeAlias` values (item 7).
- Decide how the TTL value (`app.idempotency.receipt-ttl`) is applied, because a change unit runs only once. Either keep a small startup check for that one index, or make each TTL change its own change unit.
- Run migrations before the web server and projection worker start, and fail startup if a migration fails.
- Testing: a Testcontainers test that runs the migrations on an empty database and again on an already-migrated one.

## REST API

### 11. Inconsistent, RPC-style paths

> **Status: done (2026-11-25, uncommitted).** All commands live in `AccountCommandController` under `/api/v1/accounts`: `POST /accounts` (201 with `Location`), and `POST /accounts/{id}/deposits`, `/withdrawals` and `/closure`. Closing uses `POST …/closure` rather than `DELETE`, because a closed account remains readable. Writes return the account's event `version` in the body and as an `ETag`, and `GET /accounts/{id}` returns the read model's version as its `ETag`. Receipts store the version so replays return it too. The old paths were removed (breaking). Tests: `AccountApiTests`.

- Opening and closing use verb-style paths: `POST /open-bank-account` and `DELETE /close-bank-account/{id}`.
- Deposits and withdrawals use resource paths: `/accounts/{id}/deposits`.
- Make them consistent: `POST /api/v1/accounts` (201 with a `Location` header), and `DELETE /api/v1/accounts/{id}` or `POST /accounts/{id}/closure`.
- Since writes are applied to the read model asynchronously, consider returning the new event version (or an `ETag`) so clients can wait until the read model catches up.

### 12. No validation or pagination

> **Status: done (2026-11-25, uncommitted), except currency.** Bean Validation on request bodies (positive amounts, ≤ 2 decimal places, ≤ 13 integer digits; holder not blank, ≤ 100 characters), with per-field `errors` in the problem response. `GET /accounts` takes `page`, `size` (≤ 100) and an optional `closed` filter, sorted by `createdAt` then `_id` (index updated to match). The README states that there is no authentication and no currency. Tests: `ApiExceptionHandlerTests`, `AccountApiTests`.

- There's no `@Valid` or Bean Validation. Amounts with any scale (`0.0000001`) or more than 34 digits are accepted; the latter fails when converted to `Decimal128` and returns 500. Enforce scale ≤ 2 and a maximum value, and think about currency.
- `GET /accounts` returns every account, closed ones included, with no paging.
- There's no authentication. That's acceptable for a sample, but say so in the README.

## Code quality

### 13. Unused code from scaffolding

These can go:

> **Status: done (2026-11-25, uncommitted).** All of these were deleted, along with `Message`, which only `BaseEvent` extended, for an `id` that was never set. `Exceptions.kt` had already been rewritten in item 3. The `CommandHandler` interface went too, since `AccountCommandHandler` is its only implementation.

- `AggregateRoot` (`AccountAggregate` doesn't extend it)
- `BaseEntity`, `EventSourcingHandler`, `EventProducer`, `QueryDispatcher`, `QueryHandlerMethod`, `BaseQuery`, `CommandHandlerMethod`
- `Exceptions.kt` (`AggregateNotFoundException` and `ConcurrencyException` are never thrown)
- `CommandHandler` with only one implementation

### 14. Smaller items

- `OpenAccountResponse` is a `data class` extending a class with a mutable `message`, so `equals` ignores `message`. Use a plain response data class. *(Done with item 11: `OpenAccountResponse` and `BaseResponse` were replaced by `CommandResponse`.)*
- `Instant.now()` as a default value in an event constructor (`AccountOpenedEvent.kt:15`). Inject a `Clock` into the command handler instead. *(Done: a `Clock` bean is injected into `AccountCommandHandler`, and `AccountAggregate.open` takes the time; tested with a fixed clock.)*
- The query controller uses `MongoTemplate` directly. That's fine at this size, but a small `AccountViewRepository` keeps controllers free of persistence code. *(Done: a Spring Data `AccountViewRepository` with paging; `AccountProjection` keeps `MongoTemplate` for its atomic updates.)*
- `AccountNotFoundException` is defined in the command package but used by the query side. Move it to `common`. *(Done with item 3: it moved to `core/exceptions`.)*
- `gradlew` is committed as `100644`, not executable, which is why the README says `bash ./gradlew`. Fix it with `git update-index --chmod=+x gradlew`. *(Done in commit `91b854d`; the README now uses `./gradlew`.)*
- There's a duplicate `import anyString` in `AccountCommandHandlerTests.kt:8-9`. *(Done: removed by Spotless.)*
- Untracked `mise.toml`: either commit it or add it to `.gitignore`. *(Open: your decision. It pins Java 25 and Kotlin for contributors, which argues for committing it.)*

## Test gaps

- No HTTP-level tests (`@WebMvcTest` / `MockMvc`): status codes, error bodies, and the idempotent-replay response. *(Done: `ApiExceptionHandlerTests`, `AccountApiTests`.)*
- No concurrency tests (item 1). *(Done: `MongoPersistenceTests`.)*
- No end-to-end test: command → event store → worker → read model (Awaitility). *(Done: `AccountApiTests`, `AccountProjectionWorkerTests`.)*
- No test that the event JSON keeps its shape across class changes (item 7). *(Partly done: tests check the stored type alias and that older class-name events still load. Still open: a fixed "golden" stored document per event type, so a renamed field fails a test.)*

## Suggested order

1. Make concurrency failures and idempotency races return 409 or the original result, with a test that proves it (items 1 and 3).
2. Remove business-rule checks from `apply()` (item 2).
3. Return results through a gateway reply instead of mutating commands (item 4).
4. Switch the projection to one global checkpoint (item 5).
5. Delete the unused code (item 13).
