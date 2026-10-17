package dev.boyko.aiagentswitcher.compat

import com.google.gson.*
import com.intellij.ml.llm.core.chat.session.ChatSourceAction
import com.intellij.ml.llm.core.chat.ui.chat.input.chatModeSelector.NewChatMode
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** Configure a new native chat before focus starts ACP. No project-default mutation or routing race. */
internal object ProfileChatCreator {
    fun pendingProfile(uid: String): String? = runCatching {
        val root = Path.of(System.getProperty("aiaps.home", System.getProperty("user.home")), ".jetbrains", "ai-agent-project-switcher")
        val digest = MessageDigest.getInstance("SHA-256").digest(Gson().toJson(uid).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val request = JsonParser.parseString(Files.readString(root.resolve("chat-starts").resolve("$digest.json"))).asJsonObject
        request.get("profile").asString.takeIf { request.get("chatUid").asString == uid }
    }.getOrNull()

    suspend fun create(project: Project, mode: NewChatMode, profile: String): Any = withContext(Dispatchers.EDT) {
        if (!com.intellij.openapi.components.service<UnsentPromptGuard>().confirmFocused(project))
            throw kotlinx.coroutines.CancellationException("Stayed in the chat with an unsent prompt")
        val loader = NewChatMode::class.java.classLoader
        fun type(name: String) = Class.forName(name, true, loader)
        fun service(name: String): Any {
            @Suppress("UNCHECKED_CAST")
            return project.getService(type(name) as Class<Any>)
        }
        val host = service("com.intellij.ml.llm.core.chat.session.ChatSessionHost")
        val origin = type("com.intellij.ml.llm.core.chat.session.ChatOrigin\$AIAssistantTool").getField("INSTANCE").get(null)
        val contextType = type("com.intellij.ml.llm.core.chat.session.ChatCreationContext")
        val context = contextType.constructors.single { it.parameterCount == 11 }.newInstance(origin,
            ChatSourceAction.NEW_CHAT, null, null, null, mode, false, false, false, 476, null)
        val retrievalType = type("com.intellij.ml.llm.core.chat.context.ChatRetrievalSession")
        val companion = retrievalType.getField("Companion").get(null)
        val retrieval = companion.javaClass.getMethod("create", Project::class.java).invoke(companion, project)
        val method = host.javaClass.methods.single { it.name == "createChatSession\$default" && it.parameterCount == 7 }
        val chat = suspendCoroutineUninterceptedOrReturn<Any> { continuation -> method.invoke(null, host, context, retrieval, null, continuation, 4, null) }
        val uid = chat.javaClass.getMethod("getUid").invoke(chat).toString()
        withContext(Dispatchers.IO) {
            dev.boyko.aiagentswitcher.GenericAutoRegistration.syncRoutesNow(project)
            dev.boyko.aiagentswitcher.ProfileLaunch.resolve(profile).stage(uid, project.basePath ?: error("A project directory is required"))
        }
        val focused = service("com.intellij.ml.llm.core.chat.session.FocusedChatSessionHost")
        focused.javaClass.methods.single { it.name == "focusChatSession" && it.parameterCount == 1 }.invoke(focused, chat)
        chat
    }

}
