package dev.hermeskotlin.ui.bots

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermeskotlin.core.chat.LastChatStore
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.errorMessage
import dev.hermeskotlin.core.sessions.MACHINE_SOURCES
import dev.hermeskotlin.core.sessions.SessionSummary
import dev.hermeskotlin.core.sessions.SessionsApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Bots list's Side chats: the user's own conversations in one bot's profile, newest first, apart from its
 * Bot Chat. [bot] is the profile shown, null until a bot's chat has been opened. [total] counts them all, so
 * "Show more" knows when it's done.
 */
data class SideChatsState(
    val bot: String? = null,
    val sessions: List<SessionSummary> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = false,
    /** Why the list couldn't be read. */
    val error: String? = null,
    /** Why a rename, pin, archive or delete didn't go through. */
    val notice: String? = null,
) {
    val canLoadMore: Boolean get() = sessions.size < total
}

/**
 * Sessions a bot's profile holds that aren't conversations with the user: the machine's own (scheduled runs,
 * Kanban, subagents and the like, which the Chats list leaves out too), the Runs API's (a client's or a check's
 * probes, a handoff) and room turns. The bot's Bot Chat is hidden on the gateway, so it never lists anyway.
 */
internal val SIDE_CHAT_HIDDEN_SOURCES = MACHINE_SOURCES + listOf("api_server", "bot_room")

/**
 * The bot whose side chats show: the open chat's, when it's in one of [bots]' profiles (its Bot Chat or any other
 * chat with it); otherwise the one [shown] stays, so opening a room or a chat in no bot's profile changes nothing.
 * A bot that's gone (deleted) shows nothing.
 */
internal fun sideChatsBot(shown: String?, openProfile: String?, bots: Collection<String>): String? = when {
    openProfile != null && openProfile in bots -> openProfile
    shown != null && shown in bots -> shown
    else -> null
}

/** Lists and changes one bot's side chats, in that bot's profile. */
class SideChatsViewModel(
    private val api: SessionsApi,
    private val lastChats: LastChatStore,
) : ViewModel() {

    private val _state = MutableStateFlow(SideChatsState())
    val state: StateFlow<SideChatsState> = _state.asStateFlow()

    private var gateway: GatewayUrl? = null
    private var loading: Job? = null

    fun bind(url: GatewayUrl) {
        if (gateway == url) return
        gateway = url
        loading?.cancel()
        _state.value = SideChatsState()
    }

    /** Follows the open chat (see [sideChatsBot]); a new bot's list is read at once. */
    fun follow(openProfile: String?, bots: Collection<String>) {
        val next = sideChatsBot(_state.value.bot, openProfile, bots)
        if (next == _state.value.bot) return
        loading?.cancel()
        _state.value = SideChatsState(bot = next)
        load(reset = true)
    }

    /** Reads the newest page again, as the sidebar opens: a chat may have started or moved on meanwhile. */
    fun refresh() = load(reset = true)

    fun loadMore() {
        if (_state.value.canLoadMore && !_state.value.loading) load(reset = false)
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    fun rename(session: SessionSummary, title: String) = change(
        apply = { list -> list.map { if (it.id == session.id) it.copy(title = title.trim().ifBlank { null }) else it } },
        call = { url, bot -> api.rename(url, session.id, title.trim(), bot) },
    )

    fun togglePinned(session: SessionSummary) = change(
        apply = { list -> list.map { if (it.id == session.id) it.copy(pinned = !session.pinned) else it } },
        call = { url, bot -> api.setPinned(url, session.id, !session.pinned, bot) },
    )

    /** Archived chats leave the list; they're in the Chats list's Archived, in the bot's profile. */
    fun archive(session: SessionSummary) = change(
        apply = { list -> list.filterNot { it.id == session.id } },
        call = { url, bot -> api.setArchived(url, session.id, true, bot) },
        removes = true,
    )

    /** Deletes it for good; [rememberedIn] is where the Chats list is, where a side chat left open is remembered. */
    fun delete(session: SessionSummary, rememberedIn: String?) = change(
        apply = { list -> list.filterNot { it.id == session.id } },
        call = { url, bot ->
            api.delete(url, session.id, bot).also { if (it is ApiResult.Success) lastChats.forget(url, session.id, rememberedIn) }
        },
        removes = true,
    )

    private fun load(reset: Boolean) {
        val url = gateway ?: return
        val bot = _state.value.bot ?: return
        val offset = if (reset) 0 else _state.value.sessions.size
        loading?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        loading = viewModelScope.launch {
            val result = api.list(url, limit = PAGE, offset = offset, profile = bot, excludeSources = SIDE_CHAT_HIDDEN_SOURCES)
            _state.update { state ->
                if (state.bot != bot) return@update state
                when (result) {
                    is ApiResult.Success -> {
                        // A gateway that ignores the exclusions still lists only conversations.
                        val page = result.value.sessions.filterNot { it.source in SIDE_CHAT_HIDDEN_SOURCES }
                        // The server appends pinned rows past the page, so a later page can repeat one.
                        val rows = if (reset) page else (state.sessions + page).distinctBy { it.id }
                        state.copy(sessions = rows, total = maxOf(result.value.total, rows.size), loading = false)
                    }
                    else -> state.copy(loading = false, error = result.errorMessage ?: "Couldn't load the chats.")
                }
            }
        }
    }

    private fun change(
        apply: (List<SessionSummary>) -> List<SessionSummary>,
        call: suspend (GatewayUrl, String) -> ApiResult<Unit>,
        removes: Boolean = false,
    ) {
        val url = gateway ?: return
        val bot = _state.value.bot ?: return
        val before = _state.value
        _state.update { it.copy(sessions = apply(it.sessions), total = if (removes) it.total - 1 else it.total, notice = null) }
        viewModelScope.launch {
            val result = call(url, bot)
            if (result !is ApiResult.Success) {
                _state.update { state ->
                    if (state.bot != bot) state
                    else state.copy(sessions = before.sessions, total = before.total, notice = result.errorMessage ?: "That didn't go through.")
                }
            }
        }
    }

    private companion object {
        /** Side chats shown before "Show more"; each tap reads as many again. */
        const val PAGE = 8
    }
}
