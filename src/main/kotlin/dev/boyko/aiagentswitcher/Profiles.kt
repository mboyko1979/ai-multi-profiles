package dev.boyko.aiagentswitcher

import com.intellij.openapi.components.*
import java.nio.file.Path

@Service(Service.Level.PROJECT)
@State(name = "AcpSwitcherProject", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ProjectSelection : PersistentStateComponent<ProjectSelection.Data> {
    data class Data(var selected: String = "Unassigned")
    private var data = Data()
    @Synchronized override fun getState(): Data = data.copy()
    @Synchronized override fun loadState(state: Data) { data = state.copy() }
    var selected: String
        @Synchronized
        get() = data.selected
        @Synchronized
        set(value) { data.selected = value }
}

internal fun normalizedPath(value: String?): String? {
    if (value.isNullOrBlank()) return null
    return try { Path.of(value).toAbsolutePath().normalize().toString().trimEnd('\\', '/').lowercase() }
    catch (_: Exception) { value.trim().trimEnd('\\', '/').lowercase() }
}
