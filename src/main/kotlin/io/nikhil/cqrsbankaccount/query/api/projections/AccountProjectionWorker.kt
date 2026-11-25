package io.nikhil.cqrsbankaccount.query.api.projections

import com.mongodb.MongoException
import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Filters
import com.mongodb.client.model.changestream.ChangeStreamDocument
import io.nikhil.cqrsbankaccount.core.events.EventModel
import io.nikhil.cqrsbankaccount.core.infra.EventStore
import org.bson.BsonDocument
import org.bson.Document
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.SmartLifecycle
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.findAll
import org.springframework.data.mongodb.core.findById
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.integration.support.MessageBuilder
import org.springframework.messaging.MessageChannel
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Keeps the account read model up to date by following the event store's change stream.
 *
 * The stream delivers inserts in commit order, and its resume token is the projection's single global
 * checkpoint. Each change only says which account changed: the worker then applies every event after the
 * version the read model has stored for that account, so duplicate notifications are harmless and a
 * missed or failed event is repaired by the next pass over that account. Accounts that fail are kept in
 * `projectionRetries` and retried every `app.projection.retry-interval`.
 *
 * Every running instance follows the stream; the read model's version check keeps that safe, but the work
 * is repeated. Run the projection on one instance (`app.projection.enabled`) to avoid it.
 *
 * The worker runs on its own daemon thread, started and stopped with the application context.
 */
@Component
@ConditionalOnProperty(name = ["app.projection.enabled"], havingValue = "true", matchIfMissing = true)
class AccountProjectionWorker(
    private val eventStore: EventStore,
    private val accountProjection: AccountProjection,
    @Qualifier("accountEventChannel") private val eventChannel: MessageChannel,
    private val mongoTemplate: MongoTemplate,
    @Value("\${app.projection.retry-interval:1s}") private val retryInterval: Duration,
) : SmartLifecycle {
    private val log = LoggerFactory.getLogger(AccountProjectionWorker::class.java)

    @Volatile
    private var running = false
    private var thread: Thread? = null

    /** Starts following the change stream on a background thread. */
    override fun start() {
        running = true
        thread = Thread.ofPlatform().name("account-projection").daemon().start(::run)
    }

    /** Asks the background thread to stop and waits up to ten seconds for it. */
    override fun stop() {
        running = false
        thread?.join(Duration.ofSeconds(10))
        thread = null
    }

    override fun isRunning(): Boolean = running

    /**
     * Applies every event the read model has not yet applied for one account.
     *
     * @return true on success; false, after recording the account for retry, if that fails.
     */
    fun projectAccount(accountId: String): Boolean =
        try {
            val appliedVersion = accountProjection.getAppliedVersion(accountId)
            eventStore.getEventsAfter(accountId, appliedVersion).forEach { event ->
                check(eventChannel.send(MessageBuilder.withPayload(event).build())) {
                    "The account event channel rejected event version ${event.version} for $accountId."
                }
            }
            true
        } catch (exception: RuntimeException) {
            log.error("Failed to project account {}; it will be retried.", accountId, exception)
            mongoTemplate.save(ProjectionRetry(accountId, exception.message))
            false
        }

    /**
     * The background thread's loop: follows the change stream and reopens it after a failure. When the
     * saved position has fallen out of the oplog, the checkpoint is dropped so the next pass catches up
     * every account from the event store.
     */
    private fun run() {
        while (running) {
            try {
                followEventStore()
            } catch (exception: RuntimeException) {
                if (!running) break
                if (isHistoryLost(exception)) {
                    log.warn("The saved change stream position is no longer in the oplog; catching up from scratch.")
                    mongoTemplate.remove(
                        Query.query(Criteria.where("_id").`is`(CHECKPOINT)),
                        ProjectionCheckpoint::class.java,
                    )
                } else {
                    log.error("The event store change stream failed; reopening it.", exception)
                }
                Thread.sleep(retryInterval)
            }
        }
    }

    /**
     * Opens the change stream at the saved checkpoint and projects each change until the worker stops,
     * retrying failed accounts every [retryInterval] and saving the stream position as it advances.
     */
    private fun followEventStore() {
        val savedToken = mongoTemplate.findById<ProjectionCheckpoint>(CHECKPOINT)?.resumeToken?.let(BsonDocument::parse)
        val changes = mongoTemplate.getCollection(mongoTemplate.getCollectionName(EventModel::class.java))
            .watch(listOf(Aggregates.match(Filters.eq("operationType", "insert"))))
            .maxAwaitTime(STREAM_WAIT.toMillis(), TimeUnit.MILLISECONDS)

        (savedToken?.let(changes::resumeAfter) ?: changes).cursor().use { cursor ->
            if (savedToken == null) {
                // No position yet: open the stream first so nothing written from now on is missed, then
                // catch up every account from the event store. Events seen by both are applied once.
                cursor.tryNext()?.let(::projectChange)
                eventStore.getAggregateIds().forEach(::projectAccount)
            }

            var lastSavedToken = savedToken
            var nextRetry = Instant.now()
            while (running) {
                cursor.tryNext()?.let(::projectChange)
                if (!Instant.now().isBefore(nextRetry)) {
                    retryFailedAccounts()
                    nextRetry = Instant.now() + retryInterval
                }
                val token = cursor.resumeToken
                if (token != null && token != lastSavedToken) {
                    mongoTemplate.save(ProjectionCheckpoint(CHECKPOINT, token.toJson()))
                    lastSavedToken = token
                }
            }
        }
    }

    /** Projects the account whose event [change] inserted. */
    private fun projectChange(change: ChangeStreamDocument<Document>) {
        change.fullDocument?.getString("aggregateIdentifier")?.let(::projectAccount)
    }

    /** Projects every account recorded for retry, removing those that now succeed. */
    private fun retryFailedAccounts() {
        mongoTemplate.findAll<ProjectionRetry>().forEach { retry ->
            if (projectAccount(retry.accountId)) {
                // Only remove the entry this pass read, not one recorded by a newer failure.
                mongoTemplate.remove(
                    Query.query(Criteria.where("_id").`is`(retry.accountId).and("failedAt").`is`(retry.failedAt)),
                    ProjectionRetry::class.java,
                )
            }
        }
    }

    /** Whether [exception] means the change stream cannot resume from its saved position. */
    private fun isHistoryLost(exception: RuntimeException): Boolean =
        generateSequence<Throwable>(exception) { it.cause }
            .filterIsInstance<MongoException>()
            .any { it.code == CHANGE_STREAM_HISTORY_LOST || it.code == CHANGE_STREAM_FATAL_ERROR }

    private companion object {
        const val CHECKPOINT = "account-projection"
        val STREAM_WAIT: Duration = Duration.ofMillis(500)
        const val CHANGE_STREAM_FATAL_ERROR = 280
        const val CHANGE_STREAM_HISTORY_LOST = 286
    }
}
