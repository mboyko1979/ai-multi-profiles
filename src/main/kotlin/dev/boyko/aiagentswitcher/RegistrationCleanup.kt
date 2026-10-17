package dev.boyko.aiagentswitcher

import com.google.gson.*
import java.nio.file.*
import java.time.Instant

/** Filesystem-only ownership checks; the caller holds configuration.lock. */
internal object RegistrationCleanup {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val base: Path get() = Path.of(System.getProperty("aiaps.home", System.getProperty("user.home")), ".jetbrains")
    private val dir: Path get() = base.resolve("ai-agent-project-switcher")
    private val acp: Path get() = base.resolve("acp.json")
    private val manifest: Path get() = dir.resolve("registration.json")
    private const val AGENT = "AI Multi"
    private val previousAgentNames = listOf("AI Auto (Project Switcher)")
    private fun readRoot() = JsonParser.parseString(Files.readString(acp).trimStart('\uFEFF')).asJsonObject

    /** Remove only configurations we can prove were written by one of the switchers. */
    fun removeRegistrations(current: Boolean, legacy: Boolean): Boolean {
        var changed = false
        run {
            if (!Files.isRegularFile(acp)) return@run
            val before = Files.readAllBytes(acp)
            val root = readRoot()
            val servers = root.getAsJsonObject("agent_servers") ?: return@run
            val owned = if (current && Files.isRegularFile(manifest))
                JsonParser.parseString(Files.readString(manifest).trimStart('\uFEFF')) else null
            if (owned != null) for (name in listOf(AGENT) + previousAgentNames) {
                if (servers.get(name) == owned) { servers.remove(name); changed = true }
            }
            val profileManifest = dir.resolve("profile-registrations.json")
            if (current && Files.isRegularFile(profileManifest)) {
                val profiles = JsonParser.parseString(Files.readString(profileManifest)).asJsonObject
                for ((name, entry) in profiles.entrySet()) if (servers.get(name) == entry) {
                    servers.remove(name); changed = true
                }
            }
            if (legacy) {
                val legacyDir = base.resolve("codex-project-switcher")
                val routes = legacyDir.resolve("routes.json")
                // CPS did not save an ownership manifest. Compare its exact generated
                // configurations with its saved adapter and homes; names alone are insufficient.
                val config = if (Files.isRegularFile(routes))
                    JsonParser.parseString(Files.readString(routes).trimStart('\uFEFF')).asJsonObject else null
                val command = config?.getAsJsonObject("adapter")?.get("command")?.asString
                if (command != null && Path.of(command).isAbsolute) {
                    val auto = JsonObject().apply {
                        addProperty("command", command)
                        add("args", JsonArray().apply {
                            add(legacyDir.resolve("cps-router.js").toString()); add(routes.toString())
                        })
                    }
                    val entries = linkedMapOf("Codex - Auto (CPS experimental)" to auto)
                    for ((profile, value) in config.getAsJsonObject("homes")?.entrySet().orEmpty()) {
                        val home = value.asString
                        entries["Codex - $profile (CPS)"] = JsonObject().apply {
                            addProperty("command", Path.of(command).resolveSibling("npx.cmd").toString())
                            add("args", JsonArray().apply { add("-y"); add("@agentclientprotocol/codex-acp") })
                            add("env", JsonObject().apply { addProperty("CODEX_HOME", home) })
                        }
                    }
                    for ((name, entry) in entries) if (servers.get(name) == entry) {
                        servers.remove(name); changed = true
                    }
                }
            }
            if (changed) {
                check(Files.readAllBytes(acp).contentEquals(before)) { "acp.json changed during cleanup; retry the operation" }
                Files.write(acp.resolveSibling("acp.json.aiaps-backup-${Instant.now().toEpochMilli()}-${java.util.UUID.randomUUID()}"), before, StandardOpenOption.CREATE_NEW)
                atomicWrite(acp, (gson.toJson(root) + "\n").toByteArray(Charsets.UTF_8))
            }
        }
        return changed
    }

    private fun atomicWrite(path: Path, bytes: ByteArray) {
        Files.createDirectories(path.parent)
        val tmp = Files.createTempFile(path.parent, ".aiaps-", ".tmp")
        try {
            Files.write(tmp, bytes)
            try { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(tmp) }
    }
}
