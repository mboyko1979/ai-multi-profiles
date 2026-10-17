package dev.boyko.aiagentswitcher

import com.google.gson.JsonParser
import com.intellij.ml.llm.chat.session.SessionHistoryStorage
import com.intellij.ml.llm.core.chat.session.ChatSourceAction
import com.intellij.ml.llm.core.chat.ui.chat.input.chatModeSelector.NewChatMode
import java.lang.reflect.Method
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest

/** The chat list is shared. Each chat keeps the profile that created its ACP session. */
internal object ChatProfiles {
    const val AGENT_ID = "acp.ai-multi"
    private const val PREVIOUS_AGENT_ID = "acp.ai-auto-project-switcher"
    private val sessionJson = com.google.gson.GsonBuilder().disableHtmlEscaping().create()
    private val owners: Path get() = Path.of(System.getProperty("aiaps.home", System.getProperty("user.home")),
        ".jetbrains", "ai-agent-project-switcher", "session-owners")

    /** Profile bound to this IDE chat, or null when the chat has not started an ACP session yet. */
    fun boundProfile(chatUid: String): String? = conversationRecord(chatUid)?.let { record ->
        if (record.get("kind")?.asString == "setup") "Unassigned" else record.get("profile")?.asString
    }

    fun conversationRecord(chatUid: String): com.google.gson.JsonObject? = runCatching {
        val stored = SessionHistoryStorage().getStoredAgentSession(SessionHistoryStorage.PersistanceId(chatUid)) ?: return null
        if (stored.agentId != AGENT_ID && stored.agentId != PREVIOUS_AGENT_ID) return null
        val sessionId = stored.agentSessionId ?: return null
        val file = if (sessionId.startsWith("aiaps-chat-")) owners.parent.resolve("conversations")
            .resolve(sha256(sessionJson.toJson(sessionId)) + ".json") else owners.resolve(sha256(sessionJson.toJson(sessionId)) + ".json")
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) return null
        JsonParser.parseString(Files.readString(file).trimStart('\uFEFF')).asJsonObject
    }.getOrNull()

    /** JetBrains derives acp.<sanitized-name> from the registration title. Retarget chats from the previous title. */
    fun migrateAgentId() {
        val history = Path.of(PathManager.getConfigPath(), "aia-task-history")
        if (!Files.isDirectory(history, NOFOLLOW_LINKS)) return
        val prefix = "$PREVIOUS_AGENT_ID:"
        Files.list(history).use { stream ->
            stream.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) && it.fileName.toString().endsWith(".agentsession") }
                .forEach { file ->
                    val text = Files.readString(file)
                    if (!text.startsWith(prefix)) return@forEach
                    val tmp = Files.createTempFile(history, ".agent-id-", ".tmp")
                    try {
                        Files.writeString(tmp, AGENT_ID + ":" + text.removePrefix(prefix))
                        try {
                            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                        }
                    } finally { Files.deleteIfExists(tmp) }
                }
        }
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

internal object ChatProfileSwitch {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun launch(project: Project, id: String, mode: NewChatMode?) {
        scope.launch { open(project, id, mode) }
    }

    suspend fun open(project: Project, id: String, mode: NewChatMode?) {
        try {
            withContext(Dispatchers.IO) { GenericAutoRegistration.assignNow(project, id) }
            val modeForAgent = mode?.takeIf { it.displayName == GenericAutoRegistration.AGENT }
                ?: availableChatModes(project).firstOrNull { it.displayName == GenericAutoRegistration.AGENT }
            if (modeForAgent == null) {
                notify(project, "Default project profile saved", "Select AI Multi in AI Chat and start a new chat.", NotificationType.INFORMATION)
                return
            }
            // createAndFocusNewChat is internal to AI Assistant. The inspected 262.10968.223 build exposes it as a public JVM method.
            create(project, modeForAgent)
        } catch (e: Exception) {
            notify(project, "Could not switch profile", e.message ?: "Profile switch failed", NotificationType.WARNING)
        }
    }

    /** Chat-local creation must not save a project assignment. */
    suspend fun create(project: Project, mode: NewChatMode?): Any? {
        val selectedMode = mode?.takeIf { it.displayName == GenericAutoRegistration.AGENT }
            ?: availableChatModes(project).firstOrNull { it.displayName == GenericAutoRegistration.AGENT }
            ?: error("Select AI Multi in AI Chat first")
        return suspendCoroutineUninterceptedOrReturn { continuation ->
            newChatMethod().invoke(null, project, selectedMode, ChatSourceAction.NEW_CHAT, continuation)
        }
    }

    fun report(project: Project, title: String, message: String) = notify(project, title, message, NotificationType.WARNING)

    private fun chatLoader(): ClassLoader = NewChatMode::class.java.classLoader

    private fun availableChatModes(project: Project): List<NewChatMode> {
        val type = Class.forName("com.intellij.ml.llm.core.chat.ui.chat.input.chatModeSelector.AIAssistantNewChatModeKt", true, chatLoader())
        val modes = type.getMethod("getAvailableChatModes", Project::class.java).invoke(null, project)
        @Suppress("UNCHECKED_CAST")
        return modes as List<NewChatMode>
    }

    private fun newChatMethod(): Method {
        val type = Class.forName("com.intellij.ml.llm.core.chat.ui.NewChatCreatorKt", true, chatLoader())
        return type.getMethod("createAndFocusNewChat", Project::class.java, NewChatMode::class.java,
            ChatSourceAction::class.java, kotlin.coroutines.Continuation::class.java)
    }

    private fun notify(project: Project, title: String, message: String, type: NotificationType) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            NotificationGroupManager.getInstance().getNotificationGroup("AI Agent Project Switcher")
                .createNotification(title, message, type).notify(project)
        }
    }
}
