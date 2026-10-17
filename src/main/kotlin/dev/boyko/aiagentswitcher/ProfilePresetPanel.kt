package dev.boyko.aiagentswitcher

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.datatransfer.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.*
import javax.swing.event.DocumentEvent

/** Private drafts, two independent clients, concrete model settings. */
internal class ProfilePresetPanel(private val project: Project) : JPanel(BorderLayout()) {
    var loadingChanged: (Boolean) -> Unit = {}
    var profileChanged: () -> Unit = {}
    val advanced: JComponent
    private data class Choice(val value: String, val name: String, val invalid: Boolean = false) { override fun toString() = name }
    private val hostControls = linkedMapOf("assistant" to JPanel(BorderLayout()), "air" to JPanel(BorderLayout()))
    private var host = "assistant"
    val selectedHost: String get() = host
    fun clientPage(client: String): JComponent = hostControls.getValue(client)
    fun selectHost(client: String) { host = client }
    private val splitters = mutableMapOf<String, com.intellij.ui.JBSplitter>()
    private val selectedModels = mutableMapOf<String, String>()
    private val messages = mutableMapOf<String, String>()
    private val source = ComboBox(arrayOf("None", "Text", "Markdown file"))
    private val text = JBTextArea(3, 35).apply {
        font = UIManager.getFont("TextField.font"); lineWrap = true; wrapStyleWord = true
    }
    private val file = JBTextField()
    private val nativeLink = ActionLink("Open profile AGENTS.md in editor") {
        profile?.let { p -> runCatching { ProfileConfiguration.nativeInstructions(p) }.getOrNull()?.let { path ->
            // Creation is explicitly requested by clicking the editor link. Existing rules are never overwritten.
            if (!Files.exists(path)) { Files.createDirectories(path.parent); Files.writeString(path, "# Profile instructions\n", java.nio.file.StandardOpenOption.CREATE_NEW) }
            open(path.toString())
        } }
    }
    private val textScroll = JScrollPane(text)
    private val fileRow = JPanel(BorderLayout(6, 0))
    private var profile: AgentProfiles.Entry? = null
    private var changing = false
    private var generation = 0
    private var disposed = false
    private var refreshing = false
    fun isLoading() = refreshing
    fun committed() { messages.clear() }
    private var loading: java.util.concurrent.Future<*>? = null
    private val instructionNote = JBLabel("Additional user context sent with the first prompt of a new session.")
    init {
        val instructions = JPanel(BorderLayout(0, 6))
        instructions.add(JPanel(BorderLayout(8, 0)).apply {
            add(JBLabel("Profile instructions (optional):"), BorderLayout.WEST); add(source, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        val inputs = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        inputs.add(textScroll)
        fileRow.add(file, BorderLayout.CENTER)
        fileRow.add(JPanel().apply {
            add(JButton("Browse…").apply { addActionListener {
                FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFileDescriptor("md"), project, null) {
                    file.text = it.path
                }
            } })
            add(ActionLink("Open in editor") { open(file.text) })
        }, BorderLayout.EAST)
        inputs.add(fileRow)
        inputs.add(instructionNote)
        inputs.add(JBLabel("Project AGENTS.md continues to apply. Changes affect new sessions."))
        inputs.add(nativeLink)
        instructions.add(inputs, BorderLayout.CENTER)
        advanced = instructions
        source.addActionListener {
            if (!changing) profile?.instructionsSource = listOf("none", "text", "file")[source.selectedIndex]
            updateInputs()
        }
        text.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) { if (!changing) profile?.instructionsText = text.text }
        })
        file.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) { if (!changing) profile?.instructionsFile = file.text.trim() }
        })
    }
    private fun open(value: String) {
        val target = LocalFileSystem.getInstance().refreshAndFindFileByPath(value.replace('\\', '/'))
        if (target != null) FileEditorManager.getInstance(project).openFile(target, true)
        else com.intellij.openapi.ui.Messages.showInfoMessage(project, "The file is not available. Choose an existing Markdown file.", "Profile instructions")
    }
    fun show(entry: AgentProfiles.Entry, attention: String? = null) {
        profile = entry; changing = true
        source.selectedIndex = listOf("none", "text", "file").indexOf(entry.instructionsSource).coerceAtLeast(0)
        text.text = entry.instructionsText; file.text = entry.instructionsFile
        nativeLink.isVisible = entry.agentId == "acp.registry.codex-acp" && entry.profileHome.isNotBlank()
        nativeLink.isEnabled = runCatching { Path.of(ProfileConfiguration.environment(entry)["CODEX_HOME"].orEmpty()).isAbsolute }.getOrDefault(false)
        changing = false; updateInputs()
        ++generation; loading?.cancel(true); refreshing = false; loadingChanged(false)
        val request = ProfileSettingsPrompt.peek()?.takeIf { it.profileId == entry.id }
        if (request != null) {
            if (request.host in hostControls.keys) host = request.host
            if (request.modelId.isNotBlank()) selectedModels["${entry.id}:${request.host}"] = request.modelId
            messages["${entry.id}:${request.host.ifBlank { host }}"] = request.message
        } else if (attention != null) messages["${entry.id}:$host"] = attention
        hostControls.keys.forEach { client ->
            ProfileModelDefaults.get(entry, client)?.let { ProfileModelDefaults.observe(entry.id, it) }
            render(entry, client)
        }
    }
    private fun updateInputs() {
        textScroll.isVisible = source.selectedIndex == 1; fileRow.isVisible = source.selectedIndex == 2
        instructionNote.isVisible = source.selectedIndex != 0; revalidate(); repaint()
    }
    private fun refreshOptions(client: String) {
        val entry = profile ?: return
        val revision = ++generation
        loading?.cancel(true); refreshing = true; loadingChanged(true)
        messages["${entry.id}:$client"] = "Loading agent models and defaults..."; render(entry, client)
        val draft = entry.snapshot()
        loading = ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val resolved = ProfileDefaultsProbe.prepare(project, draft)
                val c = ProfileDefaultsRefresh.discoverClient(project, resolved, client)
                val previous = ProfileModelDefaults.get(resolved, client)?.defaultModelId
                val defaults = ProfileModelDefaults.refresh(resolved, client, c)
                Triple(resolved, c, previous != null && previous != defaults.defaultModelId)
            }
            ApplicationManager.getApplication().invokeLater({
                if (!disposed && revision == generation && !project.isDisposed) {
                    result.getOrNull()?.let { (resolved, c, reset) ->
                        entry.profileHome = resolved.profileHome; entry.command = resolved.command; entry.arguments = resolved.arguments
                        entry.authenticationArguments = resolved.authenticationArguments; entry.environment = resolved.environment
                        entry.workingDirectory = resolved.workingDirectory; entry.inheritEnvironment = resolved.inheritEnvironment
                        entry.clientDefaults.removeAll { it.host == client && it.agentId == entry.agentId }
                        entry.clientDefaults.addAll(resolved.clientDefaults.filter { it.host == client && it.agentId == entry.agentId }.map { it.snapshot() })
                        ProfileCatalog.store(entry, c)
                        messages["${entry.id}:$client"] = listOfNotNull(
                            if (reset) "The previous default model is unavailable. The agent's default model was selected. Click Apply to save." else null
                        ).joinToString("\n")
                        ProfileSettingsPrompt.clearIssues(entry.id, client)
                        profileChanged()
                    }
                    result.exceptionOrNull()?.let { messages["${entry.id}:$client"] = it.message ?: "Could not load agent defaults. Previous settings were retained." }
                    loading = null; refreshing = false; loadingChanged(false)
                    hostControls.keys.forEach { render(entry, it) }
                }
            }, com.intellij.openapi.application.ModalityState.any())
        }
    }
    private fun render(entry: AgentProfiles.Entry, client: String) {
        splitters.remove(client)?.dispose()
        val controls = hostControls.getValue(client); controls.removeAll()
        val defaults = ProfileModelDefaults.get(entry, client)
        val upper = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        val links = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(12), 0))
        fun link(text: String, description: String, action: () -> Unit) = ActionLink(text) { action() }.apply {
            toolTipText = "<html><body width='340'>$description</body></html>"
            isEnabled = !refreshing
            preferredSize = Dimension(preferredSize.width + JBUI.scale(6), preferredSize.height)
        }
        links.add(link(if (defaults == null) "Load Agent Defaults" else "Refresh Models",
            if (defaults == null) "Load this agent's models and parameter defaults for new ${if (client == "air") "Air sessions" else "AI Assistant chats"}. Click Apply to save."
            else "Reload the agent's model catalog and defaults. Add new models and keep your model order, exclusions and saved parameter values. Existing deprecated values need an explicit correction or model reset. Click Apply to save.") { refreshOptions(client) })
        if (defaults != null) {
            links.add(link("Reset Model List\u2026", "Restore the last loaded model order, enable all available models and select the agent's default model. Keep per-model parameter values, including deprecated values. Click Apply to save.") {
                val answer = com.intellij.openapi.ui.Messages.showYesNoDialog(project,
                    "Restore the model order from the last load or refresh, enable all available models, and select the agent's default model?\n\nPer-model parameter settings will be kept. Click Apply to save the reset.",
                    "Reset Model List", "Reset Model List", "Cancel", com.intellij.openapi.ui.Messages.getWarningIcon())
                if (answer == com.intellij.openapi.ui.Messages.YES) {
                    runCatching { ProfileModelDefaults.resetList(defaults) }
                        .onSuccess { profileChanged(); render(entry, client) }
                        .onFailure { messages["${entry.id}:$client"] = it.message.orEmpty(); render(entry, client) }
                }
            })
            links.add(link("Remove Defaults", "Remove saved model and parameter defaults for this profile and client. New sessions will use the agent's and client's defaults. The other client and existing chats are unchanged. Click Apply to save.") {
                ProfileModelDefaults.remove(entry, client); messages.remove("${entry.id}:$client")
                ProfileSettingsPrompt.clearIssues(entry.id, client); profileChanged(); render(entry, client)
            })
        }
        upper.add(links)
        val request = ProfileSettingsPrompt.failure(entry.id, client) ?: ProfileSettingsPrompt.peek()?.takeIf { it.profileId == entry.id && it.host == client }
        val status = JBTextArea().apply {
            isEditable = false; lineWrap = true; wrapStyleWord = true; isOpaque = false
            foreground = UIUtil.getErrorForeground(); border = JBUI.Borders.empty(8, 0)
        }
        fun updateStatus() {
            val message = messages["${entry.id}:$client"]?.takeIf { it.isNotBlank() } ?: request?.message
            val missing = defaults?.let { ProfileModelDefaults.missingValues(it, entry.id) }.orEmpty()
            status.text = listOfNotNull(message, missing.takeIf { it.isNotEmpty() }?.let {
                "Select parameter values before applying, or disable the affected models:\n" + it.joinToString("\n")
            }).joinToString("\n")
            status.isVisible = status.text.isNotBlank()
            upper.revalidate(); upper.repaint()
        }
        upper.add(status); updateStatus()
        controls.add(upper, BorderLayout.NORTH)
        if (defaults == null) {
            controls.add(JBLabel("Without saved defaults, the agent and client determine the model and parameters.").apply {
                border = JBUI.Borders.empty(12, 0)
            }, BorderLayout.CENTER)
        } else renderModels(entry, client, defaults, controls, ::updateStatus)
        if (refreshing) {
            fun disable(component: Component) { component.isEnabled = false; if (component is Container) component.components.forEach(::disable) }
            disable(controls)
        } else controls.isEnabled = true
        controls.revalidate(); revalidate(); repaint()
    }
    private fun renderModels(entry: AgentProfiles.Entry, client: String, defaults: AgentProfiles.ClientDefaults, controls: JPanel, updateStatus: () -> Unit) {
        val c = ProfileModelDefaults.validationCatalog(entry.id, defaults)
        val available = c?.let { ProfileModelDefaults.modelOption(it)?.values?.map { v -> v.value } ?: listOf("") }.orEmpty()
        val listModel = DefaultListModel<AgentProfiles.ModelPreset>().apply { defaults.models.forEach { addElement(it) } }
        val list = JBList(listModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            visibleRowCount = 9; minimumSize = JBUI.size(175, 160)
            cellRenderer = ListCellRenderer { _, value, _, selected, _ ->
                SimpleColoredComponent().apply {
                    isOpaque = true
                    background = if (selected) UIUtil.getListSelectionBackground(true) else UIUtil.getListBackground()
                    val invalid = value.modelId !in available || ProfileSettingsPrompt.isInvalid(entry.id, client, value.modelId, defaults.modelOptionId, value.modelId)
                    val color = if (invalid) UIUtil.getErrorForeground() else if (!value.enabled) UIUtil.getInactiveTextColor() else if (selected) UIUtil.getListSelectionForeground(true) else UIUtil.getListForeground()
                    val style = (if (!value.enabled) SimpleTextAttributes.STYLE_STRIKEOUT else SimpleTextAttributes.STYLE_PLAIN) or
                        (if (value.modelId == defaults.defaultModelId) SimpleTextAttributes.STYLE_BOLD else SimpleTextAttributes.STYLE_PLAIN)
                    append(value.name + (if (value.modelId == defaults.defaultModelId) " - Default" else "") + (if (invalid) " - Deprecated" else ""), SimpleTextAttributes(style, color))
                    border = JBUI.Borders.empty(4)
                }
            }
        }
        val details = JPanel(GridBagLayout())
        fun updateDetails() {
            updateStatus()
            val m = list.selectedValue ?: return
            selectedModels["${entry.id}:$client"] = m.modelId; details.removeAll()
            var row = 0
            fun full(component: JComponent) { details.add(component, GridBagConstraints().apply { gridx = 0; gridy = row++; gridwidth = 2; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; anchor = GridBagConstraints.NORTHWEST; insets = JBUI.insets(4) }) }
            val default = JCheckBox("Default", defaults.defaultModelId == m.modelId).apply {
                isEnabled = m.modelId in available && defaults.defaultModelId != m.modelId
                addActionListener { m.enabled = true; defaults.defaultModelId = m.modelId; profileChanged(); list.repaint(); updateDetails() }
            }
            full(default)
            full(ActionLink("Reset defaults for this model") {
                runCatching { ProfileModelDefaults.resetModel(entry, defaults, m) }
                    .onSuccess { profileChanged(); updateDetails() }
                    .onFailure { messages["${entry.id}:$client"] = it.message.orEmpty(); render(entry, client) }
            }.apply {
                isEnabled = m.modelId in available
                toolTipText = "Restore this model's parameter values from the last load or refresh. Keep the model list and the other client's defaults. Click Apply to save."
            })
            full(JBLabel("Reset to this model's agent defaults from the last load or refresh.").apply { foreground = UIUtil.getContextHelpForeground() })
            val options = c?.let { ProfileModelDefaults.options(it, m.modelId) }.orEmpty()
            val all = options + m.settings.filter { s -> options.none { it.id == s.id && it.operation == s.operation } }.map {
                ProfileConfiguration.Option(it.id, it.operation, it.id, "select", "", "", null, emptyList())
            }
            all.forEach { o ->
                val saved = m.settings.find { it.id == o.id && it.operation == o.operation }
                val choices = (if (o.type == "boolean") listOf(Choice("true", "Enabled"), Choice("false", "Disabled")) else o.values.map { Choice(it.value, it.name) }).toMutableList()
                val rejected = ProfileSettingsPrompt.isInvalid(entry.id, client, m.modelId, o.id, saved?.value)
                val invalid = saved == null || !ProfileModelDefaults.allowed(o, saved.value) || rejected
                if (saved != null && (choices.none { it.value == saved.value } || rejected)) {
                    val previous = ProfileModelDefaults.catalog(defaults)?.let { ProfileModelDefaults.options(it, m.modelId) }
                        ?.find { it.id == o.id && it.operation == o.operation }
                    val oldLabel = if (previous?.type == "boolean") when (saved.value) {
                        "true" -> "Enabled"; "false" -> "Disabled"; else -> null
                    } else previous?.values?.find { it.value == saved.value }?.name
                    val label = choices.find { it.value == saved.value }?.name ?: oldLabel ?: saved.value
                    choices.removeAll { it.value == saved.value }; choices.add(Choice(saved.value, "$label - Deprecated", true))
                }
                if (saved == null) choices.add(Choice("", "Select a value", true))
                val selector = ComboBox(choices.toTypedArray()).apply {
                    selectedItem = choices.find { it.value == saved?.value } ?: choices.lastOrNull()
                    minimumSize = JBUI.size(160, preferredSize.height)
                    if (invalid) foreground = UIUtil.getErrorForeground()
                    toolTipText = o.description
                    renderer = object : DefaultListCellRenderer() {
                        override fun getListCellRendererComponent(l: JList<*>?, v: Any?, i: Int, selected: Boolean, focus: Boolean): Component {
                            val component = super.getListCellRendererComponent(l, v, i, selected, focus)
                            if ((v as? Choice)?.invalid == true) foreground = UIUtil.getErrorForeground()
                            return component
                        }
                    }
                    addActionListener {
                        val choice = selectedItem as? Choice ?: return@addActionListener
                        if (choice.invalid) {
                            val actual = m.settings.find { it.id == o.id && it.operation == o.operation }?.value
                            choices.find { it.value == actual }?.takeUnless { it === choice }?.let { selectedItem = it }
                            return@addActionListener
                        }
                        if (!choice.invalid) {
                            m.settings.removeAll { it.id == o.id && it.operation == o.operation }
                            m.settings.add(AgentProfiles.SessionSetting(entry.agentId, o.operation, o.id, choice.value, client))
                            foreground = UIUtil.getTextFieldForeground(); profileChanged(); updateStatus()
                        }
                    }
                }
                details.add(JBLabel(o.name + ":"), GridBagConstraints().apply { gridx = 0; gridy = row; anchor = GridBagConstraints.WEST; insets = JBUI.insets(4) })
                details.add(selector, GridBagConstraints().apply { gridx = 1; gridy = row++; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = JBUI.insets(4) })
            }
            details.add(JPanel(), GridBagConstraints().apply { gridx = 0; gridy = row; weighty = 1.0 })
            details.revalidate(); details.repaint()
        }
        fun move(delta: Int) {
            val index = list.selectedIndex; val target = index + delta
            if (index < 0 || target !in defaults.models.indices) return
            val value = defaults.models.removeAt(index); defaults.models.add(target, value)
            profileChanged(); selectedModels["${entry.id}:$client"] = value.modelId; render(entry, client)
        }
        val browser = ToolbarDecorator.createDecorator(list)
            .setAddAction { list.selectedValue?.takeIf { it.modelId in available }?.let { it.enabled = true; profileChanged(); list.repaint(); updateDetails() } }
            .setRemoveAction { list.selectedValue?.takeIf { it.modelId != defaults.defaultModelId }?.let { it.enabled = false; profileChanged(); list.repaint(); updateDetails() } }
            .setAddActionUpdater { list.selectedValue?.let { !it.enabled && it.modelId in available } == true }
            .setRemoveActionUpdater { list.selectedValue?.let { it.enabled && it.modelId != defaults.defaultModelId } == true }
            .setMoveUpAction { move(-1) }.setMoveDownAction { move(1) }
            .setMoveUpActionUpdater { list.selectedIndex > 0 }
            .setMoveDownActionUpdater { list.selectedIndex in 0 until defaults.models.size - 1 }.createPanel()
        list.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = popup(e)
            override fun mouseReleased(e: MouseEvent) = popup(e)
            fun popup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val i = list.locationToIndex(e.point)
                if (i < 0 || list.getCellBounds(i, i)?.contains(e.point) != true) return
                list.selectedIndex = i
                JPopupMenu().apply { add(JMenuItem("Set as Default").apply {
                    isEnabled = listModel[i].modelId in available
                    addActionListener { listModel[i].enabled = true; defaults.defaultModelId = listModel[i].modelId; profileChanged(); list.repaint(); updateDetails() }
                }); show(list, e.x, e.y) }
            }
        })
        list.dragEnabled = true; list.dropMode = DropMode.INSERT
        list.transferHandler = object : TransferHandler() {
            override fun getSourceActions(component: JComponent) = MOVE
            override fun createTransferable(component: JComponent): Transferable? = list.selectedValue?.let { StringSelection(it.modelId) }
            override fun canImport(support: TransferSupport) = support.isDrop && support.component === list && support.isDataFlavorSupported(DataFlavor.stringFlavor)
            override fun importData(support: TransferSupport): Boolean {
                if (!canImport(support)) return false
                val id = support.transferable.getTransferData(DataFlavor.stringFlavor) as String
                val old = defaults.models.indexOfFirst { it.modelId == id }; if (old < 0) return false
                var target = (support.dropLocation as JList.DropLocation).index
                val value = defaults.models.removeAt(old); if (target > old) target--
                defaults.models.add(target.coerceIn(0, defaults.models.size), value)
                selectedModels["${entry.id}:$client"] = id; profileChanged(); render(entry, client); return true
            }
        }
        list.addListSelectionListener { if (!it.valueIsAdjusting) updateDetails() }
        val split = com.intellij.ui.JBSplitter("AIAPS.settings.models.$client.divider", 0.35f).apply {
            firstComponent = browser; secondComponent = details; border = JBUI.Borders.empty(8, 0)
        }
        splitters[client] = split
        controls.add(split, BorderLayout.CENTER)
        list.selectedIndex = defaults.models.indexOfFirst { it.modelId == (selectedModels["${entry.id}:$client"] ?: defaults.defaultModelId) }.coerceAtLeast(0)
    }
    fun dispose() { disposed = true; generation++; loading?.cancel(true); splitters.values.forEach { it.dispose() }; splitters.clear() }
}
