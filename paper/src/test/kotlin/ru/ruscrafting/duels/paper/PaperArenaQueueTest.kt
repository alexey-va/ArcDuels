package ru.ruscrafting.duels.paper

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.mockbukkit.mockbukkit.ServerMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.loadPlugin
import ru.ruscrafting.duels.domain.ArenaId
import ru.ruscrafting.duels.domain.ServerId
import ru.ruscrafting.duels.domain.DuelMode
import ru.ruscrafting.duels.domain.DuelRules
import ru.ruscrafting.duels.domain.DuelObjectiveType
import ru.ruscrafting.duels.domain.CombatModifiers
import ru.ruscrafting.duels.domain.KitId
import ru.ruscrafting.duels.domain.MultiplayerKitPolicy
import ru.ruscrafting.duels.domain.MultiplayerLayout
import ru.ruscrafting.duels.domain.MultiplayerParticipant
import ru.ruscrafting.duels.domain.MultiplayerRoster
import ru.ruscrafting.duels.domain.MultiplayerRules
import ru.ruscrafting.duels.domain.PlayerId
import java.util.UUID
import java.util.concurrent.ExecutionException

class PaperArenaQueueTest : StringSpec({
    lateinit var server: ServerMock
    lateinit var plugin: ArcDuelsPlugin
    lateinit var paper: MockBukkitTestRuntime

    beforeSpec {
        paper = MockBukkitTestRuntime.open()
        server = paper.server
        plugin = paper.loadPlugin<ArcDuelsPlugin>()
        server.addSimpleWorld("queue-world")
        configureArena(plugin, "queue", "queue-world")
    }

    "capacity reports only arenas compatible with the selected objective" {
        val catalog = PaperArenaCatalog.load(plugin)

        catalog.capacity(DuelMode.OWN_INVENTORY, DuelObjectiveType.ELIMINATION) shouldBe ArenaCapacity(1, 1)
        catalog.capacity(DuelMode.KIT, DuelObjectiveType.ELIMINATION) shouldBe ArenaCapacity(1, 1)
        catalog.capacity(DuelMode.OWN_INVENTORY, DuelObjectiveType.KING_OF_THE_HILL) shouldBe ArenaCapacity(0, 0)
    }

    afterSpec { paper.close() }

    "arena waiters are FIFO cancelled entries are skipped and reservation stays exclusive" {
        val catalog = PaperArenaCatalog.load(plugin)
        val rules = DuelRules(DuelMode.OWN_INVENTORY)

        val active = catalog.reserve(rules)
        val cancelled = catalog.reserve(rules)
        val next = catalog.reserve(rules)

        active.isDone shouldBe true
        cancelled.isDone shouldBe false
        next.isDone shouldBe false
        catalog.queueSize() shouldBe 2
        catalog.reservedCount() shouldBe 1

        cancelled.cancel(false) shouldBe true
        active.get().close()

        next.isDone shouldBe true
        next.get().arenaId shouldBe ArenaId("queue")
        catalog.queueSize() shouldBe 0
        catalog.reservedCount() shouldBe 1
        next.get().close()
        catalog.reservedCount() shouldBe 0
    }

    "explicit arena reservations never fall back and remain queued for the selected arena" {
        val catalog = PaperArenaCatalog.load(plugin)
        val rules = DuelRules(DuelMode.OWN_INVENTORY)
        val active = catalog.reserve(rules, ArenaId("queue")).get()
        val pinned = catalog.reserve(rules, ArenaId("queue"))

        pinned.isDone shouldBe false
        catalog.choices(ServerId("spawn"), rules).single().available shouldBe false
        catalog.advertisements(allowOwnInventory = true).single().available shouldBe false
        shouldThrow<java.util.concurrent.ExecutionException> {
            catalog.reserve(rules, ArenaId("missing")).get()
        }

        active.close()
        pinned.get().arenaId shouldBe ArenaId("queue")
        pinned.get().close()
    }

    "arena catalog cannot hot reload while a match owns an arena or a pair is queued" {
        val catalog = PaperArenaCatalog.load(plugin)
        val active = catalog.reserve(DuelRules(DuelMode.OWN_INVENTORY)).get()
        val queued = catalog.reserve(DuelRules(DuelMode.OWN_INVENTORY))

        shouldThrow<IllegalStateException> { catalog.reload(plugin) }
        shouldThrow<IllegalStateException> { catalog.disable(ArenaId("queue")) }

        active.close()
        queued.get().close()
        catalog.disable(ArenaId("queue")) shouldBe 0
        shouldThrow<java.util.concurrent.ExecutionException> {
            catalog.reserve(DuelRules(DuelMode.OWN_INVENTORY)).get()
        }
    }

    "king of the hill fails fast without a hill and reserves a compatible arena when configured" {
        val eliminationOnly = PaperArenaCatalog.load(plugin)
        val kothRules = DuelRules(DuelMode.OWN_INVENTORY, objective = DuelObjectiveType.KING_OF_THE_HILL)
        shouldThrow<java.util.concurrent.ExecutionException> { eliminationOnly.reserve(kothRules).get() }

        val path = "arenas.queue.hill"
        plugin.config.set("$path.center.world", "queue-world")
        plugin.config.set("$path.center.x", 0.0)
        plugin.config.set("$path.center.y", 70.0)
        plugin.config.set("$path.center.z", 0.0)
        plugin.config.set("$path.radius", 3.5)
        plugin.config.set("$path.height", 3.0)
        val compatible = PaperArenaCatalog.load(plugin)

        compatible.reserve(kothRules).get().arenaId shouldBe ArenaId("queue")

        plugin.config.set("$path.radius", 20.0)
        shouldThrow<IllegalArgumentException> { PaperArenaCatalog.load(plugin) }
        plugin.config.set("$path.radius", 3.5)
    }

    "arena loadout allowlist is enforced for reservation and capacity" {
        plugin.config.set("arenas.queue.allowed-loadouts", listOf("OWN_INVENTORY"))
        val catalog = PaperArenaCatalog.load(plugin)

        catalog.capacity(DuelMode.OWN_INVENTORY, DuelObjectiveType.ELIMINATION) shouldBe ArenaCapacity(1, 1)
        catalog.capacity(DuelMode.KIT, DuelObjectiveType.ELIMINATION) shouldBe ArenaCapacity(0, 0)
        shouldThrow<java.util.concurrent.ExecutionException> {
            catalog.reserve(DuelRules(DuelMode.KIT, ru.ruscrafting.duels.domain.KitId("classic"))).get()
        }
        catalog.reserve(DuelRules(DuelMode.OWN_INVENTORY)).get().close()

        plugin.config.set("arenas.queue.allowed-loadouts", emptyList<String>())
        shouldThrow<IllegalArgumentException> { PaperArenaCatalog.load(plugin) }
        plugin.config.set("arenas.queue.allowed-loadouts", listOf("KIT"))
    }

    "arena objective allowlist is enforced for reservation and capacity" {
        plugin.config.set("arenas.queue.allowed-objectives", listOf("BOXING"))
        val catalog = PaperArenaCatalog.load(plugin)

        catalog.capacity(DuelMode.KIT, DuelObjectiveType.BOXING) shouldBe ArenaCapacity(1, 1)
        catalog.capacity(DuelMode.KIT, DuelObjectiveType.ELIMINATION) shouldBe ArenaCapacity(0, 0)
        shouldThrow<java.util.concurrent.ExecutionException> {
            catalog.reserve(DuelRules(DuelMode.KIT, ru.ruscrafting.duels.domain.KitId("classic"))).get()
        }
        catalog.reserve(
            DuelRules(
                DuelMode.KIT,
                ru.ruscrafting.duels.domain.KitId("boxing"),
                objective = DuelObjectiveType.BOXING,
                modifiers = CombatModifiers(false, false, false, false),
            ),
        ).get().close()

        plugin.config.set("arenas.queue.allowed-objectives", emptyList<String>())
        shouldThrow<IllegalArgumentException> { PaperArenaCatalog.load(plugin) }
        plugin.config.set("arenas.queue.allowed-objectives", listOf("ELIMINATION"))
    }

    "wide safety bounds may overlap when physical arenas keep players separated" {
        plugin.config.set("arenas.queue.hill.radius", 3.5)
        configureArena(plugin, "overlap", "queue-world")

        val catalog = PaperArenaCatalog.load(plugin)

        catalog.size() shouldBe 2
    }

    "multiplayer reservations assign every configured team and remain exclusive with pair matches" {
        plugin.config.set("arenas.queue.allowed-loadouts", listOf("KIT"))
        plugin.config.set("arenas.queue.allowed-objectives", listOf("ELIMINATION"))
        val catalog = PaperArenaCatalog.load(plugin)
        val roster = MultiplayerRoster(
            MultiplayerRules(MultiplayerLayout.THREE_TEAMS, MultiplayerKitPolicy.SHARED, KitId("classic")),
            (0 until 6).map { index ->
                MultiplayerParticipant(PlayerId(UUID(0, index + 1L)), team = index % 3 + 1, kitId = KitId("classic"))
            },
        )

        val group = catalog.reserveMultiplayer(roster).get()
        group.spawns.size shouldBe 6
        group.spawns.values.map { Triple(it.x, it.y, it.z) }.distinct().size shouldBe 6
        val pair = catalog.reserve(DuelRules(DuelMode.KIT, KitId("classic")), ArenaId("queue"))
        pair.isDone shouldBe false

        group.close()
        pair.get().arenaId shouldBe ArenaId("queue")
        pair.get().close()
    }

    "multiplayer capacity remains true while a compatible arena is occupied and reservation reports busy" {
        plugin.config.set("arenas.overlap.enabled", false)
        plugin.config.set("arenas.queue.allowed-loadouts", listOf("KIT"))
        plugin.config.set("arenas.queue.allowed-objectives", listOf("ELIMINATION"))
        val catalog = PaperArenaCatalog.load(plugin)
        val roster =
            MultiplayerRoster(
                MultiplayerRules(MultiplayerLayout.FREE_FOR_ALL, MultiplayerKitPolicy.SHARED, KitId("classic")),
                (0 until 3).map { index ->
                    MultiplayerParticipant(PlayerId(UUID(0, index + 1L)), kitId = KitId("classic"))
                },
            )

        val active = catalog.reserveMultiplayer(roster).get()
        catalog.hasMultiplayerCapacity(roster) shouldBe true
        val failure = shouldThrow<ExecutionException> { catalog.reserveMultiplayer(roster).get() }
        (failure.cause is MultiplayerArenasBusyException) shouldBe true
        active.close()
    }

    "procedural multiplayer placement supports every size and layout from three through twelve players" {
        plugin.config.set("arenas.queue.allowed-loadouts", listOf("KIT"))
        plugin.config.set("arenas.queue.allowed-objectives", listOf("ELIMINATION"))
        val catalog = PaperArenaCatalog.load(plugin)

        (3..12).forEach { size ->
            listOf(
                MultiplayerLayout.FREE_FOR_ALL,
                MultiplayerLayout.TWO_TEAMS,
                MultiplayerLayout.THREE_TEAMS,
            ).forEach { layout ->
                val roster =
                    MultiplayerRoster(
                        MultiplayerRules(layout, MultiplayerKitPolicy.SHARED, KitId("classic")),
                        (0 until size).map { index ->
                            MultiplayerParticipant(
                                PlayerId(UUID(0, index + 1L)),
                                team = layout.teamCount?.let { index % it + 1 },
                                kitId = KitId("classic"),
                            )
                        },
                    )

                withClue("procedural placement for $size players in $layout") {
                    catalog.hasMultiplayerCapacity(roster) shouldBe true
                }
                val reservation = catalog.reserveMultiplayer(roster).get()
                reservation.spawns.size shouldBe size
                reservation.spawns.values
                    .map { Triple(it.x, it.y, it.z) }
                    .distinct()
                    .size shouldBe size
                reservation.spawns.values.all(catalog.get(reservation.arenaId).bounds::contains) shouldBe true
                reservation.close()
            }
        }
    }
})

private fun configureArena(
    plugin: ArcDuelsPlugin,
    id: String,
    world: String,
) {
    val path = "arenas.$id"
    plugin.config.set("$path.enabled", true)
    plugin.config.set("$path.display-name", "Queue arena")
    plugin.config.set("$path.first-spawn.world", world)
    plugin.config.set("$path.first-spawn.x", -5.0)
    plugin.config.set("$path.first-spawn.y", 70.0)
    plugin.config.set("$path.first-spawn.z", 0.0)
    plugin.config.set("$path.first-spawn.yaw", -90.0)
    plugin.config.set("$path.first-spawn.pitch", 0.0)
    plugin.config.set("$path.second-spawn.world", world)
    plugin.config.set("$path.second-spawn.x", 5.0)
    plugin.config.set("$path.second-spawn.y", 70.0)
    plugin.config.set("$path.second-spawn.z", 0.0)
    plugin.config.set("$path.second-spawn.yaw", 90.0)
    plugin.config.set("$path.second-spawn.pitch", 0.0)
    plugin.config.set("$path.bounds.min.x", -10.0)
    plugin.config.set("$path.bounds.min.y", 60.0)
    plugin.config.set("$path.bounds.min.z", -10.0)
    plugin.config.set("$path.bounds.max.x", 10.0)
    plugin.config.set("$path.bounds.max.y", 90.0)
    plugin.config.set("$path.bounds.max.z", 10.0)
}
