package io.nikhil.cqrsbankaccount.query.api.projections

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

// Where a projection is in the event store's change stream, as a serialised resume token.
@Document("projectionCheckpoints")
data class ProjectionCheckpoint(
    @Id val name: String,
    val resumeToken: String,
    val updatedAt: Instant = Instant.now(),
)

// An account whose projection failed and is retried until it succeeds; kept in MongoDB so the retry
// survives a restart even though the change stream has moved past the failed event.
@Document("projectionRetries")
data class ProjectionRetry(
    @Id val accountId: String,
    val error: String?,
    val failedAt: Instant = Instant.now(),
)
