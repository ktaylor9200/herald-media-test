package dev.hermeskotlin.ui.chat

import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

@Composable
internal actual fun rememberPlayback(bytes: ByteArray, video: Boolean): Playback {
    val playback = remember(bytes) { AndroidPlayback(bytes, video) }
    DisposableEffect(playback) {
        playback.prepare()
        onDispose { playback.release() }
    }
    // The player reports no progress of its own: read where it is while it plays.
    LaunchedEffect(playback, playback.playing) {
        while (playback.playing) {
            playback.poll()
            delay(POLL_MS)
        }
    }
    return playback
}

@Composable
internal actual fun VideoSurface(playback: Playback, modifier: Modifier) {
    val player = playback as AndroidPlayback
    AndroidView(
        factory = { context ->
            TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    private var surface: Surface? = null

                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                        surface = Surface(texture).also(player::attach)
                    }

                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        player.attach(null)
                        surface?.release()
                        surface = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                }
            }
        },
        // A film playing keeps the screen on, as in a player app.
        update = { it.keepScreenOn = playback.playing },
        modifier = modifier,
    )
}

/**
 * Android's MediaPlayer reading the file from memory. Its calls are made on the main thread and its
 * callbacks come back there, so its state is plain Compose state. Nothing is called on it once released.
 */
private class AndroidPlayback(private val bytes: ByteArray, private val video: Boolean) : Playback {
    override var ready by mutableStateOf(false)
        private set
    override var playing by mutableStateOf(false)
        private set
    override var position by mutableLongStateOf(0L)
        private set
    override var duration by mutableLongStateOf(0L)
        private set
    override var videoAspect by mutableStateOf<Float?>(null)
        private set
    override var error by mutableStateOf<String?>(null)
        private set

    private var released = false
    private val player = MediaPlayer()

    fun prepare() {
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(if (video) AudioAttributes.CONTENT_TYPE_MOVIE else AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        player.setOnPreparedListener { prepared ->
            duration = prepared.duration.toLong().coerceAtLeast(0)
            ready = true
        }
        player.setOnVideoSizeChangedListener { _, width, height ->
            if (width > 0 && height > 0) videoAspect = width.toFloat() / height
        }
        player.setOnCompletionListener {
            playing = false
            position = duration
        }
        player.setOnErrorListener { _, what, extra ->
            playing = false
            error = errorMessage(what, extra)
            true
        }
        try {
            player.setDataSource(BytesSource(bytes))
            player.prepareAsync()
        } catch (_: Exception) {
            error = "Couldn't read this file to play it."
        }
    }

    override fun play() {
        if (released || !ready) return
        // From the end, a tap on Play starts again.
        if (duration > 0 && position >= duration) seekTo(0)
        player.start()
        playing = true
    }

    override fun pause() {
        if (released || !playing) return
        player.pause()
        playing = false
        poll()
    }

    override fun seekTo(ms: Long) {
        if (released || !ready) return
        val target = ms.coerceIn(0, duration.takeIf { it > 0 } ?: ms)
        player.seekTo(target, MediaPlayer.SEEK_CLOSEST)
        position = target
    }

    fun poll() {
        if (released || !ready) return
        position = player.currentPosition.toLong()
    }

    fun attach(surface: Surface?) {
        if (released) return
        try {
            player.setSurface(surface)
        } catch (_: IllegalStateException) {
        }
    }

    fun release() {
        if (released) return
        released = true
        playing = false
        player.release()
    }

    private fun errorMessage(what: Int, extra: Int): String = when {
        extra == MediaPlayer.MEDIA_ERROR_UNSUPPORTED || extra == MediaPlayer.MEDIA_ERROR_MALFORMED || what == MediaPlayer.MEDIA_ERROR_UNKNOWN ->
            "This phone can't play this ${if (video) "video" else "recording"}. Open it in another app instead."
        else -> "Playing stopped with an error."
    }
}

/** The whole file, already in memory, handed to the player as it asks for it. */
private class BytesSource(private val bytes: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= bytes.size) return -1
        val count = minOf(size.toLong(), bytes.size - position).toInt()
        System.arraycopy(bytes, position.toInt(), buffer, offset, count)
        return count
    }

    override fun getSize(): Long = bytes.size.toLong()

    override fun close() = Unit
}

/** How often the shown position is brought up to date while playing. */
private const val POLL_MS = 250L
