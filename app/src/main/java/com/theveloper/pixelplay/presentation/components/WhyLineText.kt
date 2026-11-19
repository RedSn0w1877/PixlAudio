package com.theveloper.pixelplay.presentation.components

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.brain.WhyLine

/**
 * The sentence behind a reason key (docs/brain-spec.md, 8.8, decision D16): the key and its one
 * argument come from the pure core; the words live here, in the app's resources. A key whose sentence
 * needs a name is not shown without one: a reason is a true statement or it is nothing.
 */
object WhyLineText {
    @StringRes
    private fun resOf(key: Int): Int = when (key) {
        WhyLine.RADIO_FANS -> R.string.brain_why_radio_fans
        WhyLine.BECAUSE_LIKED -> R.string.brain_why_because_liked
        WhyLine.BECAUSE_LISTENED -> R.string.brain_why_because_listened
        WhyLine.ARTIST_MORE -> R.string.brain_why_artist_more
        WhyLine.ON_REPEAT -> R.string.brain_why_on_repeat
        WhyLine.SIMILAR_ARTIST -> R.string.brain_why_similar_artist
        WhyLine.NEW_RELEASE -> R.string.brain_why_new_release
        // The other keys of 8.8 belong to arms that no surface serves yet.
        else -> 0
    }

    private fun needsName(key: Int): Boolean = key != WhyLine.ON_REPEAT

    /** The line for [keyName] (`WhyLine.nameOf`) and [arg], or null when there is nothing true to say. */
    fun format(resources: Resources, keyName: String?, arg: String?): String? {
        val key = WhyLine.keyOf(keyName)
        val res = resOf(key)
        if (res == 0) return null
        if (!needsName(key)) return resources.getString(res)
        val name = arg?.trim().orEmpty()
        return if (name.isEmpty()) null else resources.getString(res, name)
    }
}

/** [WhyLineText.format] with the composition's resources. */
@Composable
@ReadOnlyComposable
fun whyLineText(keyName: String?, arg: String?): String? =
    WhyLineText.format(LocalContext.current.resources, keyName, arg)
