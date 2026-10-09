package com.theveloper.pixelplay.presentation

import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression gate for a performance bug: a composable with `playerViewModel: PlayerViewModel =
 * hiltViewModel()` that is shown inside a nav destination builds a SECOND PlayerViewModel (the
 * back stack entry is its own ViewModelStoreOwner), which re-initialises every singleton state
 * holder and later tears them down in onCleared(). The activity-scoped instance must be passed
 * explicitly, so a missing argument is a compile error instead of a silent second ViewModel.
 *
 * QueueBottomSheet is the one allowed default: it is only used by the activity-level overlay
 * layer (outside the NavHost), where hiltViewModel() resolves to the activity's instance.
 */
class NoSecondPlayerViewModelTest {

    private val allowedFiles = setOf("QueueBottomSheet.kt")

    private val offending = Regex("""PlayerViewModel\s*=\s*hiltViewModel\(""")
    private val genericOffending = Regex("""(hiltViewModel|viewModel)<PlayerViewModel>\(""")

    private fun sourceRoot(): File {
        // Gradle runs unit tests with the module directory as the working directory.
        val candidates = listOf(File("src/main/java"), File("app/src/main/java"))
        return candidates.first { it.isDirectory }
    }

    @Test
    fun `no composable defaults its PlayerViewModel to hiltViewModel`() {
        val violations = sourceRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name !in allowedFiles }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (offending.containsMatchIn(line) || genericOffending.containsMatchIn(line)) {
                        "${file.name}:${index + 1}: ${line.trim()}"
                    } else {
                        null
                    }
                }
            }
            .toList()

        assertTrue(
            violations.isEmpty(),
            "Pass the activity-scoped PlayerViewModel explicitly instead of hiltViewModel():\n" +
                violations.joinToString("\n")
        )
    }
}
