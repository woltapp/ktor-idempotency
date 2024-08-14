package com.example

import com.wolt.utils.ktor.idempotency.IdempotencyKey
import com.wolt.utils.ktor.idempotency.IdempotencyResponse
import com.wolt.utils.ktor.idempotency.IdempotentResponseRepository
import java.util.concurrent.ConcurrentHashMap

class InMemoryResponseRepository : IdempotentResponseRepository {
    private val responses = ConcurrentHashMap<String, IdempotencyResponse>()

    override suspend fun storeResponse(
        resource: String,
        idempotencyKey: IdempotencyKey,
        response: ByteArray,
    ) {
        println("Storing response for idempotent request: $idempotencyKey")
        responses[generateKey(resource, idempotencyKey)] = IdempotencyResponse(isInProgress = false, response = response)
    }

    override suspend fun getResponseOrLock(
        resource: String,
        idempotencyKey: IdempotencyKey,
    ): IdempotencyResponse? {
        println("Retrieving response for idempotent request: $idempotencyKey")
        val record =
            responses.putIfAbsent(
                generateKey(resource, idempotencyKey),
                IdempotencyResponse(isInProgress = true, response = ByteArray(0)),
            )
        return record
    }

    override suspend fun deleteExpiredResponses(lastValidDate: java.time.OffsetDateTime) {
        responses.clear()
    }

    private fun generateKey(
        resource: String,
        idempotencyKey: IdempotencyKey,
    ) = "$resource:$idempotencyKey"
}
