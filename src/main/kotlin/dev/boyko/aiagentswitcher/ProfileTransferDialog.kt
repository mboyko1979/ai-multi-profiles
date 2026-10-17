package dev.boyko.aiagentswitcher

import com.google.gson.JsonObject
import com.google.gson.JsonArray
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.ui.JBUI
import com.intellij.ui.dsl.builder.*
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.UIManager
import javax.swing.JPanel
import javax.swing.BoxLayout
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import kotlin.math.abs

import dev.boyko.aiagentswitcher.compat.CompactProfileSlider

internal class ProfileTransferDialog(project: com.intellij.openapi.project.Project, private val from: String, private val to: String, private val estimates: Map<Int, JsonObject>, private val legacy: Boolean, private val recovery: Boolean = false, private val recoveryDirectory: String? = null, private val deletedProfile: Boolean = false, private val disconnected: Boolean = false) : DialogWrapper(project) {
    private val continueChat = JBRadioButton("Continue this chat and share history", true)
    private val startNewChat = JBRadioButton("Start a new chat without sharing history")
    private val available = (estimates.getValue(20).get("availableMessages")?.asInt ?: 0).coerceAtMost(20)
    private val initialCount = minOf(5, available)
    private val history = CompactProfileSlider(0, available, initialCount).apply {
        preferredSize = JBUI.size(180, 24)
    }
    private val historyCount = JSpinner(SpinnerNumberModel(initialCount, 0, available, 1))
    private val usageLabel = JBLabel("None")
    private val usage = CompactProfileSlider(0, 4, 0).apply {
        preferredSize = JBUI.size(180, 24)
    }
    private val attachmentChoices = (estimates.getValue(20).getAsJsonArray("attachments") ?: JsonArray()).associate { item ->
        val a = item.asJsonObject
        val label = StringUtil.escapeXmlEntities(a.get("name").asString)
        val origin = "${if (a.get("author").asString == "user") "You" else "AI"} · exchange ${a.get("exchange").asInt} · ${a.get("type").asString}"
        val supported = a.get("supported").asBoolean
        a.get("id").asString to JBCheckBox("<html><body style='width:300px'>$label<br>${StringUtil.escapeXmlEntities(origin)}" +
            (if (supported) "" else "<br>${StringUtil.escapeXmlEntities(a.get("reason").asString)}") + "</body></html>").apply {
            isSelected = supported; isEnabled = supported
            val details = buildList {
                add(a.get("name").asString)
                if (a.get("originalNameKnown")?.asBoolean == false) add("IDE supplied a temporary filename; original name unavailable")
                a.get("sourceName")?.asString?.takeIf { it.isNotBlank() && it != a.get("name").asString }?.let { add(it) }
                a.get("uri")?.asString?.takeIf { it.isNotBlank() }?.let { add(it) }
                if (!supported) add(a.get("reason").asString)
            }
            toolTipText = "<html>" + details.joinToString("<br>") { StringUtil.escapeXmlEntities(it) } + "</html>"
        }
    }
    private val attachmentList = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false }
    private val attachmentScroll = JBScrollPane(attachmentList).apply { preferredSize = JBUI.size(410, 130) }
    private var attachmentGroup: CollapsibleRow? = null
    private var updating = false
    private val instructions = JBTextArea(3, 40).apply {
        font = UIManager.getFont("TextField.font")
        lineWrap = true
        wrapStyleWord = true
    }
    private val consent = JBCheckBox("<html>Share selected history and project data<br>with the target provider</html>")
    private val volume = JBLabel()
    val newChat get() = startNewChat.isSelected
    val count: Int get() = history.value
    val comment get() = instructions.text.trim()
    val attachmentIds get() = JsonArray().apply {
        currentAttachments(count).forEach { a -> if (attachmentChoices[a.get("id").asString]?.isSelected == true && a.get("supported").asBoolean) add(a.get("id").asString) }
    }
    private fun currentAttachments(count: Int) = (estimates.getValue(count).getAsJsonArray("attachments") ?: JsonArray()).map { it.asJsonObject }
    init {
        if (legacy) { continueChat.isSelected = false; startNewChat.isSelected = true; continueChat.isEnabled = false }
        title = if (disconnected) "Switch AI Profile" else if (deletedProfile) "Continue with Another Profile" else if (recovery) "Agent Changed in Profile" else "Switch AI Profile"
        setOKButtonText(if (recovery) "Start New Agent Session" else "Switch Profile")
        if (recovery) {
            continueChat.text = "Start a new agent session and share selected history"
            startNewChat.text = "Start a new agent session without sharing history"
        }
        init()
        continueChat.addActionListener { refresh() }; startNewChat.addActionListener { refresh() }
        history.addChangeListener { if (!updating) refresh() }
        historyCount.addChangeListener {
            if (!updating) { history.setValueWithoutEvents(historyCount.value as Int); refresh() }
        }
        usage.addChangeListener {
            if (!updating) {
                val target = usage.value
                val targetRatio = if (target == 4) maxOf(0.875, ratio(available)) else listOf(0.0, 0.125, 0.375, 0.625)[target]
                val nearest = if (target == 0) 0 else (1..available).minByOrNull { abs(ratio(it) - targetRatio) } ?: 0
                history.setValueWithoutEvents(nearest)
                refresh()
            }
        }
        consent.addActionListener { refresh() }
        attachmentChoices.values.forEach { box -> box.addActionListener { refresh() } }
        instructions.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = refresh()
            override fun removeUpdate(e: DocumentEvent) = refresh()
            override fun changedUpdate(e: DocumentEvent) = refresh()
        })
        refresh()
    }
    fun disableSeparateChat() { startNewChat.isVisible = false }
    private fun ratio(count: Int): Double {
        val e = estimates.getValue(count)
        val excludedBytes = currentAttachments(count).filter { it.get("supported").asBoolean && attachmentChoices[it.get("id").asString]?.isSelected != true }.sumOf { it.get("textBytes").asInt }
        return (e.get("estimatedTokens").asInt.toDouble() - excludedBytes / 3.0 + kotlin.math.ceil(comment.toByteArray(Charsets.UTF_8).size / 3.0)).coerceAtLeast(0.0) / e.get("budget").asInt.coerceAtLeast(1)
    }
    private fun refresh(syncUsage: Boolean = true) {
        history.isEnabled = !newChat; historyCount.isEnabled = !newChat; usage.isEnabled = !newChat
        instructions.isEnabled = !newChat; consent.isEnabled = !newChat
        val e = estimates.getValue(count)
        val attachments = if (newChat) emptyList() else currentAttachments(count)
        attachmentChoices.values.forEach { it.isEnabled = false }
        attachmentList.removeAll()
        attachments.forEach { a -> attachmentChoices[a.get("id").asString]?.let { box ->
            box.isEnabled = !newChat && a.get("supported").asBoolean
            box.border = JBUI.Borders.empty(3, 0)
            attachmentList.add(box)
        } }
        attachmentList.revalidate(); attachmentList.repaint()
        attachmentGroup?.setTitle("Attachments: ${attachmentIds.size().takeUnless { newChat } ?: 0} of ${attachments.size} selected")
        updating = true
        historyCount.value = count
        if (syncUsage) usage.setValueWithoutEvents(if (newChat || (count == 0 && comment.isEmpty())) 0 else when {
            ratio(count) <= 0.25 -> 1
            ratio(count) <= 0.5 -> 2
            ratio(count) <= 0.75 -> 3
            else -> 4
        })
        usageLabel.text = listOf("None", "Low", "Medium", "High", "Extra High")[usage.value]
        updating = false
        volume.text = if (newChat) "No history transfer. Project default stays unchanged."
        else if (count == 0) "No history will be transferred."
        else "Share ${e.get("messages").asInt} of ${e.get("availableMessages").asInt} user messages; ${e.get("shortenedReplies")?.asInt ?: 0} replies compacted."
        if (!newChat) volume.text += " Estimated text context: ${kotlin.math.ceil(ratio(count) * 100).toInt()}%."
        val validation = doValidate()
        setErrorInfoAll(listOfNotNull(validation))
        setOKActionEnabled(validation == null)
    }
    override fun continuousValidation() = true
    override fun createCenterPanel(): JComponent = panel {
        row { comment(if (disconnected) "The previous agent session is disconnected. Choose whether to share selected history with the selected profile's agent."
            else if (deletedProfile) "The original profile was deleted. Choose whether to share selected history with this profile's agent."
            else "The agent in this profile has changed. Its old session cannot be restored automatically. Choose whether to share recent messages with the new agent.") }.visible(recovery)
        row("From:") { label(from) }
        row("To:") { label(to) }
        row("Working directory:") { label(recoveryDirectory.orEmpty()) }.visible(recovery && recoveryDirectory != null)
        buttonsGroup {
            row { cell(continueChat).align(AlignX.FILL).resizableColumn() }
            row { cell(startNewChat).align(AlignX.FILL).resizableColumn() }
        }
        row("User messages:") { cell(history); cell(historyCount) }
        row("Token consumption:") { cell(usage); cell(usageLabel) }
        row { cell(volume) }
        row { comment("User messages and the first and final AI responses are kept. Intermediate updates, thoughts and raw tool output are omitted.") }
        collapsibleGroup("Attachments") {
            row { cell(attachmentScroll).align(AlignX.FILL).resizableColumn() }
            row {
                link("Clear all") { currentAttachments(count).forEach { attachmentChoices[it.get("id").asString]?.isSelected = false }; refresh() }
                link("Select available") { currentAttachments(count).forEach { attachmentChoices[it.get("id").asString]?.isSelected = it.get("supported").asBoolean }; refresh() }
            }
        }.apply { expanded = false; packWindowHeight = true; attachmentGroup = this }
        row { comment(if (recovery) "Recovery shares the text retained in this client's local history. Attachments are not included."
            else "Available ACP attachment types are included by default. Expand the list to remove files. Some files may not be supported by the target agent.") }
        row("Additional instructions:") { scrollCell(instructions).align(AlignX.FILL).resizableColumn() }
        row { cell(consent) }
        row { comment("This older chat has no AI Multi transcript. Start a New Chat to use the chosen profile.") }.visible(legacy)
    }
    override fun doValidate(): ValidationInfo? {
        if (newChat) return null
        if (!consent.isSelected) return ValidationInfo("Confirm sharing the selected history with the target provider", consent)
        if (ratio(count) > 1.0) return ValidationInfo("The prompt is too large. Share fewer history messages or shorten additional instructions.", history)
        return null
    }
}
