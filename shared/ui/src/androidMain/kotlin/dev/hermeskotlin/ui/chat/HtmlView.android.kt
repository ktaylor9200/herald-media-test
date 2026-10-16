package dev.hermeskotlin.ui.chat

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.hermeskotlin.core.media.GatewayFile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

// Scripts run: the pages agents make are charts and small tools. What keeps them in their box is the rest
// of the setup: no cookies or sign-in, no file or content access, no bridge to the app, and every request
// either answered by the view itself from the gateway or held back until the person allows the web.
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal actual fun HtmlView(
    html: String,
    file: GatewayFile,
    allowWeb: Boolean,
    fetch: suspend (GatewayFile) -> ByteArray?,
    onOpenFile: (GatewayFile) -> Unit,
    onBlocked: (String) -> Unit,
    modifier: Modifier,
    onContentHeight: ((Int) -> Unit)?,
) {
    val context = LocalContext.current
    val fetchNow by rememberUpdatedState(fetch)
    val openNow by rememberUpdatedState(onOpenFile)
    val blockedNow by rememberUpdatedState(onBlocked)
    val heightNow by rememberUpdatedState(onContentHeight)
    // Read on WebView's own threads, not the main one.
    val webAllowed = remember(file) { AtomicBoolean(allowWeb) }
    // The page's files, fetched once each while it's open: a reload for Load doesn't fetch them again.
    val fetched = remember(file) { ConcurrentHashMap<String, ByteArray>() }
    val main = remember { Handler(Looper.getMainLooper()) }
    val pageUrl = remember(file.path) { pageUrlOf(file.path) }

    val webView = remember(file.path) {
        WebView(context).apply {
            // A WebView left at wrap_content gives the page a zero-high viewport (see PluginWebView).
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            // Pages keep small state between taps (a chosen tab, a sort); it stays on the made-up site.
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setGeolocationEnabled(false)
            settings.mediaPlaybackRequiresUserGesture = true
            // A page made for a desktop fits the screen, and pinches to zoom like one in a browser.
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url
                    if (url.host.equals(PAGE_HOST, ignoreCase = true)) {
                        if (!request.method.equals("GET", ignoreCase = true)) return status(405, "Method Not Allowed")
                        val target = url.encodedPath?.let(::gatewayFileOfPage) ?: return status(404, "Not Found")
                        val bytes = fetched[target.path]
                            ?: runBlocking { withTimeoutOrNull(FETCH_TIMEOUT_MS) { fetchNow(target) } }?.also { fetched[target.path] = it }
                            ?: return status(404, "Not Found")
                        return WebResourceResponse(mimeOf(target.name), null, ByteArrayInputStream(bytes))
                    }
                    val scheme = url.scheme?.lowercase()
                    if ((scheme == "http" || scheme == "https" || scheme == "ws" || scheme == "wss") && !webAllowed.get()) {
                        val host = url.host ?: "the web"
                        main.post { blockedNow(host) }
                        return status(403, "Held back")
                    }
                    return null
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    // Scripts and web fonts keep laying the page out after it "finishes": measure again as it settles.
                    if (heightNow == null) return
                    MEASURE_AFTER_MS.forEach { delay ->
                        view.postDelayed({
                            // Scrolled away and let go meanwhile.
                            if (!view.isAttachedToWindow) return@postDelayed
                            @Suppress("DEPRECATION") // Still the one measure of the page's zoom; there's no replacement.
                            val pixels = (view.contentHeight * view.scale).toInt()
                            if (pixels > 0) heightNow?.invoke(pixels)
                        }, delay)
                    }
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // Frames load where they are; only the page itself moving away is a link to follow.
                    if (!request.isForMainFrame) return false
                    val url = request.url
                    if (url.host.equals(PAGE_HOST, ignoreCase = true)) {
                        val target = url.encodedPath?.let(::gatewayFileOfPage)
                        if (target != null && target.path != file.path) main.post { openNow(target) }
                        return true
                    }
                    when (url.scheme?.lowercase()) {
                        "http", "https", "mailto", "tel" -> try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: ActivityNotFoundException) {
                        }
                    }
                    // Anything else (`intent:`, an app's own scheme) isn't the page's to open.
                    return true
                }
            }
        }
    }
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }
    LaunchedEffect(webView, html, allowWeb) {
        webAllowed.set(allowWeb)
        webView.loadDataWithBaseURL(pageUrl, html, "text/html", "utf-8", null)
    }
    AndroidView(factory = { webView }, modifier = modifier)
}

private fun status(code: Int, reason: String) =
    WebResourceResponse("text/plain", "utf-8", code, reason, emptyMap(), ByteArrayInputStream(ByteArray(0)))

/** A file's type for the page: Android's table first, then the web types older phones' tables lack. */
private fun mimeOf(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "js", "mjs", "cjs" -> "text/javascript"
        "css" -> "text/css"
        "json", "map" -> "application/json"
        "svg" -> "image/svg+xml"
        "wasm" -> "application/wasm"
        "html", "htm" -> "text/html"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }
}

/** When a loaded page is measured again, as its scripts and fonts settle. */
private val MEASURE_AFTER_MS = listOf(0L, 300L, 1_000L, 2_500L)

/** How long the page waits for one of its files from the gateway before treating it as missing. */
private const val FETCH_TIMEOUT_MS = 20_000L
