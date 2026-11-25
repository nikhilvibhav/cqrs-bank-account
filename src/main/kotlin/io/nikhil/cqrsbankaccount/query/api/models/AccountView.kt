package io.nikhil.cqrsbankaccount.query.api.models

import io.nikhil.cqrsbankaccount.common.dtos.AccountType
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.core.mapping.Field
import org.springframework.data.mongodb.core.mapping.FieldType
import java.math.BigDecimal
import java.time.Instant

/**
 * An account as the query API shows it: one document per account in the `accounts` read model, kept up
 * to date by [io.nikhil.cqrsbankaccount.query.api.projections.AccountProjection].
 *
 * @property id the account id.
 * @property accountHolder the account holder's name.
 * @property accountType the kind of account.
 * @property balance the current balance.
 * @property createdAt when the account was opened.
 * @property closed whether the account has been closed.
 * @property version the version of the latest event applied to this view; -1 for views written before
 *   versions were stored.
 */
@Document("accounts")
data class AccountView(
    @Id val id: String,
    val accountHolder: String,
    val accountType: AccountType,
    @field:Field(targetType = FieldType.DECIMAL128)
    val balance: BigDecimal,
    val createdAt: Instant,
    val closed: Boolean = false,
    val version: Int = -1,
)
