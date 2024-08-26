package com.wolt.utils.ktor.idempotency

import io.ktor.http.HttpMethod
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.time.Duration

class PluginConfiguration {
    var idempotentResponseRepository: IdempotentResponseRepository? = null

    /**
     * If true, the plugin will throw an exception if an error occurs during the processing of the request.
     * If false, the plugin will log the error and continue processing the request.
     *
     * Default is true. It is recommended to set this to true in production environments to ensure
     * that duplicate requests are not processed. While in adoption phase, it is recommended to set this
     * to false.
     */
    var failOnError = true

    /**
     * The time-to-live for stored responses. After this time, the stored response will be considered
     * expired and will not be returned for subsequent requests but processed as a normal request.
     *
     * Default is 7 days.
     */
    var storedResponseTTL: Duration = Duration.ofDays(7)

    /**
     * The plugin will call this listener for events such as
     * stored response is retrieved and returned, or it is expired.
     */
    var eventListener: EventListener? = null

    /**
     * The plugin will only store responses for requests with these HTTP methods.
     */
    var idempotentHttpMethods =
        setOf(
            HttpMethod.Post,
            HttpMethod.Put,
            HttpMethod.Delete,
            HttpMethod.Patch,
        )

    /**
     * Flag that controls execution of clean up worker.
     * Clean up worker is enabled by default, but consider disabling it if your response repository can leverage a built-in expiration mechanism (e.g. Redis key expiration time).
     */
    var cleanUpWorkerEnabled = true

    /**
     * The coroutine scope for the worker that cleans up expired responses.
     */
    var cleanUpWorkerScope: CoroutineScope = CoroutineScope(Dispatchers.IO + CoroutineName("idempotencyCleanUpExpiredResponses"))

    /**
     * The interval at which the worker will clean up expired responses.
     */
    var cleanUpWorkerInterval: Duration = Duration.ofMinutes(10)

    /**
     * The jitter to add to the interval to prevent all workers from running at the same time.
     */
    var cleanUpWorkerJitter: Duration = Duration.ofMinutes(10)

    companion object {
        val attributeKey = AttributeKey<Boolean>("isIdempotentResponse")
    }
}
