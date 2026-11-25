package io.nikhil.cqrsbankaccount.core.infra

import io.nikhil.cqrsbankaccount.core.commands.CommandResult
import io.nikhil.cqrsbankaccount.core.events.BaseEvent

/** Append-only storage of aggregates' event streams, together with a receipt for each applied command. */
interface EventStore {
    /**
     * Appends [events] to an aggregate's stream and records a receipt for the command that produced them,
     * atomically. The events are given consecutive versions after [expectedVersion].
     *
     * When a receipt for [commandId] already exists with the same fingerprint, nothing is written and the
     * original command's result is returned.
     *
     * @param expectedVersion the version of the stream's latest event when the command read it, or -1 for
     *   a new aggregate.
     * @param commandId identifies the command: its idempotency key, or a generated id.
     * @param commandFingerprint a hash of the command's content.
     * @return the aggregate and its version after the command.
     * @throws io.nikhil.cqrsbankaccount.core.exceptions.ConcurrencyConflictException when the stream is
     *   no longer at [expectedVersion].
     * @throws io.nikhil.cqrsbankaccount.core.exceptions.IdempotencyKeyReusedException when [commandId] was
     *   used for a command with a different fingerprint.
     */
    fun saveEvents(
        aggregateId: String,
        events: Iterable<BaseEvent>,
        expectedVersion: Int,
        commandId: String,
        commandFingerprint: String,
    ): CommandResult

    /**
     * Returns the result of the already applied command [commandId], or null if it has not been applied.
     *
     * @throws io.nikhil.cqrsbankaccount.core.exceptions.IdempotencyKeyReusedException when [commandId] was
     *   used for a command with a different fingerprint.
     */
    fun findCommandResult(commandId: String, commandFingerprint: String): CommandResult?

    /** Returns an aggregate's whole stream, oldest first; empty if the aggregate does not exist. */
    fun getEvents(aggregateId: String): List<BaseEvent>

    /** Returns the events of an aggregate's stream with a version greater than [version], oldest first. */
    fun getEventsAfter(aggregateId: String, version: Int): List<BaseEvent>

    /** Returns the id of every aggregate with at least one event. */
    fun getAggregateIds(): List<String>
}
