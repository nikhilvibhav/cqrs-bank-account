package io.nikhil.cqrsbankaccount.command.api.dtos

/**
 * The response to a successful command.
 *
 * @property accountId the account the command changed.
 * @property version the account's event version after the command. `GET /api/v1/accounts/{id}` shows the
 *   change once the read model's version (its `ETag`) has reached it. Null only for a replay of a command
 *   whose receipt predates stored versions.
 * @property message a human-readable description of the outcome.
 */
data class CommandResponse(
    val accountId: String,
    val version: Int?,
    val message: String,
)
