package com.theveloper.pixelplay.ui.glass

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints

private enum class GlassScaffoldSlot { Content, ScrollEdge, TopChrome, BottomChrome }

/**
 * A screen laid out in the glass layer model: scrolling [content] underneath, chrome floating over
 * it and refracting it.
 *
 * - [content] is recorded into the screen's own backdrop ([RecordedContent]) and is the content
 *   layer — glass components inside it render tonal, never `drawBackdrop`.
 * - [topChrome] and [bottomChrome] are drawn as *siblings* above that recording, with
 *   [LocalGlassLayer] = [GlassLayer.Chrome] and [LocalAppBackdrop] = the screen recording. Put one
 *   [GlassGroup] of actions in the top chrome rather than several separate glass buttons.
 * - [scrollEdge] adds the one [ScrollEdgeEffect] per screen under the top chrome.
 *
 * [content] receives the chrome heights as padding, so lists can start below the top bar and
 * still scroll underneath it.
 *
 * In Material 3 mode the layout is identical but nothing is recorded, there is no scroll edge,
 * and the chrome's glass components fall back to their M3 look.
 */
@Composable
fun GlassScaffold(
    modifier: Modifier = Modifier,
    topChrome: @Composable () -> Unit = {},
    bottomChrome: @Composable () -> Unit = {},
    scrollEdge: Boolean = true,
    content: @Composable (PaddingValues) -> Unit
) {
    val glass = isGlassEnabled
    val screenBackdrop = rememberPageBackdrop()

    SubcomposeLayout(modifier) { constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val top = subcompose(GlassScaffoldSlot.TopChrome) {
            CompositionLocalProvider(
                LocalGlassLayer provides GlassLayer.Chrome,
                LocalAppBackdrop provides if (glass) screenBackdrop else LocalAppBackdrop.current
            ) {
                topChrome()
            }
        }.map { it.measure(loose) }
        val bottom = subcompose(GlassScaffoldSlot.BottomChrome) {
            CompositionLocalProvider(
                LocalGlassLayer provides GlassLayer.Chrome,
                LocalAppBackdrop provides if (glass) screenBackdrop else LocalAppBackdrop.current
            ) {
                bottomChrome()
            }
        }.map { it.measure(loose) }

        val topHeight = top.maxOfOrNull { it.height } ?: 0
        val bottomHeight = bottom.maxOfOrNull { it.height } ?: 0
        val padding = PaddingValues(
            top = topHeight.toDp(),
            bottom = bottomHeight.toDp()
        )

        val body = subcompose(GlassScaffoldSlot.Content) {
            RecordedContent(backdrop = screenBackdrop, enabled = glass) {
                content(padding)
            }
        }.map { it.measure(constraints) }
        val width = body.maxOfOrNull { it.width } ?: constraints.minWidth
        val height = body.maxOfOrNull { it.height } ?: constraints.minHeight

        val edge = if (glass && scrollEdge) {
            subcompose(GlassScaffoldSlot.ScrollEdge) {
                ScrollEdgeEffect(backdrop = screenBackdrop)
            }.map { it.measure(Constraints(maxWidth = width)) }
        } else {
            emptyList()
        }

        layout(width, height) {
            body.forEach { it.place(0, 0) }
            edge.forEach { it.place(0, 0) }
            top.forEach { it.place(0, 0) }
            bottom.forEach { it.place(0, height - it.height) }
        }
    }
}
