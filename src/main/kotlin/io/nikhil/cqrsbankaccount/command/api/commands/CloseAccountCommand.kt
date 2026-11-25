package io.nikhil.cqrsbankaccount.command.api.commands

/**
 * Closes an account. Only an open account with a zero balance can be closed.
 *
 * @property accountId the account to close.
 * @property idempotencyKey optional client key that makes retries of this command safe.
 */
data class CloseAccountCommand(
    override val accountId: String,
    override val idempotencyKey: String? = null,
) : AccountCommand()
