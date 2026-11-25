package io.nikhil.cqrsbankaccount.core.events

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.util.*

/**
 * One stored event in the `eventStore` collection. Indexes are created by the migrations package; a
 * unique index on ([aggregateIdentifier], [version]) stops two writers appending the same version.
 *
 * @property id MongoDB's id for the document.
 * @property timestamp when the event was stored.
 * @property aggregateIdentifier the aggregate the event belongs to.
 * @property aggregateType the kind of aggregate, such as `Account`.
 * @property version the event's position in its aggregate's stream.
 * @property eventType the event's simple class name, for reading the collection by hand.
 * @property eventData the event itself, stored with its type alias as `_class`.
 */
@Document(collection = "eventStore")
data class EventModel(
    @Id var id: String? = null,
    var timestamp: Date = Date(),
    var aggregateIdentifier: String = "",
    var aggregateType: String = "",
    var version: Int = 0,
    var eventType: String = "",
    var eventData: BaseEvent? = null,
)
