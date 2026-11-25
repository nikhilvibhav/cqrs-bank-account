package io.nikhil.cqrsbankaccount.core.infra

import com.mongodb.MongoException
import io.nikhil.cqrsbankaccount.core.commands.CommandResult
import io.nikhil.cqrsbankaccount.core.events.BaseEvent
import io.nikhil.cqrsbankaccount.core.events.CommandReceipt
import io.nikhil.cqrsbankaccount.core.events.EventModel
import io.nikhil.cqrsbankaccount.core.exceptions.ConcurrencyConflictException
import io.nikhil.cqrsbankaccount.core.exceptions.IdempotencyKeyReusedException
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate

/**
 * [EventStore] backed by the `eventStore` and `commandReceipts` MongoDB collections.
 *
 * A command's events and its receipt are written in one multi-document transaction, so MongoDB must run as
 * a replica set. The unique index on aggregate and version makes a concurrent append of the same version
 * fail rather than fork the stream.
 */
@Repository
class MongoEventStore(
    private val mongoTemplate: MongoTemplate,
    private val transactionTemplate: TransactionTemplate,
) : EventStore {
    override fun saveEvents(
        aggregateId: String,
        events: Iterable<BaseEvent>,
        expectedVersion: Int,
        commandId: String,
        commandFingerprint: String,
    ): CommandResult {
        val newEvents = events.toList()
        require(newEvents.isNotEmpty()) { "Cannot record a command without events." }

        try {
            return transactionTemplate.execute {
                val receipt = mongoTemplate.findById(commandId, CommandReceipt::class.java)
                if (receipt != null) {
                    if (receipt.fingerprint != commandFingerprint) throw IdempotencyKeyReusedException()
                    return@execute receipt.toResult()
                }

                val latest = mongoTemplate.find(
                    Query.query(Criteria.where("aggregateIdentifier").`is`(aggregateId))
                        .with(Sort.by(Sort.Direction.DESC, "version"))
                        .limit(1),
                    EventModel::class.java,
                ).firstOrNull()?.version ?: -1

                if (latest != expectedVersion) throw ConcurrencyConflictException(aggregateId)

                newEvents.forEachIndexed { index, event ->
                    event.aggregateId = aggregateId
                    event.version = expectedVersion + index + 1
                    mongoTemplate.insert(
                        EventModel(
                            aggregateIdentifier = aggregateId,
                            aggregateType = "Account",
                            version = event.version,
                            eventType = event.javaClass.simpleName,
                            eventData = event,
                        ),
                    )
                }
                val version = expectedVersion + newEvents.size
                mongoTemplate.insert(CommandReceipt(commandId, aggregateId, commandFingerprint, version))
                CommandResult(aggregateId, version)
            }
        } catch (exception: RuntimeException) {
            if (!isWriteConflict(exception)) throw exception
            // Another transaction wrote the same receipt or event version, or the commit outcome is unknown.
            // If the same command has committed, return its result; otherwise the caller retries.
            val receipt = mongoTemplate.findById(commandId, CommandReceipt::class.java)
            if (receipt != null && receipt.fingerprint == commandFingerprint) {
                return receipt.toResult()
            }
            if (receipt != null) throw IdempotencyKeyReusedException(exception)
            throw ConcurrencyConflictException(aggregateId, exception)
        }
    }

    override fun findCommandResult(commandId: String, commandFingerprint: String): CommandResult? {
        val receipt = mongoTemplate.findById(commandId, CommandReceipt::class.java) ?: return null
        if (receipt.fingerprint != commandFingerprint) throw IdempotencyKeyReusedException()
        return receipt.toResult()
    }

    /** The result the command that wrote this receipt returned. */
    private fun CommandReceipt.toResult() = CommandResult(aggregateIdentifier, version)

    override fun getEvents(aggregateId: String): List<BaseEvent> =
        mongoTemplate.find(
            Query.query(Criteria.where("aggregateIdentifier").`is`(aggregateId))
                .with(Sort.by(Sort.Direction.ASC, "version")),
            EventModel::class.java,
        ).map { model ->
            requireNotNull(model.eventData) { "Event ${model.id} has no event data." }
        }

    override fun getEventsAfter(aggregateId: String, version: Int): List<BaseEvent> =
        mongoTemplate.find(
            Query.query(
                Criteria.where("aggregateIdentifier").`is`(aggregateId)
                    .and("version").gt(version),
            ).with(Sort.by(Sort.Direction.ASC, "version")),
            EventModel::class.java,
        ).map { model ->
            requireNotNull(model.eventData) { "Event ${model.id} has no event data." }
        }

    override fun getAggregateIds(): List<String> =
        mongoTemplate.findDistinct(
            Query(),
            "aggregateIdentifier",
            EventModel::class.java,
            String::class.java,
        )

    /**
     * Whether [exception] means a competing write or an unknown commit outcome, rather than a defect.
     *
     * Inside a transaction, MongoDB reports a competing insert as WriteConflict (112), which Spring
     * translates to `DataIntegrityViolationException` rather than `DuplicateKeyException`; a conflict found
     * at commit time arrives as a `TransactionSystemException` instead. Both carry the [MongoException].
     */
    private fun isWriteConflict(exception: RuntimeException): Boolean =
        generateSequence<Throwable>(exception) { it.cause }
            .filterIsInstance<MongoException>()
            .any { error ->
                error.code == WRITE_CONFLICT ||
                    error.code == DUPLICATE_KEY ||
                    error.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL) ||
                    error.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL)
            }

    private companion object {
        const val WRITE_CONFLICT = 112
        const val DUPLICATE_KEY = 11000
    }
}
