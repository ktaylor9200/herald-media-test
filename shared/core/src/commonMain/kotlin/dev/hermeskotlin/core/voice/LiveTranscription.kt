package dev.hermeskotlin.core.voice

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.rpc.ticketProtocols
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLParameter
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * What's said, written down while it's still being said (`/api/audio/transcribe-stream`, Desktop's live
 * dictation): the audio goes up as it's heard, so the words are ready moments after the speaker stops, rather
 * than once the whole recording has gone up and been transcribed.
 */
interface LiveTranscription {
    /** More of what's heard: 16-bit little-endian mono PCM at the rate it was opened with. */
    suspend fun send(pcm: ByteArray)

    /**
     * That's all of it: the whole transcript, blank when no words were in it, or null when the gateway couldn't
     * write it down (no live speech-to-text, a provider error, a dropped socket), so the recording goes up whole.
     */
    suspend fun finish(): String?

    /** Stops at once: nothing is written down. */
    suspend fun close()
}

/** Opens [LiveTranscription]s; null when this gateway can't (an older one, or no socket). */
fun interface LiveTranscriptions {
    /** One for PCM at [sampleRate]. [onPartial] gets the words so far, all of them, each time they change. */
    suspend fun open(url: GatewayUrl, profile: String?, sampleRate: Int, onPartial: (String) -> Unit): LiveTranscription?
}

/**
 * [LiveTranscriptions] over the gateway's socket, which takes a fresh single-use ticket in its subprotocol list
 * as `/api/audio/speak-stream` does, and `?profile=`, so the profile's own speech-to-text writes it down.
 */
class GatewayLiveTranscriptions(private val client: HttpClient, private val auth: AuthApi) : LiveTranscriptions {

    override suspend fun open(url: GatewayUrl, profile: String?, sampleRate: Int, onPartial: (String) -> Unit): LiveTranscription? {
        val ticket = (auth.mintWsTicket(url) as? ApiResult.Success)?.value?.ticket ?: return null
        val query = profile?.let { "?profile=" + it.encodeURLParameter() }.orEmpty()
        val session = try {
            client.webSocketSession(url.siblingSocketUrl("api/audio/transcribe-stream") + query) {
                header(HttpHeaders.SecWebSocketProtocol, ticketProtocols(ticket))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        return KtorLiveTranscription(session.incoming, session.outgoing, session, onPartial) { session.close() }
            .also { it.begin(sampleRate) }
    }
}

/**
 * [LiveTranscription] over a socket's frames, read in [scope]. A gateway without live speech-to-text answers
 * with an error straight away; from then on nothing more is sent, and [finish] says so.
 */
internal class KtorLiveTranscription(
    private val incoming: ReceiveChannel<Frame>,
    private val outgoing: SendChannel<Frame>,
    scope: CoroutineScope,
    private val onPartial: (String) -> Unit,
    private val closeSocket: suspend () -> Unit,
) : LiveTranscription {
    /** The transcript once the gateway has it; null when it couldn't, or the socket went first. */
    private val result = CompletableDeferred<String?>()

    init {
        scope.launch {
            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    when (val heard = parseTranscriptFrame(frame.readText())) {
                        is TranscriptFrame.Partial -> onPartial(heard.text)
                        is TranscriptFrame.Final -> {
                            result.complete(heard.transcript)
                            break
                        }
                        is TranscriptFrame.Failed -> break
                        null -> Unit
                    }
                }
            } catch (_: Exception) {
                // A dropped socket has nothing more to say; OkHttp says so with a CancellationException.
            } finally {
                result.complete(null)
            }
        }
    }

    /** The audio's rate, which the gateway needs before any of it. */
    internal suspend fun begin(sampleRate: Int) = sendQuietly(Frame.Text(buildJsonObject { put("sample_rate", sampleRate) }.toString()))

    override suspend fun send(pcm: ByteArray) {
        if (!result.isCompleted) sendQuietly(Frame.Binary(true, pcm))
    }

    override suspend fun finish(): String? {
        sendQuietly(Frame.Text(buildJsonObject { put("eos", true) }.toString()))
        return try {
            withTimeoutOrNull(FINAL_MS) { result.await() }
        } finally {
            close()
        }
    }

    override suspend fun close() {
        try {
            closeSocket()
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
        }
    }

    /** A socket already gone has nothing to say back; [result] ends on its own. */
    private suspend fun sendQuietly(frame: Frame) {
        try {
            outgoing.send(frame)
        } catch (_: Exception) {
            // Only the caller's own cancellation goes on up.
            currentCoroutineContext().ensureActive()
        }
    }

    private companion object {
        /** The words come moments after the end; past this, the recording goes up whole instead. */
        const val FINAL_MS = 5_000L
    }
}

/** The gateway's text frames while it writes down what's said. */
internal sealed interface TranscriptFrame {
    /** All the words so far. */
    data class Partial(val text: String) : TranscriptFrame

    data class Final(val transcript: String) : TranscriptFrame

    data class Failed(val message: String) : TranscriptFrame
}

/** `partial`, `final` and `error`; anything else is ignored. */
internal fun parseTranscriptFrame(text: String): TranscriptFrame? {
    val json = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
    fun string(key: String) = (json[key] as? JsonPrimitive)?.contentOrNull
    return when (string("type")) {
        "partial" -> TranscriptFrame.Partial(string("text").orEmpty())
        "final" -> TranscriptFrame.Final(string("transcript").orEmpty())
        "error" -> TranscriptFrame.Failed(string("message").orEmpty())
        else -> null
    }
}

/**
 * What a listen hears, on its way to a [LiveTranscription]. Until [start] it keeps only the last [preRollBytes],
 * what led up to the first word; from then on all of it goes to [out], those first. Fed from one thread at a time
 * (the recorder's), read from another.
 */
class HeardAudio(private val preRollBytes: Int) {
    private val held = ArrayDeque<ByteArray>()
    private var heldBytes = 0
    private var started = false

    /** What's to be sent, in order; closed once the listen is over. */
    val out = Channel<ByteArray>(Channel.UNLIMITED)

    fun add(pcm: ByteArray) {
        if (started) {
            out.trySend(pcm)
            return
        }
        held.addLast(pcm)
        heldBytes += pcm.size
        // Only as much as the pre-roll needs: the oldest goes once the rest is enough without it.
        while (held.size > 1 && heldBytes - held.first().size >= preRollBytes) heldBytes -= held.removeFirst().size
    }

    /** Speech was heard: what led up to it goes first, and everything after it follows. */
    fun start() {
        if (started) return
        started = true
        held.forEach { out.trySend(it) }
        held.clear()
        heldBytes = 0
    }

    /** The listen is over. */
    fun end() {
        out.close()
    }
}
