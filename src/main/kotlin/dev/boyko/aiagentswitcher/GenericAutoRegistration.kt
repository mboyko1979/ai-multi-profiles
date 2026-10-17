package dev.boyko.aiagentswitcher

import com.google.gson.*
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.channels.FileChannel
import java.nio.file.*
import java.time.Instant
import dev.boyko.aiagentswitcher.compat.InstalledAgents

/** All writes are serialized, including writes from other IDE processes. */
internal object GenericAutoRegistration {
    const val AGENT = "AI Multi"
    private val previousAgentNames = listOf("AI Auto (Project Switcher)")
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val base: Path get() = Path.of(System.getProperty("aiaps.home", System.getProperty("user.home")), ".jetbrains")
    private val dir: Path get() = base.resolve("ai-agent-project-switcher")
    private val acp: Path get() = base.resolve("acp.json")
    private val manifest: Path get() = dir.resolve("registration.json")
    @Volatile private var registrationEnabled = true

    fun resumeRegistration() { registrationEnabled = true }

    fun removeRegistrations(current: Boolean, legacy: Boolean): Boolean {
        var changed = false
        locked {
            if (current) registrationEnabled = false
            changed = RegistrationCleanup.removeRegistrations(current, legacy)
        }
        return changed
    }

    fun registrationStatus(): String = try {
        val entry = readRoot().getAsJsonObject("agent_servers")?.get(AGENT)
        when {
            entry == null -> "AI Multi is not registered"
            entry != desired() -> "Registration needs attention"
            !Files.isRegularFile(dir.resolve("router.js")) -> "Router is missing"
            findNode() == null -> "Registered; Node.js is missing from PATH"
            else -> "Registered in acp.json"
        }
    } catch (_: Exception) { "Registration cannot be read" }

    fun ensureAsync(project: Project) = background(project) { locked { ensure(null, project) } }
    fun syncRoutesAsync(project: Project) = background(project) { locked { updateRoutes(project) } }
    fun syncRoutesNow(project: Project) = locked { updateRoutes(project) }

    fun assignAsync(project: Project, id: String) = background(project) { assignNow(project, id) }

    fun assignNow(project: Project, id: String) = locked { commitAssignment(project, id) }

    /** Commit discovery only if its source profile is unchanged; never change project selection. */
    fun captureDefaults(project: Project, expected: AgentProfiles.Entry, prepared: AgentProfiles.Entry): Boolean {
        var committed = false
        locked {
            val profiles = service<AgentProfiles>()
            val previous = profiles.all()
            if (previous.firstOrNull { it.id == expected.id } != expected) return@locked
            profiles.replace(previous.map { if (it.id == expected.id) prepared else it })
            try { updateRoutes(project); committed = true }
            catch (error: Exception) { profiles.replace(previous); throw error }
        }
        if (committed) ApplicationManager.getApplication().messageBus.syncPublisher(ProfilesListener.TOPIC).changed()
        return committed
    }

    /** Publish a new selection only after its routing configuration is on disk. */
    private fun commitAssignment(project: Project, id: String) {
        check(!project.isDisposed) { "The project was closed before its profile could be saved" }
        require(id == "Unassigned" || service<AgentProfiles>().find(id) != null) { "Profile no longer exists" }
        val old = project.service<ProjectSelection>().selected
        service<ProjectRoutes>().set(project, id, false)
        try { updateRoutes(project) }
        catch (e: Exception) { service<ProjectRoutes>().set(project, old, false); throw e }
        project.service<ProjectSelection>().selected = id
        ApplicationManager.getApplication().messageBus.syncPublisher(ProfilesListener.TOPIC).changed()
    }

    fun saveProfiles(project: Project, entries: List<AgentProfiles.Entry>, id: String,
                     expectedProfiles: List<AgentProfiles.Entry>, expectedAssignment: String) {
        var failure: Exception? = null
        com.intellij.openapi.progress.ProgressManager.getInstance().runProcessWithProgressSynchronously({
            try {
                locked {
                    val profiles = service<AgentProfiles>()
                    val oldProfiles = profiles.all()
                    check(oldProfiles == expectedProfiles && project.service<ProjectSelection>().selected == expectedAssignment) {
                        "Profiles or project assignment changed while Settings was open. Reset this page before applying."
                    }
                    val prepared = entries.map { draft ->
                        val resolved = if (draft.agentId.isNotBlank() && !draft.advancedOverrides) InstalledAgents.resolve(project, draft) else draft.copy()
                        if (resolved.agentId.isNotBlank()) {
                            if (resolved.profileHome.isBlank()) resolved.profileHome = ProfileHomes.create(resolved.name).toString()
                            ProfileHomes.prepare(resolved)
                        }
                        validateProfile(resolved.copy(agentId = ""), entries)?.let { error("${resolved.name}: $it") }
                        resolved
                    }
                    profiles.replace(prepared)
                    try { commitAssignment(project, id) }
                    catch (e: Exception) { profiles.replace(oldProfiles); throw e }
                }
            } catch (e: Exception) { failure = e }
        }, "Saving AI agent profiles", false, project)
        failure?.let { throw com.intellij.openapi.options.ConfigurationException(it.message ?: "Could not save routing configuration") }
    }

    fun repair(project: Project, onComplete: (Result<Boolean>) -> Unit = {}) {
        fun complete(result: Result<Boolean>) {
            result.exceptionOrNull()?.let { error ->
                com.intellij.openapi.diagnostic.Logger.getInstance(javaClass).warn("AI Multi registration repair failed", error)
                notify(project, "AI Multi needs attention", error.message ?: "Registration repair failed", NotificationType.WARNING)
            }
            onComplete(result)
        }
        // Read and compare on a worker; confirmation and all UI operations run on EDT.
        ApplicationManager.getApplication().executeOnPooledThread {
            val read = runCatching { pluginEntry(readRoot().getAsJsonObject("agent_servers") ?: JsonObject()) }
            ApplicationManager.getApplication().invokeLater({
                if (read.isFailure) { complete(Result.failure(read.exceptionOrNull()!!)); return@invokeLater }
                if (project.isDisposed) { complete(Result.success(false)); return@invokeLater }
                val existing = read.getOrNull()
                if (existing != null && existing != desired() && Messages.showYesNoDialog(project,
                        "Replace the existing AI Multi registration? Unrelated agents will be preserved and acp.json backed up.",
                        "Repair AI Multi", Messages.getQuestionIcon()) != Messages.YES) {
                    complete(Result.success(false)); return@invokeLater
                }
                ApplicationManager.getApplication().executeOnPooledThread {
                    val result = runCatching { locked { ensure(existing, project) }; true }
                    ApplicationManager.getApplication().invokeLater({
                        if (result.getOrNull() == true) notify(project, "AI Multi registration repaired", "Select AI Multi in AI Chat. The ACP configuration has been refreshed; reopen the agent menu to check it.", NotificationType.INFORMATION)
                        complete(result)
                    }, com.intellij.openapi.application.ModalityState.any())
                }
            }, com.intellij.openapi.application.ModalityState.any())
        }
    }

    private fun background(project: Project, block: () -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try { block() }
            catch (e: Exception) {
                com.intellij.openapi.diagnostic.Logger.getInstance(javaClass).warn("AI Multi configuration failed", e)
                notify(project, "AI Multi needs attention", e.message ?: "Configuration update failed", NotificationType.WARNING)
            }
        }
    }
    private fun notify(project: Project, title: String, message: String, type: NotificationType) {
        if (project.isDisposed) return
        NotificationGroupManager.getInstance().getNotificationGroup("AI Agent Project Switcher")
            .createNotification(title, message, type)
            .addAction(NotificationAction.createSimple("Open Settings") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, SwitcherConfigurable::class.java)
            }).notify(project)
    }
    @Synchronized private fun locked(block: () -> Unit) {
        Files.createDirectories(dir)
        FileChannel.open(dir.resolve("configuration.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use { block() }
        }
    }
    private fun readRoot() = if (Files.exists(acp)) JsonParser.parseString(Files.readString(acp).trimStart('\uFEFF')).asJsonObject else JsonObject()
    internal fun findNode(): Path? {
        val windows = System.getProperty("os.name").startsWith("Windows")
        val filename = if (windows) "node.exe" else "node"
        val paths = (System.getenv("PATH") ?: "").split(java.io.File.pathSeparator).filter { it.isNotBlank() }
        return (paths.map { Path.of(it, filename) } + if (windows) listOf(Path.of("C:/Program Files/nodejs/node.exe")) else emptyList())
            .firstOrNull { Files.isRegularFile(it) && (windows || Files.isExecutable(it)) }
    }
    private fun desired() = JsonObject().apply {
        addProperty("command", findNode()?.toAbsolutePath()?.normalize()?.toString() ?: "node")
        add("args", JsonArray().apply { add(dir.resolve("router.js").toString()); add(dir.resolve("routes.json").toString()) })
    }
    private fun ownedRouter(entry: JsonElement?): Boolean {
        val args = entry?.takeIf { it.isJsonObject }?.asJsonObject?.getAsJsonArray("args") ?: return false
        return args.any { element ->
            element.isJsonPrimitive && element.asString.replace('\\', '/').endsWith("/ai-agent-project-switcher/router.js")
        }
    }

    private fun pluginEntry(servers: JsonObject): JsonElement? {
        servers.get(AGENT)?.let { return it }
        return previousAgentNames.firstNotNullOfOrNull { name -> servers.get(name)?.takeIf { ownedRouter(it) } }
    }

    /** The dropdown key changed from AI Auto (Project Switcher) to AI Multi. Only a plugin-owned router entry moves. */
    private fun adoptPreviousName(servers: JsonObject): Boolean {
        var changed = false
        if (!servers.has(AGENT)) {
            for (name in previousAgentNames) {
                val old = servers.get(name)
                if (!ownedRouter(old)) continue
                servers.add(AGENT, old)
                servers.remove(name)
                changed = true
                break
            }
        }
        for (name in previousAgentNames) {
            if (ownedRouter(servers.get(name))) {
                servers.remove(name)
                changed = true
            }
        }
        return changed
    }

    private fun ensure(approved: JsonElement?, project: Project) {
        if (!registrationEnabled) return
        val before = if (Files.exists(acp)) Files.readAllBytes(acp) else null
        val root = readRoot()
        val servers = root.getAsJsonObject("agent_servers") ?: JsonObject().also { root.add("agent_servers", it) }
        val renamed = adoptPreviousName(servers)
        val existing = servers.get(AGENT)
        val desired = desired()
        val previous = if (Files.isRegularFile(manifest)) JsonParser.parseString(Files.readString(manifest)) else null
        if (existing != null && existing != desired && existing != previous && existing != approved) {
            throw IllegalStateException("AI Multi registration was modified or conflicts with another entry. Use Repair Registration in Settings to review replacement.")
        }
        // Publish dependencies before the entry point so a concurrent host connection cannot load a missing module.
        for (resource in listOf("codex-account.js", "local-account.js", "profile-settings.js", "agent-recovery.js", "virtual-sessions.js", "cps-router.js")) {
            val bytes = javaClass.classLoader.getResourceAsStream(resource)?.use { it.readBytes() }
                ?: error("Bundled ACP component is missing: $resource")
            writeIfChanged(dir.resolve(if (resource == "cps-router.js") "router.js" else resource), bytes)
        }
        updateRoutes(project)
        val profileManifest = dir.resolve("profile-registrations.json")
        val previousProfiles = if (Files.isRegularFile(profileManifest))
            JsonParser.parseString(Files.readString(profileManifest)).asJsonObject else JsonObject()
        servers.add(AGENT, desired)
        val profileEntries = ProfileRegistrations.cleanup(servers, previousProfiles)
        val updated = (gson.toJson(root) + "\n").toByteArray()
        if (before == null || !before.contentEquals(updated)) {
            val now = if (Files.exists(acp)) Files.readAllBytes(acp) else null
            check(if (before == null) now == null else now != null && before.contentEquals(now)) { "acp.json changed during registration; retry the operation" }
            if (before != null) Files.write(acp.resolveSibling("acp.json.aiaps-backup-${Instant.now().toEpochMilli()}-${java.util.UUID.randomUUID()}"), before, StandardOpenOption.CREATE_NEW)
            atomicWrite(acp, updated)
        }
        writeIfChanged(manifest, gson.toJson(desired).toByteArray())
        writeIfChanged(profileManifest, gson.toJson(profileEntries).toByteArray())
        // Retarget saved chats only after the AI Multi key is the one AI Assistant will load.
        ChatProfiles.migrateAgentId()
        // AI Assistant observes VFS changes. This requests refresh, without restarting chats.
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(acp)?.refresh(true, false)
    }
    private fun updateRoutes(project: Project) {
        // Stored launch fields are a Settings snapshot. Native agent updates may remove
        // that distribution while the IDE remains open; publish the installed launch.
        // Keep profile settings/storage unchanged and never acquire an ACP session here.
        val installed = InstalledAgents.all().map { it.id }.toSet()
        val profiles = service<AgentProfiles>().all().map { profile ->
            if (!profile.advancedOverrides && profile.agentId in installed) InstalledAgents.resolve(project, profile)
            else profile
        }
        val path = dir.resolve("routes.json")
        // Another IDE process may own other project roots. Update only this root,
        // under the configuration lock, rather than publishing a stale full map.
        val roots = linkedMapOf<String, String>()
        if (Files.isRegularFile(path)) {
            val previous = JsonParser.parseString(Files.readString(path).trimStart('\uFEFF')).asJsonObject
            previous.getAsJsonArray("projects")?.forEach { entry ->
                val route = entry.asJsonObject
                roots[routeRoot(route.get("root").asString)] = route.get("profile").asString
            }
        }
        project.basePath?.let { root ->
            val id = service<ProjectRoutes>().current()[root]
            val key = routeRoot(root)
            if (id == null) roots.remove(key) else roots[key] = id
        }
        val result = JsonObject()
        result.addProperty("virtualSessions", true)
        result.addProperty("nativePreferences", true)
        result.add("projects", JsonArray().apply {
            roots.forEach { (root, id) ->
                if (profiles.any { it.id == id }) add(JsonObject().apply { addProperty("root", root); addProperty("profile", id) })
            }
        })
        result.add("profiles", JsonObject().apply {
            profiles.forEach { p ->
                add(p.id, JsonObject().apply {
                    addProperty("command", p.command)
                    add("args", JsonArray().apply { p.arguments.lines().filter { it.isNotBlank() }.forEach { add(it) } })
                    add("authenticationArgs", JsonArray().apply { p.authenticationArguments.lines().filter { it.isNotBlank() }.forEach { add(it) } })
                    addProperty("name", p.name)
                    addProperty("agentId", p.agentId)
                    addProperty("agentName", p.agentName)
                    addProperty("configurationKey", ProfileConfiguration.key(p))
                    addProperty("catalogKey", ProfileCatalog.key(p))
                    // Migrate legacy snapshots literally; only explicit Load/Refresh may replace values.
                    listOf("assistant", "air").forEach { ProfileModelDefaults.get(p, it) }
                    add("clientDefaults", gson.toJsonTree(p.clientDefaults.filter { it.agentId == p.agentId }.map {
                        it.snapshot().apply { catalogJson = ""; validationJson = "" }
                    }))
                    addProperty("instructionsSource", p.instructionsSource)
                    addProperty("instructionsText", p.instructionsText)
                    addProperty("instructionsFile", p.instructionsFile)
                    if (p.profileHome.isNotBlank()) addProperty("authRevisionPath", Path.of(p.profileHome, ProfileCredentials.REVISION_FILE).toString())
                    if (p.workingDirectory.isNotBlank()) addProperty("cwd", p.workingDirectory)
                    addProperty("inheritEnvironment", p.inheritEnvironment)
                    add("env", JsonObject().apply { (parseEnvironment(p.environment) + ProfileHomes.environment(p)).forEach { (key, value) -> addProperty(key, value) } })
                })
            }
        })
        writeIfChanged(path, (gson.toJson(result) + "\n").toByteArray(Charsets.UTF_8))
    }
    private fun writeIfChanged(path: Path, bytes: ByteArray) {
        if (Files.exists(path) && Files.readAllBytes(path).contentEquals(bytes)) return
        atomicWrite(path, bytes)
    }
    private fun routeRoot(root: String): String {
        val normalized = Path.of(root).toAbsolutePath().normalize().toString()
        return if (System.getProperty("os.name").startsWith("Windows")) normalized.lowercase(java.util.Locale.ROOT) else normalized
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
