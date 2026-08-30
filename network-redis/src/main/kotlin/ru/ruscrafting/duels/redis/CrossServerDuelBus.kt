package ru.ruscrafting.duels.redis

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ru.arc.redis.RedisOperations
import ru.arc.redis.network.RedisReplayPolicy
import ru.arc.redis.network.ValidatedRedisTopic
import ru.arc.redis.safety.MessageClaimResult
import ru.arc.redis.safety.RecentMessageDeduplicator
import ru.arc.redis.safety.RedisMessageRejection
import ru.ruscrafting.duels.domain.DuelEvent
import ru.ruscrafting.duels.domain.DuelEventPublisher
import ru.ruscrafting.duels.domain.ServerId
import java.time.Clock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

class CrossServerDuelBus(
    private val redis: RedisOperations,
    private val localServer: ServerId,
    private val logger: Logger = LoggerFactory.getLogger(CrossServerDuelBus::class.java),
    private val clock: Clock = Clock.systemUTC(),
) : DuelEventPublisher, AutoCloseable {
    private val codec = DuelEventCodec()
    private val listeners = CopyOnWriteArrayList<(DuelEvent) -> Unit>()
    private val deduplicator = RecentMessageDeduplicator(SEEN_TTL_MILLIS, MAX_SEEN_EVENTS)
    private val topic =
        ValidatedRedisTopic.open(
            redis = redis,
            channel = CHANNEL,
            codec = codec,
            originAllowed = { origin -> origin != localServer.value },
            embeddedOrigin = { event -> event.sourceServer.value },
            replay = RedisReplayPolicy(
                messageId = { event -> replayKey(event.sourceServer, event.eventId) },
                ttlMillis = SEEN_TTL_MILLIS,
                maxEntries = MAX_SEEN_EVENTS,
            ),
            clockMillis = clock::millis,
            onMessage = { event, _ -> deliver(event) },
            onRejected = { reason -> logRejection(reason) },
        )

    override fun publish(event: DuelEvent): CompletableFuture<Unit> {
        require(event.sourceServer == localServer) { "Cannot publish an event owned by another server" }
        if (claimFirstDelivery(event.sourceServer, event.eventId)) deliver(event)
        topic.publish(event)
        return CompletableFuture.completedFuture(Unit)
    }

    fun subscribe(listener: (DuelEvent) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    override fun close() {
        topic.close()
        listeners.clear()
    }

    private fun deliver(event: DuelEvent) {
        for (listener in listeners) {
            runCatching { listener(event) }
                .onFailure { failure -> logger.warn("ArcDuels event listener rejected {}", event.eventId, failure) }
        }
    }

    private fun claimFirstDelivery(
        sourceServer: ServerId,
        eventId: String,
    ): Boolean =
        when (deduplicator.claim(replayKey(sourceServer, eventId), clock.millis())) {
            MessageClaimResult.ACCEPTED -> true
            MessageClaimResult.DUPLICATE -> false
            MessageClaimResult.CAPACITY_EXCEEDED -> {
                logRejection(RedisMessageRejection.REPLAY_GUARD_FULL)
                false
            }
        }

    private fun logRejection(reason: RedisMessageRejection) {
        logger.warn("Rejected ArcDuels event: {}", reason)
    }

    companion object {
        const val CHANNEL = "arcduels:v1:events"
        private const val MAX_SEEN_EVENTS = 10_000
        private const val SEEN_TTL_MILLIS = 60L * 60L * 1_000L

        private fun replayKey(sourceServer: ServerId, eventId: String): String = "${sourceServer.value}:$eventId"
    }
}
