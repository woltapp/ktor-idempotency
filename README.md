## Ktor-Idempotency Plugin

A Ktor library to handle idempotency checks for HTTP requests. It ensures the same request isn’t processed twice, but the client always gets the same result as the first request.

### Why use it?
* TL;DR: For safe API retries.
* In distributed systems, retrying requests is common, especially after errors.
* Example: If a service consuming Kafka events makes an HTTP request to another service to deduct money, a connection error might leave the balance update uncertain. Retrying the event can be safe if the balance service supports idempotency, preventing multiple deductions.


### How does it work?
* **Idempotency Support**: Saves the response of a request using an idempotency key, returning the saved response for repeated requests with the same key.
* **Parallel requests**: If a second request with the same key arrives while the first request is still being processed, responds with status code 409. The request can be safely retried to get the saved response for the first request.
* **Response Expiry**: Configurable TTL to manage cache size. Adjust TTL based on storage size and risk of key collision.
* **Delete Expired Responses**: Periodically deletes expired responses to free up storage space.
* **Request Parameters**: Checks idempotency key, HTTP method, and path. Allows using the same key for different paths (e.g., /api/v1/accounts, /api/v1/purchases/{purchase-id}/delivery).
* **Idempotency Key**: Clients should use UUIDs to avoid collisions and ensure randomness.
* **HTTP GET**: Responses are ignored by default but can be included in the configuration, so idempotency checks for GET requests are skipped.


### Installation


1. Add the dependency to your project. (TODO: After extracting the plugin to a separate repo or publishing it to a artifact repository)


2. Add the plugin to your Ktor application.

```Kotlin
fun main() {
    embeddedServer(
        Netty,
        port = 8080,
        host = "0.0.0.0",
        module = Application::module
    ).start(wait = true)
}

fun Application.module() {
    routing {
        route("/api/v1/with-idempotency-support") {
            post("/random") {
                call.respond("Here is your random id: ${UUID.randomUUID()}")
            }
        }
        route("/api/v2/with-idempotency-support") {
            // all the routes in this block will have idempotency support
            install(IdempotencyPlugin) {
                idempotentResponseRepository = InMemoryIdempotentResponseRepository()
            }
            post("random") {
                call.respond("Here is your random id: ${UUID.randomUUID()}")
            }
            delete("/orders/{id}") {
                call.respond("Order deleted. Reference id: ${UUID.randomUUID()}")
            }
        }
    }
}


class InMemoryIdempotentResponseRepository : IdempotentResponseRepository {
    private val responses = mutableMapOf<String, ByteArray>()

    override fun storeResponse(resource: String, idempotencyKey: IdempotencyKey, response: ByteArray) {
        responses["$resource:$idempotencyKey"] = response
    }

    override fun getResponse(resource: String, idempotencyKey: IdempotencyKey): ByteArray? {
        return responses["$resource:$idempotencyKey"]
    }
    override fun deleteExpiredResponses(lastValidDate: OffsetDateTime) {
        // delete expired responses that are older than lastValidDate
        // for the testing purpose, you could also delete all the responses
        responses.clear()
    }
}

```

In the example above, if you make a POST request to `/api/v2/with-idempotency-support/random` with an HTTP header `Idempotency-Key: key-value`, the response will be saved. If you make the same request again with the same key, you will get the same response. The same applies to the DELETE request.


### Configuration
You must provide an implementation of `IdempotentResponseRepository` to store and retrieve responses. For testing, you can use `InMemoryIdempotentResponseRepository`. For production, you should use a persistent and centralized storage like Redis or a database.

Other configurations:
```Kotlin
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
```

Listeners can be used to log events or send metrics to monitoring systems. You can implement `EventListener` to listen to events.
You can also use it for testing to verify that the plugin is working as expected.

```Kotlin
install(IdempotencyPlugin) {
    idempotentResponseRepository = InMemoryIdempotentResponseRepository()
    eventListener = object : EventListener {
        override fun onEvent(event: Event) {
            when (event.eventType) {
                EventType.STORED_RESPONSE_USED -> {
                    println("Stored response used for ${event.resource}:${event.idempotencyKey}")
                }
                EventType.STORED_RESPONSE_EXPIRED -> {
                    println("Stored response expired for ${event.resource}:${event.idempotencyKey}")
                }
            }
        }
    }
}
```

##### Initial contributors
@muatik & @nualn