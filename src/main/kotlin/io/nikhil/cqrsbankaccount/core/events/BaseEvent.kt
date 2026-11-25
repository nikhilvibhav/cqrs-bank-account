package io.nikhil.cqrsbankaccount.core.events

abstract class BaseEvent(
    var version: Int = 0,
    var aggregateId: String? = null,
)
