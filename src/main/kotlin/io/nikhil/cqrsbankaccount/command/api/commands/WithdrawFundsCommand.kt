package io.nikhil.cqrsbankaccount.command.api.commands

import java.math.BigDecimal

/**
 * Takes funds from an open account. The account cannot be overdrawn.
 *
 * @property accountId the account to debit.
 * @property amount the amount to withdraw; must be greater than zero and at most the balance.
 * @property idempotencyKey optional client key that makes retries of this command safe.
 */
data class WithdrawFundsCommand(
    override val accountId: String,
    val amount: BigDecimal,
    override val idempotencyKey: String? = null,
) : AccountCommand()
