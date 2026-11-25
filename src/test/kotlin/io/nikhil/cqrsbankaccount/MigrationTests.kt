package io.nikhil.cqrsbankaccount

import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import io.nikhil.cqrsbankaccount.common.events.FundsDepositedEvent
import io.nikhil.cqrsbankaccount.core.events.CommandReceipt
import io.nikhil.cqrsbankaccount.core.events.EventModel
import io.nikhil.cqrsbankaccount.core.infra.EventStore
import io.nikhil.cqrsbankaccount.migrations._0001__CreateIndexes
import io.nikhil.cqrsbankaccount.migrations._0002__BackfillReceiptCreatedAt
import io.nikhil.cqrsbankaccount.migrations._0003__UseEventTypeAliases
import org.bson.Document
import org.bson.types.Decimal128
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Query
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import kotlin.test.assertEquals

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = ["app.projection.enabled=false"])
class MigrationTests : MongoIntegrationTest() {
    @Autowired
    private lateinit var mongoTemplate: MongoTemplate

    @Autowired
    private lateinit var eventStore: EventStore

    @Autowired
    private lateinit var environment: Environment

    private val database: MongoDatabase get() = mongoTemplate.db

    @BeforeEach
    fun clearCollections() {
        mongoTemplate.remove(Query(), EventModel::class.java)
        mongoTemplate.remove(Query(), CommandReceipt::class.java)
    }

    @Test
    fun `each change is applied once per database, in order`() {
        // A second application starting against the same database finds every change already applied.
        SpringApplicationBuilder(CqrsBankAccountApplication::class.java)
            .web(WebApplicationType.NONE)
            .properties(
                "spring.mongodb.uri=${environment.getProperty("spring.mongodb.uri")}",
                "app.projection.enabled=false",
            )
            .run()
            .close()

        val applied = database.getCollection("flamingockAuditLog")
            .find(Document("state", "APPLIED"))
            .sort(Document("createdAt", 1))
            .map { it.getString("changeId") }
            .toList()
        assertEquals(listOf("create-indexes", "backfill-receipt-created-at", "use-event-type-aliases"), applied)
    }

    @Test
    fun `an older accounts index with different keys is replaced`() {
        val accounts = database.getCollection("accounts")
        accounts.dropIndex("created_at")
        accounts.createIndex(Indexes.ascending("createdAt"), IndexOptions().name("created_at"))

        repeat(2) { _0001__CreateIndexes().apply(database) }

        val index = accounts.listIndexes().single { it.getString("name") == "created_at" }
        assertEquals(Document(mapOf("createdAt" to 1, "_id" to 1)), index.get("key", Document::class.java))
    }

    @Test
    fun `receipts without a creation time are stamped so they can expire`() {
        val receipts = database.getCollection("commandReceipts")
        val earlier = Date.from(Instant.parse("2026-01-01T00:00:00Z"))
        receipts.insertOne(
            Document(mapOf("_id" to "legacy", "aggregateIdentifier" to "account-1", "fingerprint" to "f")),
        )
        receipts.insertOne(
            Document(
                mapOf(
                    "_id" to "recent",
                    "aggregateIdentifier" to "account-1",
                    "fingerprint" to "f",
                    "createdAt" to earlier,
                ),
            ),
        )
        val clock = Clock.fixed(Instant.parse("2026-11-25T00:00:00Z"), ZoneOffset.UTC)

        repeat(2) { _0002__BackfillReceiptCreatedAt().apply(database, clock) }

        assertEquals(
            Date.from(clock.instant()),
            receipts.find(Document("_id", "legacy")).first()!!.getDate("createdAt"),
        )
        assertEquals(earlier, receipts.find(Document("_id", "recent")).first()!!.getDate("createdAt"))
    }

    @Test
    fun `events stored with class names are rewritten to their aliases and still load`() {
        val className = "io.nikhil.cqrsbankaccount.common.events.FundsDepositedEvent"
        database.getCollection("eventStore").insertOne(legacyDeposit(className))

        repeat(2) { _0003__UseEventTypeAliases().apply(database) }

        assertEquals("FundsDeposited", storedType())
        assertEquals(BigDecimal("5"), (eventStore.getEvents("account-1").single() as FundsDepositedEvent).amount)

        _0003__UseEventTypeAliases().rollback(database)
        assertEquals(className, storedType())
    }

    private fun storedType(): String =
        database.getCollection("eventStore").find().single()
            .get("eventData", Document::class.java).getString("_class")

    // An event as stored before type aliases existed.
    private fun legacyDeposit(className: String) =
        Document(
            mapOf(
                "aggregateIdentifier" to "account-1",
                "aggregateType" to "Account",
                "version" to 0,
                "eventType" to "FundsDepositedEvent",
                "eventData" to Document(
                    mapOf(
                        "_class" to className,
                        "accountId" to "account-1",
                        "amount" to Decimal128(BigDecimal("5")),
                        "version" to 0,
                        "aggregateId" to "account-1",
                    ),
                ),
            ),
        )
}
