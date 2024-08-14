
package com.example

import com.wolt.utils.ktor.idempotency.Event
import com.wolt.utils.ktor.idempotency.EventListener
import com.wolt.utils.ktor.idempotency.EventType
import com.wolt.utils.ktor.idempotency.IdempotencyKey
import com.wolt.utils.ktor.idempotency.IdempotencyPlugin
import com.wolt.utils.ktor.idempotency.IdempotentResponse
import com.wolt.utils.ktor.idempotency.SupportedResponseTypes
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpStatusCode.Companion.OK
import io.ktor.server.application.call
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertNull

class IdempotencyPluginTests {
    private lateinit var testEventListener: TestEventListener
    private lateinit var repository: InMemoryResponseRepository
    private lateinit var service: DummyService

    @Test
    fun shouldUseStoredResponseForTextResponse() =
        testApplication {
            setupModules(this)
            val idempotencyKey = UUID.randomUUID().toString()
            val path = "/test/respondWithText"

            val response = sendPostRequest(path, idempotencyKey)
            assertOkResponse(response, "Hello, world!")

            val response2 = sendPostRequest(path, idempotencyKey)
            assertOkResponse(response2, "Hello, world!")

            assertServiceCalledOnlyOnce()
        }

    @Test
    fun shouldUseStoredResponseForCustomHeader() =
        testApplication {
            setupModules(this)
            val idempotencyKey = UUID.randomUUID().toString()
            val path = "/test/customHeader"

            val response = sendPatchRequest(path, idempotencyKey)
            assertOkResponse(response, "Hello, world!")

            val response2 = sendPatchRequest(path, idempotencyKey)
            assertOkResponse(response2, "Hello, world!")
            assertEquals(response.headers["X-Custom-Header"], "value")

            assertServiceCalledOnlyOnce()
        }

    @Test
    fun shouldUseStoredResponseForOnlyStatus() =
        testApplication {
            setupModules(this)
            val idempotencyKey = UUID.randomUUID().toString()
            val path = "/test/onlyStatus"

            val response = sendPutRequest(path, idempotencyKey)
            assertResponse(response, HttpStatusCode.Created, "")

            val response2 = sendPutRequest(path, idempotencyKey)
            assertResponse(response2, HttpStatusCode.Created, "")

            assertServiceCalledOnlyOnce()
        }

    @Test
    fun shouldUseStoredResponseForStatusWithText() =
        testApplication {
            setupModules(this)
            val idempotencyKey = UUID.randomUUID().toString()
            val path = "/test/statusWithText"

            val response = sendDeleteRequest(path, idempotencyKey)
            assertResponse(response, HttpStatusCode.Created, "created")

            val response2 = sendDeleteRequest(path, idempotencyKey)
            assertResponse(response2, HttpStatusCode.Created, "created")

            assertServiceCalledOnlyOnce()
        }

    @Test
    fun shouldUseStoredResponseForNotFound() =
        testApplication {
            setupModules(this)
            val idempotencyKey = UUID.randomUUID().toString()
            val path = "/test/notfound"

            val response = sendPostRequest(path, idempotencyKey)
            assertResponse(response, HttpStatusCode.NotFound, "")

            val response2 = sendPostRequest(path, idempotencyKey)
            assertResponse(response2, HttpStatusCode.NotFound, "")

            assertServiceCalledOnlyOnce()
        }

    @Test
    fun shouldUseStoredResponseForInternalServerError() =
        testApplication {
            setupModules(this)
            val idempotencyKey = UUID.randomUUID().toString()
            val path = "/test/internalError"

            val response = sendPostRequest(path, idempotencyKey)
            assertResponse(response, HttpStatusCode.InternalServerError, "")

            val response2 = sendPostRequest(path, idempotencyKey)
            assertResponse(response2, HttpStatusCode.InternalServerError, "")

            assertServiceCalledOnlyOnce()
        }

    @Test
    fun shouldUseStoredResponseForException() =
        testApplication {
            setupModules(this)
            val idempotencyKey = UUID.randomUUID().toString()
            val path = "/test/exception"

            val response = sendPutRequest(path, idempotencyKey)
            assertResponse(response, HttpStatusCode.InternalServerError, "Internal Server Error")

            val response2 = sendPutRequest(path, idempotencyKey)
            assertResponse(response2, HttpStatusCode.InternalServerError, "Internal Server Error")

            assertServiceCalledOnlyOnce()
        }

    @Test
    fun shouldUseStoredResponseForBytes() =
        testApplication {
            setupModules(this)
            val idempotencyKey = UUID.randomUUID().toString()
            val path = "/test/bytes"

            val response = sendPostRequest(path, idempotencyKey)
            assertResponse(response, OK, "byte content")

            val response2 = sendPostRequest(path, idempotencyKey)
            assertResponse(response2, OK, "byte content")

            assertServiceCalledOnlyOnce()
        }

    @Test
    fun shouldUseStoredResponseForFile() =
        testApplication {
            setupModules(this)
            val idempotencyKey = UUID.randomUUID().toString()
            val path = "/test/file"

            val response = sendPostRequest(path, idempotencyKey)
            assertResponse(response, OK, "test file content")

            val response2 = sendPostRequest(path, idempotencyKey)
            assertResponse(response2, OK, "test file content")

            assertServiceCalledOnlyOnce()
        }

    @Test
    fun shouldUseStoredResponseForDifferentIdempotencyKeys() =
        testApplication {
            setupModules(this)
            val idempotencyKey1 = UUID.randomUUID().toString()
            val idempotencyKey2 = UUID.randomUUID().toString()
            val path = "/test/respondWithText"

            val response = sendPostRequest(path, idempotencyKey1)
            assertOkResponse(response, "Hello, world!")
            assertServiceCalled(timesInTotal = 1)

            val response2 = sendPostRequest(path, idempotencyKey2)
            assertOkResponse(response2, "Hello, world!")
            assertServiceCalled(timesInTotal = 2)
        }

    @Test
    fun shouldUseStoredResponseForDifferentPaths() =
        testApplication {
            setupModules(this)
            val idempotencyKey = UUID.randomUUID().toString()
            val path1 = "/test/respondWithText"
            val path2 = "/test/customHeader"

            val response = sendPostRequest(path1, idempotencyKey)
            assertOkResponse(response, "Hello, world!")
            assertServiceCalled(timesInTotal = 1)

            val response2 = sendPatchRequest(path2, idempotencyKey)
            assertOkResponse(response2, "Hello, world!")
            assertServiceCalled(timesInTotal = 2)
        }

    @Test
    fun shouldUseStoredResponseForDifferentMethods() =
        testApplication {
            setupModules(this)
            val idempotencyKey = UUID.randomUUID().toString()
            val path = "/test/respondWithText"

            val response = sendGetRequest(path, idempotencyKey)
            assertOkResponse(response, "Hello, world for get request!")
            assertServiceCalled(timesInTotal = 1)

            val response2 = sendPostRequest("/test/12/respondWithText?q=v", idempotencyKey)
            assertOkResponse(response2, "Hello, world!")
            assertServiceCalled(timesInTotal = 2)
        }

    @Test
    fun shouldNotUseStoredResponseWhenExpired() =
        testApplication {
            setupModules(this)

            val idempotencyKey = UUID.randomUUID().toString()
            val path = "/test/respondWithText"
            insertExpiredResponse(idempotencyKey, path)

            val response = sendPostRequest(path, idempotencyKey)
            assertOkResponse(response, "Hello, world!")
            assertServiceCalled(timesInTotal = 1)
        }

    @Test
    fun shouldNotStoreAgainWhenStoredResponseIsUsed() =
        testApplication {
            setupModules(this)
            val idempotencyKey = UUID.randomUUID().toString()
            val path = "/test/respondWithText"

            val response = sendPostRequest(path, idempotencyKey)
            assertOkResponse(response, "Hello, world!")
            assertServiceCalled(timesInTotal = 1)
            coVerify(exactly = 1) { repository.storeResponse("POST $path", any(), any()) }

            val response2 = sendPostRequest(path, idempotencyKey)
            assertOkResponse(response2, "Hello, world!")
            assertServiceCalled(timesInTotal = 1)
            // storeResponse should not be called again
            coVerify(exactly = 1) { repository.storeResponse("POST $path", any(), any()) }
        }

    @Test
    fun shouldEmitEventWhenStoredResponseIsUsed() =
        testApplication {
            setupModules(this)
            val idempotencyKey = UUID.randomUUID().toString()
            val path = "/test/respondWithText"

            val response = sendPostRequest(path, idempotencyKey)
            assertOkResponse(response, "Hello, world!")
            assertNoEventTriggered()

            val response2 = sendPostRequest(path, idempotencyKey)
            assertOkResponse(response2, "Hello, world!")
            assertTrue(testEventListener.hasEvent(EventType.STORED_RESPONSE_USED, "POST $path", idempotencyKey))
        }

    @Test
    fun shouldEmitEventWhenStoredResponseIsExpired() =
        testApplication {
            setupModules(this)
            val idempotencyKey = UUID.randomUUID().toString()
            val path = "/test/respondWithText"
            insertExpiredResponse(idempotencyKey, path)

            val response = sendPostRequest(path, idempotencyKey)
            assertOkResponse(response, "Hello, world!")
            assertTrue(testEventListener.hasEvent(EventType.STORED_RESPONSE_EXPIRED, "POST $path", idempotencyKey))
        }

    @Test
    fun shouldIgnoreGetRequestsBecauseItIsNotConfigured() =
        testApplication {
            setupModules(this)
            val idempotencyKey = UUID.randomUUID().toString()
            val path = "/test/respondWithText"

            val response1 = sendGetRequest(path, idempotencyKey)
            val response2 = sendGetRequest(path, idempotencyKey)

            assertOkResponse(response1, "Hello, world for get request!")
            assertOkResponse(response2, "Hello, world for get request!")

            coVerify(exactly = 0) { repository.storeResponse(any(), any(), any()) }
            assertNoEventTriggered()
            assertServiceCalled(timesInTotal = 2)
        }

    @Test
    fun shouldCleanUpExpiredResponses() =
        testApplication {
            repository = InMemoryResponseRepository()
            install(IdempotencyPlugin) {
                idempotentResponseRepository = repository
                cleanUpWorkerInterval = Duration.ofMillis(1)
                cleanUpWorkerJitter = Duration.ofMillis(1)
            }

            val idempotencyKey = UUID.randomUUID().toString()
            val path = "/test/respondWithText"

            sendPostRequest(path, idempotencyKey)

            // override the response to make it expired
            insertExpiredResponse(idempotencyKey, path)
            Thread.sleep(Duration.ofMillis(5))

            val storedResponse = repository.getResponseOrLock("POST $path", IdempotencyKey(idempotencyKey))

            assertNull(storedResponse)
        }

    @Test
    fun shouldExecuteOnlyOneParallelQuery() =
        runBlocking {
            testApplication {
                setupModules(this)
                val path = "/test/respondWithText"

                val testTimes = 100
                for (testTime in 0 until testTimes) {
                    val idempotencyKey = UUID.randomUUID().toString()
                    val response1Deferred = async { sendPostRequest(path, idempotencyKey) }
                    val response2Deferred = async { sendPostRequest(path, idempotencyKey) }

                    response1Deferred.await()
                    response2Deferred.await()
                }

                assertServiceCalled(testTimes)
            }
        }

    private suspend fun insertExpiredResponse(
        idempotencyKey: String,
        path: String,
        method: HttpMethod = HttpMethod.Post,
    ): IdempotentResponse {
        val expiredIdempotentResponse =
            IdempotentResponse(
                status = 200,
                headers = emptyMap(),
                responseType = SupportedResponseTypes.TextContent,
                contentType = null,
                createdAt = OffsetDateTime.now(),
                validUntil = OffsetDateTime.now().minusSeconds(1),
                content = "Hello, world!".toByteArray(),
            )

        repository.storeResponse(
            "${method.value} $path",
            IdempotencyKey(idempotencyKey),
            Json.encodeToString(expiredIdempotentResponse).toByteArray(),
        )
        return expiredIdempotentResponse
    }

    private fun setupModules(applicationTestBuilder: ApplicationTestBuilder) {
        applicationTestBuilder.setupRoutes()
        applicationTestBuilder.setupPlugin()
    }

    private fun assertServiceCalledOnlyOnce() {
        assertServiceCalled(timesInTotal = 1)
    }

    private fun assertServiceCalled(timesInTotal: Int = 1) {
        verify(exactly = timesInTotal) { service.execute() }
    }

    private suspend fun assertOkResponse(
        response: HttpResponse,
        body: String,
    ) {
        assertResponse(response, OK, body)
    }

    private suspend fun assertResponse(
        response: HttpResponse,
        status: HttpStatusCode,
        body: String,
    ) {
        assertEquals(response.status, status)
        assertEquals(response.bodyAsText(), body)
    }

    private fun assertNoEventTriggered() {
        assertTrue(testEventListener.isEmpty())
    }

    private suspend fun ApplicationTestBuilder.sendGetRequest(
        path: String,
        idempotencyKey: String,
    ) = client.get(path) {
        header("Idempotency-Key", idempotencyKey)
    }

    private suspend fun ApplicationTestBuilder.sendPostRequest(
        path: String,
        idempotencyKey: String,
    ) = client.post(path) {
        header("Idempotency-Key", idempotencyKey)
    }

    private suspend fun ApplicationTestBuilder.sendPutRequest(
        path: String,
        idempotencyKey: String,
    ) = client.put(path) {
        header("Idempotency-Key", idempotencyKey)
    }

    private suspend fun ApplicationTestBuilder.sendDeleteRequest(
        path: String,
        idempotencyKey: String,
    ) = client.delete(path) {
        header("Idempotency-Key", idempotencyKey)
    }

    private suspend fun ApplicationTestBuilder.sendPatchRequest(
        path: String,
        idempotencyKey: String,
    ) = client.patch(path) {
        header("Idempotency-Key", idempotencyKey)
    }

    private fun ApplicationTestBuilder.setupPlugin() {
        repository = spyk<InMemoryResponseRepository>()

        testEventListener = TestEventListener()

        install(IdempotencyPlugin) {
            idempotentResponseRepository = repository
            storedResponseTTL = Duration.ofSeconds(3)
            eventListener = testEventListener
        }
        install(StatusPages) {
            exception<Exception> { call, cause ->
                call.respondText(text = "Internal Server Error", status = HttpStatusCode.InternalServerError)
            }
        }
    }

    private fun ApplicationTestBuilder.setupRoutes() {
        service = mockk<DummyService>(relaxed = true)
        routing {
            get("/test/respondWithText") {
                service.execute()
                call.respond("Hello, world for get request!")
            }
            post("/test/respondWithText") {
                service.execute()
                call.respond("Hello, world!")
            }
            post("/test/{id}/respondWithText") {
                service.execute()
                call.respond("Hello, world!")
            }
            patch("/test/customHeader") {
                service.execute()
                call.response.headers.append("X-Custom-Header", "value")
                call.respond("Hello, world!")
            }
            put("/test/onlyStatus") {
                service.execute()
                call.respond(HttpStatusCode.Created)
            }

            delete("/test/statusWithText") {
                service.execute()
                call.respond(HttpStatusCode.Created, "created")
            }

            post("/test/notfound") {
                service.execute()
                call.respond(HttpStatusCode.NotFound)
            }

            post("/test/internalError") {
                service.execute()
                call.respond(HttpStatusCode.InternalServerError)
            }

            put("/test/exception") {
                service.execute()
                throw Exception("test exception")
            }

            post("/test/bytes") {
                service.execute()
                call.respondBytes { "byte content".toByteArray() }
            }

            post("/test/file") {
                service.execute()
                call.respondFile(File("src/test/resources/test.txt"))
            }
        }
    }
}

class DummyService {
    fun execute() {}
}

class TestEventListener : EventListener {
    private val events = mutableListOf<Event>()

    override fun onEvent(event: Event) {
        events.add(event)
    }

    fun hasEvent(
        eventType: EventType,
        resource: String,
        idempotencyKey: String,
    ) = events.any { it.eventType == eventType && it.resource == resource && it.idempotencyKey.value == idempotencyKey }

    fun isEmpty() = events.isEmpty()
}
