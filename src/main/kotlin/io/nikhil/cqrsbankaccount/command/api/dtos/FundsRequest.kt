package io.nikhil.cqrsbankaccount.command.api.dtos

import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.Positive
import java.math.BigDecimal

data class FundsRequest(
    @field:Positive
    @field:Digits(integer = MAX_AMOUNT_INTEGER_DIGITS, fraction = AMOUNT_FRACTION_DIGITS)
    val amount: BigDecimal,
)
