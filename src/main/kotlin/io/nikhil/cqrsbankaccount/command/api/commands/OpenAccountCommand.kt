package io.nikhil.cqrsbankaccount.command.api.commands

import io.nikhil.cqrsbankaccount.common.dtos.AccountType
import java.math.BigDecimal

data class OpenAccountCommand(
    override val accountId: String,
    val accountHolder: String,
    val accountType: AccountType,
    val openingBalance: BigDecimal = BigDecimal.ZERO,
    override val idempotencyKey: String? = null,
) : AccountCommand()
