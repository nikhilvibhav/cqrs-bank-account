package io.nikhil.cqrsbankaccount.core.infra

import com.mongodb.MongoCommandException
import io.nikhil.cqrsbankaccount.core.events.CommandReceipt
import io.nikhil.cqrsbankaccount.core.events.EventModel
import io.nikhil.cqrsbankaccount.query.api.models.AccountView
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataAccessException
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.index.Index
import org.springframework.stereotype.Component
import java.time.Duration

// Creates indexes once all beans exist and before any lifecycle component starts, so the web server and
// projection worker never run against unindexed collections. Creating an existing index is a no-op.
@Component
class MongoIndexInitializer(
    private val mongoTemplate: MongoTemplate,
    @Value("\${app.idempotency.receipt-ttl:24h}") private val receiptTtl: Duration,
) : SmartInitializingSingleton {
    private val log = LoggerFactory.getLogger(MongoIndexInitializer::class.java)

    override fun afterSingletonsInstantiated() {
        ensureIndex(
            EventModel::class.java,
            Index()
                .on("aggregateIdentifier", Sort.Direction.ASC)
                .on("version", Sort.Direction.ASC)
                .unique()
                .named("aggregate_version_unique"),
        )
        // Matches the account list's sort, including the _id tie-breaker.
        ensureIndex(
            AccountView::class.java,
            Index().on("createdAt", Sort.Direction.ASC).on("_id", Sort.Direction.ASC).named("created_at"),
        )
        ensureIndex(
            CommandReceipt::class.java,
            Index().on("createdAt", Sort.Direction.ASC).expire(receiptTtl).named("created_at_ttl"),
        )
    }

    private fun ensureIndex(type: Class<*>, index: Index) {
        val indexOps = mongoTemplate.indexOps(type)
        try {
            indexOps.createIndex(index)
        } catch (exception: DataAccessException) {
            // An index with the same name but different options (e.g. a changed TTL) already exists.
            val code = (exception.cause as? MongoCommandException)?.code
            if (code != INDEX_OPTIONS_CONFLICT && code != INDEX_KEY_SPECS_CONFLICT) throw exception
            val name = requireNotNull(index.indexOptions.getString("name"))
            log.info("Recreating index {} on {} with new options.", name, type.simpleName)
            indexOps.dropIndex(name)
            indexOps.createIndex(index)
        }
    }

    private companion object {
        const val INDEX_OPTIONS_CONFLICT = 85
        const val INDEX_KEY_SPECS_CONFLICT = 86
    }
}
