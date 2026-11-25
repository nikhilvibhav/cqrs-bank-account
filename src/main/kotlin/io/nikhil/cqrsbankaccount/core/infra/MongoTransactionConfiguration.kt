package io.nikhil.cqrsbankaccount.core.infra

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.mongodb.MongoDatabaseFactory
import org.springframework.data.mongodb.MongoTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/** Enables MongoDB multi-document transactions, which the event store uses to append events and receipts. */
@Configuration(proxyBeanMethods = false)
class MongoTransactionConfiguration {
    /** The transaction manager for the application's MongoDB database. */
    @Bean
    fun mongoTransactionManager(databaseFactory: MongoDatabaseFactory) = MongoTransactionManager(databaseFactory)

    /** Runs a block of code in a MongoDB transaction. */
    @Bean
    fun mongoTransactionTemplate(transactionManager: MongoTransactionManager) = TransactionTemplate(transactionManager)
}
