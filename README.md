# CQRS bank account

A sample Kotlin/Spring Boot backend demonstrating CQRS, event sourcing, and Spring Integration. MongoDB stores account events separately from the account read model. Spring Integration routes commands and polled events; commands return after their events and idempotency receipts commit, while the read model is updated asynchronously and may briefly lag.

## Requirements and local setup

- Java 25
- Docker, for the MongoDB replica set used by this sample

Start MongoDB in one terminal:

```shell
docker run --name cqrs-mongo -p 27017:27017 mongo:8 --replSet rs0 --bind_ip_all
```

In another terminal, initialize the replica set:

```shell
docker exec cqrs-mongo mongosh --eval "rs.initiate()"
```

MongoDB transactions require a replica set; a standalone MongoDB server is not sufficient. The application uses the `cqrs_bank_account` database on `localhost:27017`. Set `spring.mongodb.uri` to use a different instance, including its replica-set name.

Run the app:

```shell
# Windows
.\gradlew.bat bootRun

# Linux/macOS
./gradlew bootRun
```

OpenAPI UI: <http://localhost:8080/swagger-ui/index.html>

## API

> The API has no authentication or authorisation: anyone who can reach it can open, change and read any account. That is acceptable for a local sample only.

| Method and path | Purpose | Success |
| --- | --- | --- |
| `POST /api/v1/accounts` | Open an account | `201`, `Location` header |
| `POST /api/v1/accounts/{id}/deposits` | Deposit funds | `200` |
| `POST /api/v1/accounts/{id}/withdrawals` | Withdraw funds | `200` |
| `POST /api/v1/accounts/{id}/closure` | Close the account (it stays readable, with `closed: true`) | `200` |
| `GET /api/v1/accounts/{id}` | Read an account | `200`, `ETag` header |
| `GET /api/v1/accounts?page=0&size=20&closed=false` | List accounts, oldest first | `200` |

Open an account:

```http
POST /api/v1/accounts
Content-Type: application/json
Idempotency-Key: open-ada-001

{"accountHolder":"Ada Lovelace","accountType":"SAVINGS","openingBalance":100.00}
```

```http
HTTP/1.1 201 Created
Location: http://localhost:8080/api/v1/accounts/6f1c…
ETag: "0"

{"accountId":"6f1c…","version":0,"message":"Bank account opened successfully."}
```

Deposit (withdrawals take the same body):

```http
POST /api/v1/accounts/{id}/deposits
Content-Type: application/json
Idempotency-Key: deposit-ada-001

{"amount":25.00}
```

All write operations accept an optional `Idempotency-Key` header, and return the account's event `version` after the change, in the body and as the `ETag`. Because the read model is updated asynchronously, a client that needs to read its own write polls `GET /api/v1/accounts/{id}` until that response's `ETag` (the read model's version) reaches the version its write returned.

Amounts must be positive (an opening balance may be zero), with at most 2 decimal places and 13 digits before the decimal point. The account holder must not be blank and is at most 100 characters. `GET /api/v1/accounts` returns `{"items":[…],"page":0,"size":20,"totalItems":…}`; `size` is at most 100 and `closed` is an optional filter. There is no currency: every amount is in the same unit.

### Idempotency keys

The client creates a unique opaque key (for example, a UUID) for each logical write and reuses that key with the exact same request if retrying after a timeout or uncertain response. A repeated key and payload returns the original result without appending another event. Reusing the key with a different operation or payload is rejected. Keys are optional; requests without one are treated as new commands on every attempt. Keys are global rather than per client, because the API has no authentication yet.

A key is remembered for 24 hours (a TTL index on `commandReceipts`); after that, a request with the same key runs as a new command. Only successful commands are remembered: if a command is rejected (for example, insufficient funds), a retry with the same key runs again against the account's current state and may succeed.

### Account rules and consistency

Amounts use decimal values, not floating-point numbers. Opening balances must be non-negative; deposits and withdrawals must be positive. Accounts can only be closed at a zero balance. Errors are returned as RFC 9457 problem details (`application/problem+json`) with a `title` and `detail`:

| Status | When |
| --- | --- |
| `400` | Malformed or invalid request; validation failures list each field in `errors` (`[{"field":"amount","message":"…"}]`) |
| `404` | Account not found |
| `409` | The account kept being changed by concurrent requests after every retry; retrying later may succeed |
| `422` | Business rule violated: insufficient funds, account closed, closing with a non-zero balance, or an idempotency key reused with a different payload |
| `500` | Unexpected error; the detail is generic and the cause is only logged |

Each command's versioned events and idempotency receipt are committed in one MongoDB transaction. A unique `(aggregateIdentifier, version)` index and expected-version check protect the event stream from conflicting writes. When a command loses a race with another request on the same account, the server retries it from a fresh read of the account, re-checking the business rules, up to `app.commands.max-attempts` times (default 5) before returning `409`. Concurrent requests with the same idempotency key record the command once and all return its result. MongoDB transactions do not include the read model. A projection worker follows the event store's MongoDB change stream and saves its position (the stream's resume token) in `projectionCheckpoints`, so after a restart it continues where it stopped. For each new event it applies every event after the version the read model has stored for that account, so duplicate deliveries are ignored and gaps are filled in. An account that fails to project is recorded in `projectionRetries` and retried every `app.projection.retry-interval` (default 1 second). A read immediately after a successful command may therefore briefly return the previous projection.

Every instance with `app.projection.enabled=true` follows the stream. That is safe, because the read model's version check prevents double-applying, but it repeats the work, so enable the projection on one instance only.

To rebuild the read model from the event store, stop the application, drop the `accounts`, `projectionCheckpoints` and `projectionRetries` collections, and start it again. Without a checkpoint, the worker replays every account's events.

### Database migrations

Indexes and data changes are applied by [Flamingock](https://docs.flamingock.io/) from the change units in `src/main/kotlin/io/nikhil/cqrsbankaccount/migrations`. Each runs once per database, in file-name order (`_0001__CreateIndexes`, `_0002__…`), and is recorded in the `flamingockAuditLog` collection; a lock in `flamingockLock` makes concurrent instances take turns. Migrations run while the application starts, before it serves requests or projects events, and a failed migration stops it from starting, so the application needs MongoDB to start.

A released change unit is never edited. To change something, add the next numbered one, for example a `collMod` to change the 24-hour receipt TTL. Prefer idempotent changes, and mark index changes `transactional = false`, since MongoDB cannot create indexes inside a multi-document transaction.

Stored events record a stable type alias (for example `AccountOpened`) rather than a Kotlin class name; see `AccountEvent` for how to evolve them.

## Tests and CI

Run tests locally:

```shell
# Windows
.\gradlew.bat test

# Linux/macOS
./gradlew test --no-daemon
```

Kotlin sources and Gradle scripts are formatted with [Spotless](https://github.com/diffplug/spotless) and ktlint, using the rules in `.editorconfig`. `check` (and therefore `build`) fails on unformatted code:

```shell
./gradlew spotlessCheck   # report violations
./gradlew spotlessApply   # fix them
```

The Mongo persistence integration tests start a MongoDB replica set with Testcontainers. They verify event and receipt persistence, idempotency, expected-version checks, retries of concurrent commands, and idempotent projection updates. They run when Docker is available and are skipped otherwise. With Podman, point Testcontainers at the Podman socket:

```shell
export DOCKER_HOST="unix://$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}')"
export TESTCONTAINERS_RYUK_DISABLED=true
./gradlew test
```

GitHub Actions runs the formatting check and the complete test suite for pull requests targeting `main` and for pushes to `main`, using Java 25 and Docker.
