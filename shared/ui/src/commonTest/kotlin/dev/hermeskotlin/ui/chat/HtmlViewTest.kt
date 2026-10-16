package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.media.GatewayFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
