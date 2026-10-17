@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package dev.boyko.aiagentswitcher.compat

import com.intellij.ide.ui.icons.rpcId
import com.intellij.ide.ui.icons.IconId
import com.intellij.ml.llm.agents.frontend.chat.AIAssistantSplitComponentProviderImpl
import com.intellij.ml.llm.agents.frontend.chat.createChatMessagesView
import com.intellij.ml.llm.agents.frontend.compose.model.AgentEventsProvider
import com.intellij.ml.llm.agents.frontend.compose.model.RpcAgentEventsProvider
import com.intellij.ml.llm.agents.frontend.compose.ui.viewModels.ChatSessionViewModel
import com.intellij.ml.llm.agents.frontend.compose.ui.viewModels.ChatMessageViewModel
import kotlinx.collections.immutable.PersistentList
import com.intellij.ml.llm.chat.findValue
import com.intellij.ml.llm.chat.session.SessionHistoryStorage
import com.intellij.ml.llm.chat.shared.*
import com.intellij.ml.llm.aui.events.api.ToolBlockUpdatedEvent
import com.intellij.ml.llm.aui.events.api.ToolType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.components.panels.Wrapper
import com.intellij.ui.split.SplitComponentProvider
import dev.boyko.aiagentswitcher.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import javax.swing.JComponent

/** Pinned 262.10968.223 adapter. Reuses the native Compose view and task events.
 * Only response-card presentation changes; registration, task IDs and history stay native.
 */
class ProfileResponseHeaderProvider : SplitComponentProvider<ChatSessionId> {
    private val native = AIAssistantSplitComponentProviderImpl()
    private val icons = java.util.concurrent.ConcurrentHashMap<String, IconId>()
    override val binding = native.binding
    override fun createComponent(project: Project, scope: CoroutineScope, modelId: ChatSessionId): JComponent {
        val chat = modelId.findValue()
        if (chat == null || chat.currentChatMode.displayName != GenericAutoRegistration.AGENT)
            return native.createComponent(project, scope, modelId)
        val wrapper = ProfileHeaderWrapper()
        scope.launch(Dispatchers.EDT) {
            val original = RpcAgentEventsProvider(modelId)
            val authors = service<ResponseProfiles>()
            // Unknown legacy cards must not be attributed to today's selected profile.
            val oldEvents = SessionHistoryStorage().getEvents(SessionHistoryStorage.PersistanceId(chat.uid))
                .filterIsInstance<ChatSessionMessageEvent>()
            runCatching { authors.recover(chat.uid, oldEvents) }.onFailure {
                Logger.getInstance(javaClass).warn("Could not recover historical response profiles", it)
            }
            val oldIds = oldEvents.map { it.id.id }.toSet()
            LocalHistoryPresentation.observe(chat, scope)
            val provider = object : AgentEventsProvider by original {
                override fun subscribeToEvents(): Flow<ChatSessionEvent> = emptyFlow()
            }
            val model = ChatSessionViewModel(project, scope, modelId, provider)
            wrapper.putClientProperty("aiMulti.responseModel", model)
            wrapper.setContent(createChatMessagesView(project, model, wrapper))
            // Match the native collector: startup/authentication blocks cannot be
            // resolved until the agent catalogue has supplied their author.
            // Subscribe before waiting. Native startup and authentication events can
            // arrive while the agent catalogue is still loading; a later subscription drops them.
            val queued = Channel<ChatSessionEvent>(Channel.UNLIMITED)
            scope.launch {
                try { original.subscribeToEvents().collect { queued.send(it) } }
                finally { queued.close() }
            }
            ProfileStartupStatus.observe(chat, scope)
            model.availableAgents.first { agents -> agents?.any { it.id.id == ChatProfiles.AGENT_ID } == true }
            fun present(incoming: ChatSessionEvent, replay: Boolean) {
                val event = incoming
                // Native prompt processing creates the empty response card before
                // any backend content arrives. Attribute it in this same EDT turn,
                // including when the backend is still initializing or thinking.
                val prompt = (event as? ChatSessionUserPromptEvent)?.takeIf { it.agentId.id == ChatProfiles.AGENT_ID }
                val promptAuthor = prompt?.let {
                    authors.find(chat.uid, it.id.id) ?: if (!replay && it.id.id !in oldIds) {
                        val selected = ChatProfiles.boundProfile(chat.uid) ?: ProfileChatCreator.pendingProfile(chat.uid)
                            ?: project.service<ProjectSelection>().selected
                        selected?.let { service<AgentProfiles>().find(it) }
                            ?.let { profile -> authors.remember(chat.uid, prompt.id.id, profile) }
                    } else null
                }
                val block = (event as? ChatSessionMessageBlockEvent)?.takeIf { it.agentId.id == ChatProfiles.AGENT_ID }
                val snapshot = block?.let {
                    val saved = authors.find(chat.uid, it.id.id)
                    val startup = (it.event as? ToolBlockUpdatedEvent)?.toolType == ToolType.Authentication
                    val profile = if (saved == null && it.id.id !in oldIds && !replay) {
                        val selected = ChatProfiles.boundProfile(chat.uid) ?: if (startup)
                            ProfileChatCreator.pendingProfile(chat.uid) ?: project.service<ProjectSelection>().selected else null
                        selected?.let { service<AgentProfiles>().find(it) }
                    } else null
                    saved ?: profile?.let { profile -> authors.remember(chat.uid, it.id.id, profile) }
                }
                if (snapshot != null) wrapper.headers[presentation(snapshot).name] = snapshot
                if (snapshot != null) runCatching { splitAuthor(model, scope, modelId, snapshot, oldIds) }.onFailure {
                    Logger.getInstance(javaClass).warn("Could not separate response profiles", it)
                }
                if (promptAuthor != null) wrapper.headers[presentation(promptAuthor).name] = promptAuthor
                process(model, project, event, promptAuthor)
                if (promptAuthor != null) runCatching {
                    splitAuthor(model, scope, modelId, promptAuthor, oldIds)
                    wrapper.repaint()
                }.onFailure {
                    Logger.getInstance(javaClass).warn("Could not present initial response profile", it)
                }
                if (snapshot != null) runCatching { decorate(model, scope, mapOf(block.id to snapshot)) }.onFailure {
                    Logger.getInstance(javaClass).warn("Could not present response profile", it)
                }
                if (snapshot != null) wrapper.repaint()
            }
            for (event in queued) {
                if (event is ChatSessionReloadEvent) {
                    runCatching { authors.recover(chat.uid, event.events.filterIsInstance<ChatSessionMessageEvent>()) }.onFailure {
                        Logger.getInstance(javaClass).warn("Could not recover reloaded response profiles", it)
                    }
                    // Reuse native replay, inserting card boundaries only where authors differ.
                    process(model, project, ChatSessionReloadEvent(emptyList()))
                    event.events.forEach { present(it, true) }
                } else present(event, false)
            }
        }
        return wrapper
    }

    /** Same three native processors as ChatSessionViewModel's event collector.
     * Run them in one EDT turn so attribution/card boundaries precede rendering;
     * the native buffered/combine pipeline otherwise processes events after emit returns.
     */
    private fun process(model: ChatSessionViewModel, project: Project, event: ChatSessionEvent,
                        promptAuthor: ResponseProfiles.Author? = null) {
        for (name in listOf("agentStatusProcessor", "stickyPlanProcessor")) {
            val processor = model.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(model)
            processor.javaClass.getMethod("process", ChatSessionEvent::class.java).invoke(processor, event)
        }
        val manager = model.javaClass.getDeclaredField("viewModelUpdatesManager").apply { isAccessible = true }.get(model)
        // Supply the author to the native constructor itself. Publishing a bare
        // card and replacing it later still lets Compose observe the wrong title.
        // This catalogue belongs only to this view manager and is restored before
        // returning; the shared agent registration and other chats stay untouched.
        val catalogue = manager.javaClass.getDeclaredField("availableAgents").apply { isAccessible = true }
        val original = catalogue.get(manager)
        try {
            if (promptAuthor != null) {
                @Suppress("UNCHECKED_CAST")
                val agents = (original as StateFlow<List<ChatSessionAgent>?>).value
                catalogue.set(manager, MutableStateFlow(agents?.map {
                    if (it.id.id == ChatProfiles.AGENT_ID) presentation(promptAuthor) else it
                }))
            }
            manager.javaClass.getMethod("updateViewModels", Project::class.java, ChatSessionEvent::class.java).invoke(manager, project, event)
        } finally {
            if (promptAuthor != null) catalogue.set(manager, original)
        }
    }

    private fun presentation(author: ResponseProfiles.Author) = ChatSessionAgent(
        ChatSessionAgentId(ChatProfiles.AGENT_ID), "${GenericAutoRegistration.AGENT}\n        ${author.name}",
        icons.computeIfAbsent(author.agentId) { MultiIcons.Agent.rpcId() })

    private fun splitAuthor(model: ChatSessionViewModel, scope: CoroutineScope, id: ChatSessionId, author: ResponseProfiles.Author, oldIds: Set<Int>) {
        val manager = model.javaClass.getDeclaredField("viewModelUpdatesManager").apply { isAccessible = true }.get(model)
        @Suppress("UNCHECKED_CAST")
        val cards = manager.javaClass.getDeclaredField("_messageViewModels").apply { isAccessible = true }.get(manager)
            as MutableStateFlow<PersistentList<ChatMessageViewModel>>
        val card = cards.value.lastOrNull() as? ChatMessageViewModel.AgentMessageViewModel ?: return
        val previous = card.agent ?: return
        val next = presentation(author)
        // Native task startup can create a placeholder before ACP stores its binding.
        // Adopt that new placeholder; never relabel a pre-existing unknown card.
        if (previous.name == GenericAutoRegistration.AGENT) {
            @Suppress("UNCHECKED_CAST")
            val byEvent = manager.javaClass.getDeclaredField("messageViewModelByEventId").apply { isAccessible = true }
                .get(manager) as Map<ChatSessionEventId, Any>
            if (byEvent.none { (event, vm) -> vm === card && event.id in oldIds }) {
                replaceCard(manager, scope, card, next)
                return
            }
        }
        if (previous.id.id == ChatProfiles.AGENT_ID && previous != next)
            cards.value = cards.value.adding(ChatMessageViewModel.AgentMessageViewModel(scope, id, next))
    }

    private fun decorate(model: ChatSessionViewModel, scope: CoroutineScope, authors: Map<ChatSessionEventId, ResponseProfiles.Author?>) {
        val manager = model.javaClass.getDeclaredField("viewModelUpdatesManager").apply { isAccessible = true }.get(model)
        @Suppress("UNCHECKED_CAST")
        val cards = manager.javaClass.getDeclaredField("messageViewModelByEventId").apply { isAccessible = true }
            .get(manager) as Map<ChatSessionEventId, Any>
        authors.forEach { (id, author) ->
            if (author == null) return@forEach
            val card = cards[id] as? ChatMessageViewModel.AgentMessageViewModel ?: return@forEach
            val previous = card.agent
            val next = presentation(author)
            if (previous != next) replaceCard(manager, scope, card, next)
        }
    }

    /** Agent is an immutable Compose input. Replacing its backing field cannot
     * invalidate the latest visible card; publish a new native card instead.
     * Keep the native block objects and event routing so streaming/actions continue.
     */
    private fun replaceCard(manager: Any, scope: CoroutineScope,
                            previous: ChatMessageViewModel.AgentMessageViewModel, agent: ChatSessionAgent) {
        @Suppress("UNCHECKED_CAST")
        val cards = manager.javaClass.getDeclaredField("_messageViewModels").apply { isAccessible = true }.get(manager)
            as MutableStateFlow<PersistentList<ChatMessageViewModel>>
        val index = cards.value.indexOfFirst { it === previous }
        if (index < 0) return
        val replacement = ChatMessageViewModel.AgentMessageViewModel(scope, previous.chatSessionId, agent)
        fun copyState(owner: Class<*>, name: String) {
            val field = owner.getDeclaredField(name).apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST") val old = field.get(previous) as MutableStateFlow<Any>
            @Suppress("UNCHECKED_CAST") val updated = field.get(replacement) as MutableStateFlow<Any>
            updated.value = old.value
        }
        copyState(previous.javaClass, "_blocks")
        copyState(ChatMessageViewModel::class.java, "_isReverted")
        @Suppress("UNCHECKED_CAST")
        val byEvent = manager.javaClass.getDeclaredField("messageViewModelByEventId").apply { isAccessible = true }.get(manager)
            as MutableMap<ChatSessionEventId, Any>
        byEvent.entries.filter { it.value === previous }.forEach { it.setValue(replacement) }
        cards.value = cards.value.replacingAt(index, replacement)
        // Existing blocks have native jobs in the old card's child scope. That
        // scope stays under the chat lifetime; an empty placeholder can close now.
        if (previous.blocks.value.isEmpty()) previous.dispose()
    }
}
