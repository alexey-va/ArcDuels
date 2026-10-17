package ru.ruscrafting.duels.paper

import com.google.gson.Gson
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.structure.Mirror
import org.bukkit.block.structure.StructureRotation
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.structure.Structure
import org.bukkit.structure.StructureManager
import org.bukkit.util.BlockVector
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.persistence.DurableRecordJournal
import ru.arc.persistence.DurableAcknowledgementOutcome
import ru.ruscrafting.duels.domain.MatchId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Random
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong

/**
 * Lossless before-images for sliced arena cleanup. Capture is one native cell; an exclusively
 * owned detached Structure is encoded only by the storage worker. Vanilla event mutations keep
 * their existing crash window before an admitted write commits; accepted writes drain at stop.
 * New matches fail closed after any unknown journal or native rollback result.
 */
internal class DuelBlockRollback(
    private val plugin: JavaPlugin,
    private val scope: LifecycleTaskScope,
    private val queue: BudgetedTaskQueue,
    private val worker: BoundedAsyncExecutor,
    private val structures: StructureManager,
) {
    private data class Record(val id: String, val match: String, val world: UUID, val x: Int, val y: Int, val z: Int,
        val structure: String?, val structureVoid: Boolean, val sequence: Long)
    private data class Loaded(val record: Record, val structure: Structure?)
    private class Pending(val match: MatchId, val prepare: () -> Record) { var prepared: Record? = null }
    private val gson = Gson()
    private val root = plugin.dataFolder.toPath()
    // Constructor and every journal operation are worker-only, including cold initialization.
    private val journal by lazy { DurableRecordJournal(root, java.nio.file.Path.of("arena-recovery"), 16L * 1024 * 1024,
        { value: Record -> gson.toJson(value).toByteArray(Charsets.UTF_8) },
        { bytes -> gson.fromJson(bytes.toString(Charsets.UTF_8), Record::class.java) },
        { record -> require(record.id.matches(Regex("[A-Za-z0-9_-]{1,128}"))); UUID.fromString(record.match)
            require(record.structureVoid == (record.structure == null)); require(record.sequence > 0) }) }
    private val records = mutableMapOf<MatchId, MutableList<Record>>() // Storage worker only.
    private val pending = linkedMapOf<String, Pending>() // Failed accepted captures remain owned until native cleanup or terminal retry.
    private val sequence = AtomicLong()
    @Volatile var available = false
        private set

    fun recover(): CompletableFuture<Unit> = worker.submit {
        journal.loadAll().sortedBy { it.value.sequence }.map { row ->
            val record = row.value
            require(row.recordId == record.id)
            val detached = record.structure?.let { encoded ->
                ByteArrayInputStream(Base64.getDecoder().decode(encoded)).use(structures::loadStructure)
            }
            Loaded(record, detached)
        }
    }.thenCompose { loaded ->
        val result = CompletableFuture<Unit>()
        scope.callSync {
            val remaining = loaded.asReversed().iterator()
            var current: Loaded? = null
            var loading: CompletableFuture<*>? = null
            queue.submit({ plugin.isEnabled }) {
                if (current == null && remaining.hasNext()) current = remaining.next()
                val image = current
                if (image == null) true else {
                    val record = image.record
                    val world = requireNotNull(plugin.server.getWorld(record.world)) { "Arena recovery world is unavailable" }
                    loading?.takeIf { it.isCompletedExceptionally }?.join() // Already complete: propagate failure only.
                    if (!world.isChunkLoaded(record.x shr 4, record.z shr 4)) {
                        if (loading == null || loading?.isDone == true) loading = world.getChunkAtAsync(record.x shr 4, record.z shr 4)
                        false
                    } else {
                        if (record.structureVoid) world.getBlockAt(record.x, record.y, record.z).type = Material.STRUCTURE_VOID
                        else requireNotNull(image.structure).place(org.bukkit.Location(world, record.x.toDouble(), record.y.toDouble(), record.z.toDouble()),
                            false, StructureRotation.NONE, Mirror.NONE, 0, 1f, Random(0))
                        current = null
                        loading = null
                        !remaining.hasNext()
                    }
                }
            }.thenCompose { worker.submit {
                loaded.forEach { image -> acknowledge(image.record) }
                Unit
            } }.whenComplete { _, failure ->
                if (failure == null) { available = true; result.complete(Unit) }
                else { fail(failure); result.completeExceptionally(failure) }
            }
        }.whenComplete { _, failure -> if (failure != null) result.completeExceptionally(failure) }
        result
    }

    /** Admission does not wait for disk. A rejected admission cancels the native event. */
    fun remember(match: MatchId, block: Block): Boolean {
        check(Bukkit.isPrimaryThread())
        if (!available || !block.world.isChunkLoaded(block.x shr 4, block.z shr 4)) return false
        val isVoid = block.type == Material.STRUCTURE_VOID
        val captured = if (isVoid) null else structures.createStructure().also { it.fill(block.location, BlockVector(1, 1, 1), false) }
        val id = "${match.value}_${UUID.randomUUID()}"
        val world = block.world.uid
        val x = block.x
        val y = block.y
        val z = block.z
        val order = sequence.incrementAndGet()
        val write = worker.submit {
            pending[id] = Pending(match) {
                val encoded = captured?.let { detached -> ByteArrayOutputStream().use { output ->
                    structures.saveStructure(output, detached)
                    Base64.getEncoder().encodeToString(output.toByteArray())
                } }
                Record(id, match.value.toString(), world, x, y, z, encoded, isVoid, order)
            }
            commitPending(id, requireNotNull(pending[id]))
        }
        write.whenComplete { _, failure -> if (failure != null) fail(failure) }
        return !write.isCompletedExceptionally
    }

    fun <K> restore(match: MatchId, originals: MutableMap<K, BlockState>): CompletableFuture<Unit> {
        check(Bukkit.isPrimaryThread())
        val remaining = originals.entries.toList().asReversed().iterator()
        var current: Map.Entry<K, BlockState>? = null
        var loading: CompletableFuture<*>? = null
        return queue.submit({ plugin.isEnabled }) {
            if (current == null && remaining.hasNext()) current = remaining.next()
            val entry = current
            if (entry == null) true else {
                val state = entry.value
                val world = state.world
                loading?.takeIf { it.isCompletedExceptionally }?.join()
                if (!world.isChunkLoaded(state.x shr 4, state.z shr 4)) {
                    if (loading == null || loading?.isDone == true) loading = world.getChunkAtAsync(state.x shr 4, state.z shr 4)
                    false
                } else {
                    check(state.update(true, false)) { "Native duel block rollback was rejected" }
                    originals.remove(entry.key)
                    current = null
                    loading = null
                    !remaining.hasNext()
                }
            }
        }.thenCompose { worker.submit {
            records[match].orEmpty().forEach(::acknowledge)
            // A prepared record may have reached disk even when its commit returned an error.
            pending.filterValues { it.match == match }.forEach { (_, accepted) -> accepted.prepared?.let(::acknowledge) }
            records.remove(match)
            pending.entries.removeIf { it.value.match == match }
            Unit
        } }.whenComplete { _, failure -> if (failure != null) fail(failure) }
    }

    /** Reserved worker finalizer: retry every accepted capture; propagate failure and retain its owner RAM. */
    fun flushPending() {
        check(!Bukkit.isPrimaryThread())
        var firstFailure: Throwable? = null
        pending.toList().forEach { (id, accepted) ->
            try { commitPending(id, accepted) }
            catch (failure: Throwable) {
                val previous = firstFailure
                if (previous == null) firstFailure = failure else previous.addSuppressed(failure)
            }
        }
        firstFailure?.let { throw it }
    }

    private fun commitPending(id: String, accepted: Pending): Record {
        val prepared = accepted.prepared ?: accepted.prepare().also { accepted.prepared = it }
        val committed = journal.commit(id, prepared)
        records.computeIfAbsent(accepted.match) { mutableListOf() }.add(committed)
        pending.remove(id)
        return committed
    }

    private fun acknowledge(record: Record) {
        check(journal.acknowledgeExactly(record.id, record) { a, b -> a == b } != DurableAcknowledgementOutcome.CONTENT_MISMATCH) {
            "Arena recovery journal changed before acknowledgement"
        }
    }

    private fun fail(failure: Throwable) {
        available = false
        plugin.logger.log(java.util.logging.Level.SEVERE, "Duel arena recovery failed closed; pending before-images were preserved", failure)
    }
}
