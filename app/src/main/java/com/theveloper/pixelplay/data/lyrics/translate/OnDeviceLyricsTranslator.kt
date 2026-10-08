package com.theveloper.pixelplay.data.lyrics.translate

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.tasks.await

/**
 * Translates lyric lines on this phone, without the cloud (the lyrics page's Translate button).
 * An interface so the state holder that drives it can be unit-tested with a fake.
 *
 * Language codes here are the translator's own two-letter codes ([supportedLanguage] maps a
 * BCP-47 tag such as "pt-BR" to one, or says the language can't be translated on device).
 */
interface OnDeviceLyricsTranslator {
    /**
     * The BCP-47 tag of the dominant language of each text, in order; null where it can't tell.
     * Short lines are unreliable, so callers identify the song's language on joined lines.
     */
    suspend fun identifyLanguages(texts: List<String>): List<String?>

    /** The translator's code for [languageTag], or null when on-device translation can't do it. */
    fun supportedLanguage(languageTag: String): String?

    /** True when the model for [language] (a [supportedLanguage] code) is already on the phone. */
    suspend fun isModelDownloaded(language: String): Boolean

    /** Downloads whichever of the two models is missing. Mobile data is allowed: the person tapped Translate. */
    suspend fun downloadModels(source: String, target: String)

    /** Translates each line from [source] to [target]; the result has one entry per input line. */
    suspend fun translate(lines: List<String>, source: String, target: String): List<String>
}

/**
 * [OnDeviceLyricsTranslator] on Google ML Kit: language identification (bundled, offline) and the
 * on-device translator (one ~30 MB model per language, downloaded on first use and kept).
 * Clients are created per call and always closed, so nothing stays loaded between songs.
 */
@Singleton
class MlKitLyricsTranslator @Inject constructor() : OnDeviceLyricsTranslator {

    override suspend fun identifyLanguages(texts: List<String>): List<String?> {
        if (texts.isEmpty()) return emptyList()
        val identifier: LanguageIdentifier = LanguageIdentification.getClient(
            LanguageIdentificationOptions.Builder()
                .setConfidenceThreshold(LANGUAGE_ID_CONFIDENCE)
                .build()
        )
        return try {
            texts.map { text ->
                if (text.isBlank()) null
                else identifier.identifyLanguage(text).await().takeUnless { it == UNDETERMINED }
            }
        } finally {
            identifier.close()
        }
    }

    override fun supportedLanguage(languageTag: String): String? {
        // "ja-Latn" / "zh-Latn" mean romanized lyrics: the translator only reads native script,
        // so it would turn them into nonsense.
        if (languageTag.endsWith("-Latn", ignoreCase = true)) return null
        return TranslateLanguage.fromLanguageTag(languageTag)
            ?: TranslateLanguage.fromLanguageTag(Locale.forLanguageTag(languageTag).language)
    }

    override suspend fun isModelDownloaded(language: String): Boolean =
        RemoteModelManager.getInstance()
            .isModelDownloaded(TranslateRemoteModel.Builder(language).build())
            .await()

    override suspend fun downloadModels(source: String, target: String) {
        val translator = Translation.getClient(options(source, target))
        try {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        } finally {
            translator.close()
        }
    }

    override suspend fun translate(lines: List<String>, source: String, target: String): List<String> {
        if (lines.isEmpty()) return emptyList()
        val translator = Translation.getClient(options(source, target))
        return try {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            lines.map { line -> if (line.isBlank()) line else translator.translate(line).await() }
        } finally {
            translator.close()
        }
    }

    private fun options(source: String, target: String) = TranslatorOptions.Builder()
        .setSourceLanguage(source)
        .setTargetLanguage(target)
        .build()

    private companion object {
        /** ML Kit's own default; lower makes a guess on short or mixed lines too often. */
        const val LANGUAGE_ID_CONFIDENCE = 0.5f
        const val UNDETERMINED = "und"
    }
}
