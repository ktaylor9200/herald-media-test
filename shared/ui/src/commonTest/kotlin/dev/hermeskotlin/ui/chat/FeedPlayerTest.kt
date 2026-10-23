package dev.hermeskotlin.ui.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FeedPlayerTest {

    private val gone = FileDownload.Failed("That file isn't on the gateway any more.")

    @Test
    fun aRecordingTheGatewayWontHandOverIsMarkedFailed() = runTest {
        val player = FeedPlayer(this)
        player.toggle("/opt/data/reply.mp3", video = false) { gone }
        assertEquals("/opt/data/reply.mp3", player.loading)
        testScheduler.advanceUntilIdle()
        assertNull(player.loading)
        assertEquals("/opt/data/reply.mp3", player.failed)
        assertNull(player.playbackOf("/opt/data/reply.mp3"))
    }

    @Test
    fun theGatewaysReasonIsKeptToShow() = runTest {
        val player = FeedPlayer(this)
        val tooLarge = FileDownload.Failed("It's over 64 MB, too large to fetch to the phone from the chat.")
        player.toggle("/opt/data/film.mp4", video = true) { tooLarge }
        testScheduler.advanceUntilIdle()
        assertEquals(tooLarge.message, player.failure)
        // Trying again clears it while it asks.
        val gate = CompletableDeferred<FileDownload>()
        player.toggle("/opt/data/film.mp4", video = true) { gate.await() }
        assertNull(player.failure)
        assertNull(player.failed)
        gate.complete(gone)
        testScheduler.advanceUntilIdle()
        assertEquals(gone.message, player.failure)
    }

    @Test
    fun aSecondTapWhileLoadingDoesntFetchAgain() = runTest {
        val player = FeedPlayer(this)
        val gate = CompletableDeferred<FileDownload>()
        var fetches = 0
        val load: suspend () -> FileDownload = {
            fetches++
            gate.await()
        }
        // runCurrent, not advanceUntilIdle: virtual time would run out the fetch's timeout.
        player.toggle("/a.mp3", video = false, load)
        testScheduler.runCurrent()
        player.toggle("/a.mp3", video = false, load)
        testScheduler.runCurrent()
        assertEquals(1, fetches)
        gate.complete(gone)
        testScheduler.advanceUntilIdle()
        assertEquals("/a.mp3", player.failed)
    }

    @Test
    fun startingAnotherStopsTheFirstFetch() = runTest {
        val player = FeedPlayer(this)
        val never = CompletableDeferred<FileDownload>()
        player.toggle("/a.mp3", video = false) { never.await() }
        testScheduler.runCurrent()
        player.toggle("/b.mp3", video = false) { gone }
        assertEquals("/b.mp3", player.loading)
        testScheduler.advanceUntilIdle()
        // The first fetch was cancelled: it neither failed nor left anything loading.
        assertNull(player.loading)
        assertEquals("/b.mp3", player.failed)
        player.stop()
        assertNull(player.source)
    }

    @Test
    fun aGatewayTooSlowToAnswerIsSaidSo() = runTest {
        val player = FeedPlayer(this)
        player.toggle("/a.mp3", video = false) { CompletableDeferred<FileDownload>().await() }
        testScheduler.advanceUntilIdle()
        assertEquals("/a.mp3", player.failed)
        assertEquals("It took too long to come from the gateway.", player.failure)
    }
}
