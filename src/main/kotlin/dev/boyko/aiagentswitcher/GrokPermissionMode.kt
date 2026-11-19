package dev.boyko.aiagentswitcher

import com.intellij.openapi.components.service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Grok does not advertise an ACP mode, so Air's composer has no permission dropdown.
 *  The choice is stored with the profile and in that profile's Grok config.
 */
object GrokPermissionMode {
    data class Mode(val id: String, val label: String, val description: String)

    val modes = listOf(
        Mode("ask", "Ask", "Ask before each action"),
        Mode("auto", "Auto-review", "Review routine actions and ask when unsure"),
        Mode("always-approve", "Always approve", "Run actions without asking"),
    )

    fun isGrok(profile: AgentProfiles.Entry): Boolean =
        "${profile.agentId} ${profile.agentName} ${profile.command}".lowercase().contains("grok")

    fun label(id: String): String = modes.find { it.id == id }?.label ?: id

    fun readMode(text: String): String? {
        var section = ""
        for (line in text.split(Regex("\\r?\\n"))) {
            val table = Regex("^\\s*\\[([^]]+)]\\s*$").find(line)
            if (table != null) {
                section = table.groupValues[1].trim()
                continue
            }
            if (section != "ui") continue
            val match = Regex("^\\s*permission_mode\\s*=\\s*[\"']?([A-Za-z-]+)[\"']?\\s*$").find(line)
            if (match != null && modes.any { it.id == match.groupValues[1] }) return match.groupValues[1]
        }
        return null
    }

    fun writeText(text: String, mode: String): String {
        require(modes.any { it.id == mode }) { "Unknown Grok permission mode" }
        val lines = text.split(Regex("\\r?\\n")).toMutableList()
        var section = ""
        var ui = -1
        var key = -1
        lines.forEachIndexed { index, line ->
            val table = Regex("^\\s*\\[([^]]+)]\\s*$").find(line)
            if (table != null) {
                if (section == "ui" && key < 0 && ui >= 0) key = index
                section = table.groupValues[1].trim()
                if (section == "ui" && ui < 0) ui = index
                return@forEachIndexed
            }
            if (section == "ui" && Regex("^\\s*permission_mode\\s*=").containsMatchIn(line)) key = index
        }
        val assignment = "permission_mode = \"$mode\""
        if (key in lines.indices && Regex("^\\s*permission_mode\\s*=").containsMatchIn(lines[key])) lines[key] = assignment
        else if (ui >= 0) lines.add(if (key >= 0) key else ui + 1, assignment)
        else {
            if (lines.isNotEmpty() && lines.last() != "") lines.add("")
            lines.add("[ui]")
            lines.add(assignment)
        }
        return lines.joinToString("\n").replace(Regex("\\s*$"), "\n")
    }

    /** Same precedence as the router: an Air preset mode, then Grok's config, then Ask. */
    fun saved(profile: AgentProfiles.Entry): String {
        val defaults = profile.clientDefaults.find { it.host == "air" && it.agentId == profile.agentId }
        val model = defaults?.models?.find { it.modelId == defaults.defaultModelId } ?: defaults?.models?.firstOrNull()
        val chosen = model?.settings?.find { it.operation == "mode" && it.id == "mode" }?.value
            ?: profile.sessionSettings.find { it.operation == "mode" && it.id == "mode" && (it.host.isEmpty() || it.host == "air") }?.value
        if (modes.any { it.id == chosen }) return chosen!!
        val dir = home(profile) ?: return "ask"
        val text = runCatching { Files.readString(dir.resolve("config.toml")) }.getOrNull() ?: return "ask"
        return readMode(text) ?: "ask"
    }

    fun live(record: com.google.gson.JsonObject): String? =
        record.getAsJsonObject("sessionOptions")?.getAsJsonObject("modes")?.get("currentModeId")?.asString
            ?.takeIf { id -> modes.any { it.id == id } }

    /** Saves the mode for the next session and writes Grok's config. Does not restart a live session. */
    fun remember(profileId: String, mode: String) {
        require(modes.any { it.id == mode }) { "Unknown Grok permission mode" }
        val profiles = service<AgentProfiles>()
        val current = profiles.all()
        val profile = current.find { it.id == profileId } ?: error("Profile was removed")
        val updated = current.map { if (it.id == profileId) withMode(it, mode) else it }
        profiles.replace(updated)
        writeConfig(updated.first { it.id == profileId }, mode)
    }

    private fun withMode(profile: AgentProfiles.Entry, mode: String): AgentProfiles.Entry {
        val copy = profile.snapshot()
        for (defaults in copy.clientDefaults) {
            if (defaults.host != "air" || defaults.agentId != copy.agentId) continue
            for (model in defaults.models) {
                model.settings.removeAll { it.operation == "mode" && it.id == "mode" }
                model.settings.add(AgentProfiles.SessionSetting(copy.agentId, "mode", "mode", mode, "air"))
            }
        }
        copy.sessionSettings.removeAll { it.operation == "mode" && it.id == "mode" && (it.host.isEmpty() || it.host == "air") }
        copy.sessionSettings.add(AgentProfiles.SessionSetting(copy.agentId, "mode", "mode", mode, "air"))
        return copy
    }

    private fun home(profile: AgentProfiles.Entry): Path? =
        ProfileHomes.environment(profile)["GROK_HOME"]?.let { Path.of(it) }

    private fun writeConfig(profile: AgentProfiles.Entry, mode: String) {
        val dir = home(profile) ?: return
        val file = dir.resolve("config.toml")
        val text = runCatching { Files.readString(file) }.getOrDefault("")
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, ".permission-mode-", ".tmp")
        try {
            Files.writeString(tmp, writeText(text, mode))
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}
