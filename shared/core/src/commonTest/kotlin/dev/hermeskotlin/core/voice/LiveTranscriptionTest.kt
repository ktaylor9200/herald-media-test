package dev.hermeskotlin.core.voice

import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveTranscriptionTest {

    @Test
    fun theGatewaysFramesAreRead() {
        assertEquals(TranscriptFrame.Partial("book a"), parseTranscriptFrame("""{"type":"partial","text":"book a"}"""))
        assertEquals(TranscriptFrame.End, parseTranscriptFrame("""{"type":"endpoint"}"""))
        assertEquals(TranscriptFrame.Final("Book a table."), parseTranscriptFrame("""{"type":"final","transcript":"Book a table.","provider":"openai"}"""))
        assertEquals(TranscriptFrame.Failed("no live STT for the configured provider"), parseTranscriptFrame("""{"type":"error","message":"no live STT for the configured provider"}"""))
        assertNull(parseTranscriptFrame("""{"type":"later"}"""))
        assertNull(parseTranscriptFrame("not json"))
    }

    @Test
    fun theAudioGoesUpAsItsHeardAndTheWordsComeBackOnceItEnds() = runTest {
        val incoming = Channel<Frame>(Channel.UNLIMITED)
        val outgoing = Channel<Frame>(Channel.UNLIMITED)
        val partials = mutableListOf<String>()
        var socketClosed = false
        val stream = KtorLiveTranscription(incoming, outgoing, backgroundScope, { partials += it }) { socketClosed = true }

        stream.begin(16_000)
        stream.send(byteArrayOf(1, 2))
        incoming.send(Frame.Text("""{"type":"partial","text":"book a"}"""))
        stream.send(byteArrayOf(3, 4))
        incoming.send(Frame.Text("""{"type":"partial","text":"book a table"}"""))
        incoming.send(Frame.Text("""{"type":"final","transcript":"Book a table.","provider":"openai"}"""))

        assertEquals("Book a table.", stream.finish())
        assertEquals(listOf("book a", "book a table"), partials)
        // The rate first, asking to hear when the speaker has finished, then the audio as it came, then the end.
        assertEquals("""{"sample_rate":16000,"endpoint":true}""", (outgoing.receive() as Frame.Text).readText())
        assertContentEquals(byteArrayOf(1, 2), (outgoing.receive() as Frame.Binary).readBytes())
        assertContentEquals(byteArrayOf(3, 4), (outgoing.receive() as Frame.Binary).readBytes())
        assertEquals("""{"eos":true}""", (outgoing.receive() as Frame.Text).readText())
        assertTrue(socketClosed)
    }

    /** A gateway without live speech-to-text says so at once: nothing more goes up, and the recording goes up whole. */
    @Test
    fun aGatewayThatCantWriteItDownSaysSoAtOnce() = runTest {
        val incoming = Channel<Frame>(Channel.UNLIMITED)
        val outgoing = Channel<Frame>(Channel.UNLIMITED)
        // Read in the test's own scope, which advanceUntilIdle runs; it ends with the error.
        val stream = KtorLiveTranscription(incoming, outgoing, this, {}) {}
        incoming.send(Frame.Text("""{"type":"error","message":"no live STT for the configured provider"}"""))
        incoming.close()

        stream.begin(16_000)
        testScheduler.advanceUntilIdle()
        stream.send(byteArrayOf(1, 2))

        assertNull(stream.finish())
        assertEquals("""{"sample_rate":16000,"endpoint":true}""", (outgoing.receive() as Frame.Text).readText())
        // No audio after the error: the end follows the rate.
        assertEquals("""{"eos":true}""", (outgoing.receive() as Frame.Text).readText())
    }

    /** The gateway hears the speaker finish: the listen is told once, no more audio goes up, and the words follow. */
    @Test
    fun theGatewayCanSayTheSpeakerHasFinished() = runTest {
        val incoming = Channel<Frame>(Channel.UNLIMITED)
        val outgoing = Channel<Frame>(Channel.UNLIMITED)
        var ends = 0
        // Read in the test's own scope, so the end is read before what's sent after it.
        val stream = KtorLiveTranscription(incoming, outgoing, this, {}, { ends++ }) {}

        stream.begin(16_000)
        stream.send(byteArrayOf(1, 2))
        incoming.send(Frame.Text("""{"type":"endpoint"}"""))
        incoming.send(Frame.Text("""{"type":"endpoint"}"""))
        testScheduler.advanceUntilIdle()
        stream.send(byteArrayOf(3, 4))
        incoming.send(Frame.Text("""{"type":"final","transcript":"Book a table.","provider":"openai"}"""))

        assertEquals("Book a table.", stream.finish())
        assertEquals(1, ends)
        assertEquals("""{"sample_rate":16000,"endpoint":true}""", (outgoing.receive() as Frame.Text).readText())
        assertContentEquals(byteArrayOf(1, 2), (outgoing.receive() as Frame.Binary).readBytes())
        // Nothing heard after the end goes up; the end of ours is harmless.
        assertEquals("""{"eos":true}""", (outgoing.receive() as Frame.Text).readText())
    }

    /** The phone ended the listen first, and the gateway's end crossed it: there's no listen left to end. */
    @Test
    fun anEndHeardAfterTheListenIsOverIsLeftAlone() = runTest {
        val incoming = Channel<Frame>(Channel.UNLIMITED)
        var ends = 0
        val stream = KtorLiveTranscription(incoming, Channel(Channel.UNLIMITED), backgroundScope, {}, { ends++ }) {}
        incoming.send(Frame.Text("""{"type":"endpoint"}"""))
        incoming.send(Frame.Text("""{"type":"final","transcript":"Book a table.","provider":"openai"}"""))

        assertEquals("Book a table.", stream.finish())
        assertEquals(0, ends)
    }

    @Test
    fun aSocketThatGoesBeforeTheWordsSendsTheRecordingUpWhole() = runTest {
        val incoming = Channel<Frame>(Channel.UNLIMITED)
        val stream = KtorLiveTranscription(incoming, Channel(Channel.UNLIMITED), backgroundScope, {}) {}
        incoming.send(Frame.Text("""{"type":"partial","text":"book"}"""))
        // OkHttp fails a socket the gateway closed with a cancellation of its own; it isn't the caller's.
        incoming.cancel(CancellationException("WebSocket session closed with code NORMAL."))

        assertNull(stream.finish())
    }

    @Test
    fun theWordsWaitedForAreGivenUpOnInTime() = runTest {
        val stream = KtorLiveTranscription(Channel(), Channel(Channel.UNLIMITED), backgroundScope, {}) {}

        // Nothing ever comes back: the recording goes up whole instead.
        assertNull(stream.finish())
    }

    @Test
    fun onlyTheMomentBeforeTheFirstWordIsKept() {
        val heard = HeardAudio(preRollBytes = 4)
        heard.add(byteArrayOf(1, 1))
        heard.add(byteArrayOf(2, 2))
        heard.add(byteArrayOf(3, 3))
        heard.add(byteArrayOf(4, 4))

        // Speech: the last 4 bytes before it go first, then everything after it.
        heard.start()
        heard.add(byteArrayOf(5, 5))
        heard.end()

        val sent = generateSequence { heard.out.tryReceive().getOrNull() }.toList()
        assertEquals(listOf(listOf<Byte>(3, 3), listOf<Byte>(4, 4), listOf<Byte>(5, 5)), sent.map { it.toList() })
        assertTrue(heard.out.isClosedForReceive)
    }

    @Test
    fun aListenNobodySpeaksInSendsNothing() {
        val heard = HeardAudio(preRollBytes = 4)
        heard.add(byteArrayOf(1, 1))
        heard.end()

        assertNull(heard.out.tryReceive().getOrNull())
    }
}
