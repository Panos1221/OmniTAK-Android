package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import soy.engindearing.omnitak.mobile.data.ChatConversation
import soy.engindearing.omnitak.mobile.data.ChatMessage
import soy.engindearing.omnitak.mobile.data.ChatParticipant
import soy.engindearing.omnitak.mobile.data.ChatRoom
import soy.engindearing.omnitak.mobile.data.ChatStatus

/**
 * In-memory chat log. Keyed by conversationId → ordered list of messages,
 * plus a parallel map of conversation metadata. Everything sits behind
 * StateFlows so Compose screens observe reactively.
 *
 * A broadcast conversation ([ChatRoom.ALL_USERS]) is seeded on creation
 * so the "team chat" tab has a sensible landing target before any
 * contacts are discovered.
 *
 * Ingest is called from per-server collectors and the mesh pipeline
 * concurrently, so every read-modify-write runs inside
 * [MutableStateFlow.update]'s CAS loop — plain `value = value + x`
 * could drop a concurrent message under multi-server burst.
 */
class ChatStore {

    private val _conversations = MutableStateFlow<Map<String, ChatConversation>>(
        mapOf(
            ChatRoom.ALL_USERS to ChatConversation(
                id = ChatRoom.ALL_USERS,
                title = ChatRoom.ALL_USERS,
                isGroup = true,
            )
        )
    )
    val conversations: StateFlow<Map<String, ChatConversation>> = _conversations.asStateFlow()

    private val _messagesByConversation = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    val messagesByConversation: StateFlow<Map<String, List<ChatMessage>>> = _messagesByConversation.asStateFlow()

    /**
     * GAP-122 — let domain code (eg. MeshtasticManager → ChatStore wire-up
     * in OmniTAKApp) seed a conversation header up-front, before any
     * message arrives. Used so the Chat tab shows "Mesh: Primary" as a
     * channel even when no traffic has come through yet.
     */
    fun upsertConversationIfMissing(id: String, title: String, isGroup: Boolean = true) {
        _conversations.update { convos ->
            if (convos.containsKey(id)) convos
            else convos + (id to ChatConversation(id = id, title = title, isGroup = isGroup))
        }
    }

    /**
     * Seed a full [ChatConversation] if no entry exists for its id yet.
     * Used by ChatScreen to promote a contact stub (built from ContactStore)
     * into a real backing conversation with participant metadata so that
     * the send path can resolve the recipient UID and callsign.
     */
    fun upsertConversationIfMissing(conversation: ChatConversation) {
        _conversations.update { convos ->
            if (convos.containsKey(conversation.id)) convos
            else convos + (conversation.id to conversation)
        }
    }

    /**
     * GAP-123 — create the conversation if missing, OR rename it if the
     * existing title is still the placeholder we seeded before the radio
     * told us its real channel name. Used when admin-port channel reads
     * fold back into the chat list (e.g. "Mesh: Primary" → "Mesh: OmniTAK").
     */
    fun upsertOrRenameConversation(id: String, title: String, isGroup: Boolean = true) {
        _conversations.update { convos ->
            when (val current = convos[id]) {
                null -> convos + (id to ChatConversation(id = id, title = title, isGroup = isGroup))
                else ->
                    if (current.title == title) convos
                    else convos + (id to current.copy(title = title))
            }
        }
    }

    fun ingest(message: ChatMessage) {
        var added = false
        _messagesByConversation.update { byConvo ->
            val existing = byConvo[message.conversationId].orEmpty()
            if (existing.any { it.id == message.id }) {
                added = false
                byConvo
            } else {
                added = true
                byConvo + (message.conversationId to (existing + message).sortedBy { it.timeIso })
            }
        }
        if (added) upsertConversation(message, incrementUnread = !message.isFromSelf)
    }

    fun markOutgoing(message: ChatMessage) {
        _messagesByConversation.update { byConvo ->
            val existing = byConvo[message.conversationId].orEmpty()
            byConvo + (message.conversationId to (existing + message).sortedBy { it.timeIso })
        }
        upsertConversation(message, incrementUnread = false)
    }

    fun updateMessageStatus(conversationId: String, messageId: String, status: ChatStatus) {
        _messagesByConversation.update { byConvo ->
            val list = byConvo[conversationId].orEmpty()
            val idx = list.indexOfFirst { it.id == messageId }
            if (idx < 0) return@update byConvo
            val updated = list.toMutableList()
            updated[idx] = updated[idx].copy(status = status)
            byConvo + (conversationId to updated)
        }
    }

    fun markRead(conversationId: String) {
        _conversations.update { convos ->
            val convo = convos[conversationId] ?: return@update convos
            if (convo.unread == 0) convos
            else convos + (conversationId to convo.copy(unread = 0))
        }
    }

    private fun upsertConversation(message: ChatMessage, incrementUnread: Boolean) {
        _conversations.update { convos ->
            val current = convos[message.conversationId]
            val isGroup = message.conversationId == ChatRoom.ALL_USERS
            val title = current?.title
                ?: if (isGroup) ChatRoom.ALL_USERS else message.senderCallsign

            val participants = (current?.participants.orEmpty() + ChatParticipant(
                uid = message.senderUid,
                callsign = message.senderCallsign,
            )).distinctBy { it.uid }

            val next = (current ?: ChatConversation(
                id = message.conversationId,
                title = title,
                isGroup = isGroup,
            )).copy(
                title = title,
                participants = participants,
                lastMessagePreview = message.text,
                lastActivityIso = message.timeIso,
                unread = (current?.unread ?: 0) + if (incrementUnread) 1 else 0,
                // Per-server DM scoping: remember which server this thread is on
                // so replies route back to it. Group/broadcast rooms stay null.
                serverId = current?.serverId ?: if (!isGroup) message.serverId else null,
            )
            convos + (message.conversationId to next)
        }
    }
}
