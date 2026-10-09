package com.theveloper.pixelplay.presentation

import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A `statsViewModel: StatsViewModel = hiltViewModel()` default on SettingsCategoryScreen built a
 * StatsViewModel for every Settings category, which loads the whole library and computes the listening
 * stats while the category slides in, for the sake of one Developer button. That button now goes through
 * SettingsViewModel (PlaybackStatsRepository.requestRefresh()).
 */
class SettingsDoesNotBuildStatsViewModelTest {
    @Test
    fun `the settings category screen does not create a StatsViewModel`() {
        val file = listOf(
            File("src/main/java/com/theveloper/pixelplay/presentation/screens/SettingsCategoryScreen.kt"),
            File("app/src/main/java/com/theveloper/pixelplay/presentation/screens/SettingsCategoryScreen.kt")
        ).first { it.isFile }
        val offending = file.readLines().withIndex().filter { (_, line) -> "StatsViewModel" in line && !line.trim().startsWith("//") }
        assertTrue(offending.isEmpty(), "SettingsCategoryScreen references StatsViewModel: " + offending.joinToString { "line ${it.index + 1}" })
    }
}
