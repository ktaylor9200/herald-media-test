package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.chat.ReplyMedia
import dev.hermeskotlin.core.media.GatewayFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedFoldTest {

    private fun delivered(path: String) = ReplyMedia(path, path.substringAfterLast('/'), isImage = false)

    private fun fold(item: ReplyMedia, widget: Boolean = false) =
        foldOf(item, GatewayFile(item.gatewayPath).takeIf { item.onGateway }, widget)

    @Test
    fun pagesDrawingsAndWidgetsComeFolded() {
        assertEquals(Fold.Page, fold(delivered("/opt/data/out/report.html")))
        assertEquals(Fold.Drawing, fold(delivered("/opt/data/out/chart.svg")))
        assertEquals(Fold.Drawing, fold(ReplyMedia("data:image/svg+xml;base64,PHN2Zy8+", "drawing.svg", isImage = true)))
        assertEquals(Fold.Widget, fold(ReplyMedia("/opt/data/out/widget.html", "widget.html", isImage = false, preview = true), widget = true))
        // A drawing the image tool made on the web is the agent's own, so it's folded like one on the gateway.
        assertEquals(Fold.Drawing, fold(ReplyMedia("https://cdn.example.com/chart.svg", "chart.svg", isImage = true, generated = true)))
    }

    @Test
    fun whatFetchesNothingUntilTappedShowsAsItIs() {
        // Recordings and films load on Play; pictures, documents and other files are as before.
        listOf("/opt/data/out/reply.mp3", "/opt/data/out/demo.mp4", "/opt/data/out/notes.pdf", "/opt/data/out/data.zip").forEach {
            assertNull(fold(delivered(it)), it)
        }
        assertNull(fold(ReplyMedia("/opt/data/out/photo.png", "photo.png", isImage = true)))
        // A drawing from the web already waits behind its "tap to load" card.
        assertNull(fold(ReplyMedia("https://example.com/logo.svg", "logo.svg", isImage = true)))
    }

    @Test
    fun aShownItemStaysShownUntilHidden() {
        val items = ShownItems()
        assertFalse(items.isShown("/opt/data/out/report.html"))
        items.toggle("/opt/data/out/report.html")
        assertTrue(items.isShown("/opt/data/out/report.html"))
        assertFalse(items.isShown("/opt/data/out/chart.svg"))
        items.toggle("/opt/data/out/report.html")
        assertFalse(items.isShown("/opt/data/out/report.html"))
    }
}
