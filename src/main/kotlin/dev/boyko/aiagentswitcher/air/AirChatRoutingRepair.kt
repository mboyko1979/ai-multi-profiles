@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package dev.boyko.aiagentswitcher.air

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.air.shared.core.thread.acpAgentLaunchTargetId
import com.intellij.air.shared.core.thread.acpCatalogAgentId
import com.intellij.air.thread.view.*
import com.intellij.ui.components.JBLabel
import com.intellij.openapi.application.EDT
import com.intellij.air.acp.api.*
import com.intellij.openapi.project.Project
import dev.boyko.aiagentswitcher.NativeSdkBinding
import dev.boyko.aiagentswitcher.NativeRecoveryContinuation
import dev.boyko.aiagentswitcher.ProfileConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JPanel

/** Repair only the transport address of a proven AI Multi conversation.
 * Use Air's append-only log; never rewrite transcripts or replace a live owner.
 */
internal object AirChatRoutingRepair {
    private val native get() = NativeRecoveryContinuation
    private val locks = java.util.WeakHashMap<Any, Mutex>()

    suspend fun commit(path: String, thread: String) {
        val payload = owned(log(path), thread) ?: return
        fun identityType(name: String) = native.type("com.intellij.air.shared.core.identity.$name", "com.intellij.air")!!
        val localId = identityType("LocalSessionId").getConstructor(String::class.java).newInstance(thread)
        val local = identityType("LocalSessionIdentity").constructors.single { it.parameterCount == 2 }.newInstance(path, localId)
        val namespace = identityType("AgentSessionNamespaceId").getConstructor(String::class.java)
            .newInstance(acpCatalogAgentId("acp.ai-multi")!!.value)
        val sid = identityType("AgentSessionId").getConstructor(String::class.java)
            .newInstance(native.call(payload, "string", "agentSessionId"))
        val agent = identityType("AgentSessionIdentity").constructors.single { it.parameterCount == 3 }.newInstance(path, namespace, sid)
        val registryType = native.type("com.intellij.air.backend.session.api.SessionIdentityRegistryService", "com.intellij.air")!!
        val registry = native.call(registryType.getField("Companion").get(null), "getInstance")!!
        val legacyAliases = registry.javaClass.methods.any { it.name == "associate" && it.parameterCount == 4 }
        val result = if (legacyAliases) native.suspendCall(registry, "associate", local, agent, emptySet<Any>())!!
            else native.suspendCall(registry, "associate", local, agent)!!
        check(!result.javaClass.simpleName.contains("Conflict")) { "Air conversation identity belongs to another local chat" }
        repair(path, thread)
    }

    suspend fun restoreWhenFailed(manager: Any, ref: Any, runtime: Any): Boolean {
        val lock = synchronized(locks) { locks.getOrPut(runtime) { Mutex() } }
        return lock.withLock {
            val path = native.call(ref, "getPathKey-").toString()
            val thread = native.call(runtime, "getThreadId-").toString()
            val payload = owned(log(path), thread) ?: return@withLock false
            @Suppress("UNCHECKED_CAST")
            val presentation = native.call(manager, "presentationState", ref) as StateFlow<Any>
            val state = presentation.first { state ->
                fun flag(name: String) = state.javaClass.fields.singleOrNull { it.name == name }?.get(state) == true
                flag("isReadyForPrompt") || flag("hasConnectionFailure") ||
                    (!flag("isConnected") && !flag("isRestoring") && !flag("isReconnecting") && !flag("isAgentStarting") &&
                        (state.javaClass.getField("connectAttempt").get(state) as Int) > 0)
            }
            if (state.javaClass.getField("isReadyForPrompt").get(state) == true) return@withLock false
            if (listOf("isSurfaceClosed", "isAgentDisabled", "isAgentNotFound").any { key ->
                    state.javaClass.fields.singleOrNull { it.name == key }?.get(state) == true }) return@withLock false
            if (native.call(runtime, "getEntry") is RestoringEntry) return@withLock false
            commit(path, thread)
            reconnect(manager, ref, runtime, native.call(payload, "string", "agentSessionId").toString())
        }
    }

    suspend fun reconnect(manager: Any, ref: Any, runtime: Any, sid: String): Boolean {
        check(native.call(manager, "isReadyForPrompt", ref) != true) { "The chat is already connected" }
        val current = (native.call(manager, "presentationState", ref) as StateFlow<*>).value!!
        check(current.javaClass.getField("isConnected").get(current) != true) { "Wait for the current Air connection before reconnecting" }
        val payload = owned(log(native.call(ref, "getPathKey-").toString()), native.call(runtime, "getThreadId-").toString())
        check(payload != null && native.call(payload, "string", "agentSessionId") == sid) { "Air conversation changed before restoration" }
        val catalog = AcpAgentsCatalog.getInstance()
        val ordinary = com.intellij.air.acp.AcpSessionRuntimeAdapter::class.java.getConstructor().newInstance()
        @Suppress("UNCHECKED_CAST")
        val resolve = native.field(ordinary, "resolveLaunchableEntry") as suspend (AcpAgentsCatalog, AcpAgentId) -> AcpCatalogEntry?
        val original = resolve(catalog, AcpAgentId.parse("acp.ai-multi")) ?: error("AI Multi is unavailable")
        val entry = RestoringEntry(original, sid)
        NativeSdkBinding.set(native.call(runtime, "getLaunchDescriptor")!!, "entry", entry)
        val cwd = native.call(runtime, "getWorkingDir").toString()
        dev.boyko.aiagentswitcher.AirProfileBridge.connect(
            dev.boyko.aiagentswitcher.AirProfileBridge.Binding(manager, ref, runtime, sid, cwd), true)
        return true
    }

    private class RestoringEntry(private val original: AcpCatalogEntry, private val sid: String) : AcpCatalogEntry by original {
        override suspend fun resolveStartConfig(project: Project): AcpAgentStartConfig = restoreConfig(original.resolveStartConfig(project), sid)
    }

    private fun restoreConfig(config: AcpAgentStartConfig, sid: String): AcpAgentStartConfig =
        java.lang.reflect.Proxy.newProxyInstance(AcpAgentStartConfig::class.java.classLoader, arrayOf(AcpAgentStartConfig::class.java)) { _, method, args ->
            try {
                when (method.name) {
                    "getAcpArgs" -> config.acpArgs + listOf("--restore-session", sid)
                    "getArgs" -> config.args + listOf("--restore-session", sid)
                    "withEnv" -> restoreConfig(method.invoke(config, *(args ?: emptyArray())) as AcpAgentStartConfig, sid)
                    else -> method.invoke(config, *(args ?: emptyArray()))
                }
            } catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
        } as AcpAgentStartConfig

    private fun log(path: String): Any {
        val type = native.type("com.intellij.air.backend.session.log.SessionLogService", "com.intellij.air")!!
        return native.call(native.call(type.getField("Companion").get(null), "getInstance")!!, "log-", path)!!
    }

    private fun owned(log: Any, thread: String): Any? {
        @Suppress("UNCHECKED_CAST")
        val events = native.call(log, "readEvents-", thread) as List<Any>
        // Later binding events take precedence, including a previous repair.
        val payload = events.asReversed().firstOrNull {
            native.call(it, "getType").toString() in setOf("SessionCreated", "SessionStarted", "RuntimeSessionBound") &&
                native.call(native.call(it, "getPayload")!!, "string", "agentSessionId") != null
        }?.let { native.call(it, "getPayload") } ?: return null
        val sid = native.call(payload, "string", "agentSessionId") as? String ?: return null
        if (!sid.startsWith("aiaps-chat-") || native.call(payload, "string", "runtimeKind") != "Acp") return null
        val file = ProfileConfiguration.directory.resolve("conversations")
            .resolve(ProfileConfiguration.digest(Gson().toJson(sid)) + ".json")
        if (!Files.isRegularFile(file)) return null
        val record = JsonParser.parseString(Files.readString(file)).asJsonObject
        if (record.get("sessionId")?.asString != sid) return null
        val cwd = native.call(payload, "string", "cwd") as? String ?: return null
        if (Path.of(record.get("cwd").asString).toAbsolutePath().normalize() != Path.of(cwd).toAbsolutePath().normalize()) return null
        return payload
    }

    @Synchronized
    fun repair(path: String, thread: String): Boolean {
        val log = log(path)
        val payload = owned(log, thread) ?: return false
        if (native.call(payload, "string", "launchTargetId") == acpAgentLaunchTargetId("acp.ai-multi").value &&
            native.call(payload, "string", "agentId") == acpCatalogAgentId("acp.ai-multi")!!.value) return false
        val target = native.call(payload, "withString", "launchTargetId", acpAgentLaunchTargetId("acp.ai-multi").value)!!
        val corrected = native.call(target, "withString", "agentId", acpCatalogAgentId("acp.ai-multi")!!.value)!!
        fun eventType(name: String) = (native.type("com.intellij.air.shared.session.events.$name", "com.intellij.air")
            ?: native.type("com.intellij.air.backend.session.log.models.$name", "com.intellij.air"))!!
        val draft = eventType("SessionEventDraft").constructors.single { it.parameterCount == 7 }.newInstance(
            eventType("EventSource").getField("Ide").get(null), eventType("SessionEventType").getField("RuntimeSessionBound").get(null),
            native.call(corrected, "jsonObject"), eventType("EventVisibility").getField("AuditOnly").get(null), null, null, null)
        native.call(log, "append-", thread, listOf(draft))
        return true
    }

    fun contribution(context: AgentSessionBannerContext): AgentSessionBannerContribution? {
        val path = context.sessionAddress.normalizedProjectPath
        val thread = context.sessionAddress.sessionId
        if (owned(log(path), thread) == null) return null
        val text = JBLabel("<html>This chat could not reconnect to its saved profile.</html>")
        val input = MutableStateFlow(AgentSessionInput(JPanel(), null, true))
        suspend fun restore(retry: Boolean = false) {
            val managerType = native.type("com.intellij.air.acp.AcpSessionManager", "com.intellij.air")!!
            val manager = native.call(managerType.getField("Companion").get(null), "getInstance")!!
            @Suppress("UNCHECKED_CAST")
            val sessions = native.call(manager, "getOpenSessions") as StateFlow<Map<Any, Any>>
            val entry = sessions.first { map -> map.values.any { native.call(it, "getThreadId-") == thread } }
                .entries.single { native.call(it.value, "getThreadId-") == thread }
            withContext(Dispatchers.IO) {
                if (retry) {
                    val payload = owned(log(path), thread) ?: error("Air conversation changed before restoration")
                    commit(path, thread)
                    reconnect(manager, entry.key, entry.value, native.call(payload, "string", "agentSessionId").toString())
                } else restoreWhenFailed(manager, entry.key, entry.value)
            }
        }
        val confirmation = AgentSessionConfirmation("ai-multi-routing-repair", "Reconnect AI Multi chat", text,
            listOf(AgentSessionConfirmationAction("repair", "Reconnect chat", AgentSessionConfirmationActionRole.ACCEPT,
                "Reconnect to this chat's saved profile") {
                context.scope.launch {
                    try {
                        restore(true)
                        withContext(Dispatchers.EDT) { input.value = AgentSessionInput(JPanel(), null, true) }
                    } catch (error: Exception) {
                        if (error is java.util.concurrent.CancellationException) throw error
                        com.intellij.openapi.diagnostic.Logger.getInstance(AirChatRoutingRepair::class.java).warn("AI Multi routing repair failed", error)
                        withContext(Dispatchers.EDT) { text.text = "Routing repair failed. See the IDE log for details." }
                    }
                }
            }))
        @Suppress("UNCHECKED_CAST")
        return AgentSessionBannerContribution(
            MutableStateFlow<AgentSessionHeaderContribution?>(null) as StateFlow<AgentSessionHeaderContribution>,
            MutableStateFlow(emptyList()), object : AgentSessionBannerActivationHandler {
                private val activated = java.util.concurrent.atomic.AtomicBoolean()
                override suspend fun activated() {
                    if (!activated.compareAndSet(false, true)) return
                    context.scope.launch {
                        try { restore() }
                        catch (error: Exception) {
                            if (error is java.util.concurrent.CancellationException) throw error
                            com.intellij.openapi.diagnostic.Logger.getInstance(AirChatRoutingRepair::class.java).warn("AI Multi profile restoration failed", error)
                            withContext(Dispatchers.EDT) { input.value = AgentSessionInput(JPanel(), confirmation, true) }
                        }
                    }
                }
            }, input,
        )
    }
}
