package io.nikhil.cqrsbankaccount.command.api.dtos

import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.Positive
import java.math.BigDecimal

/**
 * Request body for a deposit or a withdrawal.
 *
 * @property amount a positive amount in whole cents, with at most [MAX_AMOUNT_INTEGER_DIGITS] integer digits.
 */
data class FundsRequest(
    @field:Positive
    @field:Digits(integer = MAX_AMOUNT_INTEGER_DIGITS, fraction = AMOUNT_FRACTION_DIGITS)
    val amount: BigDecimal,
)
