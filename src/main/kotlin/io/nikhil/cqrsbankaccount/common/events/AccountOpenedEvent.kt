package io.nikhil.cqrsbankaccount.common.events

import io.nikhil.cqrsbankaccount.common.dtos.AccountType
import org.springframework.data.annotation.TypeAlias
import org.springframework.data.mongodb.core.mapping.Field
import org.springframework.data.mongodb.core.mapping.FieldType
import java.math.BigDecimal
import java.time.Instant

/**
 * Recorded when an account is opened. It is always the first event of an account's stream, at version 0.
 *
 * @property accountHolder the account holder's name, already trimmed.
 * @property accountType the kind of account.
 * @property openingBalance the initial balance.
 * @property createdAt when the account was opened.
 * @property accountId the new account's id.
 */
@TypeAlias("AccountOpened")
data class AccountOpenedEvent(
    val accountHolder: String,
    val accountType: AccountType,
    @field:Field(targetType = FieldType.DECIMAL128)
    val openingBalance: BigDecimal,
    val createdAt: Instant,
    val accountId: String,
) : AccountEvent(accountId)
