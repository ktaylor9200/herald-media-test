package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.storage.KeyValueStore
import kotlinx.serialization.Serializable

/**
 * The stored session the user last had open, so the app can reopen it on launch. When it was a bot's
 * permanent chat, [bot] names the bot's profile and [botLabel] what it goes by, so it reopens as that bot's.
 * [profile] is the profile a chat is in when that isn't the one it's remembered under: one of a bot's other
 * chats, opened from the Bots list, is remembered where the Chats list was and reopens in the bot's profile.
 */
@Serializable
data class LastChat(
    val sessionId: String,
    val title: String? = null,
    val bot: String? = null,
    val botLabel: String? = null,
    val profile: String? = null,
)

/**
 * Remembers where the user left off on each gateway and profile (null: the launch profile): a stored
 * session, or nothing when they were on a new chat (which then greets them again on the next launch).
 */
class LastChatStore(private val store: KeyValueStore) {

    suspend fun get(gateway: GatewayUrl, profile: String? = null): LastChat? = store.get(key(gateway, profile))?.let {
        runCatching { HermesJson.decodeFromString(LastChat.serializer(), it) }.getOrNull()
    }

    suspend fun set(gateway: GatewayUrl, chat: LastChat?, profile: String? = null) {
        if (chat == null) store.remove(key(gateway, profile))
        else store.put(key(gateway, profile), HermesJson.encodeToString(LastChat.serializer(), chat))
    }

    /** Forgets [sessionId] if it is the remembered one, e.g. after it was deleted. */
    suspend fun forget(gateway: GatewayUrl, sessionId: String, profile: String? = null) {
        if (get(gateway, profile)?.sessionId == sessionId) set(gateway, null, profile)
    }

    private fun key(gateway: GatewayUrl, profile: String?) =
        "chat.last.v1.$gateway" + (profile?.let { "#$it" } ?: "")
}
