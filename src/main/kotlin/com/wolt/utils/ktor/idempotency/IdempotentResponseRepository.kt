package com.wolt.utils.ktor.idempotency

import io.ktor.http.ContentType
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

interface IdempotentResponseRepository {
    fun storeResponse(
        resource: String,
        idempotencyKey: IdempotencyKey,
        response: ByteArray,
    )

    fun getResponseOrLock(
        resource: String,
        idempotencyKey: IdempotencyKey,
    ): IdempotencyResponse?

    fun deleteExpiredResponses(lastValidDate: OffsetDateTime)
}

data class IdempotencyResponse(
    val isInProgress: Boolean,
    val response: ByteArray,
)

@JvmInline
value class IdempotencyKey(val value: String) {
    override fun toString(): String {
        return value
    }
}

@Serializable
data class IdempotentResponse(
    val status: Int,
    val headers: Map<String, List<String>>,
    val responseType: SupportedResponseTypes,
    val contentType: IdempotentContentTypes?,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val validUntil: OffsetDateTime,
    val content: ByteArray?,
)

enum class SupportedResponseTypes {
    TextContent,
    ByteArrayContent,
    ReadChannelContent,
    HttpStatusCodeContent,
}

@Serializable
data class IdempotentContentTypes(
    val contentType: String,
    val contentSubType: String,
) {
    companion object {
        fun fromContentType(contentType: ContentType?): IdempotentContentTypes? =
            contentType?.let {
                IdempotentContentTypes(contentType.toString(), contentType.contentSubtype)
            }
    }
}
