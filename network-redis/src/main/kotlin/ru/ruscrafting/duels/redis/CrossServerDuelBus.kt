package ru.ruscrafting.duels.redis

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ru.arc.redis.ChannelListener
import ru.arc.redis.RedisOperations
import ru.ruscrafting.duels.domain.DuelEvent
import ru.ruscrafting.duels.domain.DuelEventPublisher
import ru.ruscrafting.duels.domain.ServerId
import java.time.Clock
import java.time.Duration
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
    private val seenEvents = HashMap<String, Long>()
    private val seenEventsLock = Any()
    private val redisListener = ChannelListener(::consume)

    init {
        redis.registerChannelUnique(CHANNEL, redisListener)
    }

    override fun publish(event: DuelEvent): CompletableFuture<Unit> {
        require(event.sourceServer == localServer) { "Cannot publish an event owned by another server" }
        if (markFirstDelivery(event.sourceServer, event.eventId)) deliver(event)
        redis.publish(CHANNEL, codec.encode(event))
        return CompletableFuture.completedFuture(Unit)
    }

    fun subscribe(listener: (DuelEvent) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    override fun close() {
        redis.unregisterChannel(CHANNEL, redisListener)
        listeners.clear()
        synchronized(seenEventsLock) { seenEvents.clear() }
    }

    private fun consume(
        channel: String,
        message: String,
        originServer: String,
    ) {
        if (channel != CHANNEL || originServer == localServer.value) return
        runCatching {
            val event = codec.decode(message)
            require(event.sourceServer.value == originServer) { "Redis origin does not match event source" }
            if (!markFirstDelivery(event.sourceServer, event.eventId)) return
            deliver(event)
        }.onFailure { failure ->
            logger.warn("Rejected ArcDuels event from {}", originServer, failure)
        }
    }

    private fun deliver(event: DuelEvent) {
        for (listener in listeners) {
            runCatching { listener(event) }
                .onFailure { failure -> logger.warn("ArcDuels event listener rejected {}", event.eventId, failure) }
        }
    }

    private fun markFirstDelivery(
        sourceServer: ServerId,
        eventId: String,
    ): Boolean = synchronized(seenEventsLock) {
        val now = clock.millis()
        val key = "${sourceServer.value}:$eventId"
        val previous = seenEvents[key]
        if (previous != null) {
            if (now - previous < SEEN_TTL.toMillis()) return@synchronized false
            seenEvents[key] = now
            return@synchronized true
        }
        if (seenEvents.size >= MAX_SEEN_EVENTS) {
            val cutoff = now - SEEN_TTL.toMillis()
            seenEvents.entries.removeIf { it.value < cutoff }
            if (seenEvents.size >= MAX_SEEN_EVENTS) {
                seenEvents.entries.minByOrNull { it.value }?.key?.let(seenEvents::remove)
            }
        }
        seenEvents[key] = now
        true
    }

    companion object {
        const val CHANNEL = "arcduels:v1:events"
        private const val MAX_SEEN_EVENTS = 10_000
        private val SEEN_TTL = Duration.ofHours(1)
    }
}
