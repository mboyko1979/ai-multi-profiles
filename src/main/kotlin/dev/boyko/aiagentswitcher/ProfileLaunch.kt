package dev.boyko.aiagentswitcher

import com.google.gson.*
import com.intellij.openapi.components.service
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

/** Shared launch intent. Native clients own acquisition; this layer owns profile identity only. */
internal data class ProfileLaunch(val profile: AgentProfiles.Entry) {
    val profileId get() = profile.id

    fun stage(chatUid: String, cwd: String) {
        val root = ProfileConfiguration.directory
        val config = JsonParser.parseString(Files.readString(root.resolve("routes.json")).trimStart('\uFEFF')).asJsonObject
        val spec = config.getAsJsonObject("profiles").get(profileId) ?: error("Profile was removed")
        fun canonical(value: JsonElement): JsonElement = when {
            value.isJsonObject -> JsonObject().apply { value.asJsonObject.keySet().sorted().forEach { add(it, canonical(value.asJsonObject.get(it))) } }
            value.isJsonArray -> JsonArray().apply { value.asJsonArray.forEach { add(canonical(it)) } }
            else -> value
        }
        val gson = GsonBuilder().disableHtmlEscaping().create()
        val request = JsonObject().apply {
            addProperty("chatUid", chatUid); addProperty("profile", profileId); addProperty("cwd", cwd)
            addProperty("specHash", ProfileConfiguration.digest(gson.toJson(canonical(spec))))
            addProperty("createdAt", System.currentTimeMillis())
        }
        val dir = root.resolve("chat-starts"); Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, ".start-", ".tmp")
        try { Files.writeString(tmp, gson.toJson(request)); Files.move(tmp, dir.resolve(ProfileConfiguration.digest(gson.toJson(chatUid)) + ".json"), ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
        finally { Files.deleteIfExists(tmp) }
    }

    companion object {
        fun resolve(id: String) = ProfileLaunch(service<AgentProfiles>().find(id) ?: error("Profile was removed"))
    }
}
