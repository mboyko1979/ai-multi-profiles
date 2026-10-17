package dev.boyko.aiagentswitcher

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.util.UUID

/** Deletes only known credential files in a switcher-owned profile directory. */
internal object ProfileCredentials {
    const val REVISION_FILE = ".aiaps-auth-revision"
    fun supported(profile: AgentProfiles.Entry) = profile.agentId in setOf("acp.registry.codex-acp", "acp.registry.grok-build")

    /** A saved profile directory is not a login. Revoke exists only after auth.json is present. */
    fun hasCredentials(profile: AgentProfiles.Entry): Boolean {
        if (!supported(profile) || profile.profileHome.isBlank()) return false
        return try {
            val home = Path.of(profile.profileHome).toAbsolutePath().normalize()
            val auth = home.resolve("auth.json")
            Files.isRegularFile(auth, NOFOLLOW_LINKS) && auth.toRealPath(NOFOLLOW_LINKS) == auth
        } catch (_: Exception) {
            false
        }
    }

    fun revoke(profile: AgentProfiles.Entry): Int {
        require(supported(profile)) { "Revoke is not supported for this agent yet" }
        val root = Path.of(System.getProperty("aiaps.home", System.getProperty("user.home")),
            ".jetbrains", "ai-agent-project-switcher", "profiles").toAbsolutePath().normalize()
        require(profile.profileHome.isNotBlank()) { "Apply the profile before revoking authorization" }
        val home = Path.of(profile.profileHome).toAbsolutePath().normalize()
        require(home.parent == root && home != root && home.toRealPath() == home && root.toRealPath() == root) {
            "Authorization storage must be a switcher-owned profile directory without redirects"
        }
        val auth = home.resolve("auth.json")
        val revision = home.resolve(REVISION_FILE)
        for (file in listOf(auth, revision)) {
            require(!Files.exists(file, NOFOLLOW_LINKS) ||
                (Files.isRegularFile(file, NOFOLLOW_LINKS) && file.toRealPath() == file)) {
                "Refusing to modify a redirected or non-file authorization path"
            }
        }
        // Invalidate both running backends and recorded session owners before removing credentials.
        val tmp = Files.createTempFile(home, ".auth-revision-", ".tmp")
        try {
            Files.writeString(tmp, UUID.randomUUID().toString())
            try { Files.move(tmp, revision, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(tmp, revision, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(tmp) }
        return if (Files.deleteIfExists(auth)) 1 else 0
    }
}
