package com.theveloper.pixelplay.data.service.player

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.theveloper.pixelplay.brain.HostVoice
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import timber.log.Timber

/**
 * The system voice the host of Endless Listening speaks with: Android's own [TextToSpeech], nothing bundled, no network.
 *
 * Lazy by construction: nothing here exists until [ensure] is called, and that is called only when "Spoken host notes" is on and a note has a
 * line to say, or when the person asks to test the voice. [shutdown] gives the engine back (the switch went off, Endless Listening stopped).
 *
 * Which voice: the notes are English text, so an English voice is asked for; the person's own language wins when it is English. A voice that
 * needs the network or is not installed is never used, so a line is either said from the phone or not at all. The audio is plain media speech
 * and asks for no audio focus (the player's own focus handling stays untouched).
 *
 * Main thread only.
 */
@Singleton
class HostSpeaker @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /** How a line ended. */
    enum class Outcome { DONE, STOPPED, ERROR }

    private var tts: TextToSpeech? = null
    private var voiceState = HostVoice.VOICE_NONE
    private var starting = false
    private class Pending(val id: String, val done: CompletableDeferred<Outcome>)

    // Utterance callbacks arrive on another thread, and an older line's stop must never end a newer line.
    private val pending = java.util.concurrent.atomic.AtomicReference<Pending?>(null)
    private var nextId = 0L

    /** [HostVoice.VOICE_NONE] before [ensure] and when no usable voice exists; PENDING while the engine starts; READY. */
    val voice: Int get() = if (starting) HostVoice.VOICE_PENDING else voiceState

    /** True once [ensure] was asked and it found no usable voice. Cleared by [shutdown]. */
    var unavailable: Boolean = false
        private set

    /** Starts the engine if it is not started. Safe to call again while it starts. */
    fun ensure() {
        if (tts != null || starting || unavailable) return
        starting = true
        try {
            tts = TextToSpeech(context.applicationContext) { status -> onInit(status) }
        } catch (e: Exception) {
            Timber.w(e, "host voice: the system voice could not start")
            starting = false
            unavailable = true
            tts = null
        }
    }

    private fun onInit(status: Int) {
        starting = false
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            unavailable = true
            voiceState = HostVoice.VOICE_NONE
            engine?.shutdown()
            tts = null
            return
        }
        try {
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = finish(utteranceId, Outcome.DONE)
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = finish(utteranceId, Outcome.ERROR)
                override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId, Outcome.ERROR)
                override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId, Outcome.STOPPED)
            })
            if (chooseVoice(engine)) {
                voiceState = HostVoice.VOICE_READY
            } else {
                unavailable = true
                voiceState = HostVoice.VOICE_NONE
                engine.shutdown()
                tts = null
            }
        } catch (e: Exception) {
            Timber.w(e, "host voice: choosing a voice failed")
            unavailable = true
            voiceState = HostVoice.VOICE_NONE
            runCatching { engine.shutdown() }
            tts = null
        }
    }

    /** English, installed, no network; the person's own English first, then the best quality. False when there is none. */
    private fun chooseVoice(engine: TextToSpeech): Boolean {
        val voices: Set<Voice> = try { engine.voices ?: emptySet() } catch (e: Exception) { emptySet() }
        val usable = voices.filter { v ->
            v.locale.language == ENGLISH && !v.isNetworkConnectionRequired && !v.features.contains(FEATURE_NOT_INSTALLED)
        }
        if (usable.isEmpty()) return false
        val own = Locale.getDefault()
        val best = usable.maxWithOrNull(
            compareBy<Voice> { if (it.locale == own) 2 else if (it.locale.country == own.country && own.language == ENGLISH) 1 else 0 }
                .thenBy { it.quality }
                .thenBy { it.name }
        ) ?: return false
        return engine.setVoice(best) == TextToSpeech.SUCCESS
    }

    /**
     * Says [line]. The deferred is completed when it ends: DONE, STOPPED (by [stop] or a newer line), or ERROR. A line asked for before the
     * voice is ready ends ERROR at once (the caller waits for [voice] to be READY first).
     */
    fun speak(line: String): Deferred<Outcome> {
        val done = CompletableDeferred<Outcome>()
        val engine = tts
        if (engine == null || voice != HostVoice.VOICE_READY) {
            done.complete(Outcome.ERROR)
            return done
        }
        val id = "host-${nextId++}"
        pending.getAndSet(Pending(id, done))?.done?.complete(Outcome.STOPPED)
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
        val result = try { engine.speak(line, TextToSpeech.QUEUE_FLUSH, params, id) } catch (e: Exception) { TextToSpeech.ERROR }
        if (result != TextToSpeech.SUCCESS) finish(id, Outcome.ERROR)
        return done
    }

    /** Stops the line that is being said, at once. Nothing happens when nothing is. */
    fun stop() {
        val current = pending.get() ?: return
        try { tts?.stop() } catch (e: Exception) { /* nothing to stop */ }
        finish(current.id, Outcome.STOPPED)
    }

    private fun finish(id: String?, outcome: Outcome) {
        val current = pending.get() ?: return
        if (current.id != id) return
        if (pending.compareAndSet(current, null)) current.done.complete(outcome)
    }

    /** Gives the system voice back. The next [ensure] starts it again. */
    fun shutdown() {
        pending.getAndSet(null)?.done?.complete(Outcome.STOPPED)
        try { tts?.stop() } catch (e: Exception) { /* closing anyway */ }
        try { tts?.shutdown() } catch (e: Exception) { /* closing anyway */ }
        tts = null
        starting = false
        unavailable = false
        voiceState = HostVoice.VOICE_NONE
    }

    private companion object {
        const val ENGLISH = "en"
        const val FEATURE_NOT_INSTALLED = "notInstalled"
    }
}
