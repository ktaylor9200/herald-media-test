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

    fun play()

    fun pause()

    fun seekTo(ms: Long)
}

/** [bytes] made ready to play while in composition, and let go after. */
@Composable
internal expect fun rememberPlayback(bytes: ByteArray, video: Boolean): Playback

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
    LaunchedEffect(playback.error) { playback.error?.let(onError) }
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
    var dragged by remember(playback) { mutableStateOf<Float?>(null) }
    val duration = playback.duration
    val fraction = dragged ?: if (duration > 0) (playback.position.toFloat() / duration).coerceIn(0f, 1f) else 0f
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
        Text(
            formatPlayTime(if (dragged != null) (fraction * duration).toLong() else playback.position),
            style = Theme[typography][caption],
            color = Theme[colors][textTertiary],
        )
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .height(32.dp)
                .semantics {
                    contentDescription = "Position"
                    stateDescription = "${formatPlayTime(playback.position)} of ${formatPlayTime(duration)}"
                }
                .pointerInput(playback, duration) {
                    if (duration <= 0) return@pointerInput
                    detectTapGestures { offset -> playback.seekTo((offset.x / size.width).coerceIn(0f, 1f).times(duration).toLong()) }
                }
                .pointerInput(playback, duration) {
                    if (duration <= 0) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { offset -> dragged = (offset.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = {
                            dragged?.let { playback.seekTo((it * duration).toLong()) }
                            dragged = null
                        },
                        onDragCancel = { dragged = null },
                        onHorizontalDrag = { change, _ -> dragged = (change.position.x / size.width).coerceIn(0f, 1f) },
                    )
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(4.dp).background(Theme[colors][stroke], CircleShape))
            Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(Theme[colors][accent], CircleShape))
            Box(
                Modifier
                    .padding(start = (maxWidth - 14.dp).coerceAtLeast(0.dp) * fraction)
                    .size(14.dp)
                    .background(Theme[colors][accent], CircleShape),
            )
        }
        Text(formatPlayTime(duration), style = Theme[typography][caption], color = Theme[colors][textTertiary])
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
