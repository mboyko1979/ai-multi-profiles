package dev.boyko.aiagentswitcher

import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Comparator

/** Common clean-home refresh contract, independent of agent IDs.
 * Agents with other credential/catalog formats fail explicitly rather than supplying cached defaults.
 */
internal object ProfileDefaultsRefresh {
    fun discover(project: Project, profile: AgentProfiles.Entry): ProfileConfiguration.Capabilities = discoverWith(project, profile, null)
    fun discoverClient(project: Project, profile: AgentProfiles.Entry, host: String): ProfileConfiguration.Capabilities {
        val initialize = dev.boyko.aiagentswitcher.compat.DefaultsClients.initialize(project, host)
        run {
            // This settings metadata consumer supports recommendedValue independently
            // of the host chat UI, for every agent. Extend only this isolated probe.
            fun child(parent: com.google.gson.JsonObject, name: String): com.google.gson.JsonObject =
                parent.getAsJsonObject(name) ?: com.google.gson.JsonObject().also { parent.add(name, it) }
            val meta = child(initialize.getAsJsonObject("clientCapabilities"), "_meta")
            val air = child(child(meta, "jetbrains"), "air")
            if (!air.has("version")) air.addProperty("version", 1)
            val features = air.getAsJsonArray("capabilities") ?: com.google.gson.JsonArray().also { air.add("capabilities", it) }
            if (features.none { it.asString == "recommendedValue" }) features.add("recommendedValue")
        }
        return discoverWith(project, profile, initialize).copy(host = host, clientKey = ProfileConfiguration.digest(initialize.get("clientCapabilities").toString()))
    }
    private fun discoverWith(project: Project, profile: AgentProfiles.Entry, initialize: com.google.gson.JsonObject?): ProfileConfiguration.Capabilities {
        require(profile.profileHome.isNotBlank()) { "Authorize this profile before refreshing server defaults." }
        val original = Path.of(profile.profileHome)
        val config = original.resolve("config.toml")
        require(!Files.exists(config) || (
            Files.readAllLines(config).filter { it.trim().startsWith("[") }.all {
                it.trim() in setOf("[cli]", "[ui]", "[marketplace]", "[[marketplace.sources]]")
            })) {
            "Server-defaults refresh with custom provider configuration is not supported. Previous profile defaults were retained."
        }
        val auth = original.resolve("auth.json")
        require(Files.isRegularFile(auth)) {
            "This refresh mechanism requires file-based authorization in auth.json. Authorize the profile first; agents using other credential storage need a refresh adapter. Previous defaults were retained."
        }
        val home = Files.createTempDirectory("ai-multi-server-defaults-")
        try {
            // Copy account authorization only, never model metadata, preferences, sessions or config.
            Files.copy(auth, home.resolve("auth.json"))
            // Keep the installed launch directory: npx --no resolves its package and
            // managed runtime there. The ACP session cwd is isolated separately below.
            val isolated = profile.snapshot().apply { profileHome = home.toString() }
            val started = Instant.now()
            val value = ProfileDefaultsProbe.Connection(project, isolated, home.toString(), initialize).use { it.defaults() }
            val freshCatalog = Files.list(home).use { paths -> paths.anyMatch { metadata ->
                runCatching {
                    if (!metadata.fileName.toString().endsWith(".json") || !Files.isRegularFile(metadata) ||
                        Files.size(metadata) > 8_000_000) return@runCatching false
                    val catalog = JsonParser.parseString(Files.readString(metadata)).asJsonObject
                    val fetched = catalog.get("fetched_at")?.asString
                    val models = catalog.get("models")
                    val hasModels = models != null && when {
                        models.isJsonArray -> !models.asJsonArray.isEmpty
                        models.isJsonObject -> models.asJsonObject.size() > 0
                        else -> false
                    }
                    val timestamp = fetched?.let { Instant.parse(it) }
                    hasModels && timestamp != null && !timestamp.isBefore(started.minusSeconds(1)) &&
                        !timestamp.isAfter(Instant.now().plusSeconds(1))
                }.getOrDefault(false)
            } }
            check(freshCatalog) {
                "The agent did not confirm a fresh model catalog in a supported format. Previous profile defaults were retained."
            }
            return value
        } finally {
            Files.walk(home).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}
