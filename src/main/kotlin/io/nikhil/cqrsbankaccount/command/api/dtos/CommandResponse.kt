package io.nikhil.cqrsbankaccount.command.api.dtos

// The account's event version after the command; GET /api/v1/accounts/{id} shows the change once the
// read model's version (its ETag) has reached it.
data class CommandResponse(
    val accountId: String,
    val version: Int?,
    val message: String,
)
