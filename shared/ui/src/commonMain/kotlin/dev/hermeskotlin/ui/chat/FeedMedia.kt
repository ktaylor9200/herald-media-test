package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.ExternalLink
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Maximize2
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.ShieldAlert
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.chat.ReplyMedia
import dev.hermeskotlin.core.media.GatewayFile
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.DropdownMenu
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.components.MenuAction
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.label
import dev.hermeskotlin.designsystem.onAccent
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.text as textColor
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one recording or film the chat plays, wherever it is in the feed. It belongs to the chat, not to the
 * card that started it, so a recording goes on while the card scrolls out of sight; starting another stops
 * it. Loaded on the first tap of Play, not before: a reply full of recordings costs nothing until one is played.
 */
@Stable
internal class FeedPlayer(private val scope: CoroutineScope) {
    /** What's loaded to play: a reply's media source. */
    var source by mutableStateOf<String?>(null)
        private set
    var playback by mutableStateOf<Playback?>(null)
        private set

    /** The source being fetched from the gateway, before it can play. */
    var loading by mutableStateOf<String?>(null)
        private set

    /** The source the gateway wouldn't hand over, last time it was asked. */
    var failed by mutableStateOf<String?>(null)
        private set

    private var job: Job? = null

    /** [source]'s player, when it's the one loaded. */
    fun playbackOf(source: String): Playback? = playback.takeIf { this.source == source }

    /** Plays or pauses [source]; one not loaded yet is fetched with [load] and starts once it's ready. */
    fun toggle(source: String, video: Boolean, load: suspend () -> ByteArray?) {
        val current = playbackOf(source)
        if (current != null && current.error == null) {
            if (current.playing) current.pause() else current.play()
            return
        }
        if (loading == source) return
        stop()
        failed = null
        loading = source
        job = scope.launch {
            val bytes = try {
                withTimeoutOrNull(LOAD_TIMEOUT_MS) { load() }
            } finally {
                if (loading == source) loading = null
            }
            if (bytes == null) {
                failed = source
                return@launch
            }
            this@FeedPlayer.source = source
            playback = newPlayback(bytes, video).also { it.play() }
        }
    }

    fun pause() {
        playback?.pause()
    }

    /** Lets go of what's loaded, or stops fetching it. */
    fun stop() {
        job?.cancel()
        job = null
        loading = null
        playback?.release()
        playback = null
        source = null
    }

    private companion object {
        const val LOAD_TIMEOUT_MS = 60_000L
    }
}

/** The chat's player, for the cards in its feed; null where there's none (a preview), and they show as files. */
internal val LocalFeedPlayer = staticCompositionLocalOf<FeedPlayer?> { null }

/** A chat's [FeedPlayer]: paused when the app goes to the background, let go with the chat. */
@Composable
internal fun rememberFeedPlayer(): FeedPlayer {
    val scope = rememberCoroutineScope()
    val player = remember { FeedPlayer(scope) }
    DisposableEffect(player) { onDispose { player.stop() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }
    PollWhilePlaying(player.playback)
    return player
}

/**
 * A recording a reply delivered, as a pill-shaped player in the feed: Play, its name over a bar to tap or
 * drag through it, the time, and a menu to open it full screen or elsewhere, save or share it.
 */
@Composable
internal fun ReplyAudio(item: ReplyMedia, file: GatewayFile) {
    val feed = LocalFeedPlayer.current ?: return ReplyFile(item)
    val load = LocalMediaLoader.current
    val openFile = LocalOpenFile.current
    val playback = feed.playbackOf(item.source)
    val playing = playback?.playing == true
    val busy = feed.loading == item.source || playback != null && !playback.ready && playback.error == null
    val error = playback?.error ?: "Couldn't get it".takeIf { feed.failed == item.source }
    var scrub by remember(item.source) { mutableStateOf<Long?>(null) }
    Row(
        Modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .height(56.dp)
            .clip(CircleShape)
            .border(1.dp, Theme[colors][stroke], CircleShape)
            .background(Theme[colors][surface])
            .padding(start = 8.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            if (busy) {
                Spinner(Modifier.size(20.dp))
            } else {
                IconButton(
                    if (playing) Lucide.Pause else Lucide.Play,
                    contentDescription = "${if (playing) "Pause" else "Play"} ${item.name}",
                    onClick = { feed.toggle(item.source, video = false) { load(item.source) } },
                    tint = Theme[colors][onAccent],
                    containerColor = Theme[colors][accent],
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    item.name,
                    style = Theme[typography][label],
                    color = Theme[colors][textColor],
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    when {
                        error != null -> error
                        playback?.ready == true -> "${formatPlayTime(scrub ?: playback.position)} / ${formatPlayTime(playback.duration)}"
                        else -> item.name.substringAfterLast('.', "audio").uppercase()
                    },
                    style = Theme[typography][caption],
                    color = if (error != null) Theme[colors][danger] else Theme[colors][textTertiary],
                    maxLines = 1,
                )
            }
            SeekBar(
                position = playback?.position ?: 0,
                duration = playback?.duration ?: 0,
                onSeek = { playback?.seekTo(it) },
                onScrub = { scrub = it },
                modifier = Modifier.fillMaxWidth().height(20.dp),
                thumb = 10.dp,
            )
        }
        MediaMenu(item, onOpenFull = openFile?.let { open -> { open(file) } })
    }
}

/**
 * A film a reply delivered, played where it is in the feed: a tap on the picture plays or pauses it, the bar
 * goes through it, and full screen opens it in the viewer. It pauses once scrolled out of sight.
 */
@Composable
internal fun ReplyVideo(item: ReplyMedia, file: GatewayFile) {
    val feed = LocalFeedPlayer.current ?: return ReplyFile(item)
    val load = LocalMediaLoader.current
    val openFile = LocalOpenFile.current
    val playback = feed.playbackOf(item.source)
    val playing = playback?.playing == true
    val busy = feed.loading == item.source || playback != null && !playback.ready && playback.error == null
    val error = playback?.error ?: "Couldn't get ${item.name} from the gateway.".takeIf { feed.failed == item.source }
    var scrub by remember(item.source) { mutableStateOf<Long?>(null) }
    val toggle = { feed.toggle(item.source, video = true) { load(item.source) } }
    // Sound with nothing to watch isn't what a film is for.
    DisposableEffect(feed, item.source) { onDispose { if (feed.source == item.source) feed.pause() } }
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    Column(
        Modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, Theme[colors][stroke], shape)
            .background(Theme[colors][surface]),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio((playback?.videoAspect ?: (16f / 9f)).coerceIn(0.56f, 2.4f))
                .background(Color.Black)
                .clickable(onClickLabel = if (playing) "Pause" else "Play", onClick = toggle),
            contentAlignment = Alignment.Center,
        ) {
            // A new view for each player: the surface is handed to the one it was made for.
            if (playback != null) key(playback) { VideoSurface(playback, Modifier.fillMaxSize()) }
            when {
                busy -> Spinner()
                error != null -> Text(
                    error,
                    style = Theme[typography][caption],
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp),
                )
                !playing -> Box(
                    Modifier.size(56.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    UnstyledIcon(Lucide.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        item.name,
                        style = Theme[typography][label],
                        color = Theme[colors][textColor],
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (playback?.ready == true) {
                            "${formatPlayTime(scrub ?: playback.position)} / ${formatPlayTime(playback.duration)}"
                        } else {
                            item.name.substringAfterLast('.', "video").uppercase()
                        },
                        style = Theme[typography][caption],
                        color = Theme[colors][textTertiary],
                        maxLines = 1,
                    )
                }
                if (playback?.ready == true) {
                    SeekBar(
                        position = playback.position,
                        duration = playback.duration,
                        onSeek = playback::seekTo,
                        onScrub = { scrub = it },
                        modifier = Modifier.fillMaxWidth().height(20.dp),
                        thumb = 10.dp,
                    )
                }
            }
            if (openFile != null) {
                IconButton(Lucide.Maximize2, contentDescription = "Open ${item.name} full screen", onClick = {
                    feed.pause()
                    openFile(file)
                })
            }
            MediaMenu(item, onOpenFull = null)
        }
    }
}

/**
 * A page the agent made, drawn live in the feed as on Desktop, as tall as it draws up to a limit, with a fade
 * where it goes on. Scrolling over it scrolls the chat; a tap opens it full screen, where it can be used.
 * What it wants from the web waits for Load, as in the viewer.
 */
@Composable
internal fun ReplyPage(item: ReplyMedia, file: GatewayFile) {
    val load = LocalMediaLoader.current
    val openFile = LocalOpenFile.current
    val density = LocalDensity.current
    var attempt by remember(item.source) { mutableIntStateOf(0) }
    var failed by remember(item.source) { mutableStateOf(false) }
    val html by produceState<String?>(null, item.source, attempt) {
        failed = false
        value = withTimeoutOrNull(20_000) { load(item.source) }?.decodeToString()
        if (value == null) failed = true
    }
    var allowWeb by remember(item.source) { mutableStateOf(false) }
    val blocked = remember(item.source) { mutableStateListOf<String>() }
    var drawnPx by remember(item.source) { mutableIntStateOf(0) }
    val drawn = with(density) { drawnPx.toDp() }
    val height = if (drawnPx > 0) drawn.coerceIn(PAGE_MIN, PAGE_MAX) else PAGE_START
    val open: (() -> Unit)? = openFile?.let { { it(file) } }
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    Column(
        Modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, Theme[colors][stroke], shape)
            .background(Theme[colors][surface]),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            UnstyledIcon(Lucide.Globe, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(16.dp))
            Text(
                item.name,
                style = Theme[typography][label],
                color = Theme[colors][textColor],
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (blocked.isNotEmpty() && !allowWeb) {
                Button(
                    "Load",
                    onClick = { allowWeb = true },
                    leadingIcon = Lucide.ShieldAlert,
                    size = ButtonSize.Small,
                    variant = ButtonVariant.Secondary,
                )
            }
            if (open != null) IconButton(Lucide.Maximize2, contentDescription = "Open ${item.name} full screen", onClick = open)
            MediaMenu(item, onOpenFull = null)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clickable(enabled = failed, onClickLabel = "Try again") { attempt++ },
            contentAlignment = Alignment.Center,
        ) {
            val page = html
            when {
                page != null -> {
                    HtmlView(
                        page,
                        file,
                        allowWeb = allowWeb,
                        fetch = { linked -> load(linked.path) },
                        onOpenFile = { linked -> openFile?.invoke(linked) },
                        onBlocked = { host -> if (host !in blocked) blocked += host },
                        modifier = Modifier.fillMaxSize(),
                        onContentHeight = { drawnPx = it },
                    )
                    // Above the page and taking its touches, so a drag scrolls the chat rather than the page.
                    Box(Modifier.fillMaxSize().clickable(enabled = open != null, onClickLabel = "Open ${item.name}") { open?.invoke() })
                    if (drawn > PAGE_MAX) {
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(48.dp)
                                .background(Brush.verticalGradient(listOf(Color.Transparent, Theme[colors][surface]))),
                        )
                    }
                }
                failed -> Text(
                    "Couldn't load ${item.name}. Tap to try again.",
                    style = Theme[typography][caption],
                    color = Theme[colors][textTertiary],
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp),
                )
                else -> Spinner()
            }
        }
    }
}

/** A page's height in the feed before it's measured, and the least and most it takes once it is. */
private val PAGE_START = 220.dp
private val PAGE_MIN = 96.dp
private val PAGE_MAX = 460.dp

/** The rest of what can be done with a delivered file: open it full screen or in another app, save or share it. */
@Composable
private fun MediaMenu(item: ReplyMedia, onOpenFull: (() -> Unit)?) {
    val load = LocalMediaLoader.current
    val actions = rememberMediaActions()
    val notify = LocalNotice.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }

    fun withBytes(action: suspend (ByteArray) -> Unit) {
        scope.launch {
            val bytes = load(item.source)
            if (bytes == null) notify("Couldn't get ${item.name} from the gateway.") else action(bytes)
        }
    }

    DropdownMenu(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        items = {
            fun act(block: () -> Unit) = { expanded = false; block() }
            if (onOpenFull != null) MenuAction("Open full screen", Lucide.Maximize2, act(onOpenFull))
            MenuAction("Open in another app", Lucide.ExternalLink, act { withBytes { bytes -> actions.open(bytes, item.name)?.let(notify) } })
            MenuAction("Save", Lucide.Download, act { withBytes { notify(actions.save(it, item.name)) } })
            MenuAction("Share", Lucide.Share2, act { withBytes { actions.share(it, item.name) } })
        },
    ) {
        IconButton(Lucide.EllipsisVertical, contentDescription = "More for ${item.name}", onClick = { expanded = true })
    }
}
