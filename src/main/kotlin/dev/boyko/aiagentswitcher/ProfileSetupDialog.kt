package dev.boyko.aiagentswitcher

import com.google.gson.JsonObject
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.boyko.aiagentswitcher.compat.ProfileAuthenticationChooser
import java.awt.BorderLayout
import java.awt.GridLayout
import java.util.concurrent.Future
import javax.swing.*

/** A profile-owned ACP sign-in connection, deliberately without a conversation or prompt input. */
internal class ProfileSetupDialog(private val project: Project, private val draft: AgentProfiles.Entry,
                                  private val completed: (AgentProfiles.Entry) -> Unit) : DialogWrapper(project, true) {
    private val status = JBLabel("Connecting to the selected agent…").apply { putClientProperty("html.disable", true) }
    private val choices = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val body = JPanel(BorderLayout(0, 12)).apply { border = JBUI.Borders.empty(12); add(status, BorderLayout.NORTH); add(choices, BorderLayout.CENTER) }
    @Volatile private var connection: ProfileDefaultsProbe.Connection? = null
    @Volatile private var closed = false
    private var worker: Future<*>? = null
    @Volatile private var terminal: KillableColoredProcessHandler? = null
    private var console: ConsoleView? = null
    init { title = "Authorize — ${draft.name}"; init(); start() }
    override fun createCenterPanel() = body.apply { preferredSize = JBUI.size(580, 280) }
    override fun createActions() = arrayOf(cancelAction)
    private fun ui(action: () -> Unit) = ApplicationManager.getApplication().invokeLater({ if (!closed && !project.isDisposed) action() }, ModalityState.any())
    private fun work(action: () -> Unit) {
        worker = ApplicationManager.getApplication().executeOnPooledThread {
            try { action() }
            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            catch (e: Exception) { ui { status.text = e.cause?.message ?: e.message ?: "Authorization did not complete."; choices.components.forEach { it.isEnabled = true } } }
        }
    }
    private fun start() = work {
        val profile = ProfileDefaultsProbe.prepare(project, draft)
        // Reserve one durable identity for the draft, including cancelled login attempts.
        ui { copyLaunch(profile) }
        val client = ProfileDefaultsProbe.Connection(project, profile)
        connection = client
        if (closed) { client.close(); return@work }
        client.clientRequest = ::clientAction
        val methods = client.methods()
        choose(profile, methods)
    }
    private fun choose(profile: AgentProfiles.Entry, methods: com.google.gson.JsonArray) {
        if (methods.isEmpty) { loadNow(profile); return }
        val selected = ProfileAuthenticationChooser.choose(methods) { caption, request, respond -> ui {
            status.text = caption
            choices.removeAll()
            request.options.forEachIndexed { index, option ->
                choices.add(JButton(option.description).apply {
                    putClientProperty("html.disable", true)
                    toolTipText = methods[index].asJsonObject.get("description")?.takeUnless { it.isJsonNull }?.asString
                    addActionListener {
                        choices.components.forEach { it.isEnabled = false }
                        respond(option.id)
                    }
                })
            }
            body.revalidate(); body.repaint()
        } }
        if (!closed && selected != null) authorizeNow(profile, selected)
    }
    private fun copyLaunch(profile: AgentProfiles.Entry) {
        draft.profileHome = profile.profileHome; draft.command = profile.command; draft.arguments = profile.arguments
        draft.authenticationArguments = profile.authenticationArguments; draft.environment = profile.environment
        draft.workingDirectory = profile.workingDirectory; draft.inheritEnvironment = profile.inheritEnvironment
    }
    private fun authorizeNow(profile: AgentProfiles.Entry, method: JsonObject) {
        ui { choices.components.forEach { it.isEnabled = false }; status.text = "Signing in… Complete the agent's authorization flow." }
        try {
            val client = connection ?: error("Profile setup was closed.")
            val params = JsonObject().apply { addProperty("methodId", method.get("id").asString) }
            val terminalMethod = method.get("type")?.asString == "terminal" || method.getAsJsonObject("_meta")?.has("terminal-auth") == true
            if (terminalMethod) {
                val spec = client.request("_ai_multi/login_spec", params)
                val command = GeneralCommandLine(spec.get("command").asString).withParameters(spec.getAsJsonArray("args").map { it.asString })
                    .withWorkDirectory(spec.get("cwd").asString)
                    .withParentEnvironmentType(if (spec.get("inheritEnvironment").asBoolean) GeneralCommandLine.ParentEnvironmentType.CONSOLE else GeneralCommandLine.ParentEnvironmentType.NONE)
                    .withEnvironment(spec.getAsJsonObject("env").entrySet().associate { it.key to it.value.asString })
                check(!closed) { "Profile setup was closed." }
                val process = KillableColoredProcessHandler(command); terminal = process
                // Dispose may have run while the process constructor was launching the terminal.
                if (closed) { ProfileProcessCleanup.stop(process.process); return }
                ApplicationManager.getApplication().invokeAndWait({
                    if (!closed) {
                        console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console.also { view ->
                            body.add(view.component, BorderLayout.SOUTH); view.component.preferredSize = JBUI.size(560, 180)
                            view.attachToProcess(process); body.revalidate()
                        }
                    }
                }, ModalityState.any())
                process.startNotify()
                if (closed) { ProfileProcessCleanup.stop(process.process); return }
                process.waitFor()
                check(!closed && process.exitCode == 0) { "Agent sign-in did not complete. Choose a sign-in method to retry." }
                client.close()
                val replacement = ProfileDefaultsProbe.Connection(project, profile).also { it.clientRequest = ::clientAction }
                connection = replacement
                if (closed) { replacement.close(); return }
            } else client.request("_ai_multi/authorize", params, 600)
            loadNow(profile)
        } catch (e: Exception) {
            if (closed || e is InterruptedException) throw e
            ui { status.text = e.cause?.message ?: e.message ?: "Authorization did not complete." }
            choose(profile, (connection ?: throw e).methods())
        }
    }
    private fun loadNow(profile: AgentProfiles.Entry) {
        ui { status.text = "Loading authorized session options…" }
        val value = (connection ?: error("Profile setup was closed.")).defaults()
        if (closed) return
        ProfileCatalog.store(profile, value)
        ui { copyLaunch(profile); completed(draft); close(OK_EXIT_CODE) }
    }
    private fun clientAction(message: JsonObject): JsonObject {
        val method = message.get("method").asString
        if (method !in listOf("elicitation/create", "client/elicitation/create")) error("Client action is not available during authorization")
        val params = message.getAsJsonObject("params") ?: error("Missing authorization request")
        var result = JsonObject().apply { addProperty("action", "cancel") }
        ApplicationManager.getApplication().invokeAndWait({
            if (!closed) {
                val url = params.get("url")?.asString
                if (url != null) {
                    val uri = java.net.URI(url)
                    require(uri.scheme in listOf("http", "https")) { "Unsupported authorization URL" }
                    BrowserUtil.browse(url)
                    status.text = params.get("message")?.asString ?: "Complete sign-in in your browser."
                    result = JsonObject().apply { addProperty("action", "accept") }
                } else {
                    val schema = params.getAsJsonObject("requestedSchema") ?: error("Unsupported authorization request")
                    val panel = JPanel(GridLayout(0, 2, 8, 8)); val fields = linkedMapOf<String, Pair<JsonObject, JComponent>>()
                    schema.getAsJsonObject("properties")?.entrySet()?.forEach { (id, raw) ->
                        val property = raw.asJsonObject
                        val field: JComponent = if (property.get("type")?.asString == "boolean") JCheckBox()
                            else if (property.has("enum")) JComboBox(property.getAsJsonArray("enum").map { it.asString }.toTypedArray())
                            else if (ProfileConfiguration.sensitive(id) || property.get("format")?.asString == "password") JPasswordField()
                            else JTextField()
                        fields[id] = property to field; panel.add(JLabel(property.get("title")?.asString ?: id)); panel.add(field)
                    }
                    if (JOptionPane.showConfirmDialog(body, panel, "Agent authorization", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
                        val content = JsonObject()
                        fields.forEach { (id, pair) -> when (val field = pair.second) {
                            is JCheckBox -> content.addProperty(id, field.isSelected)
                            is JComboBox<*> -> content.addProperty(id, field.selectedItem?.toString())
                            is JPasswordField -> content.addProperty(id, String(field.password))
                            is JTextField -> content.addProperty(id, field.text)
                        } }
                        result = JsonObject().apply { addProperty("action", "accept"); add("content", content) }
                    }
                }
            }
        }, ModalityState.any())
        return result
    }
    override fun dispose() {
        closed = true; worker?.cancel(true); console?.dispose()
        val login = terminal; terminal = null
        if (login != null) ApplicationManager.getApplication().executeOnPooledThread { ProfileProcessCleanup.stop(login.process) }
        val client = connection; connection = null
        if (client != null) ApplicationManager.getApplication().executeOnPooledThread { runCatching { client.close() } }
        super.dispose()
    }
}
