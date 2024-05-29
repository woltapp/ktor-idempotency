package com.wolt.utils.ktor.idempotency

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.OffsetDateTime

class CleanUpWorker(
    private val scope: CoroutineScope,
    private val jitter: Duration,
    private val interval: Duration,
    private val idempotentResponseRepository: IdempotentResponseRepository,
    private val storedResponseTTL: Duration,
) {
    private val logger = KotlinLogging.logger {}

    fun start() {
        val job =
            scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                while (isActive) {
                    delay(sleepDuration().toMillis())
                    try {
                        idempotentResponseRepository.deleteExpiredResponses(
                            OffsetDateTime.now().minus(storedResponseTTL),
                        )
                    } catch (e: Exception) {
                        logger.error(e) { "Cannot clean up expired responses" }
                    }
                }
            }
        job.start()
    }

    private fun sleepDuration(): Duration {
        val jitter = (0L..jitter.toMillis()).random()
        return interval.plus(Duration.ofMillis(jitter))
    }
}
