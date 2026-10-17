package dev.boyko.aiagentswitcher

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

@Service(Service.Level.APP)
@State(name = "AcpSwitcherProjectRoutes", storages = [Storage("AcpSwitcherRoutes.xml")])
class ProjectRoutes : PersistentStateComponent<ProjectRoutes.Data> {
    data class Data(var routes: MutableMap<String, String> = mutableMapOf())
    private var data = Data()
    @Synchronized override fun getState() = Data(data.routes.toMutableMap())
    @Synchronized override fun loadState(state: Data) { data = state }
    @Synchronized fun set(project: Project, profile: String, synchronize: Boolean = true) {
        val path = project.basePath ?: return
        if (profile == "Unassigned") data.routes.remove(path) else data.routes[path] = profile
        if (synchronize) GenericAutoRegistration.syncRoutesAsync(project)
    }
    @Synchronized fun current(): Map<String, String> = data.routes.toMap()
}
