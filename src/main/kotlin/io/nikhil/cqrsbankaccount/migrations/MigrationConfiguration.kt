package io.nikhil.cqrsbankaccount.migrations

import com.mongodb.client.MongoClient
import io.flamingock.store.mongodb.sync.MongoDBSyncAuditStore
import io.flamingock.targetsystem.mongodb.sync.MongoDBSyncTargetSystem
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.mongodb.core.MongoTemplate

// Flamingock applies the change units in this package once per database, in file-name order, and records
// each in the flamingockAuditLog collection (with a lock in flamingockLock, so one instance migrates at a
// time). flamingock.management-mode=INITIALIZING_BEAN runs them while the context starts, before the web
// server and projection worker; a failed change stops the application from starting.
//
// Change units are never edited once released: to change something they did, add a new one.
@Configuration(proxyBeanMethods = false)
class MigrationConfiguration {
    // The sync-driver target system rather than the Spring Data one, whose documented support stops at
    // Spring Data MongoDB 4.x. It uses the application's own client and database.
    @Bean
    fun accountDatabase(mongoClient: MongoClient, mongoTemplate: MongoTemplate): MongoDBSyncTargetSystem =
        MongoDBSyncTargetSystem(ACCOUNT_DATABASE, mongoClient, mongoTemplate.db.name)

    @Bean
    fun migrationAuditStore(accountDatabase: MongoDBSyncTargetSystem): MongoDBSyncAuditStore =
        MongoDBSyncAuditStore.from(accountDatabase)

    companion object {
        const val ACCOUNT_DATABASE = "account-database"
    }
}
