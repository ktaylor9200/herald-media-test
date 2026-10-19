package dev.hermeskotlin.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.sessions.SessionSummary
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.components.MinTouchTarget
import dev.hermeskotlin.designsystem.eyebrow
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusSmall
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.bots.SideChatsState

/**
 * The side chats a folded section still shows: none, except the open one, so folding never hides where the
 * user is. Open, all of them.
 */
internal fun visibleSideChats(sessions: List<SessionSummary>, collapsed: Boolean, selectedId: String?): List<SessionSummary> =
    if (collapsed) sessions.filter { it.id == selectedId } else sessions

/**
 * The Bots list's Side chats, under the bots: a "Side chats · [label]" toggle with a + for a new chat with the bot,
 * then its conversations as the Chats list draws them, the open one filled, and "Show more" while there are more.
 */
internal fun LazyListScope.sideChats(
    state: SideChatsState,
    label: String,
    collapsed: Boolean,
    selectedId: String?,
    statuses: Map<String, RowStatus>,
    drafts: Set<String>,
    onToggle: () -> Unit,
    onNewChat: () -> Unit,
    onOpen: (SessionSummary) -> Unit,
    onActions: (SessionSummary) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    item(key = "side-chats-label") { SideChatsToggle(label, collapsed, onToggle, onNewChat) }
    state.notice?.let { message ->
        item(key = "side-chats-notice") { MessageBanner(message, onDismiss = onDismissNotice, modifier = Modifier.padding(vertical = 4.dp)) }
    }
    items(visibleSideChats(state.sessions, collapsed, selectedId), key = { "side:${it.id}" }) { session ->
        SessionRow(
            session,
            selected = session.id == selectedId,
            showSnippet = false,
            status = statuses[session.id],
            draft = session.id in drafts,
            onClick = { onOpen(session) },
            onActions = { onActions(session) },
        )
    }
    if (collapsed) return
    when {
        state.sessions.isNotEmpty() -> if (state.canLoadMore) item(key = "side-chats-more") {
            if (state.loading) ListSpinner()
            else Row(Modifier.fillMaxWidth().padding(start = 4.dp)) {
                Button("Show more", onClick = onLoadMore, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            }
        }
        state.loading -> item(key = "side-chats-loading") { ListSpinner() }
        state.error != null -> item(key = "side-chats-error") {
            ListNotice("Couldn't load $label's chats. ${state.error}", action = "Try again", onAction = onRetry)
        }
        else -> item(key = "side-chats-empty") { ListNotice("No other chats with $label yet. + starts one.") }
    }
}

/** "SIDE CHATS · VIVIAN" as a toggle, its chevron down while open, with a + for a new chat with the bot. */
@Composable
private fun SideChatsToggle(label: String, collapsed: Boolean, onToggle: () -> Unit, onNewChat: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = MinTouchTarget)
                .clip(RoundedCornerShape(Theme[radii][radiusSmall]))
                .clickable(onClickLabel = if (collapsed) "Show side chats" else "Hide side chats", role = Role.Button, onClick = onToggle)
                .semantics(mergeDescendants = true) {
                    heading()
                    stateDescription = if (collapsed) "Collapsed" else "Expanded"
                }
                .padding(start = 6.dp, end = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "SIDE CHATS · ${label.uppercase()}",
                style = Theme[typography][eyebrow],
                color = Theme[colors][textTertiary],
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            UnstyledIcon(
                Lucide.ChevronDown,
                contentDescription = null,
                tint = Theme[colors][textTertiary],
                modifier = Modifier.size(14.dp).rotate(if (collapsed) -90f else 0f),
            )
        }
        IconButton(Lucide.Plus, contentDescription = "New chat with $label", onClick = onNewChat, tint = Theme[colors][textTertiary])
    }
}
