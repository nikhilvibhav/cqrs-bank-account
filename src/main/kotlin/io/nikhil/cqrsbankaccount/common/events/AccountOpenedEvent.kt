package io.nikhil.cqrsbankaccount.common.events

import io.nikhil.cqrsbankaccount.common.dtos.AccountType
import org.springframework.data.annotation.TypeAlias
import org.springframework.data.mongodb.core.mapping.Field
import org.springframework.data.mongodb.core.mapping.FieldType
import java.math.BigDecimal
import java.time.Instant

@TypeAlias("AccountOpened")
data class AccountOpenedEvent(
    val accountHolder: String,
    val accountType: AccountType,
    @field:Field(targetType = FieldType.DECIMAL128)
    val openingBalance: BigDecimal,
    val createdAt: Instant,
    val accountId: String,
) : AccountEvent(accountId)
