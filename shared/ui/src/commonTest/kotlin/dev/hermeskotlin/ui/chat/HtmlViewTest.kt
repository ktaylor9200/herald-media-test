package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.media.GatewayFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HtmlViewTest {

    @Test
    fun aPageIsDrawnAtItsPathSoRelativeLinksFindItsFolder() {
        assertEquals("https://files.herald.invalid/opt/data/out/report.html", pageUrlOf("/opt/data/out/report.html"))
        assertEquals("https://files.herald.invalid/~/notes/index.html", pageUrlOf("~/notes/index.html"))
        assertEquals("https://files.herald.invalid/C:/work/page.html", pageUrlOf("C:/work/page.html"))
        // Spaces, `#`, `?` and non-ASCII are escaped, so they stay part of the name.
        assertEquals("https://files.herald.invalid/tmp/My%20Chart%20%231%3F.html", pageUrlOf("/tmp/My Chart #1?.html"))
        assertEquals("https://files.herald.invalid/tmp/%C3%BC%F0%9F%98%80.html", pageUrlOf("/tmp/ü😀.html"))
    }

    @Test
    fun anAddressOnThePageSiteIsTheGatewayFileBesideIt() {
        assertEquals(GatewayFile("/opt/data/out/style.css"), gatewayFileOfPage("/opt/data/out/style.css"))
        assertEquals(GatewayFile("~/notes/chart.js"), gatewayFileOfPage("/~/notes/chart.js"))
        assertEquals(GatewayFile("C:/work/data.json"), gatewayFileOfPage("/C:/work/data.json"))
        assertEquals(GatewayFile("/tmp/My Chart #1?.png"), gatewayFileOfPage("/tmp/My%20Chart%20%231%3F.png"))
        assertEquals(GatewayFile("/tmp/ü😀.png"), gatewayFileOfPage("/tmp/%C3%BC%F0%9F%98%80.png"))
        // A `%` that starts no escape is kept as written.
        assertEquals(GatewayFile("/tmp/100%real.png"), gatewayFileOfPage("/tmp/100%real.png"))
        assertNull(gatewayFileOfPage("/opt/data/"))
        assertNull(gatewayFileOfPage(""))
    }

    @Test
    fun pathsGoThereAndBackUnchanged() {
        listOf("/opt/data/out/report.html", "~/a b/c.html", "C:/x/y.html", "/tmp/50% off & more (1).html", "/tmp/日本語.html").forEach { path ->
            val encoded = pageUrlOf(path).removePrefix("https://$PAGE_HOST")
            assertEquals(GatewayFile(path), gatewayFileOfPage(encoded), path)
        }
    }

    @Test
    fun aDrawingIsShownAsAnImageSoItsScriptsDontRun() {
        val page = svgPage(pageUrlOf("/opt/data/out/My Chart.svg"))
        assertTrue("""<img src="https://files.herald.invalid/opt/data/out/My%20Chart.svg" alt="">""" in page, page)
        assertFalse("<script" in page)
        // A data URL's quotes can't end the attribute early.
        val data = svgPage("data:image/svg+xml;utf8,<svg xmlns=\"http://www.w3.org/2000/svg\"/>")
        assertTrue("src=\"data:image/svg+xml;utf8,&lt;svg xmlns=&quot;http://www.w3.org/2000/svg&quot;/>\"" in data, data)
    }

    @Test
    fun drawingsAreToldApartFromPictures() {
        listOf("/opt/data/chart.svg", "https://example.com/logo.SVG?v=2", "data:image/svg+xml;base64,PHN2Zy8+").forEach {
            assertTrue(isSvgSource(it), it)
        }
        // A gzipped drawing can't be drawn from the gateway's bytes as they come: it's a file to save or open.
        listOf("/opt/data/chart.png", "data:image/png;base64,AAAA", "https://example.com/svg/logo.png", "/x/icon.svgz").forEach {
            assertFalse(isSvgSource(it), it)
        }
    }

    @Test
    fun aPageReadsOnlyTheFilesInItsOwnFolder() {
        val page = GatewayFile("/opt/data/out/report.html")
        listOf("/opt/data/out/style.css", "/opt/data/out/assets/chart.js", "/opt/data/out/report.html").forEach {
            assertTrue(pageMayRead(page, GatewayFile(it)), it)
        }
        listOf(
            // Above its folder, beside it, or anywhere else on the gateway.
            "/opt/data/secrets.txt", "/opt/data/outside/x.css", "/etc/passwd", "~/notes.md",
            // Hidden files and folders, however deep.
            "/opt/data/out/.env", "/opt/data/out/assets/.git/config", "/opt/data/out/.ssh/id_ed25519",
        ).forEach { assertFalse(pageMayRead(page, GatewayFile(it)), it) }
        // A `..` the page escaped so the view wouldn't fold it away still can't climb out.
        assertFalse(pageMayRead(page, gatewayFileOfPage("/opt/data/out/..%2F..%2F..%2Fetc%2Fpasswd")!!))
        // Nor can one written with Windows separators.
        assertFalse(pageMayRead(page, GatewayFile("/opt/data/out/a\\..\\..\\x.txt")))
        // In the home folder, the page's folder there and below.
        val notes = GatewayFile("~/notes/index.html")
        assertTrue(pageMayRead(notes, GatewayFile("~/notes/app.js")))
        assertFalse(pageMayRead(notes, GatewayFile("~/.hermes/auth.json")))
        assertFalse(pageMayRead(notes, GatewayFile("/home/me/notes/app.js")))
    }

    @Test
    fun onlyATapFollowsALinkOutOfAPage() {
        val page = GatewayFile("/opt/data/out/report.html")
        assertEquals(PageLink.App, pageLinkOf(page, "https", "example.com", "/", tapped = true))
        assertEquals(PageLink.App, pageLinkOf(page, "mailto", null, "me@example.com", tapped = true))
        assertEquals(PageLink.File(GatewayFile("/opt/data/out/part2.html")), pageLinkOf(page, "https", PAGE_HOST, "/opt/data/out/part2.html", tapped = true))
        // A page moving itself could carry what it read to a site in the address.
        assertEquals(PageLink.Stay, pageLinkOf(page, "https", "evil.example", "/", tapped = false))
        assertEquals(PageLink.Stay, pageLinkOf(page, "https", PAGE_HOST, "/opt/data/out/part2.html", tapped = false))
        // Itself, other apps' schemes and folders go nowhere.
        assertEquals(PageLink.Stay, pageLinkOf(page, "https", PAGE_HOST, "/opt/data/out/report.html", tapped = true))
        assertEquals(PageLink.Stay, pageLinkOf(page, "intent", null, "//scan", tapped = true))
        assertEquals(PageLink.Stay, pageLinkOf(page, "https", PAGE_HOST, "/opt/data/", tapped = true))
    }

    @Test
    fun aPageCantOpenItsOwnConnectionsUntilTheWebIsAllowed() {
        val policy = """<meta http-equiv="Content-Security-Policy" content="connect-src https://files.herald.invalid data: blob:">"""
        // After the doctype, which has to come first or the page is laid out in quirks mode.
        assertEquals("<!DOCTYPE html>$policy<html><body>hi</body></html>", guardedPage("<!DOCTYPE html><html><body>hi</body></html>", allowWeb = false))
        assertEquals(
            "\uFEFF<?xml version=\"1.0\"?>\n<!-- made by the agent -->\n<!doctype html>$policy\n<p>x</p>",
            guardedPage("\uFEFF<?xml version=\"1.0\"?>\n<!-- made by the agent -->\n<!doctype html>\n<p>x</p>", allowWeb = false),
        )
        assertEquals("$policy<p>no doctype</p>", guardedPage("<p>no doctype</p>", allowWeb = false))
        // A doctype later in the page isn't the page's own.
        assertEquals("$policy<p>a</p><!doctype html>", guardedPage("<p>a</p><!doctype html>", allowWeb = false))
        assertEquals("<!doctype html><p>web</p>", guardedPage("<!doctype html><p>web</p>", allowWeb = true))
    }

    @Test
    fun heldBackSitesAreListedShort() {
        assertEquals("cdn.jsdelivr.net", blockedSites(listOf("cdn.jsdelivr.net")))
        assertEquals("a.com and b.com", blockedSites(listOf("a.com", "b.com")))
        assertEquals("a.com, b.com and 2 more", blockedSites(listOf("a.com", "b.com", "c.com", "d.com")))
    }

    @Test
    fun playTimes() {
        assertEquals("0:00", formatPlayTime(0))
        assertEquals("0:07", formatPlayTime(7_900))
        assertEquals("3:41", formatPlayTime(221_000))
        assertEquals("1:02:09", formatPlayTime(3_729_000))
        assertEquals("0:00", formatPlayTime(-5))
    }
}
