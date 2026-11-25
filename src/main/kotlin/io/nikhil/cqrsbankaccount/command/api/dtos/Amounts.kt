package io.nikhil.cqrsbankaccount.command.api.dtos

/**
 * The most digits a request amount may have before the decimal point: small enough that balances stay far
 * inside MongoDB Decimal128's 34 significant digits.
 */
const val MAX_AMOUNT_INTEGER_DIGITS = 13

/** The most digits a request amount may have after the decimal point: amounts are in whole cents. */
const val AMOUNT_FRACTION_DIGITS = 2
