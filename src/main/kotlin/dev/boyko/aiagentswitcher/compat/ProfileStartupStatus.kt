@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package dev.boyko.aiagentswitcher.compat

import com.intellij.ml.llm.agents.acp.client.AcpSessionLifecycleManager
import com.intellij.ml.llm.core.chat.session.ChatSession
import com.intellij.ml.llm.core.chat.session.impl.ChatSessionImpl
import com.intellij.openapi.diagnostic.Logger
import dev.boyko.aiagentswitcher.GenericAutoRegistration
import kotlinx.coroutines.*
import java.util.Collections
import java.util.WeakHashMap

/** Apply configured preferences through the native lifecycle.
 * Startup/authentication events belong to the host. Emitting an extra in-progress
 * block before load replay can strand its pre-prompt readiness collector.
 */
internal object ProfileStartupStatus {
    private val observed = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<AcpSessionLifecycleManager, Boolean>()))

    fun observe(chat: ChatSession, scope: CoroutineScope) {
        if (chat.currentChatMode.displayName != GenericAutoRegistration.AGENT || chat !is ChatSessionImpl) return
        scope.launch {
            var tracked: AcpSessionLifecycleManager? = null
            try {
                // Chat creation installs the native session asynchronously.
                var session = chat.agentSession
                while (session == null) { delay(50); session = chat.agentSession }
                if (session.javaClass.name != "com.intellij.ml.llm.agents.acp.session.AcpAgentSession") return@launch
                val manager = session.javaClass.getDeclaredField("lifecycleManager").apply { isAccessible = true }
                    .get(session) as AcpSessionLifecycleManager
                val type = AcpSessionLifecycleManager::class.java
                val disabled = type.getDeclaredMethod("isExplicitAuthenticationDisabled").apply { isAccessible = true }
                    .invoke(manager) as Boolean
                if (!disabled || !observed.add(manager)) return@launch
                tracked = manager
                ProfileAgentPreferences.beforePrompt(chat, manager)
                if (manager.ensureSessionReady()) {
                    ChatHandoffClient(chat as Any).nativeSessionOrNull()?.let { ProfileAgentPreferences.synchronize(chat, it) }
                }
            } catch (cancelled: CancellationException) {
                tracked?.let { observed.remove(it) }
                throw cancelled
            } catch (error: Exception) {
                Logger.getInstance(ProfileStartupStatus::class.java).warn("Could not synchronize profile session preferences", error)
            }
        }
    }
}
