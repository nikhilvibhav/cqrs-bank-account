package io.nikhil.cqrsbankaccount.common.events

import org.springframework.data.annotation.TypeAlias
import org.springframework.data.mongodb.core.mapping.Field
import org.springframework.data.mongodb.core.mapping.FieldType
import java.math.BigDecimal

/**
 * Recorded when funds are withdrawn from an account.
 *
 * @property accountId the account that was debited.
 * @property amount the amount withdrawn; always positive.
 */
@TypeAlias("FundsWithdrawn")
data class FundsWithdrawnEvent(
    val accountId: String,
    @field:Field(targetType = FieldType.DECIMAL128)
    val amount: BigDecimal,
) : AccountEvent(accountId)
