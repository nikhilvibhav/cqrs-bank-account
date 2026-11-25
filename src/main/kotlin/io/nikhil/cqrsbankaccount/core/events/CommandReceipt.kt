package io.nikhil.cqrsbankaccount.core.events

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Proof that a command was applied, stored in the same transaction as its events.
 *
 * Recorded for every command, with or without a client idempotency key: when a commit's outcome is
 * unknown, the retry finds this receipt instead of applying the command a second time. A TTL index on
 * [createdAt] (see the migrations package) removes receipts after 24 hours.
 *
 * @property commandId the client's idempotency key, or a generated id when there was none.
 * @property aggregateIdentifier the aggregate the command changed.
 * @property fingerprint a hash of the command's content, to detect a key reused for a different command.
 * @property version the aggregate's event version after the command; null on receipts written before
 *   versions were stored.
 * @property createdAt when the command was applied.
 */
@Document("commandReceipts")
data class CommandReceipt(
    @Id val commandId: String,
    val aggregateIdentifier: String,
    val fingerprint: String,
    val version: Int? = null,
    val createdAt: Instant = Instant.now(),
)
