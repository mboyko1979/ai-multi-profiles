package dev.boyko.aiagentswitcher

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/** Persistent user data lives outside plugin binaries and survives plugin upgrades. */
internal object ProfileHomes {
    private val root: Path get() = Path.of(System.getProperty("aiaps.home", System.getProperty("user.home")),
        ".jetbrains", "ai-agent-project-switcher", "profiles")

    fun create(name: String): Path = create(root, name)

    fun create(root: Path, name: String): Path {
        Files.createDirectories(root)
        val slug = name.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').take(80).ifBlank { "profile" }
        for (suffix in 0..Int.MAX_VALUE) {
            val path = root.resolve(if (suffix == 0) slug else "${slug}_$suffix")
            try { return Files.createDirectory(path) }
            catch (_: FileAlreadyExistsException) { /* Atomically reserve the next suffix across IDE processes. */ }
        }
        error("Cannot allocate a profile directory")
    }

    fun environment(profile: AgentProfiles.Entry): Map<String, String> {
        if (profile.profileHome.isBlank()) return emptyMap()
        val home = Path.of(profile.profileHome)
        val agent = "${profile.agentId} ${profile.agentName}".lowercase(Locale.ROOT)
        // Use vendor-specific account directories without changing Git/SSH's user home.
        if ("codex" in agent) return mapOf("CODEX_HOME" to home.toString())
        if ("claude" in agent) return mapOf("CLAUDE_CONFIG_DIR" to home.resolve(".claude").toString())
        if ("grok" in agent) return mapOf("GROK_HOME" to home.toString())
        val result = linkedMapOf("HOME" to home.toString(), "USERPROFILE" to home.toString(),
            "XDG_CONFIG_HOME" to home.resolve(".config").toString(), "XDG_DATA_HOME" to home.resolve(".local/share").toString(),
            "APPDATA" to home.resolve("AppData/Roaming").toString(), "LOCALAPPDATA" to home.resolve("AppData/Local").toString())
        return result
    }

    fun prepare(profile: AgentProfiles.Entry) {
        Files.createDirectories(Path.of(profile.profileHome))
        environment(profile).values.distinct().forEach { Files.createDirectories(Path.of(it)) }
    }
}
