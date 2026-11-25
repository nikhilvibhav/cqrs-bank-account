package io.nikhil.cqrsbankaccount.common.events

import io.nikhil.cqrsbankaccount.core.events.BaseEvent

// Every event an account can record. Being sealed, `when` over it is checked for exhaustiveness.
//
// Each subclass has a @TypeAlias, which is what Spring Data writes as the stored `_class`, so classes can
// be renamed or moved without breaking stored events. Aliases must never change. To change an event's
// shape, add fields with defaults; for an incompatible change, add a new event type and keep the old one
// readable. Events written before the aliases existed store the full class name and still load.
sealed class AccountEvent(aggregateId: String) : BaseEvent(aggregateId = aggregateId) {
    companion object {
        val types: List<Class<out AccountEvent>> = AccountEvent::class.sealedSubclasses.map { it.java }
    }
}
