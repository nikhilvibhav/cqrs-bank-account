package io.nikhil.cqrsbankaccount.common.events

import org.springframework.data.annotation.TypeAlias
import org.springframework.data.mongodb.core.mapping.Field
import org.springframework.data.mongodb.core.mapping.FieldType
import java.math.BigDecimal

@TypeAlias("FundsWithdrawn")
data class FundsWithdrawnEvent(
    val accountId: String,
    @field:Field(targetType = FieldType.DECIMAL128)
    val amount: BigDecimal,
) : AccountEvent(accountId)
