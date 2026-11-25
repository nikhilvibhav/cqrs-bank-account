package io.nikhil.cqrsbankaccount

import io.nikhil.cqrsbankaccount.command.api.commands.DepositFundsCommand
import io.nikhil.cqrsbankaccount.command.api.commands.OpenAccountCommand
import io.nikhil.cqrsbankaccount.command.domain.AccountAggregate
import io.nikhil.cqrsbankaccount.common.dtos.AccountType
import io.nikhil.cqrsbankaccount.common.events.AccountOpenedEvent
import io.nikhil.cqrsbankaccount.common.events.FundsDepositedEvent
import io.nikhil.cqrsbankaccount.core.commands.CommandResult
import io.nikhil.cqrsbankaccount.core.events.CommandReceipt
import io.nikhil.cqrsbankaccount.core.events.EventModel
import io.nikhil.cqrsbankaccount.core.exceptions.ConcurrencyConflictException
import io.nikhil.cqrsbankaccount.core.exceptions.IdempotencyKeyReusedException
import io.nikhil.cqrsbankaccount.core.infra.CommandDispatcher
import io.nikhil.cqrsbankaccount.core.infra.EventStore
import io.nikhil.cqrsbankaccount.query.api.models.AccountView
import io.nikhil.cqrsbankaccount.query.api.projections.AccountProjection
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Query
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CONTENDERS = 8

/**
 * Tests the event store, receipts and projection against a real MongoDB replica set. The projection worker
 * is off so the tests control when events are projected, and commands get [CONTENDERS] attempts so that
 * every contender in the concurrency tests eventually wins its turn.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = ["app.projection.enabled=false", "app.commands.max-attempts=$CONTENDERS"])
class MongoPersistenceTests : MongoIntegrationTest() {
    @Autowired
    private lateinit var mongoTemplate: MongoTemplate

    @Autowired
    private lateinit var eventStore: EventStore

    @Autowired
    private lateinit var accountProjection: AccountProjection

    @Autowired
    private lateinit var commandDispatcher: CommandDispatcher

    @BeforeEach
    fun clearCollections() {
        mongoTemplate.remove(Query(), EventModel::class.java)
        mongoTemplate.remove(Query(), CommandReceipt::class.java)
        mongoTemplate.remove(Query(), AccountView::class.java)
    }

    @Test
    fun `startup creates the event store, read model and receipt TTL indexes`() {
        val eventIndex = mongoTemplate.indexOps(EventModel::class.java).indexInfo
            .single { it.name == "aggregate_version_unique" }
        assertTrue(eventIndex.isUnique)
        assertTrue(mongoTemplate.indexOps(AccountView::class.java).indexInfo.any { it.name == "created_at" })
        val receiptIndex = mongoTemplate.indexOps(CommandReceipt::class.java).indexInfo
            .single { it.name == "created_at_ttl" }
        assertEquals(Duration.ofHours(24), receiptIndex.expireAfter.orElseThrow())
    }

    @Test
    fun `events are stored under their type alias and older full class names still load`() {
        val opened = AccountOpenedEvent(
            accountHolder = "Ada Lovelace",
            accountType = AccountType.SAVINGS,
            openingBalance = BigDecimal.ZERO,
            createdAt = Instant.EPOCH,
            accountId = "account-1",
        )
        eventStore.saveEvents("account-1", listOf(opened), -1, "open-command", "open-fingerprint")

        val stored = mongoTemplate.getCollection("eventStore").find().single()
        assertEquals("AccountOpened", stored.get("eventData", org.bson.Document::class.java).getString("_class"))

        // An event written before aliases existed, as it is stored in MongoDB.
        mongoTemplate.getCollection("eventStore").insertOne(
            org.bson.Document(
                mapOf(
                    "aggregateIdentifier" to "account-1",
                    "aggregateType" to "Account",
                    "version" to 1,
                    "eventType" to "FundsDepositedEvent",
                    "eventData" to org.bson.Document(
                        mapOf(
                            "_class" to FundsDepositedEvent::class.java.name,
                            "accountId" to "account-1",
                            "amount" to org.bson.types.Decimal128(BigDecimal("5")),
                            "version" to 1,
                            "aggregateId" to "account-1",
                        ),
                    ),
                ),
            ),
        )

        val events = eventStore.getEvents("account-1")
        assertEquals(listOf(AccountOpenedEvent::class, FundsDepositedEvent::class), events.map { it::class })
        assertEquals(BigDecimal("5"), (events[1] as FundsDepositedEvent).amount)
    }

    @Test
    fun `event store appends events and receipts and reads events in order`() {
        val opening = AccountOpenedEvent(
            accountHolder = "Ada Lovelace",
            accountType = AccountType.SAVINGS,
            openingBalance = BigDecimal("10.00"),
            createdAt = Instant.EPOCH,
            accountId = "account-1",
        )
        val deposit = FundsDepositedEvent("account-1", BigDecimal("5.00"))

        eventStore.saveEvents("account-1", listOf(opening, deposit), -1, "open-command", "open-fingerprint")

        assertEquals(listOf(0, 1), eventStore.getEvents("account-1").map { it.version })
        assertEquals(listOf(1), eventStore.getEventsAfter("account-1", 0).map { it.version })
        assertEquals(CommandResult("account-1", 1), eventStore.findCommandResult("open-command", "open-fingerprint"))
        assertEquals(2, mongoTemplate.count(Query(), EventModel::class.java))
    }

    @Test
    fun `event store rejects an idempotency key with a different fingerprint`() {
        eventStore.saveEvents(
            "account-1",
            listOf(
                AccountOpenedEvent(
                    accountHolder = "Ada Lovelace",
                    accountType = AccountType.SAVINGS,
                    openingBalance = BigDecimal.ZERO,
                    createdAt = Instant.EPOCH,
                    accountId = "account-1",
                ),
            ),
            -1,
            "same-key",
            "original-fingerprint",
        )

        assertFailsWith<IdempotencyKeyReusedException> {
            eventStore.saveEvents(
                "account-1",
                listOf(FundsDepositedEvent("account-1", BigDecimal.ONE)),
                0,
                "same-key",
                "different-fingerprint",
            )
        }

        assertEquals(1, mongoTemplate.count(Query(), EventModel::class.java))
        assertNull(eventStore.findCommandResult("missing-key", "fingerprint"))
    }

    @Test
    fun `event store treats a matching idempotency key as an already committed command`() {
        val opening = AccountOpenedEvent(
            accountHolder = "Ada Lovelace",
            accountType = AccountType.SAVINGS,
            openingBalance = BigDecimal.ZERO,
            createdAt = Instant.EPOCH,
            accountId = "account-1",
        )
        eventStore.saveEvents("account-1", listOf(opening), -1, "same-key", "same-fingerprint")

        val result = eventStore.saveEvents(
            "account-1",
            listOf(FundsDepositedEvent("account-1", BigDecimal("100"))),
            0,
            "same-key",
            "same-fingerprint",
        )

        // The original command's result, not one for the deposit.
        assertEquals(CommandResult("account-1", 0), result)
        assertEquals(1, mongoTemplate.count(Query(), EventModel::class.java))
    }

    @Test
    fun `event store rejects a stale expected version without appending events`() {
        eventStore.saveEvents(
            "account-1",
            listOf(
                AccountOpenedEvent(
                    accountHolder = "Ada Lovelace",
                    accountType = AccountType.SAVINGS,
                    openingBalance = BigDecimal.ZERO,
                    createdAt = Instant.EPOCH,
                    accountId = "account-1",
                ),
            ),
            -1,
            "open-command",
            "open-fingerprint",
        )

        assertFailsWith<ConcurrencyConflictException> {
            eventStore.saveEvents(
                "account-1",
                listOf(FundsDepositedEvent("account-1", BigDecimal.ONE)),
                -1,
                "stale-command",
                "stale-fingerprint",
            )
        }

        assertEquals(1, mongoTemplate.count(Query(), EventModel::class.java))
        assertNull(eventStore.findCommandResult("stale-command", "stale-fingerprint"))
    }

    @Test
    fun `projection persists balances and ignores duplicate event delivery`() {
        val opened = AccountOpenedEvent(
            accountHolder = "Ada Lovelace",
            accountType = AccountType.CURRENT,
            openingBalance = BigDecimal("10.00"),
            createdAt = Instant.EPOCH,
            accountId = "account-1",
        ).apply { version = 0 }
        val deposit = FundsDepositedEvent("account-1", BigDecimal("5.00")).apply { version = 1 }

        accountProjection.project(opened)
        accountProjection.project(deposit)
        accountProjection.project(deposit)

        val account = requireNotNull(mongoTemplate.findById("account-1", AccountView::class.java))
        assertEquals(BigDecimal("15.00"), account.balance)
        assertEquals(1, account.version)
        assertEquals(1, accountProjection.getAppliedVersion("account-1"))
    }

    @Test
    fun `concurrent commands on one account are retried and none are lost`() {
        commandDispatcher.send(OpenAccountCommand("account-1", "Ada Lovelace", AccountType.SAVINGS))

        val results = runConcurrently { commandDispatcher.send(DepositFundsCommand("account-1", BigDecimal.ONE)) }

        assertTrue(results.all { it.isSuccess }, "Every deposit should succeed: $results")
        val account = AccountAggregate.rehydrate("account-1", eventStore.getEvents("account-1"))
        assertEquals(BigDecimal(CONTENDERS), account.balance)
        assertEquals(CONTENDERS, account.version)
    }

    @Test
    fun `concurrent retries with one idempotency key record one command and share its result`() {
        val results = runConcurrently { attempt ->
            // Each attempt generates its own account id, as the controller does for every request.
            commandDispatcher.send(
                OpenAccountCommand("account-$attempt", "Ada Lovelace", AccountType.SAVINGS, idempotencyKey = "open-1"),
            )
        }

        assertTrue(results.all { it.isSuccess }, "Every retry should succeed: $results")
        assertEquals(1, results.map { it.getOrThrow() }.distinct().size)
        assertEquals(1, mongoTemplate.count(Query(), EventModel::class.java))
        assertEquals(1, mongoTemplate.count(Query(), CommandReceipt::class.java))
    }

    /** Starts every task at the same moment so they contend for the same account or receipt. */
    private fun <T> runConcurrently(task: (Int) -> T): List<Result<T>> {
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(CONTENDERS)
        try {
            val futures = (1..CONTENDERS).map { attempt ->
                executor.submit<Result<T>> {
                    start.await()
                    runCatching { task(attempt) }
                }
            }
            start.countDown()
            return futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }
}
