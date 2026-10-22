package dev.hermeskotlin.ui.bots

import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.chat.LastChat
import dev.hermeskotlin.core.chat.LastChatStore
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.sessions.SessionSummary
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.ui.sessions.visibleSideChats
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SideChatsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val gateway = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val store = InMemoryKeyValueStore()
    private val lastChats = LastChatStore(store)

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** A gateway whose bots each hold [chats] side chats, "<bot>-1", "<bot>-2", …, newest first. */
    private fun viewModel(requests: MutableList<HttpRequestData>, chats: Int = 3, listStatus: HttpStatusCode = HttpStatusCode.OK): SideChatsViewModel {
        val config = MockEngineConfig()
        config.dispatcher = dispatcher
        config.addHandler { request ->
            requests += request
            when {
                request.url.encodedPath == "/api/sessions" -> {
                    val bot = request.url.parameters["profile"]
                    val offset = request.url.parameters["offset"]?.toInt() ?: 0
                    val limit = request.url.parameters["limit"]?.toInt() ?: 20
                    val rows = (offset + 1..minOf(offset + limit, chats)).joinToString(",") { """{"id":"$bot-$it","title":"Chat $it","source":"desktop"}""" }
                    respond("""{"sessions":[$rows],"total":$chats}""", listStatus, json)
                }
                // A gateway without snapshots, so writes go without an identity (SessionsApiTest covers that).
                request.url.encodedPath.endsWith("/mutation-snapshot") -> respond("""{"detail":"Not Found"}""", HttpStatusCode.NotFound, json)
                else -> respond("{}", HttpStatusCode.OK, json)
            }
        }
        val client = createHttpClient(MockEngine(config), PersistentCookiesStorage(InMemoryKeyValueStore()))
        return SideChatsViewModel(SessionsApi(client), lastChats).also { it.bind(gateway) }
    }

    @Test
    fun theOpenChatsBotIsFollowedAndARoomOrAnotherProfileKeepsIt() {
        val bots = setOf("vivian", "evelyn", "default")
        // Nothing shows until a bot's chat is opened.
        assertNull(sideChatsBot(null, null, bots))
        assertEquals("vivian", sideChatsBot(null, "vivian", bots))
        assertEquals("evelyn", sideChatsBot("vivian", "evelyn", bots))
        // A room, or a chat in the launch profile or in no bot's profile, leaves the bot as it was.
        assertEquals("vivian", sideChatsBot("vivian", null, bots))
        assertEquals("vivian", sideChatsBot("vivian", "work", bots))
        // A bot that's been deleted shows nothing.
        assertNull(sideChatsBot("franklin", null, bots))
    }

    @Test
    fun aBotsOwnConversationsAreListedInItsProfileWithoutMachineRunsOrProbes() = runTest(dispatcher) {
        val requests = mutableListOf<HttpRequestData>()
        val vm = viewModel(requests)

        vm.follow("vivian", setOf("vivian"))

        val list = requests.single { it.url.encodedPath == "/api/sessions" }.url.parameters
        assertEquals("vivian", list["profile"])
        assertEquals("exclude", list["archived"])
        val left = list["exclude_sources"].orEmpty().split(",").toSet()
        // Scheduled runs, Kanban, subagents and one-shots, as the Chats list leaves out; Runs API probes and room turns too.
        assertTrue(left.containsAll(setOf("cron", "kanban", "subagent", "oneshot", "tool", "acp", "api_server", "bot_room")), "$left")
        assertEquals(listOf("vivian-1", "vivian-2", "vivian-3"), vm.state.value.sessions.map { it.id })
        assertFalse(vm.state.value.canLoadMore)
    }

    @Test
    fun showMoreReadsTheNextEightUntilThereAreNoMore() = runTest(dispatcher) {
        val requests = mutableListOf<HttpRequestData>()
        val vm = viewModel(requests, chats = 11)

        vm.follow("vivian", setOf("vivian"))
        assertEquals(8, vm.state.value.sessions.size)
        assertTrue(vm.state.value.canLoadMore)

        vm.loadMore()

        assertEquals((1..11).map { "vivian-$it" }, vm.state.value.sessions.map { it.id })
        assertFalse(vm.state.value.canLoadMore)
        assertEquals("8", requests.last { it.url.encodedPath == "/api/sessions" }.url.parameters["offset"])
    }

    @Test
    fun switchingBotsReadsTheOtherBotsChats() = runTest(dispatcher) {
        val requests = mutableListOf<HttpRequestData>()
        val vm = viewModel(requests)
        val bots = setOf("vivian", "evelyn")

        vm.follow("vivian", bots)
        vm.follow(null, bots)
        assertEquals("vivian", vm.state.value.bot)
        vm.follow("evelyn", bots)

        assertEquals("evelyn", vm.state.value.bot)
        assertEquals(listOf("evelyn-1", "evelyn-2", "evelyn-3"), vm.state.value.sessions.map { it.id })
        // Following the same bot again doesn't read it again.
        assertEquals(2, requests.count { it.url.encodedPath == "/api/sessions" })
    }

    @Test
    fun archivingAndDeletingAreDoneInTheBotsProfileAndTheRowLeaves() = runTest(dispatcher) {
        val requests = mutableListOf<HttpRequestData>()
        val vm = viewModel(requests)
        vm.follow("vivian", setOf("vivian"))
        // Left open last time, remembered where the Chats list is.
        lastChats.set(gateway, LastChat("vivian-2", profile = "vivian"), "work")

        vm.archive(vm.state.value.sessions[0])
        vm.delete(vm.state.value.sessions.first { it.id == "vivian-2" }, rememberedIn = "work")

        val patch = requests.single { it.method == HttpMethod.Patch }
        assertEquals("/api/sessions/vivian-1", patch.url.encodedPath)
        val delete = requests.single { it.method == HttpMethod.Delete }
        assertEquals("vivian", delete.url.parameters["profile"])
        assertEquals(listOf("vivian-3"), vm.state.value.sessions.map { it.id })
        assertEquals(1, vm.state.value.total)
        assertNull(lastChats.get(gateway, "work"))
    }

    @Test
    fun aListThatCantBeReadSaysWhy() = runTest(dispatcher) {
        val vm = viewModel(mutableListOf(), listStatus = HttpStatusCode.ServiceUnavailable)

        vm.follow("vivian", setOf("vivian"))

        assertTrue(vm.state.value.sessions.isEmpty())
        assertFalse(vm.state.value.loading)
        assertTrue(vm.state.value.error != null)
    }

    @Test
    fun aFoldedSectionStillShowsTheOpenChat() {
        val chats = listOf(SessionSummary("a"), SessionSummary("b"), SessionSummary("c"))
        assertEquals(listOf("b"), visibleSideChats(chats, collapsed = true, selectedId = "b").map { it.id })
        assertTrue(visibleSideChats(chats, collapsed = true, selectedId = "elsewhere").isEmpty())
        assertEquals(chats, visibleSideChats(chats, collapsed = false, selectedId = "b"))
    }
}
