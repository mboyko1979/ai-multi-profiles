@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package dev.boyko.aiagentswitcher.compat

import com.agentclientprotocol.model.*
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.ml.llm.agents.acp.client.settings.*
import com.intellij.ml.llm.core.chat.session.ChatSessionVm
import com.intellij.openapi.components.service
import dev.boyko.aiagentswitcher.*
import kotlinx.serialization.json.Json

/** Catalogs seed native presentation only. The live session owns accepted selections. */
internal object ProfileCatalogPresentation {
    /** Clear unsupported legacy controls when binding a different real SDK session. */
    fun publishSelected(chat: ChatSessionVm, result: JsonObject, session: com.agentclientprotocol.client.ClientSession) {
        val id = ProfileToolbarIsolation.cacheId(chat.uid)
        val options = if (session.configOptionsSupported) session.configOptions.value else emptyList()
        val values = options.mapNotNull { option -> when (option) {
            is SessionConfigOption.Select -> option.id to SessionConfigOptionValue.StringValue(option.currentValue.value)
            is SessionConfigOption.BooleanOption -> option.id to SessionConfigOptionValue.BoolValue(option.currentValue)
            else -> null
        } }.toMap()
        AcpConfigOptionsManager.getInstance(chat.project).announceConfigOptions(id, options, values)
        if (!result.has("models")) AcpModelManager::class.java.getDeclaredMethod("announceSupportedModels",
            com.intellij.ml.llm.agents.acp.api.AcpAgentId::class.java, List::class.java, ModelInfo::class.java, ModelInfo::class.java)
            .apply { isAccessible = true }.invoke(AcpModelManager.getInstance(chat.project), id, emptyList<ModelInfo>(), null, null)
        if (!result.has("modes")) AcpModeManager::class.java.getDeclaredMethod("announceSupportedModes",
            com.intellij.ml.llm.agents.acp.api.AcpAgentId::class.java, List::class.java, SessionMode::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(AcpModeManager.getInstance(chat.project), id, emptyList<SessionMode>(), null, false)
        publishLegacy(chat, result)
    }
    fun publishLegacy(chat: ChatSessionVm, original: JsonObject) {
        val result = profile(chat)?.let { ProfileModelDefaults.display(it, "assistant", original) } ?: original
        val id = ProfileToolbarIsolation.cacheId(chat.uid)
        val json = Json { ignoreUnknownKeys = true }
        result.getAsJsonObject("models")?.let { state ->
            val models = state.getAsJsonArray("availableModels").map { json.decodeFromString(ModelInfo.serializer(), it.toString()) }
            val current = models.firstOrNull { it.modelId.value == state.get("currentModelId")?.asString }
            AcpModelManager::class.java.getDeclaredMethod("announceSupportedModels", com.intellij.ml.llm.agents.acp.api.AcpAgentId::class.java,
                List::class.java, ModelInfo::class.java, ModelInfo::class.java).apply { isAccessible = true }
                .invoke(AcpModelManager.getInstance(chat.project), id, models, current, current)
        }
        result.getAsJsonObject("modes")?.let { state ->
            val modes = state.getAsJsonArray("availableModes").map { json.decodeFromString(SessionMode.serializer(), it.toString()) }
            AcpModeManager::class.java.getDeclaredMethod("announceSupportedModes", com.intellij.ml.llm.agents.acp.api.AcpAgentId::class.java,
                List::class.java, SessionMode::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                .invoke(AcpModeManager.getInstance(chat.project), id, modes, modes.firstOrNull { it.id.value == state.get("currentModeId")?.asString }, false)
        }
    }
    fun profile(chat: ChatSessionVm): AgentProfiles.Entry? = service<AgentProfiles>().find(
        ChatProfiles.boundProfile(chat.uid) ?: ProfileChatCreator.pendingProfile(chat.uid) ?: chat.project.service<ProjectSelection>().selected)
    fun seed(chat: ChatSessionVm) {
        val session = ChatHandoffClient(chat).nativeSessionOrNull()
        if (session != null && (session.configOptions.value.isNotEmpty() || session.availableModels.isNotEmpty())) return
        val recorded = ChatProfiles.conversationRecord(chat.uid)
        if (recorded != null) {
            // Restored chats show their last accepted state, never today's profile defaults.
            val saved = recorded.getAsJsonObject("sessionOptions") ?: return
            val state = profile(chat)?.let { ProfileModelDefaults.display(it, "assistant", saved) } ?: saved
            val json = Json { ignoreUnknownKeys = true }
            val options = state.getAsJsonArray("configOptions")?.map { json.decodeFromString(SessionConfigOption.serializer(), it.toString()) }.orEmpty()
            val values = options.mapNotNull { option -> when (option) {
                is SessionConfigOption.Select -> option.id to SessionConfigOptionValue.StringValue(option.currentValue.value)
                is SessionConfigOption.BooleanOption -> option.id to SessionConfigOptionValue.BoolValue(option.currentValue)
                else -> null
            } }.toMap()
            AcpConfigOptionsManager.getInstance(chat.project).announceConfigOptions(ProfileToolbarIsolation.cacheId(chat.uid), options, values)
            publishLegacy(chat, state)
            return
        }
        // A pending new chat has no accepted model or option values yet.
        // Wait for the real session result instead of seeding an old discovery snapshot.
    }
}
