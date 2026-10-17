@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package dev.boyko.aiagentswitcher.compat

import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.model.SessionConfigOption
import com.agentclientprotocol.model.SessionConfigOptionValue
import com.intellij.ml.llm.agents.acp.api.AcpAgentId
import com.intellij.ml.llm.agents.acp.client.AcpSessionLifecycleManager
import com.intellij.ml.llm.agents.acp.client.settings.AcpConfigOptionsManager
import com.intellij.ml.llm.core.chat.session.ChatSession
import com.intellij.openapi.components.service
import dev.boyko.aiagentswitcher.AgentProfiles
import dev.boyko.aiagentswitcher.ChatProfiles
import dev.boyko.aiagentswitcher.ProjectSelection
import dev.boyko.aiagentswitcher.ClientPreferencePolicy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Present values already resolved by the shared policy; ClientPreferences owns persistence. */
internal object ProfileAgentPreferences {
    private val mutex = Mutex()

    suspend fun synchronize(chat: ChatSession, session: ClientSession): List<SessionConfigOption> = mutex.withLock {
        // Optional ACP surfaces throw when accessed without support. In particular,
        // a successful load may omit options even when session/new supplied them.
        if (!session.configOptionsSupported) return@withLock emptyList()
        val options = session.configOptions.value
        if (options.isEmpty()) return@withLock options
        val record = ChatProfiles.conversationRecord(chat.uid)
        // The router commits a profile switch before the native option flow is
        // updated. Do not interpret the old backend's last values as the new one's.
        val confirmed = record?.getAsJsonObject("sessionOptions")?.getAsJsonArray("configOptions")
        if (confirmed != null) {
            val current = values(options).mapKeys { it.key.value }.mapValues { (_, value) ->
                when (value) {
                    is SessionConfigOptionValue.StringValue -> value.value
                    is SessionConfigOptionValue.BoolValue -> value.value.toString()
                    else -> return@withLock options
                }
            }
            val expected = confirmed.associate { item -> item.asJsonObject.let {
                it.get("id").asString to it.get("currentValue").asString
            } }
            if (current != expected) return@withLock options
        }
        val profileId = record?.get("profile")?.asString ?: ProfileChatCreator.pendingProfile(chat.uid)
            ?: chat.project.service<ProjectSelection>().selected
        val segment = record?.get("currentSegment")?.asString ?: profileId
        val boundSegment = record?.getAsJsonArray("segments")?.firstOrNull {
            it.asJsonObject.get("id")?.asString == segment
        }?.asJsonObject
        val agentId = if (record != null) boundSegment?.getAsJsonObject("agentIdentity")?.get("agentId")?.asString
            ?: boundSegment?.getAsJsonObject("author")?.get("agentId")?.asString
        else service<AgentProfiles>().find(profileId)?.agentId
        if (agentId.isNullOrBlank() || agentId == ChatProfiles.AGENT_ID) return@withLock options
        val providerId = AcpAgentId.parse(ClientPreferencePolicy.key(profileId, agentId))
        val manager = AcpConfigOptionsManager.getInstance(chat.project)
        // registerConfigOptions also sends setters, even for accepted values.
        // Announce presentation only; the shared router owns defaults and persistence.
        manager.announceConfigOptions(providerId, options, values(options))
        session.configOptions.value
    }

    private fun values(options: List<SessionConfigOption>) = options.mapNotNull { option ->
        when (option) {
            is SessionConfigOption.Select -> option.id to SessionConfigOptionValue.StringValue(option.currentValue.value)
            is SessionConfigOption.BooleanOption -> option.id to SessionConfigOptionValue.BoolValue(option.currentValue)
            else -> null
        }
    }.toMap()

    /** Native prompt preparation waits for preference application, even if the
     * toolbar has not rendered yet. Existing validation/actions keep their order.
     */
    fun beforePrompt(chat: ChatSession, manager: AcpSessionLifecycleManager) {
        val field = AcpSessionLifecycleManager::class.java.getDeclaredField("beforePromptActions").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        // BeforePromptResult is package-private in the pinned host. Keep the
        // callback erased across plugin classloaders and return its real singleton.
        val actions = field.get(manager) as List<suspend () -> Any>
        fun result(name: String): Any = Class.forName(
            "com.intellij.ml.llm.agents.acp.client.BeforePromptResult\$$name", true, manager.javaClass.classLoader
        ).getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        val bridge: suspend () -> Any = {
            if (PreparedAssistantProfiles.selected(chat) != null) result("Declined")
            else {
                ChatHandoffClient(chat as Any).nativeSessionOrNull()?.let { synchronize(chat, it) }
                PreparedAssistantProfiles.firstPrompt(chat)
                result("Proceed")
            }
        }
        field.set(manager, actions + bridge)
    }
}
