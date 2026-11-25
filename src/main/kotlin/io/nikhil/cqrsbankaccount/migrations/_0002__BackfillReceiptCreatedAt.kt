package io.nikhil.cqrsbankaccount.migrations

import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import io.flamingock.api.annotations.Apply
import io.flamingock.api.annotations.Change
import io.flamingock.api.annotations.Rollback
import io.flamingock.api.annotations.TargetSystem
import io.nikhil.cqrsbankaccount.migrations.MigrationConfiguration.Companion.ACCOUNT_DATABASE
import java.time.Clock
import java.util.Date

// Receipts written before CommandReceipt.createdAt existed are ignored by the TTL index and would be kept
// forever. Stamping them with the migration time lets them expire 24 hours from now. Idempotent, so it
// does not need a transaction.
@TargetSystem(id = ACCOUNT_DATABASE)
@Change(id = "backfill-receipt-created-at", author = "Nikhil Vibhav", transactional = false)
class _0002__BackfillReceiptCreatedAt {
    @Apply
    fun apply(database: MongoDatabase, clock: Clock) {
        database.getCollection("commandReceipts").updateMany(
            Filters.exists("createdAt", false),
            Updates.set("createdAt", Date.from(clock.instant())),
        )
    }

    // Nothing to undo: a partly applied backfill is completed by applying it again.
    @Rollback
    fun rollback() = Unit
}
