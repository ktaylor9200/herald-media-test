package dev.hermeskotlin.ui.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FeedPlayerTest {

    @Test
    fun aRecordingTheGatewayWontHandOverIsMarkedFailed() = runTest {
        val player = FeedPlayer(this)
        player.toggle("/opt/data/reply.mp3", video = false) { null }
        assertEquals("/opt/data/reply.mp3", player.loading)
        testScheduler.advanceUntilIdle()
        assertNull(player.loading)
        assertEquals("/opt/data/reply.mp3", player.failed)
        assertNull(player.playbackOf("/opt/data/reply.mp3"))
    }

    @Test
    fun aSecondTapWhileLoadingDoesntFetchAgain() = runTest {
        val player = FeedPlayer(this)
        val gate = CompletableDeferred<ByteArray?>()
        var fetches = 0
        val load: suspend () -> ByteArray? = {
            fetches++
            gate.await()
        }
        // runCurrent, not advanceUntilIdle: virtual time would run out the fetch's timeout.
        player.toggle("/a.mp3", video = false, load)
        testScheduler.runCurrent()
        player.toggle("/a.mp3", video = false, load)
        testScheduler.runCurrent()
        assertEquals(1, fetches)
        gate.complete(null)
        testScheduler.advanceUntilIdle()
        assertEquals("/a.mp3", player.failed)
    }

    @Test
    fun startingAnotherStopsTheFirstFetch() = runTest {
        val player = FeedPlayer(this)
        val never = CompletableDeferred<ByteArray?>()
        player.toggle("/a.mp3", video = false) { never.await() }
        testScheduler.runCurrent()
        player.toggle("/b.mp3", video = false) { null }
        assertEquals("/b.mp3", player.loading)
        testScheduler.advanceUntilIdle()
        // The first fetch was cancelled: it neither failed nor left anything loading.
        assertNull(player.loading)
        assertEquals("/b.mp3", player.failed)
        player.stop()
        assertNull(player.source)
    }
}
