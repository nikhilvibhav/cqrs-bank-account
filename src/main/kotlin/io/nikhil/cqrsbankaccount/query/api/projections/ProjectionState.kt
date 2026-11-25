package io.nikhil.cqrsbankaccount.query.api.projections

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Where a projection is in the event store's change stream.
 *
 * @property name the projection's name.
 * @property resumeToken the change stream's resume token, serialised as JSON.
 * @property updatedAt when the checkpoint was last saved.
 */
@Document("projectionCheckpoints")
data class ProjectionCheckpoint(
    @Id val name: String,
    val resumeToken: String,
    val updatedAt: Instant = Instant.now(),
)

/**
 * An account whose projection failed and is retried until it succeeds. It is kept in MongoDB so the retry
 * survives a restart even though the change stream has moved past the failed event.
 *
 * @property accountId the account to project again.
 * @property error the message of the latest failure.
 * @property failedAt when the latest failure happened.
 */
@Document("projectionRetries")
data class ProjectionRetry(
    @Id val accountId: String,
    val error: String?,
    val failedAt: Instant = Instant.now(),
)
