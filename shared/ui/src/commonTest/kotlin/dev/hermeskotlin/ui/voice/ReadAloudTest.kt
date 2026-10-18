package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.voice.AudioApi
import dev.hermeskotlin.core.voice.Recording
import dev.hermeskotlin.core.voice.SpeechPlayer
import dev.hermeskotlin.core.voice.SpokenAudio
import dev.hermeskotlin.core.voice.VoiceActivity
import dev.hermeskotlin.core.voice.VoiceRecorder
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadAloudTest {

    private val gateway = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private object NoMic : VoiceRecorder {
        override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit) = Recording(ByteArray(0), "audio/wav", false)
        override fun finish() = Unit
    }

    /** A microphone that records until it's stopped. */
    private object OpenMic : VoiceRecorder {
        override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit): Recording = awaitCancellation()
        override fun finish() = Unit
    }

    /** Records what it played; with [hold], each clip plays until the reading is stopped. */
    private class Player(private val hold: Boolean = false) : SpeechPlayer {
        val played = mutableListOf<SpokenAudio>()
        val started = CompletableDeferred<Unit>()
        override suspend fun play(audio: SpokenAudio) {
            played += audio
            started.complete(Unit)
            if (hold) awaitCancellation()
        }
    }

    private fun audio(requests: MutableList<HttpRequestData>, fails: Boolean = false) = AudioApi(
        createHttpClient(
            MockEngine { request ->
                requests += request
                if (fails) {
                    respond("""{"ok":false,"error":"No TTS provider is configured."}""", HttpStatusCode.InternalServerError, json)
                } else {
                    respond("""{"ok":true,"data_url":"data:audio/mpeg;base64,AAAA","mime_type":"audio/mpeg"}""", HttpStatusCode.OK, json)
                }
            },
            PersistentCookiesStorage(InMemoryKeyValueStore()),
        ),
    )

    private fun spoken(requests: List<HttpRequestData>) =
        requests.filter { it.url.encodedPath == "/api/audio/speak" }.map { (it.body as TextContent).text }

    @Test
    fun aReplyIsReadWithTheProfilesVoiceAsAVoiceChatWouldReadIt() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val player = Player()
        val voice = VoiceController(audio(requests), NoMic, player, backgroundScope, backgroundScope)

        voice.readAloud("r1", "## Done\nThe **deploy** finished.\n```sh\nmake deploy\n```", gateway, "work") { error(it) }

        assertEquals("r1", voice.reading.value?.key)
        assertNull(voice.reading.first { it == null })
        assertEquals(1, player.played.size)
        val speak = requests.single { it.url.encodedPath == "/api/audio/speak" }
        assertEquals("work", speak.url.parameters["profile"])
        // Markdown and code are silence, as in a voice chat.
        assertEquals(listOf("""{"text":"Done The deploy finished."}"""), spoken(requests))
    }

    @Test
    fun tappingTheSpeakerAgainStopsIt() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val player = Player(hold = true)
        val voice = VoiceController(audio(requests), NoMic, player, backgroundScope, backgroundScope)

        voice.readAloud("r1", "First reply.", gateway, null) { error(it) }
        player.started.await()
        assertEquals(false, voice.reading.value?.preparing)

        voice.readAloud("r1", "First reply.", gateway, null) { error(it) }
        assertNull(voice.reading.value)
    }

    @Test
    fun anotherReplyTakesOver() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val player = Player(hold = true)
        val voice = VoiceController(audio(requests), NoMic, player, backgroundScope, backgroundScope)

        voice.readAloud("r1", "First reply.", gateway, null) { error(it) }
        player.started.await()
        voice.readAloud("r2", "Second reply.", gateway, null) { error(it) }

        assertEquals("r2", voice.reading.value?.key)
        voice.reading.first { it?.preparing == false }
        assertEquals("r2", voice.reading.value?.key)
        assertEquals(listOf("""{"text":"First reply."}""", """{"text":"Second reply."}"""), spoken(requests))
    }

    @Test
    fun dictatingStopsTheReadingSoTheMicrophoneDoesntWriteItDown() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val player = Player(hold = true)
        val voice = VoiceController(audio(requests), OpenMic, player, backgroundScope, backgroundScope)

        voice.readAloud("r1", "A long reply.", gateway, null) { error(it) }
        player.started.await()
        voice.startDictation(gateway, null) { error("nothing was said") }
        assertNull(voice.reading.value)
        assertTrue(voice.dictation.value.recording)

        // And a speaker tapped while dictating doesn't start.
        voice.readAloud("r2", "Another reply.", gateway, null) { error(it) }
        assertNull(voice.reading.value)
        assertEquals(listOf("""{"text":"A long reply."}"""), spoken(requests))
    }

    @Test
    fun aGatewayWithoutSpeechSaysSo() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val player = Player()
        val voice = VoiceController(audio(requests, fails = true), NoMic, player, backgroundScope, backgroundScope)
        val errors = mutableListOf<String>()

        voice.readAloud("r1", "Hello there.", gateway, null) { errors += it }

        assertNull(voice.reading.first { it == null })
        assertTrue(player.played.isEmpty())
        assertEquals(1, errors.size, "$errors")
    }

    @Test
    fun aReplyWithNothingToSayIsntSent() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val voice = VoiceController(audio(requests), NoMic, Player(), backgroundScope, backgroundScope)
        val errors = mutableListOf<String>()

        voice.readAloud("r1", "```\nls -la\n```", gateway, null) { errors += it }

        assertNull(voice.reading.value)
        assertTrue(requests.isEmpty())
        assertEquals(listOf("There's nothing in this reply to read aloud."), errors)
    }
}
