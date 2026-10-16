package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ShieldAlert
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.media.FileText
import dev.hermeskotlin.core.media.GatewayFile
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.designsystem.warning
import dev.hermeskotlin.designsystem.warningSoft

/**
 * [html] drawn as a page in a web view of its own: scripts run, but the view holds no sign-in, reads no
 * files on the phone, and fetches only from the gateway (the page's own folder, as [fetch] reads it) until
 * [allowWeb]. Each site it was kept from goes to [onBlocked], once per load. A tapped link to another file
 * on the gateway goes to [onOpenFile]; one to the web opens in the browser. [onContentHeight] hears how tall
 * the page draws, in pixels, as it loads and settles, for a card in the chat to size itself to it.
 */
@Composable
internal expect fun HtmlView(
    html: String,
    file: GatewayFile,
    allowWeb: Boolean,
    fetch: suspend (GatewayFile) -> ByteArray?,
    onOpenFile: (GatewayFile) -> Unit,
    onBlocked: (String) -> Unit,
    modifier: Modifier = Modifier,
    onContentHeight: ((Int) -> Unit)? = null,
)

/**
 * A web page the agent made, drawn: the text preview when it holds the whole page, else the whole file
 * (a page cut off at the preview's 512 KB draws broken). What the page wanted from the web is held back
 * until a tap on Load, as a picture from the web waits for one in a reply: fetching it tells that site the
 * page was opened, and a page steered by something the agent read could put data in the address.
 */
@Composable
internal fun HtmlPage(
    file: GatewayFile,
    text: FileText,
    download: suspend (GatewayFile) -> FileDownload,
    onOpenFile: (GatewayFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val html by produceState(text.text.takeUnless { text.truncated }, text) {
        if (text.truncated) value = (download(file) as? FileDownload.Ready)?.bytes?.decodeToString() ?: text.text
    }
    var allowWeb by remember(file) { mutableStateOf(false) }
    val blocked = remember(file) { mutableStateListOf<String>() }
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (blocked.isNotEmpty() && !allowWeb) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Theme[colors][warningSoft], shape)
                    .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                UnstyledIcon(Lucide.ShieldAlert, contentDescription = null, tint = Theme[colors][warning], modifier = Modifier.size(16.dp))
                Text(
                    "Held back content from ${blockedSites(blocked)}",
                    style = Theme[typography][caption],
                    color = Theme[colors][warning],
                    modifier = Modifier.weight(1f),
                )
                Button("Load", onClick = { allowWeb = true }, size = ButtonSize.Small, variant = ButtonVariant.Secondary)
            }
        }
        Box(
            Modifier.fillMaxWidth().weight(1f).clip(shape).border(1.dp, Theme[colors][stroke], shape),
            contentAlignment = Alignment.Center,
        ) {
            val page = html
            if (page == null) {
                Spinner()
            } else {
                HtmlView(
                    page,
                    file,
                    allowWeb = allowWeb,
                    fetch = { linked -> (download(linked) as? FileDownload.Ready)?.bytes },
                    onOpenFile = onOpenFile,
                    onBlocked = { host -> if (host !in blocked) blocked += host },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** `cdn.example.com`, `a.com and b.com`, `a.com, b.com and 3 more`. */
internal fun blockedSites(hosts: List<String>): String = when (hosts.size) {
    0 -> "the web"
    1 -> hosts[0]
    2 -> "${hosts[0]} and ${hosts[1]}"
    else -> "${hosts[0]}, ${hosts[1]} and ${hosts.size - 2} more"
}

/**
 * The made-up site a page is drawn at: never looked up, as the view answers every request to it itself
 * with the gateway's files. `.invalid` is reserved, so it can't be anyone's real site.
 */
internal const val PAGE_HOST = "files.herald.invalid"

/**
 * The address [path] is drawn at, so relative links in the page (`style.css`, `../data.json`) resolve to
 * the files beside it: `/opt/data/out/report.html` at `https://files.herald.invalid/opt/data/out/report.html`,
 * `~/` as a `/~/` folder, a Windows drive as a folder of its own. Each part is percent-escaped.
 */
internal fun pageUrlOf(path: String): String {
    val absolute = when {
        path.startsWith("~/") -> "/~/" + path.removePrefix("~/")
        path.startsWith("/") -> path
        else -> "/$path"
    }
    return "https://$PAGE_HOST" + absolute.split('/').joinToString("/") { escapeSegment(it) }
}

/**
 * The gateway file an address on [PAGE_HOST] stands for, from its still-escaped path: the reverse of
 * [pageUrlOf]. Null for a folder.
 */
internal fun gatewayFileOfPage(encodedPath: String): GatewayFile? {
    val decoded = encodedPath.split('/').joinToString("/") { unescapeSegment(it) }
    if (decoded.isEmpty() || decoded.endsWith("/")) return null
    val path = when {
        decoded.startsWith("/~/") -> "~/" + decoded.removePrefix("/~/")
        WINDOWS_DRIVE.containsMatchIn(decoded) -> decoded.substring(1)
        else -> decoded
    }
    return GatewayFile(path)
}

private val WINDOWS_DRIVE = Regex("""^/[A-Za-z]:/""")

/** Characters a path segment holds as they are (RFC 3986's unreserved ones and the sub-delimiters, `:` and `@`). */
private fun Char.keptInPath(): Boolean =
    this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this in "-._~!$&'()*+,;=:@"

private fun escapeSegment(segment: String): String = buildString {
    segment.encodeToByteArray().forEach { byte ->
        val c = (byte.toInt() and 0xFF).toChar()
        if (byte >= 0 && c.keptInPath()) {
            append(c)
        } else {
            append('%')
            append(HEX[(byte.toInt() shr 4) and 0xF])
            append(HEX[byte.toInt() and 0xF])
        }
    }
}

/**
 * `%XX` escapes as UTF-8 bytes; a `%` that starts none stays as it is. Literal text between escapes is
 * encoded a run at a time, so a character outside the BMP keeps both halves of its pair.
 */
private fun unescapeSegment(segment: String): String {
    if ('%' !in segment) return segment
    val out = ArrayList<Byte>(segment.length)
    var literal = 0
    var i = 0
    fun flush(end: Int) {
        if (end > literal) segment.substring(literal, end).encodeToByteArray().forEach { out += it }
    }
    while (i < segment.length) {
        val escape = segment[i] == '%' && i + 2 <= segment.lastIndex && segment[i + 1].isHex() && segment[i + 2].isHex()
        if (escape) {
            flush(i)
            out += segment.substring(i + 1, i + 3).toInt(16).toByte()
            i += 3
            literal = i
        } else {
            i++
        }
    }
    flush(segment.length)
    return out.toByteArray().decodeToString()
}

private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private const val HEX = "0123456789ABCDEF"
