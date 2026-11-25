package io.nikhil.cqrsbankaccount

import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.mongodb.MongoDBContainer

/**
 * Base class for Spring Boot tests: the application migrates MongoDB at startup, so every context needs
 * it. One replica set is started on first use and shared by all test classes. Subclasses add
 * `@Testcontainers(disabledWithoutDocker = true)` so they are skipped, rather than failing, without Docker.
 */
abstract class MongoIntegrationTest {
    companion object {
        private val mongo: MongoDBContainer by lazy {
            MongoDBContainer("mongo:8.0.17")
                .withReplicaSet()
                .withCommand("--replSet", "docker-rs", "--wiredTigerCacheSizeGB", "0.25")
                .apply { start() }
        }

        @JvmStatic
        @DynamicPropertySource
        fun registerMongoProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.mongodb.uri") { mongo.replicaSetUrl }
        }
    }
}
