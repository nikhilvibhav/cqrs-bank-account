package io.nikhil.cqrsbankaccount.query.api.models

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.mongodb.repository.MongoRepository

/**
 * Read access to the account read model. `AccountProjection` writes it with `MongoTemplate` instead, since
 * it needs conditional, atomic updates.
 */
interface AccountViewRepository : MongoRepository<AccountView, String> {
    /** Returns a page of the accounts that are closed, or open, as [closed] says. */
    fun findByClosed(closed: Boolean, pageable: Pageable): Page<AccountView>
}
