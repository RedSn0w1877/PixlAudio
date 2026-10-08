package com.theveloper.pixelplay.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.MainCoroutineExtension
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.tais.dj.DjAction
import com.theveloper.pixelplay.data.tais.dj.DjIntent
import com.theveloper.pixelplay.data.tais.dj.DjRouteResult
import com.theveloper.pixelplay.data.tais.dj.TaisDjEngine
import com.theveloper.pixelplay.data.tais.dj.TaizoTurn
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainCoroutineExtension::class)
class TaisChatViewModelTest {

    private val engine: TaisDjEngine = mockk(relaxed = true)

    private fun viewModel() = TaisChatViewModel(engine, mockk(relaxed = true), mockk(relaxed = true))

    @Test
    fun `a second send while Taizo is answering is ignored and the reply replaces its own bubble`() = runTest {
        val reply = CompletableDeferred<TaizoTurn>()
        coEvery { engine.respond("first", any()) } coAnswers { reply.await() }
        val vm = viewModel()

        vm.onInputChange("first")
        vm.sendPrompt()
        advanceUntilIdle()
        assertThat(vm.isResponding.value).isTrue()

        vm.onInputChange("second")
        vm.sendPrompt()
        advanceUntilIdle()
        coVerify(exactly = 0) { engine.respond("second", any()) }
        assertThat(vm.uiState.value.messages.filterIsInstance<TaisChatMessage.User>().map { it.text })
            .containsExactly("first")

        reply.complete(TaizoTurn.Conversation("Hi there"))
        advanceUntilIdle()

        val messages = vm.uiState.value.messages
        assertThat(messages.none { it is TaisChatMessage.Thinking }).isTrue()
        assertThat(messages.last()).isInstanceOf(TaisChatMessage.TextReply::class.java)
        assertThat((messages.last() as TaisChatMessage.TextReply).text).isEqualTo("Hi there")
        assertThat(vm.isResponding.value).isFalse()
    }

    @Test
    fun `a media reply shows at once and its intro arrives later`() = runTest {
        val songs = listOf(Song.emptySong().copy(id = "1", title = "Song", artist = "Artist"))
        val result = DjRouteResult.Offline(songs)
        val intro = CompletableDeferred<String?>()
        coEvery { engine.respond("play chill", any()) } returns
            TaizoTurn.Media(DjIntent("play chill", DjAction.PLAY, emptyList(), listOf("chill"), ""), result)
        coEvery { engine.introFor("play chill", result) } coAnswers { intro.await() }
        val vm = viewModel()

        vm.onInputChange("play chill")
        vm.sendPrompt()
        advanceUntilIdle()

        val card = vm.uiState.value.messages.last() as TaisChatMessage.DjReply
        assertThat(card.aiIntro).isNull()
        assertThat(vm.isResponding.value).isFalse()

        intro.complete("Here's something mellow")
        advanceUntilIdle()

        val updated = vm.uiState.value.messages.last() as TaisChatMessage.DjReply
        assertThat(updated.id).isEqualTo(card.id)
        assertThat(updated.aiIntro).isEqualTo("Here's something mellow")
    }

    @Test
    fun `a failure never leaves a spinner behind`() = runTest {
        coEvery { engine.respond("boom", any()) } throws IllegalStateException("boom")
        val vm = viewModel()

        vm.onInputChange("boom")
        runCatching {
            vm.sendPrompt()
            advanceUntilIdle()
        }

        assertThat(vm.uiState.value.messages.none { it is TaisChatMessage.Thinking }).isTrue()
        assertThat(vm.isResponding.value).isFalse()
    }
}
