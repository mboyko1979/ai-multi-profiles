package dev.boyko.aiagentswitcher.compat

import com.intellij.openapi.components.*
import dev.boyko.aiagentswitcher.AgentProfiles
import dev.boyko.aiagentswitcher.ChatProfiles
import com.intellij.ml.llm.chat.shared.*

/** Immutable attribution of native response cards; contains no account or prompt data. */
@Service(Service.Level.APP)
@State(name = "AiMultiResponseProfiles", storages = [Storage("AiMultiResponseProfiles.xml")])
class ResponseProfiles : PersistentStateComponent<ResponseProfiles.Data> {
    data class Author(var profileId: String = "", var name: String = "", var agentId: String = "", var agentName: String = "")
    data class Data(var chats: MutableMap<String, MutableMap<String, Author>> = mutableMapOf())
    private var data = Data()
    @Synchronized override fun getState() = Data(data.chats.mapValues { (_, entries) ->
        entries.mapValues { it.value.copy() }.toMutableMap()
    }.toMutableMap())
    @Synchronized override fun loadState(state: Data) { data = state }
    @Synchronized fun find(chat: String, event: Int): Author? = data.chats[chat]?.get(event.toString())?.copy()
    @Synchronized fun remember(chat: String, event: Int, profile: AgentProfiles.Entry): Author =
        remember(chat, event, Author(profile.id, profile.name, profile.agentId, profile.agentName))
    @Synchronized fun remember(chat: String, event: Int, author: Author): Author =
        data.chats.getOrPut(chat) { mutableMapOf() }.getOrPut(event.toString()) {
            author.copy()
        }.copy()

    /** Recover from a namespaced tool or an unambiguous exact public answer.
     * Never infer an old author from the current selection. */
    fun recover(chat: String, events: List<ChatSessionMessageEvent>) {
        val record = ChatProfiles.conversationRecord(chat) ?: return
        val segments = record.getAsJsonArray("segments") ?: return
        val bySegment = segments.associate { item ->
            val segment = item.asJsonObject
            val saved = segment.getAsJsonObject("author")
            val author = saved?.let { Author(it.get("profileId").asString, it.get("name").asString,
                it.get("agentId")?.asString.orEmpty(), it.get("agentName")?.asString.orEmpty()) }
            segment.get("id").asString to author
        }
        // Match exact public answer text when no namespaced tool was called.
        // Older continuations without provenance are deliberately left unknown.
        val textAuthors = mutableMapOf<String, MutableSet<String>>()
        record.getAsJsonArray("exchanges")?.forEach { item ->
            val exchange = item.asJsonObject
            val textBySegment = linkedMapOf<String, StringBuilder>()
            exchange.getAsJsonArray("updates")?.forEach updates@ { value ->
                val update = value.asJsonObject
                if (update.get("sessionUpdate")?.asString != "agent_message_chunk") return@updates
                val content = update.getAsJsonObject("content") ?: return@updates
                if (content.get("type")?.asString != "text") return@updates
                val segmentId = update.getAsJsonObject("_meta")?.getAsJsonObject("ai-multi")?.get("segmentId")?.asString
                    ?: (if (exchange.getAsJsonArray("continuations")?.size().let { it == null || it == 0 })
                        exchange.get("segment")?.asString else null)
                    ?: return@updates
                textBySegment.getOrPut(segmentId) { StringBuilder() }.append(content.get("text")?.asString.orEmpty())
            }
            textBySegment.forEach { (segment, text) ->
                if (text.isNotEmpty()) textAuthors.getOrPut(text.toString()) { mutableSetOf() }.add(segment)
            }
        }
        val group = mutableListOf<ChatSessionMessageBlockEvent>()
        fun flush() {
            val identifiedByTool = group.mapNotNull { block ->
                val step = block.event.javaClass.methods.firstOrNull { it.name == "getStepId" && it.parameterCount == 0 }
                    ?.invoke(block.event)?.toString() ?: return@mapNotNull null
                bySegment.entries.firstOrNull { step.startsWith(it.key + ":") }?.value
            }.distinctBy { it.profileId to it.agentId }
            val answer = group.filter { it.event.javaClass.simpleName == "MarkdownBlockUpdatedEvent" }
                .joinToString("") { it.event.javaClass.getMethod("getTextChunk").invoke(it.event) as String }
            val identified = if (identifiedByTool.isNotEmpty()) identifiedByTool else
                textAuthors[answer].orEmpty().takeIf { ids -> ids.all { bySegment[it] != null } }
                    .orEmpty().mapNotNull { bySegment[it] }.distinctBy { it.profileId to it.agentId }
            if (identified.size == 1) group.forEach { remember(chat, it.id.id, identified.single()) }
            group.clear()
        }
        events.forEach { event ->
            when (event) {
                is ChatSessionUserPromptEvent -> flush()
                is ChatSessionFailureEvent -> flush()
                is ChatSessionMessageBlockEvent -> if (event.agentId.id == ChatProfiles.AGENT_ID) {
                    group.add(event)
                    if (event.event.javaClass.simpleName == "ResultBlockUpdatedEvent") flush()
                }
            }
        }
        flush()
    }
}
