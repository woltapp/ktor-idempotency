package com.wolt.utils.ktor.idempotency

interface EventListener {
    fun onEvent(event: Event)
}

data class Event(
    val eventType: EventType,
    val resource: String,
    val idempotencyKey: IdempotencyKey,
)

enum class EventType {
    /**
     * Event type for when a stored response is returned to the client skipping
     * the actual request processing.
     */
    STORED_RESPONSE_USED,

    /**
     * Event type for when a stored response is expired and proceeding with the
     * actual request processing.
     */
    STORED_RESPONSE_EXPIRED,
}
