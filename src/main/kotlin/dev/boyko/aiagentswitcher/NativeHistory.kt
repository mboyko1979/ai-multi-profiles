package dev.boyko.aiagentswitcher

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.ArrayDeque

/** A bounded in-memory view of native history for an explicitly requested transfer. Never writes messages. */
internal object NativeHistory {
    private val native get() = NativeRecoveryContinuation
    private fun get(value: Any, name: String) = native.call(value, name)
    private fun row(id: String, value: String) = JsonObject().apply {
        addProperty("id", id); addProperty("status", "unknown")
        add("user", JsonArray().apply { add(text(value)) }); add("updates", JsonArray())
    }
    private fun text(value: String) = JsonObject().apply { addProperty("type", "text"); addProperty("text", value) }
    private fun reply(row: JsonObject?, value: String, boundary: Boolean = false) {
        if (row == null || value.isEmpty()) return
        row.getAsJsonArray("updates").add(JsonObject().apply {
            addProperty("sessionUpdate", "agent_message_chunk"); add("content", text(value))
            if (boundary) addProperty("replyBoundary", true)
        })
    }
    private fun attachment(row: JsonObject, value: Any, urlGetter: String = "getUrl") {
        val uri = runCatching { get(value, urlGetter) as? String }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
        val name = runCatching { get(value, "getName") as? String }.getOrNull().orEmpty().ifBlank { "Native attachment" }
        row.getAsJsonArray("user").add(JsonObject().apply { addProperty("type", "resource_link"); addProperty("uri", uri); addProperty("name", name) })
    }
    fun assistant(uid: String): JsonArray {
        val storageType = native.type("com.intellij.ml.llm.chat.session.SessionHistoryStorage", "com.intellij.ml.llm") ?: return JsonArray()
        val pid = Class.forName(storageType.name + "\$PersistanceId", true, storageType.classLoader).getConstructor(String::class.java).newInstance(uid)
        val storage = get(storageType.getField("Companion").get(null), "getInstance")!!
        native.call(storage, "flush", pid)
        val events = native.call(storage, "getEvents", pid) as List<*>
        val rows = ArrayDeque<JsonObject>()
        for (event in events.filterNotNull()) when (event.javaClass.simpleName) {
            "ChatSessionUserPromptEvent" -> {
                val agent = get(get(event, "getAgentId")!!, "getId") as String
                if (agent !in listOf(ChatProfiles.AGENT_ID, "acp.ai-auto-project-switcher")) continue
                val value = get(event, "getPrompt") as String
                if (value.startsWith("AI_MULTI_HANDOFF:")) continue
                val item = row(get(event, "getId").toString(), value)
                (get(event, "getAttachments") as List<*>).filterNotNull().forEach { attachment(item, it) }
                rows.add(item); if (rows.size > 20) rows.removeFirst()
            }
            "ChatSessionMessageBlockEvent" -> {
                val block = get(event, "getEvent") ?: continue
                when (block.javaClass.simpleName) {
                    "MarkdownBlockUpdatedEvent" -> reply(rows.peekLast(), get(block, "getTextChunk") as? String ?: "", true)
                    "ResultBlockUpdatedEvent" -> {
                        reply(rows.peekLast(), get(block, "getResult") as? String ?: "", true)
                        rows.peekLast()?.addProperty("status", when {
                            get(block, "getCancelled") == true -> "cancelled"
                            get(block, "getErrorCode") != null -> "error"
                            else -> "complete"
                        })
                    }
                }
                rows.peekLast()?.addProperty("lastEventId", get(event, "getId").toString())
            }
        }
        return JsonArray().apply { rows.forEach(::add) }
    }
    fun air(runtime: Any): JsonArray {
        val projection = get(native.field(runtime, "projectionHandle")!!, "getProjection") ?: return JsonArray()
        val rows = ArrayDeque<JsonObject>()
        for (entry in (get(projection, "getTranscript") as List<*>).filterNotNull()) {
            if (entry.javaClass.simpleName != "SessionMessage" || get(entry, "getParentToolCallId") != null) continue
            val role = get(entry, "getRole").toString().lowercase()
            val value = get(entry, "getText") as String
            if (role == "user") {
                if (value.startsWith("AI_MULTI_HANDOFF:")) continue
                val item = row(get(entry, "getId").toString(), value)
                (get(entry, "getAttachments") as List<*>).filterNotNull().forEach { attachment(item, it) }
                rows.add(item); if (rows.size > 20) rows.removeFirst()
            } else if (role in listOf("agent", "assistant")) {
                reply(rows.peekLast(), value, true)
                rows.peekLast()?.addProperty("lastEventId", get(entry, "getId").toString())
            }
        }
        return JsonArray().apply { rows.forEach(::add) }
    }
    fun forRecovery(request: JsonObject): JsonArray {
        val uid = request.get("clientChatId")?.takeUnless { it.isJsonNull }?.asString
        if (uid != null && request.get("clientName")?.asString?.startsWith("air-plugin:") != true) return assistant(uid)
        val managerType = native.type("com.intellij.air.acp.AcpSessionManager", "com.intellij.air") ?: return JsonArray()
        val manager = get(managerType.getField("Companion").get(null), "getInstanceIfCreated") ?: return JsonArray()
        val store = native.field(manager, "runtimeStore") ?: return JsonArray()
        for (ref in (get(store, "refsSnapshot") as List<*>).filterNotNull()) {
            val runtime = native.call(store, "get", ref) ?: continue
            if (!get(runtime, "getWorkingDir").toString().equals(request.get("cwd").asString, true)) continue
            val projection = get(native.field(runtime, "projectionHandle")!!, "getProjection") ?: continue
            val binding = get(projection, "getRuntimeBinding") ?: continue
            if (get(binding, "getAgentSessionId") == request.get("sessionId").asString) return air(runtime)
        }
        return JsonArray()
    }
    /** Recovery transfers text only; attachment selection remains in the ordinary native chooser. */
    fun recoveryEstimates(history: JsonArray, templates: Map<Int, JsonObject>): Map<Int, JsonObject> = (0..20).associateWith { count ->
        val selected = history.toList().takeLast(count)
        val bytes = selected.sumOf { it.toString().toByteArray(Charsets.UTF_8).size }
        (templates[count]?.deepCopy() ?: JsonObject()).apply {
            addProperty("availableMessages", history.size()); addProperty("messages", selected.size); addProperty("exchanges", selected.size)
            addProperty("estimatedTokens", if (selected.isEmpty()) 0 else (bytes + 10_000 + 2) / 3)
            add("attachments", JsonArray()); addProperty("shortenedReplies", 0)
        }
    }
    fun selectedText(history: JsonArray, count: Int) = JsonArray().apply {
        history.toList().takeLast(count).forEach { item -> add(item.asJsonObject.deepCopy().apply {
            add("user", JsonArray().apply { item.asJsonObject.getAsJsonArray("user").filter { it.asJsonObject.get("type")?.asString == "text" }.forEach(::add) })
        }) }
    }
}
