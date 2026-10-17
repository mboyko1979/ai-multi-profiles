@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package dev.boyko.aiagentswitcher

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** Pinned host adapters: a consented transfer runs as a native task, never a router-side prompt. */
internal object NativeRecoveryContinuation {
    internal fun type(name: String, plugin: String): Class<*>? {
        val root = PluginManagerCore.getPlugin(PluginId.getId(plugin)) ?: return null
        // Resolve module descriptors through the runtime's public JVM methods;
        // Kotlin's internal property accessor is not a stable IDE binary API.
        val modules = call(PluginManagerCore.getPluginSet(), "getEnabledModules") as List<*>
        val loaders = listOf(root.pluginClassLoader) + modules.filterNotNull()
            .filter { call(it, "getPluginId") == root.pluginId }
            .mapNotNull { call(it, "getPluginClassLoader") as? ClassLoader }
        for (loader in loaders.filterNotNull().distinct()) {
            try { return Class.forName(name, true, loader) }
            catch (_: ClassNotFoundException) { /* Air isolates its content modules. */ }
        }
        return null
    }
    internal fun field(value: Any, name: String): Any? {
        var cls: Class<*>? = value.javaClass
        while (cls != null) {
            try { return cls.getDeclaredField(name).apply { isAccessible = true }.get(value) }
            catch (_: NoSuchFieldException) { cls = cls.superclass }
        }
        error("Unsupported native recovery field: $name")
    }
    internal fun call(value: Any, name: String, vararg args: Any?): Any? {
        val cls = value as? Class<*> ?: value.javaClass
        return cls.methods.sortedBy { it.name != name }.first { it.name.startsWith(name) && it.parameterCount == args.size }
            .apply { isAccessible = true }.invoke(if (value is Class<*>) null else value, *args)
    }
    internal suspend fun suspendCall(value: Any, name: String, vararg args: Any?): Any? =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            try { call(value, name, *args, continuation) }
            catch (error: InvocationTargetException) { throw error.targetException }
        }
    private fun pending(request: JsonObject, token: String): Boolean {
        val root = Path.of(System.getProperty("aiaps.home", System.getProperty("user.home")), ".jetbrains", "ai-agent-project-switcher", "conversations")
        val id = request.get("sessionId").asString
        val digest = MessageDigest.getInstance("SHA-256").digest(com.google.gson.Gson().toJson(id).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val file = root.resolve("$digest.json")
        if (!Files.isRegularFile(file)) return false
        val record = JsonParser.parseString(Files.readString(file)).asJsonObject
        return record.getAsJsonObject("pendingHandoff")?.get("token")?.asString == token
    }
    private fun airPrompt(requestType: Class<*>, token: String, comment: String): Any {
        val ctor = requestType.constructors.single { it.parameterTypes.lastOrNull()?.name == "kotlin.jvm.internal.DefaultConstructorMarker" }
        val values = arrayOfNulls<Any>(ctor.parameterCount)
        values[0] = token; values[1] = comment.ifBlank { "Continue with the new agent" }
        values[values.size - 2] = ((1 shl (values.size - 2)) - 1) and 3.inv()
        return ctor.newInstance(*values)
    }
    suspend fun continueSession(project: Project, request: JsonObject, token: String, comment: String) {
        withTimeout(60_000) {
            while (!project.isDisposed && pending(request, token)) {
                if (continueAssistant(project, request, token, comment) || continueAir(request, token, comment)) return@withTimeout
                delay(100)
            }
        }
    }
    private suspend fun continueAssistant(project: Project, request: JsonObject, token: String, comment: String): Boolean {
        val uid = request.get("clientChatId")?.takeUnless { it.isJsonNull }?.asString ?: return false
        val hostType = type("com.intellij.ml.llm.core.chat.session.ChatSessionHost", "com.intellij.ml.llm") ?: return false
        val host = project.getService(hostType) ?: return false
        val chat = (call(host, "getAllChats") as List<*>).filterNotNull().firstOrNull { call(it, "getUid") == uid } ?: return false
        val agent = call(chat, "getAgentSession") ?: return false
        if (field(agent, "activeTask") != null) return false // An already admitted prompt will carry the transfer itself.
        val bridgeType = Class.forName("dev.boyko.aiagentswitcher.compat.ChatHandoffClient", true, javaClass.classLoader)
        val bridge = bridgeType.getConstructor(Any::class.java).newInstance(chat)
        val session = call(bridge, "nativeSessionOrNull") ?: return false
        if (call(session, "getSessionId").toString() != request.get("sessionId").asString) return false
        val started: suspend () -> Unit = {}
        suspendCall(bridge, "continueNow", token, comment, started)
        return true
    }
    private suspend fun continueAir(request: JsonObject, token: String, comment: String): Boolean {
        val managerType = type("com.intellij.air.acp.AcpSessionManager", "com.intellij.air") ?: return false
        val companion = managerType.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
        val manager = call(companion, "getInstanceIfCreated") ?: return false
        val store = field(manager, "runtimeStore") ?: return false
        val refs = call(store, "refsSnapshot") as List<*>
        for (ref in refs.filterNotNull()) {
            val runtime = call(store, "get", ref) ?: continue
            val cwd = call(runtime, "getWorkingDir").toString()
            if (!cwd.equals(request.get("cwd").asString, ignoreCase = true)) continue
            val handle = field(runtime, "projectionHandle") ?: continue
            val projection = call(handle, "getProjection") ?: continue
            val binding = call(projection, "getRuntimeBinding") ?: continue
            if (call(binding, "getAgentSessionId") != request.get("sessionId").asString) continue
            if (call(manager, "isReadyForPrompt", ref) != true || call(manager, "acceptsPrompt", ref) != true) continue
            val requestType = Class.forName("com.intellij.air.backend.session.api.SessionPromptRequest", true, managerType.classLoader)
            val prompt = airPrompt(requestType, token, comment)
            // Use the host's normal admission, cancellation, tools and response renderer.
            // Submit once. A failed/ambiguous admission must never automatically resend.
            suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
                try {
                    managerType.methods.single { it.name.startsWith("sendPrompt-") && it.name.endsWith("\$default") }
                        .invoke(null, manager, ref, prompt, 0L, false, continuation, 12, null)
                } catch (error: InvocationTargetException) { throw error.targetException }
            }
            return true
        }
        return false
    }
}
