package dev.boyko.aiagentswitcher.compat

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ml.llm.core.chat.session.ChatSessionVm
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileDocumentManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicReference

/** Version-scoped adapter for AI Assistant 262.10968.223. All turns use its native task runner. */
internal class ChatHandoffClient(private val chat: Any) {
    constructor(vm: ChatSessionVm) : this(vm.session as Any)
    private fun getter(target: Any, name: String): Any = target.javaClass.methods.single { it.name == name && it.parameterCount == 0 }.apply { isAccessible = true }.invoke(target)
    private val agent: Any get() = getter(chat, "getAgentSession")
    fun nativeSessionOrNull(): com.agentclientprotocol.client.ClientSession? = runCatching {
        val lifecycle = field(agent, "lifecycleManager")
        // Presentation observes the existing session; the lifecycle's reusable-session
        // accessor logs every call, flooding the IDE log from each toolbar collector.
        val acquired = (field(lifecycle, "clientSessionRef") as AtomicReference<*>).get()
            ?: return@runCatching null
        getter(acquired, "getSession") as? com.agentclientprotocol.client.ClientSession
    }.getOrNull()
    private fun field(target: Any, name: String): Any {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            try { return type.getDeclaredField(name).apply { isAccessible = true }.get(target) }
            catch (_: NoSuchFieldException) { type = type.superclass }
        }
        error("Unsupported AI Assistant field: $name")
    }

    private suspend fun invokeSuspend(target: Any, name: String, vararg args: Any?): Any? =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            val method = target.javaClass.methods.firstOrNull { it.name.startsWith(name) && it.parameterCount == args.size + 1 }
                ?: target.javaClass.declaredMethods.single { it.name.startsWith(name) && it.parameterCount == args.size + 1 }
            method.isAccessible = true
            try { method.invoke(target, *args, continuation) }
            catch (e: InvocationTargetException) { throw e.targetException }
        }

    suspend fun control(method: String, params: JsonObject = JsonObject()): JsonObject = withContext(Dispatchers.Default) {
        val lifecycle = field(agent, "lifecycleManager")
        val block: suspend (Any) -> Any? = { session ->
            val id = session.javaClass.methods.first { it.name.startsWith("getSessionId") && it.parameterCount == 0 }.invoke(session).toString()
            params.addProperty("sessionId", id)
            val client = getter(session, "getClient")
            val protocol = getter(client, "getProtocol")
            invokeSuspend(protocol, "sendRequestRaw", method, Json.parseToJsonElement(params.toString()), null)
        }
        val result = invokeSuspend(lifecycle, "withClientSession", block)
        JsonParser.parseString(result.toString()).asJsonObject
    }

    suspend fun cancelActiveTask(): Unit = withContext(Dispatchers.Default) {
        withTimeout(15000) {
            invokeSuspend(agent, "cancel")
            invokeSuspend(agent, "awaitTaskCompletion")
        }
    }
    suspend fun reconnect(): Unit = withContext(Dispatchers.Default) {
        check(invokeSuspend(field(agent, "lifecycleManager"), "ensureSessionReady") == true) { "The selected profile did not establish a session." }
    }

    /** Native steering support is cached per acquired session at initialization.
     * A virtual chat keeps that client session when its backend profile changes.
     * Update only this chat's cached boolean from the target's actual ACP metadata.
     */
    suspend fun updateSteeringSupport(supported: Boolean): Unit = withContext(Dispatchers.Default) {
        val lifecycle = field(agent, "lifecycleManager")
        val block: suspend (Any) -> Any? = { session ->
            // Native lifecycle reparses AgentInfo when it reacquires this client.
            // Preserve the other extension fields and update only the target flag.
            val info = getter(getter(session, "getClient"), "getAgentInfo")
            val oldMeta = info.javaClass.getMethod("get_meta").invoke(info)
            val meta = oldMeta?.let { JsonParser.parseString(it.toString()).takeIf { json -> json.isJsonObject }?.asJsonObject } ?: JsonObject()
            meta.add("steering", JsonObject().apply { addProperty("supported", supported) })
            info.javaClass.getDeclaredField("_meta").apply { isAccessible = true }.set(info, Json.parseToJsonElement(meta.toString()))
            null
        }
        invokeSuspend(lifecycle, "withClientSession", block)
        @Suppress("UNCHECKED_CAST")
        val reference = field(lifecycle, "clientSessionRef") as AtomicReference<Any?>
        val acquired = reference.get() ?: return@withContext
        acquired.javaClass.getDeclaredField("supportsSteering").apply { isAccessible = true }.setBoolean(acquired, supported)
        // Publish the changed field without replacing the native client session.
        reference.compareAndSet(acquired, acquired)
    }

    fun verifyTaskAdapter() {
        val session = agent
        check(session.javaClass.name == "com.intellij.ml.llm.agents.acp.session.AcpAgentSession") { "Unsupported AI Assistant session implementation" }
        val loader = session.javaClass.classLoader
        val stateType = session.javaClass.declaredMethods.single { it.name == "publishState" }.parameterTypes[0]
        stateType.getField("Initializing")
        val requestType = Class.forName("com.intellij.ml.llm.aui.events.api.TaskRequest", true, loader)
        requestType.getConstructor(String::class.java, List::class.java)
        for (name in listOf("reserveActiveTaskIfIdle", "publishState", "startFileChangeTracking", "resetPlan", "sendUserPrompt"))
            check(session.javaClass.declaredMethods.any { it.name == name }) { "Unsupported AI Assistant task API: $name" }
        check(field(session, "lifecycleManager").javaClass.methods.any { it.name.startsWith("startNewTask") && it.parameterCount == 1 }) {
            "Unsupported AI Assistant task lifecycle API"
        }
    }

    /** Mirrors native prepareNewTaskIfIdle and runTaskAndWaitForCompletion, omitting the
     * synthetic user prompt and task-name events. A continuation keeps the existing title.
     * The native lifecycle owns tools, progress, cancellation and file-change tracking.
     * No direct provider prompt or background-only task is used. No automatic resubmission.
     */
    suspend fun continueNow(token: String, comment: String, onStarted: suspend () -> Unit = {}): Unit = withContext(Dispatchers.Default) {
        val session = agent
        verifyTaskAdapter()
        val loader = session.javaClass.classLoader
        val stateType = session.javaClass.declaredMethods.single { it.name == "publishState" }.parameterTypes[0]
        val initializing = stateType.getField("Initializing").get(null)
        val requestType = Class.forName("com.intellij.ml.llm.aui.events.api.TaskRequest", true, loader)
        val request = requestType.getConstructor(String::class.java, List::class.java).newInstance(token, emptyList<Any>())
        withContext(Dispatchers.EDT) { FileDocumentManager.getInstance().saveAllDocuments() }
        val mutex = field(session, "turnMutex") as Mutex
        mutex.lock()
        val completion: Deferred<*>
        var reserved: Any? = null
        try {
            val reserve = session.javaClass.getDeclaredMethod("reserveActiveTaskIfIdle").apply { isAccessible = true }
            val task = reserve.invoke(session) ?: error("Cancel the active AI task before switching profiles")
            reserved = task
            if (comment.isNotBlank()) invokeSuspend(session, "sendUserPrompt", comment, emptyList<Any>())
            invokeSuspend(session, "publishState", initializing)
            invokeSuspend(session, "startFileChangeTracking", task)
            completion = getter(task, "getCompletion") as Deferred<*>
            invokeSuspend(session, "resetPlan")
            val frontend = field(session, "frontendSession")
            frontend.javaClass.methods.single { it.name == "logAgentTaskStarted" && it.parameterCount == 0 }
                .apply { isAccessible = true }.invoke(frontend)
            invokeSuspend(session, "publishState", stateType.getField("Thinking").get(null))
            val lifecycle = field(session, "lifecycleManager")
            lifecycle.javaClass.methods.single { it.name.startsWith("startNewTask") && it.parameterCount == 1 }
                .apply { isAccessible = true }.invoke(lifecycle, request)
        } catch (e: Exception) {
            val active = session.javaClass.getDeclaredField("activeTask").apply { isAccessible = true }
            if (reserved != null && active.get(session) === reserved) {
                active.set(session, null)
                invokeSuspend(session, "publishState", stateType.getField("Idle").get(null))
            }
            throw e
        } finally { mutex.unlock() }
        onStarted()
        completion.await()
    }
}
