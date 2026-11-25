package io.nikhil.cqrsbankaccount.command.api.commands

import io.nikhil.cqrsbankaccount.core.commands.BaseCommand

/**
 * Every command an account accepts. Being sealed, `when` over it is checked for exhaustiveness.
 */
sealed class AccountCommand : BaseCommand() {
    /** The account the command applies to. */
    abstract val accountId: String
}
