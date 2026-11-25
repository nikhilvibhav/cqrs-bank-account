package io.nikhil.cqrsbankaccount

import io.nikhil.cqrsbankaccount.command.api.commands.DepositFundsCommand
import io.nikhil.cqrsbankaccount.command.api.commands.OpenAccountCommand
import io.nikhil.cqrsbankaccount.common.dtos.AccountType
import io.nikhil.cqrsbankaccount.common.events.FundsDepositedEvent
import io.nikhil.cqrsbankaccount.core.events.CommandReceipt
import io.nikhil.cqrsbankaccount.core.events.EventModel
import io.nikhil.cqrsbankaccount.core.infra.CommandDispatcher
import io.nikhil.cqrsbankaccount.query.api.models.AccountView
import io.nikhil.cqrsbankaccount.query.api.projections.AccountProjectionWorker
import io.nikhil.cqrsbankaccount.query.api.projections.ProjectionCheckpoint
import io.nikhil.cqrsbankaccount.query.api.projections.ProjectionRetry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.findById
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.test.annotation.DirtiesContext
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.fail

// Runs the real worker against a MongoDB change stream. The context is closed after the class so its
// worker does not keep projecting while other test classes use the same database.
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = ["app.projection.enabled=true", "app.projection.retry-interval=200ms"])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AccountProjectionWorkerTests : MongoIntegrationTest() {
    @Autowired
    private lateinit var mongoTemplate: MongoTemplate

    @Autowired
    private lateinit var commandDispatcher: CommandDispatcher

    @Autowired
    private lateinit var worker: AccountProjectionWorker

    @BeforeEach
    fun clearCollections() {
        listOf(EventModel::class, CommandReceipt::class, AccountView::class, ProjectionRetry::class)
            .forEach { mongoTemplate.remove(Query(), it.java) }
        if (!worker.isRunning) worker.start()
    }

    @Test
    fun `new events are projected from the change stream`() {
        commandDispatcher.send(OpenAccountCommand("account-1", "Ada Lovelace", AccountType.SAVINGS, BigDecimal("10")))
        commandDispatcher.send(DepositFundsCommand("account-1", BigDecimal("5")))

        awaitBalance("account-1", BigDecimal("15"))
    }

    @Test
    fun `without a checkpoint, events written earlier are caught up on start`() {
        worker.stop()
        mongoTemplate.remove(Query(), ProjectionCheckpoint::class.java)
        commandDispatcher.send(OpenAccountCommand("account-1", "Ada Lovelace", AccountType.SAVINGS, BigDecimal("10")))

        worker.start()

        awaitBalance("account-1", BigDecimal("10"))
    }

    @Test
    fun `after a restart, the worker resumes from its checkpoint`() {
        commandDispatcher.send(OpenAccountCommand("account-1", "Ada Lovelace", AccountType.SAVINGS, BigDecimal("10")))
        awaitBalance("account-1", BigDecimal("10"))
        await("a checkpoint") { mongoTemplate.findById<ProjectionCheckpoint>("account-projection") != null }

        worker.stop()
        commandDispatcher.send(DepositFundsCommand("account-1", BigDecimal("5")))
        worker.start()

        awaitBalance("account-1", BigDecimal("15"))
    }

    @Test
    fun `an account that fails to project is retried until it succeeds`() {
        commandDispatcher.send(OpenAccountCommand("account-1", "Ada Lovelace", AccountType.SAVINGS, BigDecimal("10")))
        awaitBalance("account-1", BigDecimal("10"))

        // A stored event the worker cannot read, as if written by a faulty release.
        mongoTemplate.insert(
            EventModel(aggregateIdentifier = "account-1", aggregateType = "Account", version = 1, eventData = null),
        )
        await("a recorded retry") { mongoTemplate.findById<ProjectionRetry>("account-1") != null }

        val broken = mongoTemplate.findOne(
            Query.query(Criteria.where("aggregateIdentifier").`is`("account-1").and("version").`is`(1)),
            EventModel::class.java,
        )!!
        mongoTemplate.save(
            broken.copy(
                eventData = FundsDepositedEvent("account-1", BigDecimal("5")).apply {
                    version = 1
                },
            ),
        )

        awaitBalance("account-1", BigDecimal("15"))
        await("the retry to be cleared") { mongoTemplate.findById<ProjectionRetry>("account-1") == null }
    }

    private fun awaitBalance(accountId: String, balance: BigDecimal) =
        await("balance $balance") {
            mongoTemplate.findById<AccountView>(accountId)?.balance?.compareTo(balance) == 0
        }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = Instant.now() + Duration.ofSeconds(15)
        while (Instant.now().isBefore(deadline)) {
            if (condition()) return
            Thread.sleep(50)
        }
        fail("Timed out waiting for $description.")
    }
}
