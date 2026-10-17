package dev.boyko.aiagentswitcher.compat

import com.intellij.ml.llm.chat.session.SessionHistoryStorage
import com.intellij.ml.llm.core.chat.session.ChatSessionVm
import com.intellij.ml.llm.core.chat.session.ChatTitle
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Repair only the internal marker accidentally used as a title by dev.10/dev.11. */
internal object ChatTitleRepair {
    suspend fun repair(vm: ChatSessionVm) {
        val chat = vm.session
        val old = chat.title
        // Native ACP task-name events also set isCustom=true. The reserved marker,
        // rather than that flag, identifies the title produced by the old adapter.
        if (!old.text.startsWith("AI_MULTI_HANDOFF:")) return
        try {
            val prompt = withContext(Dispatchers.IO) {
                SessionHistoryStorage().getEvents(SessionHistoryStorage.PersistanceId(chat.uid))
                    .firstOrNull { it.javaClass.simpleName == "ChatSessionUserPromptEvent" }
                    ?.let { it.javaClass.getMethod("getPrompt").invoke(it) as? String }
            }?.trim()?.takeIf { it.isNotEmpty() } ?: return
            withContext(Dispatchers.EDT) {
                if (!vm.project.isDisposed && chat.title == old)
                    chat.javaClass.getMethod("setTitle", ChatTitle::class.java).invoke(chat, ChatTitle(prompt.take(200), old.isCustom))
            }
        } catch (e: Exception) {
            Logger.getInstance(ChatTitleRepair::class.java).warn("Could not recover the original AI Multi chat title", e)
        }
    }
}
