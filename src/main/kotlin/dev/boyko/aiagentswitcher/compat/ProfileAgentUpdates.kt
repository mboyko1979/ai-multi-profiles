@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package dev.boyko.aiagentswitcher.compat

import com.intellij.ml.llm.agents.acp.api.AcpAgentId
import com.intellij.ml.llm.agents.acp.client.AcpEvent
import com.intellij.ml.llm.agents.acp.client.AcpSessionLifecycleManager
import com.agentclientprotocol.model.SessionConfigOption
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.ml.llm.core.chat.session.ChatSession
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import dev.boyko.aiagentswitcher.AgentProfiles
import dev.boyko.aiagentswitcher.ChatProfiles
import dev.boyko.aiagentswitcher.GenericAutoRegistration
import dev.boyko.aiagentswitcher.NativeRecoveryContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** The host checks AI Multi's local registration, which has no registry update.
 * Route that check to the committed agent, retaining the host's updater and UI.
 */
internal object ProfileAgentUpdates {
    // Only an in-flight native update owns this accepted-selection snapshot.
    // It is neither a catalog cache nor a source of defaults for new chats.
    private val restoring = ConcurrentHashMap<String, JsonObject>()
    fun isRestoring(uid: String) = restoring.containsKey(uid)
    fun forget(uid: String) { restoring.remove(uid) }

    suspend fun restoreSelections(chat: ChatSession) {
        val saved=restoring[chat.uid] ?: return
        try { ChatHandoffClient(chat as Any).control("_ai_multi/restore_selections",saved.deepCopy()) }
        finally { restoring.remove(chat.uid,saved) }
    }
    private data class Target(val segment: String, val agent: AcpAgentId)
    private fun target(chat: ChatSession): Target? {
        val record = ChatProfiles.conversationRecord(chat.uid) ?: return null
        val segmentId = record.get("currentSegment")?.asString ?: return null
        val segment = record.getAsJsonArray("segments")?.firstOrNull {
            it.asJsonObject.get("id")?.asString == segmentId
        }?.asJsonObject ?: return null
        val profile = service<AgentProfiles>().find(segment.get("profile")?.asString ?: return null) ?: return null
        val bound = segment.getAsJsonObject("agentIdentity")?.get("agentId")?.asString ?: return null
        // A changed identity requires the router's recovery dialog. Never update
        // the replacement on behalf of the previous agent's live conversation.
        if (bound.isBlank() || bound != profile.agentId || profile.advancedOverrides) return null
        return Target(segmentId, AcpAgentId.parse(bound))
    }

    suspend fun ensureTerms(chat: ChatSession, manager: AcpSessionLifecycleManager): Boolean {
        val target = target(chat) ?: return true
        val native = NativeRecoveryContinuation
        val type = Class.forName("com.intellij.ml.llm.agents.acp.registry.AcpTermsOfService", true, mlPluginClassLoader())
        return native.suspendCall(ApplicationManager.getApplication().getService(type), "ensureAcceptedInChat",
            target.agent, native.field(manager, "chatOptionChooser")) == true
    }

    internal suspend fun checkBoundAgent(chat: ChatSession, checked: MutableSet<String>, check: suspend (AcpAgentId) -> Boolean): Boolean {
        val target = target(chat) ?: return false
        if (!checked.add(target.segment)) return false
        val updated = check(target.agent)
        if (updated) withContext(Dispatchers.IO) { GenericAutoRegistration.syncRoutesNow(chat.project) }
        return updated
    }

    suspend fun beforePrompt(chat: ChatSession, manager: AcpSessionLifecycleManager, checked: MutableSet<String>): Boolean {
        val native = NativeRecoveryContinuation
        // Match native eligibility: updates run before the first response, not
        // in the middle of an existing conversation or a steering operation.
        val status = Class.forName("com.intellij.ml.llm.core.chat.session.ChatSessionStatusUtilsKt", true, mlPluginClassLoader())
        if (listOf("hasAgentResponseFlow", "isAgentRespondingFlow").any {
                val flow = status.getMethod(it, ChatSession::class.java).invoke(null, chat)
                native.call(flow, "getValue") == true
            }) return false
        val updated = checkBoundAgent(chat, checked) { id ->
            val type = Class.forName("com.intellij.ml.llm.agents.acp.registry.AcpAgentUpdateService", true, mlPluginClassLoader())
            val updater = ApplicationManager.getApplication().getService(type)
            native.suspendCall(updater, "checkAndApplyInChat", id, chat.project, native.field(manager, "chatOptionChooser")) == true
        }
        if (!updated) return false
        reloadAfterUpdate(chat, manager)
        return true
    }

    internal suspend fun reloadAfterUpdate(chat: ChatSession, manager: AcpSessionLifecycleManager) {
        val native = NativeRecoveryContinuation
        val current=ChatHandoffClient(chat as Any).nativeSessionOrNull()
        val record=ChatProfiles.conversationRecord(chat.uid)
        if(current!=null && record!=null) {
            val settings=JsonArray()
            fun remember(operation:String,id:String,value:String) {
                if (Regex("secret|token|password|credential|api.?key", RegexOption.IGNORE_CASE).containsMatchIn(id)) return
                settings.add(JsonObject().apply { addProperty("operation",operation);addProperty("id",id);addProperty("value",value) })
            }
            if(current.configOptionsSupported)for(option in current.configOptions.value)when(option) {
                is SessionConfigOption.Select -> remember("config",option.id.value,option.currentValue.value)
                is SessionConfigOption.BooleanOption -> remember("config",option.id.value,option.currentValue.toString())
                else -> Unit
            }
            if(current.modelsSupported)remember("model","model",current.currentModel.value.value)
            if(current.modesSupported)remember("mode","mode",current.currentMode.value.value)
            val segment=record.getAsJsonArray("segments").first { it.asJsonObject.get("id")==record.get("currentSegment") }.asJsonObject
            restoring[chat.uid]=JsonObject().apply {
                add("profile",record.get("profile"));add("agentId",segment.getAsJsonObject("agentIdentity").get("agentId"));add("settings",settings)
            }
        }
        // Pinned Assistant 262 adapter: these are the same native reload and
        // resubmission operations used by its own successful update callback.
        (native.field(manager, "clientSessionInitLock") as Mutex).withLock {
            manager.mainEventsChannel.send(AcpEvent.ResubmitUserPrompt())
            ChatHandoffClient(chat as Any).nativeSessionOrNull()?.cancel()
            AcpSessionLifecycleManager::class.java.getDeclaredMethod("handleAgentReload", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(manager, true)
            val processes = Class.forName("com.intellij.ml.llm.agents.acp.process.AcpProcessHandlerService", true, mlPluginClassLoader())
            val service = native.call(processes.getField("Companion").get(null), "getInstance", chat.project)!!
            native.call(service, "stopProcessHandler", AcpAgentId.parse(ChatProfiles.AGENT_ID), chat.uid)
        }
    }
}
