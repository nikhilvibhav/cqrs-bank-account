package io.nikhil.cqrsbankaccount.common.events

import org.springframework.boot.persistence.autoconfigure.EntityScanner
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.mongodb.MongoManagedTypes
import org.springframework.data.mongodb.core.mapping.Document

/** Registers the account event classes with Spring Data MongoDB so their stored type aliases resolve. */
@Configuration(proxyBeanMethods = false)
class AccountEventMappingConfiguration {
    /**
     * Replaces Boot's default, which only scans for `@Document` classes. Events are embedded in event store
     * documents rather than being documents themselves, so they are added explicitly: Spring Data can only
     * resolve a stored type alias to a class it already knows about.
     */
    @Bean
    fun mongoManagedTypes(context: ApplicationContext): MongoManagedTypes =
        MongoManagedTypes.fromIterable(EntityScanner(context).scan(Document::class.java) + AccountEvent.types)
}
