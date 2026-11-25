package io.nikhil.cqrsbankaccount.core.events

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

// Recorded for every command, with or without a client idempotency key: when a commit's outcome is
// unknown, the retry finds this receipt instead of applying the command a second time. A TTL index on
// createdAt (see the migrations package) removes receipts after 24 hours.
@Document("commandReceipts")
data class CommandReceipt(
    @Id val commandId: String,
    val aggregateIdentifier: String,
    val fingerprint: String,
    val version: Int? = null,
    val createdAt: Instant = Instant.now(),
)
