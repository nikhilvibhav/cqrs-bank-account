package io.nikhil.cqrsbankaccount.command.api.commands

data class CloseAccountCommand(
    override val accountId: String,
    override val idempotencyKey: String? = null,
) : AccountCommand()
