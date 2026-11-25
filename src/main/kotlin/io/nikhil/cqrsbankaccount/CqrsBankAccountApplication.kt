package io.nikhil.cqrsbankaccount

import io.flamingock.api.annotations.EnableFlamingock
import io.flamingock.api.annotations.Stage
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Entry point of the bank account service.
 *
 * The service follows CQRS with event sourcing: commands are validated by [command.domain.AccountAggregate]
 * and stored as events in MongoDB, and [query.api.projections.AccountProjectionWorker] projects those events
 * into the `accounts` read model served by the query API. Flamingock applies the MongoDB migrations in the
 * `migrations` package while the context starts.
 */
@EnableFlamingock(stages = [Stage(location = "io.nikhil.cqrsbankaccount.migrations")])
@SpringBootApplication
class CqrsBankAccountApplication

/** Starts the Spring Boot application. */
fun main(args: Array<String>) {
    runApplication<CqrsBankAccountApplication>(*args)
}
