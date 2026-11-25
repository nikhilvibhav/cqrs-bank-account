package io.nikhil.cqrsbankaccount.core.events

/**
 * Something that happened to an aggregate, stored in the event store as part of its stream.
 *
 * @property version the event's position in its aggregate's stream, starting at 0. The event store
 *   assigns it when the event is saved.
 * @property aggregateId the aggregate the event belongs to.
 */
abstract class BaseEvent(
    var version: Int = 0,
    var aggregateId: String? = null,
)
