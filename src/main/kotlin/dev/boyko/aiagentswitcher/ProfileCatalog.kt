package dev.boyko.aiagentswitcher

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*

/** Authorized catalogs are scoped to profile, client and model; live choices cannot replace defaults. */
internal object ProfileCatalog {
    private val gson = Gson()
    fun key(profile: AgentProfiles.Entry): String {
        val revision = profile.profileHome.takeIf { it.isNotBlank() }?.let {
            runCatching { Files.readString(Path.of(it, ProfileCredentials.REVISION_FILE)) }.getOrDefault("")
        }.orEmpty()
        return ProfileConfiguration.digest(gson.toJson(listOf(profile.id, profile.agentId, profile.profileHome,
            profile.advancedOverrides, profile.environment, profile.inheritEnvironment, revision,
            profile.command, profile.arguments, profile.workingDirectory)))
    }
    private fun file(profile: AgentProfiles.Entry, host: String) = ProfileConfiguration.directory.resolve("catalogs")
        .resolve(ProfileConfiguration.digest(profile.id) + (if (host.isBlank()) "" else "-$host-defaults") + ".json")
    @JvmOverloads fun cached(profile: AgentProfiles.Entry, host: String = ""): ProfileConfiguration.Capabilities? = runCatching {
        if (profile.profileHome.isBlank()) return null
        val path = file(profile, host)
        if (!Files.isRegularFile(path) || Files.size(path) > 8_000_000) return null
        val root = JsonParser.parseString(Files.readString(path)).asJsonObject
        if (root.get("catalogKey")?.asString != key(profile) || root.get("version")?.asInt != 1) return null
        ProfileConfiguration.parseCapabilities(root)
    }.getOrNull()
    private fun options(options: List<ProfileConfiguration.Option>) = gson.toJsonTree(options.map { option ->
        mapOf("id" to option.id, "operation" to option.operation, "name" to option.name, "type" to option.type,
            "category" to option.category, "description" to option.description, "currentValue" to option.currentValue,
            "options" to option.values.map { mapOf("value" to it.value, "name" to it.name) },
            "modelDefault" to option.modelDefault, "defaultSource" to option.defaultSource)
    })
    @Synchronized fun store(profile: AgentProfiles.Entry, value: ProfileConfiguration.Capabilities, defaults: Boolean = true) {
        if (profile.profileHome.isBlank()) return
        val previous = cached(profile, value.host)
        val selected = value.options.map { option ->
            if (defaults) option else option.copy(currentValue = previous?.options?.firstOrNull {
                it.id == option.id && it.operation == option.operation
            }?.currentValue)
        }
        val root = JsonObject().apply {
            addProperty("version", 1); addProperty("catalogKey", key(profile)); addProperty("observedAt", value.observedAt)
            addProperty("host", value.host); addProperty("clientKey", value.clientKey)
            add("warnings", gson.toJsonTree(value.warnings)); add("options", options(selected))
            add("models", com.google.gson.JsonArray().apply { value.models.forEach { model -> add(JsonObject().apply {
                addProperty("modelId", model.modelId); add("options", options(model.options)); add("warnings", gson.toJsonTree(model.warnings))
            }) } })
        }
        val path = file(profile, value.host); Files.createDirectories(path.parent)
        val tmp = Files.createTempFile(path.parent, ".catalog-", ".tmp")
        try {
            Files.writeString(tmp, gson.toJson(root))
            try { Files.move(tmp, path, ATOMIC_MOVE, REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(tmp, path, REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(tmp) }
    }
}
