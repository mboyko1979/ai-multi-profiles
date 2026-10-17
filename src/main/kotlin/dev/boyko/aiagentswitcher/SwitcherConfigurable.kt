package dev.boyko.aiagentswitcher

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.util.Disposer
import com.intellij.ui.*
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import com.intellij.util.ui.JBUI
import dev.boyko.aiagentswitcher.compat.InstalledAgents
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.UUID
import javax.swing.*

/** Private drafts are committed only by the Settings Apply operation. */
class SwitcherConfigurable(private val project: Project) : Configurable, Configurable.NoScroll {
    private var ui: Editor? = null
    override fun getDisplayName() = "AI Multi Profiles"
    override fun createComponent(): JComponent = (ui ?: Editor().also { ui = it }).root
    override fun isModified() = ui?.isModified() ?: false
    override fun apply() { ui?.save() }
    override fun reset() { ui?.reset() }
    override fun disposeUIResources() {
        if (ProfileSettingsPrompt.onShown != null) ProfileSettingsPrompt.onShown = null
        ui?.let { Disposer.dispose(it) }; ui = null
    }

    private inner class Editor : Disposable {
        private val model = DefaultListModel<AgentProfiles.Entry>()
        private val profiles = JBList(model)
        private val assignment = JCheckBox("Default Profile (project-specific)")
        private val name = JBTextField()
        private val agent = ComboBox<InstalledAgents.Choice>().apply {
            preferredSize = Dimension(JBUI.scale(220), preferredSize.height)
            minimumSize = Dimension(JBUI.scale(120), minimumSize.height)
        }
        private val agentStatus = JBLabel()
        private val refreshAgentsLink = com.intellij.ui.components.ActionLink("Refresh agents") { refreshAgents() }.apply {
            autoHideOnDisable = false
        }
        private var refreshingAgents = false
        private var availabilityGeneration = 0
        private var selectedAgentAvailable = false
        private val accountEmail = JBLabel("Account unknown").apply { putClientProperty("html.disable", true) }
        private val accountId = JBLabel("—").apply { putClientProperty("html.disable", true) }
        private val duplicateAccount = JBLabel().apply { isVisible = false; putClientProperty("html.disable", true) }
        private val presets = ProfilePresetPanel(project)
        private val authorize = com.intellij.ui.components.ActionLink("Authorize…") {
            edited?.let { entry ->
                flush()
                ProfileSetupDialog(project, entry) { if (!disposed) { presets.show(entry); updateAuthorization() } }.show()
                if (!disposed) updateAuthorization()
            }
        }
        private var accountGeneration = 0
        private val refreshAccountLink = com.intellij.ui.components.ActionLink("Refresh account info") { updateAccount() }
        private var accountPopup: JBPopup? = null
        private val revoke = com.intellij.ui.components.ActionLink("Revoke") {
            edited?.let { entry ->
                revokeAuthorization(entry.id)
            }
        }
        private val cards = JPanel(CardLayout())
        private val status = JBLabel()
        private var registrationBusy = false
        private val repairRegistrationLink = com.intellij.ui.components.ActionLink("Repair Registration…") { repairRegistration() }
        private val refreshRegistrationLink = com.intellij.ui.components.ActionLink("Refresh") { refreshStatus() }
        private val splitter = JBSplitter("AIAPS.settings.divider", 0.28f)
        private var baseline = emptyList<AgentProfiles.Entry>()
        private var baselineAssignment = "Unassigned"
        private var edited: AgentProfiles.Entry? = null
        private var changing = false
        private var disposed = false
        private var assignedId = "Unassigned"
        private val form = panel {
            row { cell(assignment) }
            row { comment("New chats use the project default. Choosing a profile in chat keeps this default unchanged.") }
            row("Name:") {
                cell(name).align(AlignX.FILL).resizableColumn()
                    .validationOnInput { nameError()?.let { ValidationInfo(it, name) } }
            }
            row("Agent:") {
                cell(agent)
                cell(refreshAgentsLink)
                cell(JButton(com.intellij.openapi.util.IconLoader.getIcon("/icons/profileInfo.svg", SwitcherConfigurable::class.java)).apply {
                    preferredSize = Dimension(JBUI.scale(28), preferredSize.height)
                    minimumSize = preferredSize
                    margin = JBUI.emptyInsets()
                    isBorderPainted = false
                    isContentAreaFilled = false
                    toolTipText = "<html><body width='260'>Install agents in AI Assistant → Agents.<br><br>Select an agent, then authorize this profile here.</body></html>"
                    addActionListener {
                        com.intellij.openapi.ui.Messages.showInfoMessage(project, "Install agents in AI Assistant → Agents.\n\nSelect an agent, then use Authorize on this page.", "ACP providers")
                    }
                })
            }
            row { cell(agentStatus) }
            row("Authorization:") {
                label("Managed by profile")
            }.topGap(TopGap.SMALL)
            row {
                cell(authorize)
                cell(revoke)
                link("Account info") { showAccountInfo(it.source as JComponent) }
            }
            row { comment("Changes apply to new sessions. Existing sessions retain their original profile.") }
        }
        private val profileTabs = JTabbedPane()
        val root: JComponent

        init {
            ProfileSettingsPrompt.onShown = shown@{ request ->
                if (disposed || project.isDisposed) return@shown
                val index = (0 until model.size()).firstOrNull { model[it].id == request.profileId } ?: return@shown
                if (profiles.selectedIndex != index) profiles.selectedIndex = index else showProfile(model[index])
            }
            profiles.selectionMode = ListSelectionModel.SINGLE_SELECTION
            profiles.cellRenderer = object : SimpleListCellRenderer<AgentProfiles.Entry>() {
                override fun customize(list: JList<out AgentProfiles.Entry>, value: AgentProfiles.Entry?, index: Int, selected: Boolean, hasFocus: Boolean) {
                    text = value?.name?.ifBlank { "Unnamed profile" }.orEmpty()
                    icon = AllIcons.RunConfigurations.Application
                    font = list.font.deriveFont(if (value?.id == assignedId) java.awt.Font.BOLD else java.awt.Font.PLAIN)
                    toolTipText = if (value?.id == assignedId) "Default for this project" else null
                }
            }
            profiles.emptyText.text = "No profiles"
            profiles.addListSelectionListener {
                if (!changing && !it.valueIsAdjusting) { flush(); showProfile(profiles.selectedValue) }
            }
            profiles.addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) = showDefaultMenu(e)
                override fun mouseReleased(e: MouseEvent) = showDefaultMenu(e)

                private fun showDefaultMenu(e: MouseEvent) {
                    if (!e.isPopupTrigger) return
                    val index = profiles.locationToIndex(e.point)
                    if (index < 0 || profiles.getCellBounds(index, index)?.contains(e.point) != true) return
                    profiles.selectedIndex = index
                    val entry = model[index]
                    JPopupMenu().apply {
                        add(JMenuItem("Set as Default for Current Project").apply {
                            isEnabled = entry.id != assignedId
                            addActionListener {
                                assignedId = entry.id
                                rebuildChoices()
                            }
                        })
                        show(profiles, e.x, e.y)
                    }
                    e.consume()
                }
            })
            name.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: javax.swing.event.DocumentEvent) {
                    if (!changing) { flush(); profiles.repaint(); rebuildChoices() }
                }
            })
            agent.addActionListener {
                if (!changing) {
                    flush()
                    edited?.let {
                        val choice = agent.selectedItem as? InstalledAgents.Choice
                        if (it.agentId != choice?.id.orEmpty()) {
                            it.agentId = choice?.id.orEmpty(); it.agentName = choice?.name.orEmpty()
                            it.profileHome = ""; it.advancedOverrides = false
                            it.command = ""; it.arguments = ""; it.environment = ""; it.workingDirectory = ""
                            showProfile(it)
                        }
                    }
                }
            }
            val duplicate = object : DumbAwareAction("Duplicate profile", "Duplicate the selected profile", AllIcons.Actions.Copy) {
                override fun actionPerformed(e: AnActionEvent) {
                    flush()
                    edited?.let { add(it.snapshot().copy(id = UUID.randomUUID().toString(), name = uniqueName("${it.name} Copy"),
                        profileHome = "", advancedOverrides = if (it.agentId.isBlank()) it.advancedOverrides else false)) }
                }
                override fun update(e: AnActionEvent) { e.presentation.isEnabled = edited != null }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            }
            val browser = ToolbarDecorator.createDecorator(profiles)
                .setAddAction { flush(); add(newProfile()) }
                .setRemoveAction {
                    val index = profiles.selectedIndex
                    if (index >= 0) {
                        if (assignedId == model[index].id) assignedId = "Unassigned"
                        changing = true; model.remove(index); changing = false
                        showProfile(null)
                        if (!model.isEmpty) profiles.selectedIndex = index.coerceAtMost(model.size() - 1)
                        rebuildChoices()
                    }
                }
                .setMoveUpAction { move(-1) }.setMoveDownAction { move(1) }
                .setMoveUpActionUpdater { profiles.selectedIndex > 0 }
                .setMoveDownActionUpdater { profiles.selectedIndex in 0 until model.size() - 1 }
                .addExtraAction(duplicate).createPanel()
            browser.minimumSize = JBUI.size(150, 0)
            form.border = JBUI.Borders.empty(12)
            form.registerValidators(this)
            fun page(component: JComponent) = com.intellij.ui.components.JBScrollPane(
                JPanel(BorderLayout()).apply { add(component, BorderLayout.NORTH) }
            ).apply {
                border = JBUI.Borders.empty()
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            }
            listOf("assistant", "air").forEach { presets.clientPage(it).border = JBUI.Borders.empty(12) }
            presets.advanced.border = JBUI.Borders.empty(12)
            profileTabs.addTab("General", page(form))
            profileTabs.addTab("New AI Assistant Chat Defaults", page(presets.clientPage("assistant")))
            profileTabs.addTab("New Air Session Defaults", page(presets.clientPage("air")))
            val advanced = JPanel(BorderLayout()).apply {
                add(presets.advanced, BorderLayout.NORTH)
                add(panel {
                    group("ACP registration") {
                        row { cell(status).align(AlignX.FILL).resizableColumn() }
                        row { cell(repairRegistrationLink); cell(refreshRegistrationLink) }
                        row { comment("Repair restores AI Multi entries in acp.json. Refresh checks registration status.") }
                    }
                }.apply { border = JBUI.Borders.empty(12) }, BorderLayout.CENTER)
            }
            profileTabs.addTab("Advanced", page(advanced))
            profileTabs.addChangeListener {
                when (profileTabs.selectedIndex) { 1 -> presets.selectHost("assistant"); 2 -> presets.selectHost("air") }
            }
            cards.add(profileTabs, "editor")
            cards.add(panel {
                row { label("Add an agent profile to get started") }
                row { link("Add Profile") { add(newProfile()) } }
            }.apply { border = JBUI.Borders.empty(12) }, "empty")
            val initialization = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.CENTER, JBUI.scale(8), JBUI.scale(32))).apply {
                add(com.intellij.util.ui.AsyncProcessIcon("Profile initialization").also { Disposer.register(this@Editor, it) })
                add(JBLabel("Initializing profile… Loading agent options."))
            }
            cards.add(initialization, "initializing")
            presets.loadingChanged = { busy ->
                (cards.layout as CardLayout).show(cards, if (edited == null) "empty" else "editor")
            }
            presets.profileChanged = { updateAuthorization() }
            assignment.addActionListener {
                if (!changing) {
                    assignedId = if (assignment.isSelected) edited?.id ?: "Unassigned" else "Unassigned"
                    profiles.repaint()
                }
            }
            splitter.firstComponent = browser
            splitter.secondComponent = cards
            root = object : JPanel(BorderLayout()), Scrollable {
                override fun getPreferredScrollableViewportSize() = preferredSize
                override fun getScrollableUnitIncrement(rect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(20)
                override fun getScrollableBlockIncrement(rect: Rectangle, orientation: Int, direction: Int) = rect.height
                override fun getScrollableTracksViewportWidth() = true
                override fun getScrollableTracksViewportHeight() = true
            }.apply {
                add(splitter, BorderLayout.CENTER)
                preferredSize = JBUI.size(760, 420)
                minimumSize = Dimension(0, 0)
            }
            cards.minimumSize = Dimension(0, 0)
            form.minimumSize = Dimension(0, 0)
            splitter.minimumSize = Dimension(0, 0)
            splitter.secondComponent.minimumSize = Dimension(0, 0)
            refreshAgents(); reset(); refreshStatus()
        }

        private fun entries() = (0 until model.size()).map { model[it].snapshot() }
        private fun newProfile() = AgentProfiles.newEntry().apply {
            name = uniqueName("New Profile")
        }
        private fun uniqueName(base: String): String {
            val names = entries().map { it.name.lowercase() }.toSet()
            return generateSequence(0) { it + 1 }.map { if (it == 0) base else "$base ($it)" }.first { it.lowercase() !in names }
        }
        private fun add(entry: AgentProfiles.Entry) {
            model.addElement(entry); profiles.selectedIndex = model.size() - 1
            profiles.ensureIndexIsVisible(profiles.selectedIndex); rebuildChoices(); name.requestFocusInWindow()
        }
        private fun move(offset: Int) {
            flush()
            val index = profiles.selectedIndex
            val target = index + offset
            if (index < 0 || target !in 0 until model.size()) return
            changing = true
            val entry = model.remove(index); model.add(target, entry); profiles.selectedIndex = target
            changing = false
            profiles.ensureIndexIsVisible(target); rebuildChoices()
        }
        private fun flush() {
            val entry = edited ?: return
            entry.name = name.text.trim()
            if (entry.agentId.isNotBlank()) entry.advancedOverrides = false
        }
        private fun showProfile(entry: AgentProfiles.Entry?) {
            accountPopup?.cancel()
            changing = true; edited = entry
            val choices = (0 until agent.itemCount).map { agent.getItemAt(it) }.toMutableList()
            if (entry != null && entry.agentId.isNotBlank() && choices.none { it.id == entry.agentId }) {
                choices += InstalledAgents.Choice(entry.agentId, "${entry.agentName} (unavailable)")
                agent.model = DefaultComboBoxModel(choices.toTypedArray())
            }
            agent.selectedItem = choices.firstOrNull { it.id == entry?.agentId }
            name.text = entry?.name.orEmpty()
            (cards.layout as CardLayout).show(cards, if (entry == null) "empty" else "editor")
            changing = false
            updateAuthorization()
            rebuildChoices()
            entry?.let {
                val attention = ProfileSettingsPrompt.peek()?.takeIf { request -> request.profileId == it.id }?.message
                presets.show(it, attention)
                if (attention != null) profileTabs.selectedIndex = if (presets.selectedHost == "air") 2 else 1
            }
            checkSelectedAgent()
        }
        private fun checkSelectedAgent() {
            val revision = ++availabilityGeneration
            val selected = edited?.agentId.orEmpty()
            selectedAgentAvailable = false
            updateAuthorization()
            if (selected.isBlank() || refreshingAgents) return
            ApplicationManager.getApplication().executeOnPooledThread {
                val result = runCatching { InstalledAgents.all().any { it.id == selected } }
                ApplicationManager.getApplication().invokeLater({
                    if (!disposed && !project.isDisposed && revision == availabilityGeneration && !refreshingAgents) {
                        selectedAgentAvailable = result.getOrDefault(false)
                        agentStatus.text = when {
                            result.isFailure -> "Could not check installed agents. Click Refresh agents to retry."
                            !selectedAgentAvailable -> "This agent appears to be no longer installed. Click Refresh agents."
                            else -> ""
                        }
                        agentStatus.isVisible = agentStatus.text.isNotEmpty()
                        updateAuthorization()
                    }
                }, com.intellij.openapi.application.ModalityState.any())
            }
        }
        private fun updateAuthorization() {
            val entry = edited
            val saved = entry?.let { service<AgentProfiles>().find(it.id) }
            val signedIn = saved != null && saved.agentId == entry.agentId &&
                saved.profileHome == entry.profileHome && ProfileCredentials.hasCredentials(saved)
            authorize.isEnabled = entry?.agentId?.isNotBlank() == true && selectedAgentAvailable && !refreshingAgents
            authorize.isVisible = !signedIn
            authorize.toolTipText = "Authorize this profile with the selected agent."
            revoke.isVisible = signedIn
            revoke.isEnabled = signedIn
            revoke.toolTipText = "Remove this profile's saved authorization."
            updateAccount()
        }
        private fun updateAccount() {
            val entry = edited?.snapshot() ?: return
            val revision = ++accountGeneration
            refreshAccountLink.isEnabled = false
            refreshAccountLink.text = "Refreshing account info…"
            accountEmail.text = "Checking account…"; accountId.text = "—"
            val started = System.nanoTime()
            ApplicationManager.getApplication().executeOnPooledThread {
                val identity = ProfileAccounts.discover(entry)
                val duplicates = ProfileAccounts.duplicates(service<AgentProfiles>().all().map { it.name to ProfileAccounts.discover(it) })
                ApplicationManager.getApplication().invokeLater({
                    val remaining = (650L - (System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0).toInt()
                    Timer(remaining) {
                        if (!disposed && revision == accountGeneration && !project.isDisposed) {
                            refreshAccountLink.isEnabled = true
                            refreshAccountLink.text = "Refresh account info"
                            accountEmail.text = identity.email ?: "Account unknown"
                            accountId.text = identity.accountId ?: "—"
                            duplicateAccount.text = duplicates.joinToString(" ")
                            duplicateAccount.isVisible = duplicates.isNotEmpty()
                            accountPopup?.takeUnless { it.isDisposed }?.pack(true, true)
                        }
                    }.apply { isRepeats = false; start() }
                }, com.intellij.openapi.application.ModalityState.any())
            }
        }
        private fun showAccountInfo(anchor: JComponent) {
            accountPopup?.cancel()
            updateAccount()
            val content = panel {
                row("Account email:") { cell(accountEmail) }
                row("Account ID:") { cell(accountId) }
                row { cell(refreshAccountLink) }
                row { cell(duplicateAccount) }
            }.apply { border = JBUI.Borders.empty(12) }
            accountPopup = JBPopupFactory.getInstance().createComponentPopupBuilder(content, null)
                .setTitle("Account info — ${edited?.name.orEmpty()}")
                .setRequestFocus(true).setFocusable(true).setResizable(true).setMovable(true)
                .createPopup().also { it.showUnderneathOf(anchor) }
        }
        private fun revokeAuthorization(id: String) {
            revoke.isEnabled = false
            ProfileAuthorization.revoke(project, id) { if (!disposed) { updateAuthorization(); edited?.let { presets.show(it) } } }
        }
        private fun refreshAgents() {
            if (disposed || refreshingAgents) return
            refreshingAgents = true
            availabilityGeneration++
            selectedAgentAvailable = false
            updateAuthorization()
            refreshAgentsLink.isEnabled = false
            refreshAgentsLink.text = "Refreshing agents…"
            agentStatus.text = ""
            agentStatus.isVisible = false
            val started = System.nanoTime()
            ApplicationManager.getApplication().executeOnPooledThread {
                val result = runCatching { InstalledAgents.all() }
                ApplicationManager.getApplication().invokeLater({
                    // Keep fast local catalog reads visible for at least one readable interval.
                    // Delay presentation only; never block the EDT or the discovery worker.
                    val remaining = (650L - (System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0).toInt()
                    Timer(remaining) {
                        if (!disposed && !project.isDisposed) {
                            applyAgents(result)
                            refreshingAgents = false
                            refreshAgentsLink.text = "Refresh agents"
                            refreshAgentsLink.isEnabled = true
                            updateAuthorization()
                        }
                    }.apply { isRepeats = false; start() }
                }, com.intellij.openapi.application.ModalityState.any())
            }
        }
        private fun applyAgents(result: Result<List<InstalledAgents.Choice>>) {
            val selected = edited?.agentId
            result.exceptionOrNull()?.let { Logger.getInstance(SwitcherConfigurable::class.java).warn("Cannot load installed ACP agents", it) }
            val available = result.getOrDefault(emptyList())
            selectedAgentAvailable = available.any { it.id == selected }
            agentStatus.text = when {
                result.isFailure -> "Could not load installed agents. See the IDE log and try Refresh agents."
                !selected.isNullOrBlank() && !selectedAgentAvailable -> "This agent appears to be no longer installed. Click Refresh agents."
                available.isEmpty() -> "No installed local ACP agents found."
                else -> ""
            }
            agentStatus.isVisible = agentStatus.text.isNotEmpty()
            val wasChanging = changing; changing = true
            val choices = (listOf(InstalledAgents.Choice("", "Select Agent")) + available).toMutableList()
            edited?.let { entry ->
                if (entry.agentId.isNotBlank() && choices.none { it.id == entry.agentId }) {
                    choices += InstalledAgents.Choice(entry.agentId, "${entry.agentName} (unavailable)")
                }
            }
            agent.model = DefaultComboBoxModel(choices.toTypedArray())
            agent.selectedItem = (0 until agent.itemCount).map { agent.getItemAt(it) }.firstOrNull { it.id == selected }
            changing = wasChanging
        }
        private fun rebuildChoices() {
            val wasChanging = changing; changing = true
            assignment.isSelected = edited?.id == assignedId
            assignment.isEnabled = edited != null
            changing = wasChanging
            profiles.repaint()
        }
        private fun nameError(): String? = when {
            name.text.isBlank() -> "Enter a profile name"
            entries().any { it.id != edited?.id && it.name.equals(name.text.trim(), true) } -> "A profile with this name already exists"
            else -> null
        }
        fun isModified(): Boolean { flush(); return entries() != baseline || assignedId != baselineAssignment }
        fun save() {
            if (presets.isLoading()) throw ConfigurationException("Wait for agent model loading to finish before applying settings.")
            flush()
            for (entry in entries()) {
                for (host in listOf("assistant", "air")) {
                    // Apply commits all profiles, but unrelated existing defaults must
                    // not prevent saving a removal or an edit in another client.
                    if (!ProfileModelDefaults.changed(entry, baseline.find { it.id == entry.id }, host)) continue
                    val defaults = ProfileModelDefaults.get(entry, host) ?: continue
                    val problems = ProfileModelDefaults.problems(defaults, entry.id) +
                        ProfileSettingsPrompt.failure(entry.id, host)?.issues.orEmpty().filter { issue ->
                            if (issue.configId == defaults.modelOptionId) defaults.defaultModelId == issue.value
                            else defaults.models.any { m -> m.enabled && m.modelId == issue.modelId && m.settings.any { s -> s.id == issue.configId && s.value == issue.value } }
                        }.map { it.reason }
                    if (problems.isNotEmpty()) {
                        profiles.selectedIndex = (0 until model.size()).first { model[it].id == entry.id }
                        profileTabs.selectedIndex = if (host == "air") 2 else 1
                        throw ConfigurationException("${entry.name} / ${if (host == "air") "Air" else "AI Assistant"}: ${problems.distinct().joinToString("; ")}")
                    }
                }
                if (entry.instructionsText.toByteArray().size > 65536) throw ConfigurationException("${entry.name}: instructions exceed 64 KiB")
                if (entry.instructionsSource == "file") {
                    val valid = runCatching {
                        val path = java.nio.file.Path.of(entry.instructionsFile)
                        path.isAbsolute && entry.instructionsFile.endsWith(".md", true) && java.nio.file.Files.isRegularFile(path) && java.nio.file.Files.size(path) <= 65536
                    }.getOrDefault(false)
                    if (!valid) throw ConfigurationException("${entry.name}: choose an existing Markdown file up to 64 KiB")
                }
                if (entry.agentId.isBlank() && baseline.none { it.id == entry.id }) throw ConfigurationException("Select an installed agent for ${entry.name}")
                val error = validateProfile(entry, entries()) ?: continue
                profiles.selectedIndex = (0 until model.size()).first { model[it].id == entry.id }
                throw ConfigurationException("${entry.name.ifBlank { "Unnamed profile" }}: $error")
            }
            if (assignedId != "Unassigned" && entries().none { it.id == assignedId }) throw ConfigurationException("Select an existing profile for this project")
            if (service<AgentProfiles>().all() != baseline) throw ConfigurationException("Profiles changed in another Settings window. Reset this page before applying.")
            if (project.service<ProjectSelection>().selected != baselineAssignment) throw ConfigurationException("This project's assignment changed while Settings was open. Reset this page before applying.")
            GenericAutoRegistration.saveProfiles(project, entries(), assignedId, baseline, baselineAssignment)
            entries().forEach { entry -> listOf("assistant", "air").forEach { ProfileSettingsPrompt.resolved(entry.id, it) } }
            presets.committed()
            reset()
            GenericAutoRegistration.ensureAsync(project); refreshStatus()
        }
        fun reset() {
            val prompt = ProfileSettingsPrompt.peek()
            val selected = prompt?.profileId ?: edited?.id
            if (prompt != null && service<AgentProfiles>().find(prompt.profileId) == null) ProfileSettingsPrompt.clear()
            changing = true
            baseline = service<AgentProfiles>().all(); baselineAssignment = project.service<ProjectSelection>().selected
            assignedId = baselineAssignment
            model.clear(); baseline.forEach { model.addElement(it.snapshot()) }
            profiles.selectedIndex = baseline.indexOfFirst { it.id == selected }.takeIf { it >= 0 } ?: if (baseline.isEmpty()) -1 else 0
            showProfile(profiles.selectedValue); rebuildChoices(); changing = false
        }
        private fun refreshStatus() {
            if (disposed || registrationBusy) return
            registrationBusy = true
            setRegistrationBusy("Refreshing registration…", false)
            val started = System.nanoTime()
            ApplicationManager.getApplication().executeOnPooledThread {
                val text = GenericAutoRegistration.registrationStatus()
                ApplicationManager.getApplication().invokeLater({
                    finishRegistration(started, text)
                }, com.intellij.openapi.application.ModalityState.any())
            }
        }
        private fun repairRegistration() {
            if (disposed || registrationBusy) return
            registrationBusy = true
            setRegistrationBusy("Repairing registration…", true)
            val started = System.nanoTime()
            GenericAutoRegistration.repair(project) { result ->
                ApplicationManager.getApplication().executeOnPooledThread {
                    val text = when {
                        result.isFailure -> "Could not repair registration. See the IDE log and retry."
                        result.getOrNull() != true -> "Repair cancelled. ${GenericAutoRegistration.registrationStatus()}"
                        else -> GenericAutoRegistration.registrationStatus()
                    }
                    ApplicationManager.getApplication().invokeLater({ finishRegistration(started, text) },
                        com.intellij.openapi.application.ModalityState.any())
                }
            }
        }
        private fun setRegistrationBusy(text: String, repairing: Boolean) {
            status.text = text
            repairRegistrationLink.isEnabled = false
            refreshRegistrationLink.isEnabled = false
            repairRegistrationLink.text = if (repairing) "Repairing registration…" else "Repair Registration…"
            refreshRegistrationLink.text = if (repairing) "Refresh" else "Refreshing…"
        }
        private fun finishRegistration(started: Long, text: String) {
            val remaining = (650L - (System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0).toInt()
            Timer(remaining) {
                if (!disposed && !project.isDisposed) {
                    status.text = text
                    registrationBusy = false
                    repairRegistrationLink.text = "Repair Registration…"
                    refreshRegistrationLink.text = "Refresh"
                    repairRegistrationLink.isEnabled = true
                    refreshRegistrationLink.isEnabled = true
                }
            }.apply { isRepeats = false; start() }
        }
        override fun dispose() {
            disposed = true
            if (ProfileSettingsPrompt.onShown != null) ProfileSettingsPrompt.onShown = null
            accountGeneration++; accountPopup?.cancel(); presets.dispose(); splitter.dispose()
        }
    }
}
