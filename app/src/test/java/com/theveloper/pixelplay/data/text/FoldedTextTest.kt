package com.theveloper.pixelplay.data.text

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class FoldedTextTest {
    @Test
    fun foldsCaseAndDiacritics() {
        assertThat(FoldedText.fold("Beyoncé")).isEqualTo("beyonce")
        assertThat(FoldedText.fold("BEYONCE")).isEqualTo("beyonce")
        assertThat(FoldedText.fold("Sigur Rós")).isEqualTo("sigur ros")
    }

    @Test
    fun collapsesWhitespaceAndHandlesEmpty() {
        assertThat(FoldedText.fold("  The   Weeknd ")).isEqualTo("the weeknd")
        assertThat(FoldedText.fold(null)).isEmpty()
        assertThat(FoldedText.fold("")).isEmpty()
    }
}
