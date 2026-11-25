package io.nikhil.cqrsbankaccount.core.commands

// The aggregate a command changed and its event version afterwards. A client can compare the version
// with the read model's to know when its write is visible there. It is null only when replaying a
// command whose receipt was recorded before receipts stored versions.
data class CommandResult(
    val aggregateId: String,
    val version: Int?,
)
