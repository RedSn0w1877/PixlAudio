package com.theveloper.pixelplay.data.brain.store

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.theveloper.pixelplay.brain.AudioFeatures

/**
 * Stage 5 (brain.db version 8): what the phone measured from a track's decoded audio (`AudioFeatures`, docs/brain-spec.md 3.3). cache:
 * one row per recording, measured once per [version] of the estimator; a row of an older version is measured again the next time the
 * track plays. Nothing here is the listener's history. A failed attempt is a row too ([failures] > 0, no values), so a track that cannot
 * be decoded is not tried on every play.
 */
@Entity(tableName = "audio_measure")
data class AudioMeasureEntity(
    @PrimaryKey val rid: Long,
    val version: Int,
    /** Tempo x 10; 0 when not measured. */
    val bpmX10: Int,
    /** 0..1000. */
    val tempoConf: Int,
    /** Integrated loudness in LUFS x 10, or `AudioFeatures.LOUDNESS_UNKNOWN`. */
    val lufsX10: Int,
    val onsetX1000: Int,
    val analysedMs: Int,
    /** Where the decode window started in the track, and how long the track was (for the diagnostics row). */
    val windowStartMs: Long,
    val durationMs: Long,
    val measuredAtMs: Long,
    /** Failed attempts in a row; reset by a success. */
    val failures: Int,
) {
    /** The measurement as the pure core reads it; null for a failed row. */
    fun result(): AudioFeatures.Result? =
        if (failures > 0 || analysedMs <= 0) null else AudioFeatures.Result(bpmX10, tempoConf, lufsX10, analysedMs, onsetX1000, version)
}

@Dao
interface AudioMeasureDao {
    @Upsert
    suspend fun upsert(row: AudioMeasureEntity)

    @Query("SELECT * FROM audio_measure WHERE rid = :rid")
    suspend fun get(rid: Long): AudioMeasureEntity?

    @Query("SELECT * FROM audio_measure WHERE rid IN (:rids)")
    suspend fun getAll(rids: List<Long>): List<AudioMeasureEntity>

    @Query("SELECT COUNT(*) FROM audio_measure WHERE failures = 0")
    suspend fun countMeasured(): Int
}

/** What the DNA assembly reads of the measurements: an interface so the enricher's tests need no database. */
interface AudioMeasureLookup {
    suspend fun measured(rid: Long): AudioFeatures.Result?

    companion object {
        val NONE: AudioMeasureLookup = object : AudioMeasureLookup {
            override suspend fun measured(rid: Long): AudioFeatures.Result? = null
        }
    }
}
