@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package dev.boyko.aiagentswitcher.compat

import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.model.InitializeResponse
import com.agentclientprotocol.model.NewSessionResponse
import com.google.gson.JsonObject
import com.intellij.ml.llm.core.chat.session.ChatSessionVm
import com.intellij.openapi.application.ApplicationManager
import dev.boyko.aiagentswitcher.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** Retained native SDK sessions for one unsent Assistant draft; never a metadata cache. */
internal object PreparedAssistantProfiles {
    private data class Binding(val session: ClientSession, val agentInfo: Any, val supportsSteering: Boolean)
    private class Draft {
        val sessions = mutableMapOf<String, Binding>()
        @Volatile var requested: String? = null
    }
    private val drafts = WeakHashMap<Any, Draft>()
    val selectionChanges = MutableStateFlow(0L)
    private val native get() = NativeRecoveryContinuation
    private val json = Json { ignoreUnknownKeys = true }

    private fun invoke(target: Any, name: String, vararg args: Any?): Any? {
        val method = target.javaClass.declaredMethods.first { it.name.startsWith(name) && it.parameterCount == args.size }
        return method.apply { isAccessible = true }.invoke(target, *args)
    }
    private suspend fun invokeSuspending(target: Any, name: String, vararg args: Any?): Any? =
        suspendCoroutineUninterceptedOrReturn { continuation -> invoke(target, name, *args, continuation) }

    fun selected(chat: Any): String? = synchronized(drafts) { drafts[chat]?.requested }
    private fun changed() {
        selectionChanges.update { it + 1 }
        ApplicationManager.getApplication().messageBus.syncPublisher(ProfilesListener.TOPIC).changed()
    }

    suspend fun choose(chat: ChatSessionVm, profile: String) {
        val draft = synchronized(drafts) { drafts.getOrPut(chat.session) {
            Draft().also {
                (native.call(chat.session, "getSessionScope") as CoroutineScope).coroutineContext[Job]?.invokeOnCompletion {
                    synchronized(drafts) { drafts.remove(chat.session) }
                }
            }
        } }
        // Selecting a launch profile is local and immediate, even while its
        // native preparation is pending. Only the latest queued choice wins.
        draft.requested = profile; changed()
        try { withContext(Dispatchers.Default) {
        val agent = native.call(chat.session, "getAgentSession") ?: error("Native agent session is unavailable")
        // Share the host's admission lock; a prompt cannot race native binding replacement.
        val lock = native.field(agent, "turnMutex") as Mutex
        lock.withLock {
            if (draft.requested != profile) return@withLock
            check(native.field(agent, "activeTask") == null) { "Stop the active task before switching profiles" }
            val bridge = ChatHandoffClient(chat)
            val state = bridge.control("_ai_multi/describe")
            check(state.get("virtual")?.asBoolean == true && state.get("exchanges")?.asInt == 0 && state.get("busy")?.asBoolean == false) {
                "This chat already has messages; reopen the profile chooser to transfer history"
            }
            val previousProfile = state.get("profile").asString
            if (draft.requested != profile) return@withLock
            if (previousProfile == profile) { draft.requested = null; changed(); return@withLock }
            val current = bridge.nativeSessionOrNull() ?: error("Native profile session is unavailable")
            val previous = Binding(current, native.call(current.client, "getAgentInfo")!!, native.call(agent, "getSupportsSteering") == true)
            draft.sessions[previousProfile] = previous
            // Once routing can commit, finish SDK rebinding or rollback even if
            // the chooser's coroutine is cancelled. Never leave mismatched peers.
            withContext(NonCancellable) {
            val selected = bridge.control("_ai_multi/switch", JsonObject().apply {
                addProperty("profile", profile); addProperty("fresh", true); addProperty("retainPrepared", true)
                add("revision", state.get("revision"))
            })
            try {
                val binding = draft.sessions[profile] ?: run {
                    val init = json.decodeFromString(InitializeResponse.serializer(), selected.getAsJsonObject("initialize").toString())
                    val response = json.decodeFromString(NewSessionResponse.serializer(), selected.getAsJsonObject("session").toString())
                    val infoType = current.client.agentInfo.javaClass
                    val info = infoType.constructors.single { it.parameterCount == 5 }.newInstance(
                        native.call(init, "getProtocolVersion"), init.agentCapabilities, init.authMethods, init.agentInfo, init._meta)
                    val sessionType = Class.forName("com.agentclientprotocol.client.ClientSessionImpl", true, current.javaClass.classLoader)
                    val session = sessionType.constructors.single { it.parameterCount == 7 }.newInstance(
                        current.client, response.sessionId.value, current.parameters, current.operations, response, current.client.protocol, null) as ClientSession
                    Binding(session, info, selected.get("supportsSteering")?.asBoolean == true).also { draft.sessions[profile] = it }
                }
                withContext(NonCancellable) {
                    install(agent, binding)
                    ProfileCatalogPresentation.publishSelected(chat, selected.getAsJsonObject("session"), binding.session)
                    bridge.control("_ai_multi/prepared_ready", JsonObject().apply { addProperty("profile", profile) })
                }
            } catch (error: Exception) {
                try { withContext(NonCancellable) {
                    val revision = bridge.control("_ai_multi/describe").get("revision")
                    val restored = bridge.control("_ai_multi/switch", JsonObject().apply {
                        addProperty("profile", previousProfile); addProperty("fresh", true); addProperty("retainPrepared", true); add("revision", revision)
                    })
                    install(agent, previous)
                    ProfileCatalogPresentation.publishSelected(chat, restored.getAsJsonObject("session"), previous.session)
                    bridge.control("_ai_multi/prepared_ready", JsonObject().apply { addProperty("profile", previousProfile) })
                } } catch (rollback: Exception) { error.addSuppressed(rollback) }
                throw error
            }
            }
            if (draft.requested == profile) { draft.requested = null; changed() }
        }
        } } finally {
            if (draft.requested == profile) draft.requested = null
            changed()
        }
    }

    /** Keep native notification routing, option flows and capability checks bound to the selected SDK handle. */
    private suspend fun install(agent: Any, binding: Binding) {
        val session = binding.session
        val client = session.client
        client.javaClass.getDeclaredField("_agentInfo").apply { isAccessible = true }.set(client, CompletableDeferred(binding.agentInfo))
        val sid = native.call(session, "getSessionId")!!
        val bind: suspend () -> Unit = {
            native.call(client, "removeSessionHolder-", sid)
            val holder = invoke(client, "getOrCreateSessionHolder-", sid)!!
            native.suspendCall(holder, "drainEventsAndCompleteSession", session)
        }
        invokeSuspending(client, "withInitializingSession", bind)
        val manager = native.field(agent, "lifecycleManager")!!
        val acquiredType = Class.forName(manager.javaClass.superclass.name + "\$AcquiredSession", true, manager.javaClass.classLoader)
        val acquired = acquiredType.declaredConstructors.single().apply { isAccessible = true }.newInstance(session, binding.supportsSteering)
        @Suppress("UNCHECKED_CAST")
        (native.field(manager, "clientSessionRef") as AtomicReference<Any>).set(acquired)
        native.call(manager, "watchConfigOptionUpdates", session)
    }

    /** Before the first native task, release retained SDK references. The router closes unused providers on session/prompt. */
    fun firstPrompt(chat: Any) { synchronized(drafts) { drafts.remove(chat) } }
}
