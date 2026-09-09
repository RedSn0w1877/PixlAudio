package com.theveloper.pixelplay.data.tais.lyrics

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.data.model.LyricsSourcePreference
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.model.SyncedLine
import com.theveloper.pixelplay.data.model.SyncedWord
import com.theveloper.pixelplay.data.repository.LyricsRepository
import com.theveloper.pixelplay.data.tais.TaisAiEngine
import com.theveloper.pixelplay.data.tais.dsp.openDataSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Coordinates lyric persistence and whole-song acoustic alignment. Audio window boundaries
 * are independent of text; the global CTC pass preserves intros, interludes and repeated lines.
 */
@Singleton
class TaisLyricsAligner @Inject constructor(
    private val lyricsRepository: LyricsRepository,
    private val wav2Vec2Aligner: TaisWav2Vec2Aligner,
    private val taisAiEngine: TaisAiEngine,
    @param:ApplicationContext private val context: Context
) {
    /** What [alignmentStateFor] found for a given [Lyrics] object, without touching the network. */
    sealed interface AlignmentState {
        /** Every synced line already has word-level timestamps — nothing to align. */
        data object WordSynced : AlignmentState
        /** Line-level timestamps only. [com.theveloper.pixelplay.presentation.components.LyricLineRow]'s
         * line-duration sweep already covers this case in the UI; [forceAlign] can upgrade it. */
        data class LineSyncedOnly(val lines: List<SyncedLine>) : AlignmentState
        /** No timing at all — forced alignment is the only way to get word sync. */
        data class PlainTextOnly(val lines: List<String>) : AlignmentState
        /** No lyrics found for this track at all. */
        data object NoLyrics : AlignmentState
    }

    fun alignmentStateFor(lyrics: Lyrics?, forceResync: Boolean = false): AlignmentState {
        if (forceResync) {
            // Explicit resync distrusts the old anchors as well as the old word timings.
            // Keep the selected text, including repeated lines, until replacement succeeds.
            val lines = lyrics?.synced?.takeIf { it.isNotEmpty() }?.map { it.line }
                ?: lyrics?.plain.orEmpty()
            return if (lines.any { it.isNotBlank() }) AlignmentState.PlainTextOnly(lines)
            else AlignmentState.NoLyrics
        }
        val synced = lyrics?.synced
        if (!synced.isNullOrEmpty()) {
            // Blank/instrumental-break spacer lines never get word timings even from a fully
            // successful forceAlign() (see that function's doc) — ignore them here so their
            // presence doesn't perpetually disqualify an otherwise-complete sync.
            val nonBlankLines = synced.filter { it.line.isNotBlank() }
            val hasWordTimings = nonBlankLines.isNotEmpty() && nonBlankLines.all { !it.words.isNullOrEmpty() }
            // A real song's words are never *all* stamped at exactly 0ms — that pattern only
            // comes from a previous forceAlign() run that produced no usable alignment (every
            // chunk failed). Treating it as already-synced would make every future re-run
            // silently skip re-aligning forever instead of retrying.
            val allWords = nonBlankLines.asSequence().flatMap { it.words.orEmpty() }
            val looksDegenerate = hasWordTimings && allWords.all { it.time == 0 }
            return if (hasWordTimings && !looksDegenerate) {
                AlignmentState.WordSynced
            } else {
                AlignmentState.LineSyncedOnly(synced)
            }
        }
        val plain = lyrics?.plain
        if (!plain.isNullOrEmpty()) {
            return AlignmentState.PlainTextOnly(plain)
        }
        return AlignmentState.NoLyrics
    }

    suspend fun findOnlineSyncedLyrics(song: Song) = lyricsRepository.findOnlineSyncedLyrics(song)

    suspend fun saveOnlineSyncedLyrics(song: Song, result: com.theveloper.pixelplay.data.repository.OnlineSyncedLyrics) =
        lyricsRepository.saveOnlineSyncedLyrics(song, result)

    /** Fetches lyrics through the existing repository, then classifies them via [alignmentStateFor]. */
    suspend fun loadAndClassify(
        song: Song,
        sourcePreference: LyricsSourcePreference = LyricsSourcePreference.EMBEDDED_FIRST,
        forceResync: Boolean = false
    ): Pair<Lyrics?, AlignmentState> {
        val lyrics = lyricsRepository.getLyrics(song, sourcePreference)
        return lyrics to alignmentStateFor(lyrics, forceResync)
    }

    /**
     * Aligns [text] (one entry per lyric line, already known to be correct — this never trusts a
     * model guess over the real lyrics) against the audio at [sourceAudioPath] (any format
     * MediaCodec can decode) to produce word-level timestamps.
     *
     * [knownLineStartMs] is retained for caller compatibility; old timestamps do not constrain
     * the alignment. [onWindowProgress] reports audio windows analyzed, independent of lyrics.
     */
    suspend fun forceAlign(
        sourceAudioPath: String,
        text: List<String>,
        knownLineStartMs: List<Int>? = null,
        onWindowProgress: suspend (Int, Int) -> Unit = { _, _ -> }
    ): List<SyncedLine> = taisAiEngine.runExclusive {
        withContext(Dispatchers.Default) {
            val samples = decodeToMono16k(sourceAudioPath)
            if (samples.isEmpty() || text.isEmpty()) return@withContext emptyList()
            val totalDurationMs = (samples.size.toLong() * 1000L / SAMPLE_RATE).toInt()

            data class TargetWord(val lineIndex: Int, val word: String)
            val targetWords = mutableListOf<TargetWord>()
            text.forEachIndexed { lineIndex, line ->
                val words = line.split(WHITESPACE_REGEX).filter { it.isNotBlank() }
                if (words.isEmpty()) return@forEachIndexed
                words.forEach { targetWords.add(TargetWord(lineIndex, it)) }
            }
            if (targetWords.isEmpty()) return@withContext emptyList()

            // Use one acoustic timeline. Guessed per-line windows force lyrics into intros
            // and interludes, and clamping/interpolation can disguise failed matches as success.
            val timings = wav2Vec2Aligner.align(samples, targetWords.map { it.word }, onWindowProgress)
            check(timings.size == targetWords.size && timings.any { it.startMs > 0 }) {
                "The acoustic model could not align the full lyrics. Existing lyrics were kept."
            }
            val startTimes = timings.map { it.startMs }.toIntArray()
            check(startTimes.all { it in 0..totalDurationMs } &&
                startTimes.asList().zipWithNext().all { (a, b) -> a <= b }) {
                "The acoustic model returned inconsistent timings. Existing lyrics were kept."
            }

            val wordsByLine = mutableMapOf<Int, MutableList<SyncedWord>>()
            for (i in targetWords.indices) {
                wordsByLine.getOrPut(targetWords[i].lineIndex) { mutableListOf() }
                    .add(SyncedWord(time = startTimes[i], word = targetWords[i].word, startsNewWord = true))
            }

            val result = mutableListOf<SyncedLine>()
            var lastKnownTime = 0
            text.indices.forEach { lineIndex ->
                val wordsForLine = wordsByLine[lineIndex]
                val lineTime = wordsForLine?.first()?.time ?: lastKnownTime
                lastKnownTime = wordsForLine?.last()?.time ?: lastKnownTime
                result.add(SyncedLine(time = lineTime, line = text[lineIndex], words = wordsForLine))
            }
            result
        }
    }

    /**
     * Persists [alignedLines] (the output of [forceAlign]) through the existing lyrics cache
     * pipeline — [LyricsRepository.updateLyrics] already handles DB + in-memory cache, all this
     * adds is formatting word-level lines into the enhanced-LRC `<mm:ss.xx>word` inline-tag
     * format [com.theveloper.pixelplay.utils.LyricsUtils.parseLyrics] round-trips through. Future
     * playbacks hit the cache and skip re-running alignment entirely.
     */
    suspend fun persistAligned(song: Song, alignedLines: List<SyncedLine>) {
        val words = alignedLines.flatMap { it.words.orEmpty() }
        require(words.isNotEmpty() && words.any { it.time > 0 } &&
            words.all { it.time >= 0 } && words.zipWithNext().all { (a, b) -> a.time <= b.time }) {
            "No usable word timing was produced. Existing lyrics were kept."
        }
        currentCoroutineContext().ensureActive()
        val rawLyrics = alignedLines.joinToString("\n") { line ->
            val linePrefix = "[${formatLrcTimestamp(line.time)}]"
            val words = line.words
            if (words.isNullOrEmpty()) {
                linePrefix + line.line
            } else {
                linePrefix + words.mapIndexed { index, word ->
                    val separator = if (index > 0 && word.startsNewWord) " " else ""
                    "$separator<${formatLrcTimestamp(word.time)}>${word.word}"
                }.joinToString("")
            }
        }
        lyricsRepository.updateLyrics(song, rawLyrics)
    }

    private fun formatLrcTimestamp(timeMs: Int): String {
        val totalSeconds = timeMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val hundredths = (timeMs % 1000) / 10
        return String.format("%02d:%02d.%02d", minutes, seconds, hundredths)
    }

    // ---------------------------------------------------------------------------------------

    // ---------------------------------------------------------------------------------------
    // Decode: any container/codec -> mono 16kHz float PCM (the acoustic model's fixed input contract).
    // ---------------------------------------------------------------------------------------

    private suspend fun decodeToMono16k(source: String): FloatArray {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            openDataSource(context, extractor, source)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No audio track found in this song")
            var outputFormat = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)
            var sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val codec = MediaCodec.createDecoderByType(outputFormat.getString(MediaFormat.KEY_MIME)!!)
            decoder = codec
            codec.configure(outputFormat, null, null, 0)
            codec.start()

            val mono = GrowableFloatArray(sampleRate * 60)
            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            var lastDecoderProgress = android.os.SystemClock.elapsedRealtime()
            while (!sawOutputEos) {
                currentCoroutineContext().ensureActive()
                check(android.os.SystemClock.elapsedRealtime() - lastDecoderProgress < 30_000L) {
                    "Audio decoding stalled. Please download this song again and retry."
                }
                if (!sawInputEos) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val input = codec.getInputBuffer(inIndex)
                        val size = input?.let { extractor.readSampleData(it, 0) } ?: -1
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outputFormat = codec.outputFormat
                        sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    outIndex >= 0 -> {
                        lastDecoderProgress = android.os.SystemClock.elapsedRealtime()
                        try {
                            sawOutputEos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            if (bufferInfo.size > 0) {
                                val output = checkNotNull(codec.getOutputBuffer(outIndex))
                                output.position(bufferInfo.offset)
                                output.limit(bufferInfo.offset + bufferInfo.size)
                                appendMonoPcm(output, outputFormat, channelCount, mono)
                            }
                        } finally {
                            codec.releaseOutputBuffer(outIndex, false)
                        }
                    }
                }
            }
            return resampleTo16k(mono.toFloatArray(), sampleRate)
        } finally {
            // A corrupt track or cancellation must not leak codec instances across a playlist.
            decoder?.let { codec ->
                try { codec.stop() } catch (_: Exception) { }
                codec.release()
            }
            extractor.release()
        }
    }

    private fun appendMonoPcm(buffer: ByteBuffer, format: MediaFormat, channelCount: Int, out: GrowableFloatArray) {
        val encoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        buffer.order(ByteOrder.nativeOrder())
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val floats = buffer.asFloatBuffer()
            while (floats.hasRemaining()) {
                var sum = floats.get()
                for (c in 1 until channelCount) {
                    if (!floats.hasRemaining()) break
                    sum += floats.get()
                }
                out.add(sum / channelCount)
            }
        } else {
            val shorts = buffer.asShortBuffer()
            while (shorts.hasRemaining()) {
                var sum = shorts.get().toFloat() / Short.MAX_VALUE
                for (c in 1 until channelCount) {
                    if (!shorts.hasRemaining()) break
                    sum += shorts.get().toFloat() / Short.MAX_VALUE
                }
                out.add(sum / channelCount)
            }
        }
    }

    private fun resampleTo16k(samples: FloatArray, sourceRate: Int): FloatArray {
        if (sourceRate == SAMPLE_RATE) return samples
        val ratio = SAMPLE_RATE.toDouble() / sourceRate
        val newLength = (samples.size * ratio).toInt()
        val out = FloatArray(newLength)
        for (i in 0 until newLength) {
            val srcPos = i / ratio
            val i0 = srcPos.toInt().coerceIn(0, samples.size - 1)
            val i1 = (i0 + 1).coerceIn(0, samples.size - 1)
            val frac = (srcPos - i0).toFloat()
            out[i] = samples[i0] * (1 - frac) + samples[i1] * frac
        }
        return out
    }

    private companion object {
        private const val TAG = "TaisLyricsAligner"
        private const val TIMEOUT_US = 10_000L
        /** wav2vec2-base-960h's fixed input contract. */
        private const val SAMPLE_RATE = 16000
        private val WHITESPACE_REGEX = Regex("\\s+")
    }
}

/** Growable primitive float buffer — avoids the boxing cost of an ArrayList&lt;Float&gt; for a full track's samples. */
private class GrowableFloatArray(initialCapacity: Int) {
    private var data = FloatArray(initialCapacity.coerceAtLeast(16))
    private var size = 0

    fun add(value: Float) {
        if (size == data.size) {
            data = data.copyOf(data.size * 2)
        }
        data[size++] = value
    }

    fun toFloatArray(): FloatArray = data.copyOf(size)
}
