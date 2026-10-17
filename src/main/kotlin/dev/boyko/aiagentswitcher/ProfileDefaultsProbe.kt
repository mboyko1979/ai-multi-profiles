package dev.boyko.aiagentswitcher

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ApplicationManager
import dev.boyko.aiagentswitcher.compat.InstalledAgents
import java.nio.file.Files
import java.util.Comparator
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Future

/** Explicit isolated setup. No prompts, chat history, tools or MCP servers. */
internal object ProfileDefaultsProbe {
    fun cached(project: Project, draft: AgentProfiles.Entry) = ProfileCatalog.cached(draft)
    fun prepare(project: Project, draft: AgentProfiles.Entry): AgentProfiles.Entry {
        val resolved = if (draft.agentId.isNotBlank() && !draft.advancedOverrides) InstalledAgents.resolve(project, draft) else draft.snapshot()
        require(resolved.command.isNotBlank()) { "Select an installed agent." }
        if (resolved.profileHome.isBlank()) resolved.profileHome = ProfileHomes.create(resolved.name).toString()
        ProfileHomes.prepare(resolved)
        return resolved
    }
    fun discover(project: Project, draft: AgentProfiles.Entry): ProfileConfiguration.Capabilities =
        ProfileDefaultsRefresh.discover(project, draft).also {
            ProfileConfiguration.captureDefaults(draft, it)
            ProfileCatalog.store(draft, it)
        }

    fun discoverClients(project: Project, draft: AgentProfiles.Entry): Map<String, ProfileConfiguration.Capabilities> {
        val values = dev.boyko.aiagentswitcher.compat.DefaultsClients.hosts.associateWith { host ->
            ProfileDefaultsRefresh.discoverClient(project, draft, host)
        }
        // Commit both observations only after both refreshes succeed.
        values.values.forEach { ProfileConfiguration.captureModelDefaults(draft, it) }
        values.values.forEach { ProfileCatalog.store(draft, it) }
        val assistant = values.getValue("assistant")
        ProfileCatalog.store(draft, assistant.copy(host = "", options = assistant.options.map { if (it.category == "thought_level") it.copy(currentValue = it.modelDefault) else it }))
        return values
    }

    class Connection @JvmOverloads constructor(project: Project, private val profile: AgentProfiles.Entry, probeCwd: String? = null, initialize: JsonObject? = null) : AutoCloseable {
        private val temporary = Files.createTempDirectory("ai-multi-setup-")
        private val pending = ConcurrentHashMap<String, CompletableFuture<JsonObject>>()
        private val counter = AtomicInteger()
        private val child: Process
        private val writer: java.io.BufferedWriter
        private val reader: Future<*>
        private val diagnostics: Future<*>
        private val cwd = probeCwd ?: project.basePath ?: temporary.toString()
        @Volatile var clientRequest: ((JsonObject) -> JsonObject)? = null
        private val closing = AtomicBoolean()
        private val closed get() = closing.get()
        init {
            var started: Process? = null
            try {
                require(profile.profileHome.isNotBlank()) { "Authorize this profile before loading session options." }
                for (resource in listOf("cps-router.js", "profile-settings.js", "codex-account.js", "local-account.js")) {
                    val bytes = javaClass.classLoader.getResourceAsStream(resource)?.use { it.readBytes() } ?: error("Missing ACP setup component")
                    Files.write(temporary.resolve(resource), bytes)
                }
                val spec = mapOf("command" to profile.command, "args" to profile.arguments.lines().filter { it.isNotBlank() },
                    "authenticationArgs" to profile.authenticationArguments.lines().filter { it.isNotBlank() },
                    "cwd" to profile.workingDirectory.ifBlank { cwd }, "inheritEnvironment" to profile.inheritEnvironment,
                    "env" to (parseEnvironment(profile.environment) + ProfileHomes.environment(profile)),
                    "agentId" to profile.agentId, "probeInitialize" to initialize, "discoverModels" to (initialize != null))
                val config = temporary.resolve("probe.json")
                Files.writeString(config, Gson().toJson(mapOf("profiles" to mapOf("probe" to spec), "projects" to emptyList<Any>())))
                child = ProcessBuilder(GenericAutoRegistration.findNode()?.toString() ?: "node", temporary.resolve("cps-router.js").toString(),
                    config.toString(), "--profile-setup").directory(java.io.File(cwd)).start().also { started = it }
                diagnostics = ApplicationManager.getApplication().executeOnPooledThread {
                    child.errorStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                        // Never log raw provider stderr, credentials, proxy metadata or launch environment.
                        if (line.startsWith("[AIAPS] Model-default metadata ") && line.length <= 8192)
                            com.intellij.openapi.diagnostic.Logger.getInstance(ProfileDefaultsProbe::class.java)
                                .info("Profile defaults [" + profile.name + "]: " + line.removePrefix("[AIAPS] "))
                    } }
                }
                writer = child.outputStream.bufferedWriter()
                reader = ApplicationManager.getApplication().executeOnPooledThread {
                    try {
                        child.inputStream.bufferedReader().use { input ->
                            while (!closed) {
                                val line = input.readLine() ?: break
                                require(line.length <= 2_000_000) { "Provider options are too large." }
                                val message = JsonParser.parseString(line).asJsonObject
                                if (!message.has("method")) pending.remove(message.get("id")?.asString)?.complete(message)
                                else if (message.has("id")) {
                                    val response = runCatching { clientRequest?.invoke(message) }.getOrNull()
                                    send(JsonObject().apply {
                                        addProperty("jsonrpc", "2.0"); add("id", message.get("id"))
                                        if (response != null) add("result", response)
                                        else add("error", JsonObject().apply { addProperty("code", -32601); addProperty("message", "Client action is unavailable in profile setup") })
                                    })
                                }
                            }
                        }
                    } finally { pending.values.forEach { it.completeExceptionally(IllegalStateException("Provider closed the profile setup connection.")) }; pending.clear() }
                }
            } catch (e: Exception) {
                started?.let { ProfileProcessCleanup.stop(it) }
                runCatching { deleteTemporary() }; throw e
            }
        }
        @Synchronized private fun send(message: JsonObject) { writer.write(message.toString()); writer.newLine(); writer.flush() }
        fun request(method: String, params: JsonObject = JsonObject(), timeoutSeconds: Long = 35): JsonObject {
            check(!closed) { "Profile setup was cancelled." }
            val id = counter.incrementAndGet().toString(); val future = CompletableFuture<JsonObject>(); pending[id] = future
            try {
                send(JsonObject().apply { addProperty("jsonrpc", "2.0"); addProperty("id", id); addProperty("method", method); add("params", params) })
                val message = future.get(timeoutSeconds, TimeUnit.SECONDS)
                if (message.has("error")) error(message.getAsJsonObject("error").get("message").asString)
                return message.getAsJsonObject("result") ?: JsonObject()
            } finally { pending.remove(id) }
        }
        fun methods() = request("_ai_multi/setup").getAsJsonArray("authMethods")
        fun defaults() = ProfileConfiguration.parseCapabilities(request("_ai_multi/defaults", JsonObject().apply { addProperty("cwd", cwd) }, 300))
        private fun deleteTemporary() { Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
        override fun close() {
            if (!closing.compareAndSet(false, true)) return
            clientRequest = null
            pending.values.forEach { it.cancel(true) }; pending.clear()
            try { ProfileProcessCleanup.stop(child, 7) }
            finally {
                runCatching { writer.close() }
                runCatching { reader.get(2, TimeUnit.SECONDS) }; reader.cancel(true)
                diagnostics.cancel(true)
                runCatching { deleteTemporary() }
            }
        }
    }
}
