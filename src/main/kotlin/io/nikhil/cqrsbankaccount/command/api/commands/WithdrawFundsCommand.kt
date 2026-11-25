package io.nikhil.cqrsbankaccount.command.api.commands

import java.math.BigDecimal

data class WithdrawFundsCommand(
    override val accountId: String,
    val amount: BigDecimal,
    override val idempotencyKey: String? = null,
) : AccountCommand()
