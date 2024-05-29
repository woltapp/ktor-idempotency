package com.wolt.utils.ktor.idempotency

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.hooks.ResponseBodyReadyForSend
import io.ktor.server.http.content.HttpStatusCodeContent
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.util.toByteArray
import io.ktor.util.toMap
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.OffsetDateTime

val IdempotencyPlugin =
    createRouteScopedPlugin(
        name = "IdempotencyPlugin",
        createConfiguration = ::PluginConfiguration,
    ) {

        val responseRepository =
            pluginConfig.idempotentResponseRepository
                ?: throw IllegalArgumentException("IdempotentRequestRepository must be provided")

        CleanUpWorker(
            scope = pluginConfig.cleanUpWorkerScope,
            jitter = pluginConfig.cleanUpWorkerJitter,
            interval = pluginConfig.cleanUpWorkerInterval,
            idempotentResponseRepository = responseRepository,
            storedResponseTTL = pluginConfig.storedResponseTTL,
        ).start()

        val logger = KotlinLogging.logger {}

        onCall { call ->
            try {
                interceptRequest(call, logger, pluginConfig, responseRepository)
            } catch (e: Exception) {
                logger.error { "Cannot intercept request: $e to handle idempotency" }
                if (pluginConfig.failOnError) {
                    throw e
                }
            }
        }

        on(ResponseBodyReadyForSend) { call, content ->
            try {
                storeResponse(call, logger, content, pluginConfig)
            } catch (e: Exception) {
                logger.error { "Cannot store response: $e" }
                if (pluginConfig.failOnError) {
                    throw e
                }
            }
        }
    }

private suspend fun interceptRequest(
    call: ApplicationCall,
    logger: KLogger,
    pluginConfig: PluginConfiguration,
    responseRepository: IdempotentResponseRepository,
) {
    logger.info { "Intercepting request: ${call.request.httpMethod} ${call.request.uri}" }
    call.attributes.put(PluginConfiguration.attributeKey, false)
    if (call.request.httpMethod !in pluginConfig.idempotentHttpMethods) {
        return
    }
    val idempotencyKey = getIdempotencyKey(call) ?: return

    logger.debug { "Idempotency key: $idempotencyKey" }
    val requestIdentity = getRequestIdentity(call)
    val idempotencyRecord = responseRepository.getResponseOrLock(requestIdentity, idempotencyKey) ?: return

    if (idempotencyRecord.isInProgress) {
        call.attributes.put(PluginConfiguration.attributeKey, true)
        call.respondText(
            text = "There is currently another in-progress request with this Idempotency Key.",
            contentType = ContentType.Text.Plain,
            status = HttpStatusCode.Conflict,
        )
        return
    }

    val storedResponse = Json.decodeFromString<IdempotentResponse>(String(idempotencyRecord.response))

    if (storedResponse.validUntil.isBefore(OffsetDateTime.now())) {
        pluginConfig.eventListener?.onEvent(
            Event(
                eventType = EventType.STORED_RESPONSE_EXPIRED,
                resource = requestIdentity,
                idempotencyKey = idempotencyKey,
            ),
        )
        return
    }

    call.attributes.put(PluginConfiguration.attributeKey, true)

    sendStoredResponse(logger, idempotencyKey, storedResponse, call)

    pluginConfig.eventListener?.onEvent(
        Event(
            eventType = EventType.STORED_RESPONSE_USED,
            resource = requestIdentity,
            idempotencyKey = idempotencyKey,
        ),
    )
}

private suspend fun sendStoredResponse(
    logger: KLogger,
    idempotencyKey: IdempotencyKey,
    storedResponse: IdempotentResponse,
    call: ApplicationCall,
) {
    logger.debug { "Returning stored response for idempotent request: $idempotencyKey" }

    logger.debug { "Stored response: $storedResponse" }

    storedResponse.headers.forEach { (key, values) ->
        values.forEach { value ->
            call.response.headers.append(key, value)
        }
    }

    val contentType =
        storedResponse.contentType?.let {
            ContentType(storedResponse.contentType.contentType, storedResponse.contentType.contentSubType)
        }

    val status = HttpStatusCode.fromValue(storedResponse.status)

    when (storedResponse.responseType) {
        SupportedResponseTypes.TextContent ->
            call.respondText(
                text = storedResponse.content?.let { String(storedResponse.content) } ?: "",
                contentType = ContentType.Application.Json,
                status = status,
            )

        SupportedResponseTypes.ReadChannelContent, SupportedResponseTypes.ByteArrayContent ->
            call.respondBytes(
                bytes = storedResponse.content!!,
                contentType = contentType,
                status = status,
            )

        SupportedResponseTypes.HttpStatusCodeContent -> call.respond(status)
    }
}

private suspend fun storeResponse(
    call: ApplicationCall,
    logger: KLogger,
    content: OutgoingContent,
    pluginConfig: PluginConfiguration,
) {
    val isStoredResponse = call.attributes[PluginConfiguration.attributeKey]
    if (isStoredResponse) {
        return
    }

    if (call.request.httpMethod !in pluginConfig.idempotentHttpMethods) {
        return
    }

    val idempotencyKey = getIdempotencyKey(call) ?: return
    val requestIdentity = getRequestIdentity(call)

    val status =
        when (content) {
            is OutgoingContent.ByteArrayContent -> content.status?.value ?: call.response.status()?.value
            is HttpStatusCodeContent -> content.status.value
            else -> null
        } ?: HttpStatusCode.OK.value

    val headers = call.response.headers.allValues().toMap()

    val responseType =
        when (content) {
            is TextContent -> SupportedResponseTypes.TextContent
            is OutgoingContent.ByteArrayContent -> SupportedResponseTypes.ByteArrayContent
            is HttpStatusCodeContent -> SupportedResponseTypes.HttpStatusCodeContent
            is OutgoingContent.ReadChannelContent -> SupportedResponseTypes.ReadChannelContent
            else -> throw Exception("Unsupported content type")
        }

    val response =
        IdempotentResponse(
            status = status,
            headers = headers,
            responseType = responseType,
            contentType = IdempotentContentTypes.fromContentType(content.contentType),
            content = getStoredResponseContent(content),
            createdAt = OffsetDateTime.now(),
            validUntil = OffsetDateTime.now().plus(pluginConfig.storedResponseTTL),
        )

    val serialisedResponse = Json.encodeToString(response)
    logger.debug { "Storing response for idempotent request: $idempotencyKey" }
    pluginConfig.idempotentResponseRepository?.storeResponse(
        requestIdentity,
        idempotencyKey,
        serialisedResponse.toByteArray(),
    )
}

private fun getRequestIdentity(call: ApplicationCall) = "${call.request.httpMethod.value} ${call.request.uri}"

private suspend fun getStoredResponseContent(content: OutgoingContent) =
    when (content) {
        is OutgoingContent.ByteArrayContent -> content.bytes()
        is OutgoingContent.ReadChannelContent -> content.readFrom().toByteArray()
        else -> null
    }

private const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"

fun getIdempotencyKey(call: ApplicationCall): IdempotencyKey? = call.request.headers[IDEMPOTENCY_KEY_HEADER]?.let(::IdempotencyKey)
