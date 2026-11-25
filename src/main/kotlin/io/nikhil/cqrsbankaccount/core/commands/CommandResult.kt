package io.nikhil.cqrsbankaccount.core.commands

/**
 * The outcome of a successful command.
 *
 * @property aggregateId the aggregate the command changed.
 * @property version the aggregate's event version afterwards. A client can compare it with the read
 *   model's to know when its write is visible there. It is null only when replaying a command whose receipt
 *   was recorded before receipts stored versions.
 */
data class CommandResult(
    val aggregateId: String,
    val version: Int?,
)
