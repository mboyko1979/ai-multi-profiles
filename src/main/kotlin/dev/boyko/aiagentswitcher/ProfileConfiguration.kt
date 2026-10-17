package dev.boyko.aiagentswitcher

import com.google.gson.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Provider ACP option descriptions and independent, per-profile overrides. */
internal object ProfileConfiguration {
    data class Value(val value: String, val name: String) { override fun toString() = name }
    data class Option(val id: String, val operation: String, val name: String, val type: String,
                      val category: String, val description: String, val currentValue: String?, val values: List<Value>,
                      val modelDefault: String? = null, val defaultSource: String = "unknown")
    data class ModelOptions(val modelId: String, val options: List<Option>, val warnings: List<String> = emptyList())
    data class Capabilities(val options: List<Option>, val observedAt: Long, val warnings: List<String>,
        val host: String = "", val clientKey: String = "", val models: List<ModelOptions> = emptyList())
    val directory: Path get() = Path.of(System.getProperty("aiaps.home", System.getProperty("user.home")),
        ".jetbrains", "ai-agent-project-switcher")
    fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }
    fun environment(profile: AgentProfiles.Entry): Map<String, String> =
        (if (profile.inheritEnvironment) System.getenv() else emptyMap()) + parseEnvironment(profile.environment) + ProfileHomes.environment(profile)
    fun key(profile: AgentProfiles.Entry): String {
        val env = environment(profile).toSortedMap()
        val files = (listOf(profile.command, profile.workingDirectory) + profile.arguments.lines() +
            listOfNotNull(profile.profileHome.takeIf { it.isNotBlank() }?.let { Path.of(it, ProfileCredentials.REVISION_FILE).toString() }))
            .filter { it.isNotBlank() }.map { value ->
                val stamp = runCatching { val p = Path.of(value); if (Files.exists(p)) "${Files.size(p)}:${Files.getLastModifiedTime(p).toMillis()}" else "" }.getOrDefault("")
                listOf(value, stamp)
            }
        return digest(Gson().toJson(listOf(profile.agentId, profile.command, profile.arguments, profile.workingDirectory, env, files)))
    }
    fun parseCapabilities(root: JsonObject): Capabilities =
        Capabilities(root.getAsJsonArray("options").mapNotNull { parseOption(it.asJsonObject) }, root.get("observedAt").asLong,
            root.getAsJsonArray("warnings")?.map { it.asString }.orEmpty(),
            root.get("host")?.asString.orEmpty(), root.get("clientKey")?.asString.orEmpty(),
            root.getAsJsonArray("models")?.map { item -> item.asJsonObject.let { model ->
                ModelOptions(model.get("modelId").asString, model.getAsJsonArray("options").mapNotNull { parseOption(it.asJsonObject) },
                    model.getAsJsonArray("warnings")?.map { it.asString }.orEmpty())
            } }.orEmpty())
    private fun parseOption(o: JsonObject): Option? {
        val id = o.get("id")?.asString ?: return null
        if (sensitive(id)) return null
        val type = o.get("type")?.asString ?: return null
        if (type !in listOf("select", "boolean")) return null
        return Option(id, o.get("operation").asString, o.get("name").asString, type,
            o.get("category")?.asString.orEmpty(), o.get("description")?.asString.orEmpty(),
            o.get("currentValue")?.takeUnless { it.isJsonNull }?.asString,
            o.getAsJsonArray("options")?.map { Value(it.asJsonObject.get("value").asString, it.asJsonObject.get("name").asString) }.orEmpty(),
            o.get("modelDefault")?.takeUnless { it.isJsonNull }?.asString, o.get("defaultSource")?.asString ?: "unknown")
    }
    fun sensitive(id: String) = Regex("access[_-]?token|refresh[_-]?token|id[_-]?token|auth[_-]?token|secret|password|api[_-]?key|credential|^token$", RegexOption.IGNORE_CASE).containsMatchIn(id)
    fun settings(profile: AgentProfiles.Entry, host: String): List<AgentProfiles.SessionSetting> = profile.sessionSettings.filter {
        it.agentId == profile.agentId && (it.host == host || it.host.isBlank())
    }
    fun hasSavedDefaults(profile: AgentProfiles.Entry, host: String): Boolean =
        profile.clientDefaults.any { it.host == host && it.agentId == profile.agentId } ||
        settings(profile, host).isNotEmpty() || profile.agentDefaults.any {
            it.agentId == profile.agentId && (it.host == host || it.host.isBlank())
        }
    @JvmOverloads fun saved(profile: AgentProfiles.Entry, option: Option, host: String = ""): String? = settings(profile, host)
        .firstOrNull { it.id == option.id && it.operation == option.operation }?.value
    private fun splitLegacy(settings: MutableList<AgentProfiles.SessionSetting>) {
        val legacy = settings.filter { it.host.isBlank() }
        settings.removeAll(legacy.toSet())
        legacy.forEach { old -> listOf("assistant", "air").forEach { client ->
            if (settings.none { it.host == client && it.agentId == old.agentId && it.id == old.id && it.operation == old.operation })
                settings.add(old.copy(host = client))
        } }
    }
    @JvmOverloads fun select(profile: AgentProfiles.Entry, option: Option, value: String?, host: String = "") {
        if (host.isNotBlank()) splitLegacy(profile.sessionSettings)
        profile.sessionSettings.removeAll { it.agentId == profile.agentId && it.id == option.id && it.operation == option.operation && it.host == host }
        if (value != null) profile.sessionSettings.add(AgentProfiles.SessionSetting(profile.agentId, option.operation, option.id, value, host))
    }
    fun removeDefaults(profile: AgentProfiles.Entry, host: String) {
        require(host in listOf("assistant", "air"))
        // Old shared settings become separate client settings before one is cleared.
        splitLegacy(profile.sessionSettings)
        splitLegacy(profile.agentDefaults)
        profile.sessionSettings.removeAll { it.agentId == profile.agentId && it.host == host }
        profile.agentDefaults.removeAll { it.agentId == profile.agentId && it.host == host }
        if (profile.agentDefaults.none { it.agentId == profile.agentId }) {
            profile.defaultsAgentId = ""
            profile.defaultsObservedAt = 0
        }
    }
    fun captureDefaults(profile: AgentProfiles.Entry, capabilities: Capabilities) {
        require(capabilities.warnings.isEmpty()) { "Agent defaults could not be confirmed. Previous defaults were retained." }
        val settings = capabilities.options.map { option ->
            val value = requireNotNull(option.currentValue) { "Agent did not report a default for ${option.name}. Previous defaults were retained." }
            require(if (option.type == "boolean") value in listOf("true", "false") else option.values.any { it.value == value }) {
                "Agent reported an unavailable default for ${option.name}. Previous defaults were retained."
            }
            AgentProfiles.SessionSetting(profile.agentId, option.operation, option.id, value, capabilities.host)
        }
        if (capabilities.host.isBlank()) profile.agentDefaults = settings.toMutableList()
        else {
            splitLegacy(profile.agentDefaults)
            profile.agentDefaults.removeAll { it.agentId == profile.agentId && it.host == capabilities.host }
            profile.agentDefaults.addAll(settings)
        }
        profile.defaultsAgentId = profile.agentId
        profile.defaultsObservedAt = capabilities.observedAt
    }
    fun captureModelDefaults(profile: AgentProfiles.Entry, capabilities: Capabilities) {
        val modelOption = capabilities.options.firstOrNull { it.category == "model" || it.operation == "model" }
        val selected = modelOption?.let { saved(profile, it, capabilities.host) ?: it.currentValue }
        val model = capabilities.models.firstOrNull { it.modelId == selected }
        val dependent = model?.options ?: capabilities.options.takeIf { modelOption?.currentValue == selected }.orEmpty()
        val options = capabilities.options.filterNot { it.category in listOf("thought_level", "model_config") } +
            dependent.filter { it.category in listOf("thought_level", "model_config") }.map { option ->
                if (option.category == "thought_level") option.copy(currentValue = option.modelDefault) else option
            }.filter { it.currentValue != null }
        captureDefaults(profile, capabilities.copy(options = options))
    }
    fun nativeInstructions(profile: AgentProfiles.Entry): Path? {
        if (profile.agentId != "acp.registry.codex-acp") return null
        val home = environment(profile)["CODEX_HOME"]?.takeIf { it.isNotBlank() } ?: return null
        val root = Path.of(home)
        if (!root.isAbsolute) return null
        return root.resolve("AGENTS.override.md").takeIf { Files.isRegularFile(it) && Files.size(it) > 0 } ?: root.resolve("AGENTS.md")
    }
}
