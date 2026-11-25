package io.nikhil.cqrsbankaccount.query.api.projections

import io.nikhil.cqrsbankaccount.common.events.AccountClosedEvent
import io.nikhil.cqrsbankaccount.common.events.AccountEvent
import io.nikhil.cqrsbankaccount.common.events.AccountOpenedEvent
import io.nikhil.cqrsbankaccount.common.events.FundsDepositedEvent
import io.nikhil.cqrsbankaccount.common.events.FundsWithdrawnEvent
import io.nikhil.cqrsbankaccount.query.api.models.AccountView
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.integration.annotation.ServiceActivator
import org.springframework.stereotype.Component

/**
 * Applies account events to the `accounts` read model, one event at a time and in version order.
 *
 * Each view stores the version of the latest event applied to it, and every write is conditional on that
 * version. An event that was already applied is ignored, so delivering an event more than once is
 * harmless; an event that would leave a gap is rejected, so the caller can fetch the missing ones first.
 */
@Component
class AccountProjection(private val mongoTemplate: MongoTemplate) {
    /**
     * Applies [event] to its account's view, creating the view from the opening event.
     *
     * @throws IllegalStateException when [event] is not the next version for its account, or when another
     *   writer changed the view at the same time; applying the account's events again resolves both.
     */
    @ServiceActivator(inputChannel = "accountEventChannel")
    fun project(event: AccountEvent) {
        val accountId = requireNotNull(event.aggregateId) { "Account event is missing its aggregate ID." }
        val current = mongoTemplate.findById(accountId, AccountView::class.java)

        if (current != null && current.version >= event.version) return
        if (current == null) {
            check(event is AccountOpenedEvent && event.version == 0) {
                "Cannot project account $accountId: expected its opening event before version ${event.version}."
            }
            try {
                mongoTemplate.insert(
                    AccountView(
                        id = event.accountId,
                        accountHolder = event.accountHolder,
                        accountType = event.accountType,
                        balance = event.openingBalance,
                        createdAt = event.createdAt,
                        version = event.version,
                    ),
                )
            } catch (exception: DuplicateKeyException) {
                val latest = mongoTemplate.findById(accountId, AccountView::class.java)
                if (latest != null && latest.version >= event.version) return
                throw exception
            }
            return
        }

        check(event.version == current.version + 1) {
            "Cannot project event version ${event.version} for account $accountId after version ${current.version}."
        }

        val update = when (event) {
            // Only for rows written before the read model stored versions (version -1).
            is AccountOpenedEvent -> {
                check(current.version == -1 && event.version == 0) {
                    "Account $accountId has no opening event at version zero."
                }
                Update()
                    .set("accountHolder", event.accountHolder)
                    .set("accountType", event.accountType)
                    .set("balance", event.openingBalance)
                    .set("createdAt", event.createdAt)
                    .set("closed", false)
            }

            is FundsDepositedEvent -> Update().inc("balance", event.amount)

            is FundsWithdrawnEvent -> Update().inc("balance", event.amount.negate())

            is AccountClosedEvent -> Update().set("closed", true)
        }.set("version", event.version)

        val result = mongoTemplate.updateFirst(
            Query.query(
                Criteria.where("_id").`is`(accountId)
                    .and("version").`is`(current.version),
            ),
            update,
            AccountView::class.java,
        )
        if (result.matchedCount == 0L) {
            val latest = mongoTemplate.findById(accountId, AccountView::class.java)
            check(latest != null && latest.version >= event.version) {
                "Projection update raced for account $accountId at event version ${event.version}; it will be retried."
            }
        }
    }

    /** Returns the version of the latest event applied to the account's view, or -1 if there is no view. */
    fun getAppliedVersion(accountId: String): Int =
        mongoTemplate.findById(accountId, AccountView::class.java)?.version ?: -1
}
