package dev.boyko.aiagentswitcher.compat

import com.intellij.ml.llm.core.chat.session.ChatSessionVm
import com.intellij.ml.llm.core.chat.ui.chat.input.AIAssistantInput
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.Document
import dev.boyko.aiagentswitcher.ChatProfiles
import dev.boyko.aiagentswitcher.LocalChatState
import kotlinx.coroutines.*

/** Keep the native local transcript and disable the existing composer, without an ACP session. */
internal object LocalHistoryPresentation {
    fun observe(chat: ChatSessionVm, scope: CoroutineScope) = scope.launch(Dispatchers.EDT) {
        var document: Document? = null
        var wasWritable = true
        var input: AIAssistantInput? = null
        var wasEnabled = true
        var tooltip: String? = null
        try {
            while (isActive) {
                val readOnly = withContext(Dispatchers.IO) { LocalChatState.readOnly(ChatProfiles.conversationRecord(chat.uid)) }
                val current = chat.associatedInput.value as? AIAssistantInput
                if (document != null && (!readOnly || current?.document !== document)) {
                    document?.setReadOnly(!wasWritable)
                    input?.editorTextField?.isEnabled = wasEnabled
                    input?.editorTextField?.toolTipText = tooltip
                    document = null; input = null
                }
                if (readOnly && current != null) {
                    if (document == null) {
                        document = current.document; wasWritable = current.document.isWritable; input = current
                        wasEnabled = current.editorTextField.isEnabled; tooltip = current.editorTextField.toolTipText
                    }
                    current.document.setReadOnly(true)
                    current.editorTextField.isEnabled = false
                    current.editorTextField.toolTipText = "Local history only. Choose another profile to continue."
                }
                delay(200)
            }
        } finally {
            document?.setReadOnly(!wasWritable)
            input?.editorTextField?.isEnabled = wasEnabled
            input?.editorTextField?.toolTipText = tooltip
        }
    }
}
