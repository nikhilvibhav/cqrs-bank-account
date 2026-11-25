package io.nikhil.cqrsbankaccount.command.api.dtos

import io.nikhil.cqrsbankaccount.common.dtos.AccountType
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.math.BigDecimal

/**
 * Request body for opening an account.
 *
 * @property accountHolder the account holder's name; not blank and at most 100 characters.
 * @property accountType the kind of account to open.
 * @property openingBalance the initial balance in whole cents; zero when omitted, and never negative.
 */
data class OpenAccountRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val accountHolder: String,
    val accountType: AccountType,
    @field:PositiveOrZero
    @field:Digits(integer = MAX_AMOUNT_INTEGER_DIGITS, fraction = AMOUNT_FRACTION_DIGITS)
    val openingBalance: BigDecimal = BigDecimal.ZERO,
)
