package io.nikhil.cqrsbankaccount.command.api.commands

import java.math.BigDecimal

/**
 * Adds funds to an open account.
 *
 * @property accountId the account to credit.
 * @property amount the amount to deposit; must be greater than zero.
 * @property idempotencyKey optional client key that makes retries of this command safe.
 */
data class DepositFundsCommand(
    override val accountId: String,
    val amount: BigDecimal,
    override val idempotencyKey: String? = null,
) : AccountCommand()
