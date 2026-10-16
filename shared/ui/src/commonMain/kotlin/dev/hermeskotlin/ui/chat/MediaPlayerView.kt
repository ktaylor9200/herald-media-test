package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.composables.icons.lucide.AudioLines
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Play
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.accentSoft
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.label
import dev.hermeskotlin.designsystem.onAccent
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.text as textColor
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import kotlinx.coroutines.delay

/** A recording or film loaded to play, its state readable from Compose. Times are in milliseconds. */
internal interface Playback {
    /** Ready to play: the file was read and its length is known. */
    val ready: Boolean
    val playing: Boolean
    val position: Long

    /** 0 until [ready], or when the file doesn't say. */
    val duration: Long

    /** A film's width over its height, once known; null for a recording. */
    val videoAspect: Float?

    /** Why it can't be played, in plain words. */
    val error: String?

    /** Plays from where it is; before it's [ready], as soon as it is. */
    fun play()

    fun pause()

    fun seekTo(ms: Long)

    /** Brings [position] up to date; the player reports no progress of its own. */
    fun poll()

    /** Lets the player go; nothing else is done on it after. */
    fun release()
}

/** [bytes] read to play, getting ready in the background. */
internal expect fun newPlayback(bytes: ByteArray, video: Boolean): Playback

/** [bytes] made ready to play while in composition, and let go after. */
@Composable
internal fun rememberPlayback(bytes: ByteArray, video: Boolean): Playback {
    val playback = remember(bytes) { newPlayback(bytes, video) }
    DisposableEffect(playback) { onDispose { playback.release() } }
    PollWhilePlaying(playback)
    return playback
}

/** Keeps [playback]'s position current while it plays. */
@Composable
internal fun PollWhilePlaying(playback: Playback?) {
    val playing = playback?.playing == true
    LaunchedEffect(playback, playing) {
        while (playing) {
            playback?.poll()
            delay(POLL_MS)
        }
    }
}

/** How often the shown position is brought up to date while playing. */
private const val POLL_MS = 250L

/** Where [playback]'s picture is drawn. */
@Composable
internal expect fun VideoSurface(playback: Playback, modifier: Modifier = Modifier)

/**
 * A recording or film the agent made, played in the viewer: a film's picture (a tap plays or pauses it),
 * then play and pause, the time, and a bar to tap or drag through it. Playing pauses when the app goes to
 * the background. What the phone can't play goes to [onError], for the viewer to offer the file elsewhere.
 */
@Composable
internal fun MediaPlayerView(bytes: ByteArray, name: String, video: Boolean, onError: (String) -> Unit, modifier: Modifier = Modifier) {
    val playback = rememberPlayback(bytes, video)
    val feed = LocalFeedPlayer.current
    LaunchedEffect(playback.error) { playback.error?.let(onError) }
    // One thing plays at a time: the viewer's player stops the chat's.
    LaunchedEffect(playback.playing) { if (playback.playing) feed?.pause() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { playback.pause() }
    val toggle = { if (playback.playing) playback.pause() else playback.play() }
    Column(modifier, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        if (video) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Theme[radii][radiusMedium]))
                    .background(Color.Black)
                    .clickable(enabled = playback.ready, onClickLabel = if (playback.playing) "Pause" else "Play", onClick = toggle),
                contentAlignment = Alignment.Center,
            ) {
                val aspect = playback.videoAspect
                VideoSurface(playback, if (aspect != null) Modifier.aspectRatio(aspect) else Modifier.fillMaxSize())
                if (!playback.ready) Spinner()
            }
            Spacer(Modifier.height(12.dp))
        } else {
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(96.dp).background(Theme[colors][accentSoft], RoundedCornerShape(28.dp)),
                contentAlignment = Alignment.Center,
            ) {
                UnstyledIcon(Lucide.AudioLines, contentDescription = null, tint = Theme[colors][accentText], modifier = Modifier.size(44.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(
                name,
                style = Theme[typography][label],
                color = Theme[colors][textColor],
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
        PlayerControls(playback, toggle, Modifier.widthIn(max = 560.dp).fillMaxWidth())
        if (!video) Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun PlayerControls(playback: Playback, toggle: () -> Unit, modifier: Modifier) {
    // Where a drag along the bar has got to, shown in place of the playing position until it's let go.
    var scrub by remember(playback) { mutableStateOf<Long?>(null) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            if (playback.ready) {
                IconButton(
                    if (playback.playing) Lucide.Pause else Lucide.Play,
                    contentDescription = if (playback.playing) "Pause" else "Play",
                    onClick = toggle,
                    tint = Theme[colors][onAccent],
                    containerColor = Theme[colors][accent],
                )
            } else {
                Spinner(Modifier.size(20.dp))
            }
        }
        Text(formatPlayTime(scrub ?: playback.position), style = Theme[typography][caption], color = Theme[colors][textTertiary])
        SeekBar(
            position = playback.position,
            duration = playback.duration,
            onSeek = playback::seekTo,
            onScrub = { scrub = it },
            modifier = Modifier.weight(1f).height(32.dp),
        )
        Text(formatPlayTime(playback.duration), style = Theme[typography][caption], color = Theme[colors][textTertiary])
    }
}

/**
 * A bar through a recording: tap a place to go there, or drag along it, [onScrub] getting where the drag has
 * got to (null once it's let go) and [onSeek] where it ended. Does nothing while [duration] isn't known.
 */
@Composable
internal fun SeekBar(
    position: Long,
    duration: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onScrub: (Long?) -> Unit = {},
    thumb: Dp = 14.dp,
) {
    var dragged by remember { mutableStateOf<Float?>(null) }
    val fraction = dragged ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val enabled = duration > 0
    BoxWithConstraints(
        modifier
            .semantics {
                contentDescription = "Position"
                stateDescription = "${formatPlayTime(position)} of ${formatPlayTime(duration)}"
            }
            .pointerInput(duration) {
                if (duration <= 0) return@pointerInput
                detectTapGestures { offset -> onSeek(((offset.x / size.width).coerceIn(0f, 1f) * duration).toLong()) }
            }
            .pointerInput(duration) {
                if (duration <= 0) return@pointerInput
                fun at(x: Float) = (x / size.width).coerceIn(0f, 1f)
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragged = at(offset.x)
                        onScrub((at(offset.x) * duration).toLong())
                    },
                    onDragEnd = {
                        dragged?.let { onSeek((it * duration).toLong()) }
                        dragged = null
                        onScrub(null)
                    },
                    onDragCancel = {
                        dragged = null
                        onScrub(null)
                    },
                    onHorizontalDrag = { change, _ ->
                        dragged = at(change.position.x)
                        onScrub((at(change.position.x) * duration).toLong())
                    },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val track = if (enabled) Theme[colors][stroke] else Theme[colors][stroke].copy(alpha = 0.6f)
        Box(Modifier.fillMaxWidth().height(4.dp).background(track, CircleShape))
        Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(Theme[colors][accent], CircleShape))
        if (enabled) {
            Box(
                Modifier
                    .padding(start = (maxWidth - thumb).coerceAtLeast(0.dp) * fraction)
                    .size(thumb)
                    .background(Theme[colors][accent], CircleShape),
            )
        }
    }
}

/** 0:07, 3:41, 1:02:09. */
internal fun formatPlayTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    val ss = seconds.toString().padStart(2, '0')
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$ss" else "$minutes:$ss"
}
