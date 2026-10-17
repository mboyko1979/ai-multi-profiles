package dev.boyko.aiagentswitcher

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*

/** Local presentation choices are separate from the committed conversation and never change its binding. */
internal object LocalChatState {
    private fun file(record: JsonObject, suffix: String) = ProfileConfiguration.directory.resolve("local-chats")
        .resolve(ProfileConfiguration.digest(Gson().toJson(record.get("sessionId").asString)) + ".$suffix.json")
    fun readOnly(record: JsonObject?): Boolean = record != null && runCatching {
        val state = JsonParser.parseString(Files.readString(file(record, "view"))).asJsonObject
        state.get("readOnly")?.asBoolean == true && listOf("sessionId", "cwd", "currentSegment", "revision").all { state.get(it) == record.get(it) }
    }.getOrDefault(false)
    fun select(record: JsonObject, profile: String) {
        require(readOnly(record)) { "This chat is not in local read-only mode." }
        val target = JsonObject().apply {
            listOf("sessionId", "cwd", "currentSegment", "revision").forEach { add(it, record.get(it)) }
            addProperty("profile", profile)
        }
        val path = file(record, "target")
        Files.createDirectories(path.parent)
        val tmp = Files.createTempFile(path.parent, ".target-", ".tmp")
        try {
            Files.writeString(tmp, target.toString())
            try { Files.move(tmp, path, ATOMIC_MOVE, REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(tmp, path, REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(tmp) }
    }

    /** Stage a user-selected target after native acquisition failed. No binding is changed;
     * the router must obtain consent before creating a replacement session. */
    fun selectDisconnected(record: JsonObject, profile: String) {
        val view = JsonObject().apply {
            listOf("sessionId", "cwd", "currentSegment", "revision").forEach { add(it, record.get(it)) }
            addProperty("readOnly", true)
            addProperty("reason", "disconnected")
        }
        val path = file(record, "view")
        Files.createDirectories(path.parent)
        val tmp = Files.createTempFile(path.parent, ".view-", ".tmp")
        try {
            Files.writeString(tmp, view.toString())
            Files.move(tmp, path, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(tmp) }
        select(record, profile)
    }

}
