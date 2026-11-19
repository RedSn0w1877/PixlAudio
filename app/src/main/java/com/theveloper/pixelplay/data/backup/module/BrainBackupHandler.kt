package com.theveloper.pixelplay.data.backup.module

import com.google.gson.Gson
import com.theveloper.pixelplay.data.backup.model.BackupSection
import com.theveloper.pixelplay.data.brain.BrainEngine
import com.theveloper.pixelplay.data.brain.BrainMixes
import com.theveloper.pixelplay.data.brain.store.BrainStateDao
import com.theveloper.pixelplay.data.brain.store.BrainStateEntity
import com.theveloper.pixelplay.data.brain.store.FeedbackDao
import com.theveloper.pixelplay.data.brain.store.FeedbackEntity
import com.theveloper.pixelplay.data.recommendation.TasteAdjustmentCodec
import com.theveloper.pixelplay.data.recommendation.TasteAdjustmentRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The music brain's backup module (docs/brain-spec.md, 12.2, 13.2; see [BrainBackupFormat]): the salt, the taste changes and the exclusions.
 * A restore puts them back and asks for one replay in the background, which makes the caches (affinities, islands, the learned weights) again
 * from the event log. It touches nothing at app start and the replay is the brain's own single background rebuild.
 */
@Singleton
class BrainBackupHandler @Inject constructor(
    private val states: BrainStateDao,
    private val feedback: FeedbackDao,
    private val tasteAdjustments: TasteAdjustmentRepository,
    private val engine: dagger.Lazy<BrainEngine>,
    private val gson: Gson,
) : BackupModuleHandler {
    override val section: BackupSection = BackupSection.BRAIN

    private suspend fun saltOrNull(): Long? =
        states.get(BrainMixes.KEY_SALT)?.let { String(it, Charsets.UTF_8).toLongOrNull() }

    private suspend fun parsed(): BrainBackupFormat.Parsed {
        val rows = feedback.all().map {
            BrainBackupFormat.FeedbackRow(it.id, it.kind, it.targetKind, it.targetId, it.createdAtMs, it.undoneAtMs)
        }
        val adjustments = tasteAdjustments.list()
        return BrainBackupFormat.Parsed(saltOrNull()?.toString(), if (adjustments.isEmpty()) "" else TasteAdjustmentCodec.encode(adjustments), rows)
    }

    override suspend fun export(): String {
        val p = parsed()
        return BrainBackupFormat.encode(gson, p.salt?.toLongOrNull(), p.adjustments, p.feedback)
    }

    override suspend fun countEntries(): Int = parsed().let { it.feedback.size + tasteAdjustments.list().size }

    override suspend fun snapshot(): String = export()

    override suspend fun restore(payload: String) {
        val p = BrainBackupFormat.decode(gson, payload) ?: throw IllegalArgumentException("The brain backup could not be read.")
        p.salt?.let { states.put(BrainStateEntity(BrainMixes.KEY_SALT, it.toByteArray(Charsets.UTF_8))) }
        feedback.clear()
        feedback.insertAll(p.feedback.map { FeedbackEntity(it.id, it.kind, it.targetKind, it.targetId, it.createdAtMs, it.undoneAtMs) })
        tasteAdjustments.replaceAll(if (p.adjustments.isBlank()) emptyList() else TasteAdjustmentCodec.decode(p.adjustments))
        // The caches are made again from the log, once, in the background.
        engine.get().requestRebuild(force = true)
    }

    override suspend fun rollback(snapshot: String) = restore(snapshot)
}
