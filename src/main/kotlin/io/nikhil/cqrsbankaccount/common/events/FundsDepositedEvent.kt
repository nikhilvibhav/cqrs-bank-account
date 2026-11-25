package io.nikhil.cqrsbankaccount.common.events

import org.springframework.data.annotation.TypeAlias
import org.springframework.data.mongodb.core.mapping.Field
import org.springframework.data.mongodb.core.mapping.FieldType
import java.math.BigDecimal

/**
 * Recorded when funds are deposited into an account.
 *
 * @property accountId the account that was credited.
 * @property amount the amount deposited; always positive.
 */
@TypeAlias("FundsDeposited")
data class FundsDepositedEvent(
    val accountId: String,
    @field:Field(targetType = FieldType.DECIMAL128)
    val amount: BigDecimal,
) : AccountEvent(accountId)
