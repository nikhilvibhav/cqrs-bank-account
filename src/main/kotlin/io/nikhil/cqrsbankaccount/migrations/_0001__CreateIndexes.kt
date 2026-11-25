package io.nikhil.cqrsbankaccount.migrations

import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import io.flamingock.api.annotations.Apply
import io.flamingock.api.annotations.Change
import io.flamingock.api.annotations.Rollback
import io.flamingock.api.annotations.TargetSystem
import io.nikhil.cqrsbankaccount.migrations.MigrationConfiguration.Companion.ACCOUNT_DATABASE
import org.bson.Document
import java.util.concurrent.TimeUnit

// Indexes cannot be created inside a multi-document transaction, so this change is not transactional.
// Databases set up by the earlier startup index code already have these indexes; creating an identical
// index is a no-op, and an older accounts index with different keys is replaced.
@TargetSystem(id = ACCOUNT_DATABASE)
@Change(id = "create-indexes", author = "Nikhil Vibhav", transactional = false)
class _0001__CreateIndexes {
    @Apply
    fun apply(database: MongoDatabase) {
        database.getCollection("eventStore").createIndex(
            Indexes.ascending("aggregateIdentifier", "version"),
            IndexOptions().name("aggregate_version_unique").unique(true),
        )

        // Matches the account list's sort, including the _id tie-breaker.
        val accounts = database.getCollection("accounts")
        val accountsKeys = Indexes.ascending("createdAt", "_id").toBsonDocument()
        val existing = accounts.listIndexes().firstOrNull { it.getString("name") == "created_at" }
        if (existing != null && existing.get("key", Document::class.java).toBsonDocument() != accountsKeys) {
            accounts.dropIndex("created_at")
        }
        accounts.createIndex(accountsKeys, IndexOptions().name("created_at"))

        // Receipts expire after 24 hours, which is how long a client may retry with the same key. To change
        // it, add a change unit that runs collMod on this index's expireAfterSeconds.
        database.getCollection("commandReceipts").createIndex(
            Indexes.ascending("createdAt"),
            IndexOptions().name("created_at_ttl").expireAfter(24, TimeUnit.HOURS),
        )
    }

    // Deliberately does nothing: these indexes may predate this change, and dropping them would remove
    // the event store's protection against conflicting writes. Applying again is safe.
    @Rollback
    fun rollback() = Unit
}
