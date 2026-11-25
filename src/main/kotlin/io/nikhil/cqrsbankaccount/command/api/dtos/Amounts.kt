package io.nikhil.cqrsbankaccount.command.api.dtos

// Limits for monetary amounts in requests: whole cents, and small enough that balances stay far inside
// MongoDB Decimal128's 34 significant digits.
const val MAX_AMOUNT_INTEGER_DIGITS = 13
const val AMOUNT_FRACTION_DIGITS = 2
