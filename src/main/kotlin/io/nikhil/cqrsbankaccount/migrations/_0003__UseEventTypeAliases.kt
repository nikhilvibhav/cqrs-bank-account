package io.nikhil.cqrsbankaccount.migrations

import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import io.flamingock.api.annotations.Apply
import io.flamingock.api.annotations.Change
import io.flamingock.api.annotations.Rollback
import io.flamingock.api.annotations.TargetSystem
import io.nikhil.cqrsbankaccount.migrations.MigrationConfiguration.Companion.ACCOUNT_DATABASE

// Rewrites events stored with a full Kotlin class name as their _class to the stable @TypeAlias, so the
// event classes can later be renamed or moved. The names are literals on purpose: this change must keep
// describing the data as it was, whatever the classes are called later. Idempotent.
@TargetSystem(id = ACCOUNT_DATABASE)
@Change(id = "use-event-type-aliases", author = "Nikhil Vibhav", transactional = false)
class _0003__UseEventTypeAliases {
    @Apply
    fun apply(database: MongoDatabase) {
        val events = database.getCollection("eventStore")
        ALIASES.forEach { (className, alias) ->
            events.updateMany(Filters.eq(TYPE_FIELD, className), Updates.set(TYPE_FIELD, alias))
        }
    }

    @Rollback
    fun rollback(database: MongoDatabase) {
        val events = database.getCollection("eventStore")
        ALIASES.forEach { (className, alias) ->
            events.updateMany(Filters.eq(TYPE_FIELD, alias), Updates.set(TYPE_FIELD, className))
        }
    }

    private companion object {
        const val TYPE_FIELD = "eventData._class"
        val ALIASES = mapOf(
            "io.nikhil.cqrsbankaccount.common.events.AccountOpenedEvent" to "AccountOpened",
            "io.nikhil.cqrsbankaccount.common.events.FundsDepositedEvent" to "FundsDeposited",
            "io.nikhil.cqrsbankaccount.common.events.FundsWithdrawnEvent" to "FundsWithdrawn",
            "io.nikhil.cqrsbankaccount.common.events.AccountClosedEvent" to "AccountClosed",
        )
    }
}
