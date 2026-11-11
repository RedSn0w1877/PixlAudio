package com.theveloper.pixelplay.data.service.player

import android.net.Uri
import androidx.media3.common.MediaItem
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * What an item resolved ahead of its player (the crossfade player, a restored queue, the watch) is given: its stream,
 * never a Spatial audio render, which would be persisted with the queue and outlive the decision (cloud-audio-spec §6.3).
 */
class BakedStreamTest {

    private val spotifyUri = mockk<Uri>()
    private val streamUri = mockk<Uri>()

    private fun item() = MediaItem.Builder().setMediaId("song").setUri(spotifyUri).build()

    @Test
    fun `a song that starts on a render keeps its spotify uri`() = runTest {
        val item = item()
        var streamAsked = false

        val resolved = withBakedStream(item, spotifyUri, startsOnRender = { true }, streamUri = {
            streamAsked = true
            streamUri
        })

        assertSame(item, resolved)
        assertSame(spotifyUri, resolved.localConfiguration?.uri)
        assertFalse(streamAsked)
    }

    @Test
    fun `the stream is baked in`() = runTest {
        val resolved = withBakedStream(item(), spotifyUri, startsOnRender = { false }, streamUri = { streamUri })

        assertSame(streamUri, resolved.localConfiguration?.uri)
    }

    @Test
    fun `a stream that could not be resolved leaves the item as it was`() = runTest {
        val item = item()

        val resolved = withBakedStream(item, spotifyUri, startsOnRender = { false }, streamUri = { spotifyUri })

        assertSame(item, resolved)
    }
}
