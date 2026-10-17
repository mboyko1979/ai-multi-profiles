plugins {
    id("org.jetbrains.intellij.platform") version "2.19.0"
    kotlin("jvm") version "2.4.0"
}

group = "dev.boyko.aiagentswitcher"
version = "0.2.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        val installedIde = providers.gradleProperty("idePath").orNull
        if (installedIde != null) local(installedIde) else intellijIdea("2026.2.3")
        val localAi = providers.gradleProperty("aiAssistantPath").orNull
        if (localAi != null) localPlugin(localAi)
        else plugin("com.intellij.ml.llm:262.10968.223")
        providers.gradleProperty("airPluginPath").orNull?.let { localPlugin(it) }
    }
}

kotlin { jvmToolchain(21) }

intellijPlatform {
    providers.gradleProperty("verificationSandbox").orNull?.let { sandboxContainer.set(file(it)) }
    // Kotlin-only sources and UI DSL; no .form bytecode instrumentation is needed.
    instrumentCode.set(false)
    pluginConfiguration {
        // Floor is the tested IDEA 2026.2 build. No until-build: 2024.3+ plugins stay installable on newer IDEs.
        ideaVersion {
            sinceBuild = "262.10968"
            untilBuild = provider { null }
        }
        changeNotes = """
            <ul>
            <li>First beta of AI Multi Profiles. Several ACP accounts and agents, a per-project Default Profile, and switching inside one AI Multi chat.</li>
            <li>Requires IntelliJ IDEA 2026.2 build 262.10968 or newer, with AI Assistant or Air.</li>
            </ul>
        """.trimIndent()
    }
    publishing {
        channels = listOf("beta")
    }
}

val routerTest = tasks.register<Exec>("routerTest") {
    group = "verification"
    description = "Run ACP routing, ownership and failure regression tests"
    commandLine(providers.gradleProperty("nodeExecutable").getOrElse("node"), "router/test-router.js")
}
val clientPreferencesTest = tasks.register<Exec>("clientPreferencesTest") {
    group = "verification"
    commandLine(providers.gradleProperty("nodeExecutable").getOrElse("node"), "router/test-client-preferences.js")
}
tasks.named("check") { dependsOn(clientPreferencesTest) }
tasks.named("buildPlugin") { dependsOn(clientPreferencesTest) }
val liveModelFiltersTest = tasks.register<Exec>("liveModelFiltersTest") {
    group = "verification"
    commandLine(providers.gradleProperty("nodeExecutable").getOrElse("node"), "router/test-live-model-filters.js")
}
tasks.named("check") { dependsOn(liveModelFiltersTest) }
tasks.named("buildPlugin") { dependsOn(liveModelFiltersTest) }
val profileSetupTest = tasks.register<Exec>("profileSetupTest") {
    group = "verification"
    commandLine(providers.gradleProperty("nodeExecutable").getOrElse("node"), "router/test-profile-setup.js")
}
val hostProfilesTest = tasks.register<Exec>("hostProfilesTest") {
    group = "verification"
    commandLine(providers.gradleProperty("nodeExecutable").getOrElse("node"), "router/test-host-profiles.js")
}
tasks.named("check") { dependsOn(hostProfilesTest) }
tasks.named("buildPlugin") { dependsOn(hostProfilesTest) }
tasks.named("check") { dependsOn(profileSetupTest) }
tasks.named("buildPlugin") { dependsOn(profileSetupTest) }
tasks.named("check") { dependsOn(routerTest) }
tasks.named("buildPlugin") { dependsOn(routerTest) }

val processLifecycleTest = tasks.register<Exec>("processLifecycleTest") {
    group = "verification"
    description = "Verify owned ACP tree cleanup, temporary discovery sessions and bounded profile switches"
    commandLine(providers.gradleProperty("nodeExecutable").getOrElse("node"), "router/test-process-lifecycle.js")
}
tasks.named("check") { dependsOn(processLifecycleTest) }
tasks.named("buildPlugin") { dependsOn(processLifecycleTest) }

val profileSettingsTest = tasks.register<Exec>("profileSettingsTest") {
    group = "verification"
    commandLine(providers.gradleProperty("nodeExecutable").getOrElse("node"), "router/test-profile-settings.js")
}
val automaticDefaultsTest = tasks.register<Exec>("automaticDefaultsTest") {
    group = "verification"
    commandLine(providers.gradleProperty("nodeExecutable").getOrElse("node"), "router/test-automatic-defaults.js")
}
tasks.named("check") { dependsOn(automaticDefaultsTest) }
tasks.named("buildPlugin") { dependsOn(automaticDefaultsTest) }
tasks.named("check") { dependsOn(profileSettingsTest) }
tasks.named("buildPlugin") { dependsOn(profileSettingsTest) }

val virtualSessionsTest = tasks.register<Exec>("virtualSessionsTest") {
    group = "verification"
    description = "Verify virtual ACP conversations and provider handoff with mock backends"
    commandLine(providers.gradleProperty("nodeExecutable").getOrElse("node"), "router/test-virtual-sessions.js")
}
tasks.named("check") { dependsOn(virtualSessionsTest) }
tasks.named("buildPlugin") { dependsOn(virtualSessionsTest) }

val transparentProxyTest = tasks.register<Exec>("transparentProxyTest") {
    group = "verification"
    description = "Verify transparent ACP forwarding and consented recovery after a profile agent changes"
    commandLine(providers.gradleProperty("nodeExecutable").getOrElse("node"), "router/test-transparent-proxy.js")
}
tasks.named("check") { dependsOn(transparentProxyTest) }
tasks.named("buildPlugin") { dependsOn(transparentProxyTest) }

val restoreDefaultClientsTest = tasks.register<Exec>("restoreDefaultClientsTest") {
    group = "verification"
    commandLine(providers.gradleProperty("nodeExecutable").getOrElse("node"), "router/test-restore-default-clients.js")
}
tasks.named("check") { dependsOn(restoreDefaultClientsTest) }
tasks.named("buildPlugin") { dependsOn(restoreDefaultClientsTest) }

val conversationStorageTest = tasks.register<Exec>("conversationStorageTest") {
    group = "verification"
    description = "Verify Windows sharing violations and conversation snapshot recovery"
    commandLine(providers.gradleProperty("nodeExecutable").getOrElse("node"), "router/test-conversation-storage.js")
}
tasks.named("check") { dependsOn(conversationStorageTest) }
tasks.named("buildPlugin") { dependsOn(conversationStorageTest) }

val credentialsVerification = sourceSets.create("verification")
val profilePresetsTest = tasks.register<JavaExec>("profilePresetsTest") {
    group = "verification"
    dependsOn(tasks.named(credentialsVerification.classesTaskName), tasks.named("classes"))
    classpath = credentialsVerification.runtimeClasspath + sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
    mainClass.set("dev.boyko.aiagentswitcher.ProfilePresetsChecks")
    // XML serialization loads IDEA's Java 25 platform interfaces; use the matching IDE runtime.
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
}
tasks.named("check") { dependsOn(profilePresetsTest) }
tasks.named("buildPlugin") { dependsOn(profilePresetsTest) }
val profileCredentialsTest = tasks.register<JavaExec>("profileCredentialsTest") {
    group = "verification"
    description = "Verify physical profile credential deletion and filesystem boundaries"
    dependsOn(tasks.named(credentialsVerification.classesTaskName), tasks.named("classes"))
    classpath = credentialsVerification.runtimeClasspath + sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.boyko.aiagentswitcher.ProfileCredentialsChecks")
}
tasks.named("check") { dependsOn(profileCredentialsTest) }
tasks.named("buildPlugin") { dependsOn(profileCredentialsTest) }

val registrationCleanupTest = tasks.register<JavaExec>("registrationCleanupTest") {
    group = "verification"
    description = "Verify owned ACP registration cleanup and preservation of credentials and other agents"
    dependsOn(tasks.named(credentialsVerification.classesTaskName), tasks.named("classes"))
    classpath = credentialsVerification.runtimeClasspath + sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
    mainClass.set("dev.boyko.aiagentswitcher.RegistrationCleanupChecks")
}
tasks.named("check") { dependsOn(registrationCleanupTest) }
tasks.named("buildPlugin") { dependsOn(registrationCleanupTest) }

// A sandbox must never write registration or ownership files in the user's home.
tasks.named<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask>("runIde") {
    composeHotReload.set(false)
    composeHotReloadAgentConfiguration.setFrom(emptyList<Any>())
    val sandboxHome = providers.gradleProperty("sandboxHome").orNull?.let { file(it) }
        ?: layout.buildDirectory.dir("sandbox-home").get().asFile
    systemProperty("aiaps.project.root", projectDir.absolutePath)
    systemProperty("aiaps.home", sandboxHome.absolutePath)
    systemProperty("user.home", sandboxHome.absolutePath)
    providers.gradleProperty("uiProbeAgent").orNull?.let { jvmArgs("-javaagent:$it") }
    providers.gradleProperty("sandboxProject").orNull?.let { args(it) }
}

val modelDefaultsTest = tasks.register<Exec>("modelDefaultsTest") {
    group = "verification"
    description = "Verify settings-only authoritative model recommendations and per-model isolation"
    commandLine(providers.gradleProperty("nodeExecutable").getOrElse("node"), "router/test-model-defaults.js")
}
tasks.named("check") { dependsOn(modelDefaultsTest) }
tasks.named("buildPlugin") { dependsOn(modelDefaultsTest) }

val concreteDefaultsTest = tasks.register<Exec>("concreteDefaultsTest") {
    group = "verification"
    commandLine(providers.gradleProperty("nodeExecutable").getOrElse("node"), "router/test-concrete-defaults.js")
}
tasks.named("check") { dependsOn(concreteDefaultsTest) }
tasks.named("buildPlugin") { dependsOn(concreteDefaultsTest) }

val concreteDefaultsPersistenceTest = tasks.register<JavaExec>("concreteDefaultsPersistenceTest") {
    group = "verification"
    dependsOn(tasks.named(credentialsVerification.classesTaskName), tasks.named("classes"))
    classpath = credentialsVerification.runtimeClasspath + sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
    mainClass.set("dev.boyko.aiagentswitcher.ConcreteDefaultsChecks")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
}
tasks.named("check") { dependsOn(concreteDefaultsPersistenceTest) }
tasks.named("buildPlugin") { dependsOn(concreteDefaultsPersistenceTest) }

// Searchable-options generation also launches an IDE. Keep plugin storage out of the real home.
tasks.named<org.jetbrains.intellij.platform.gradle.tasks.BuildSearchableOptionsTask>("buildSearchableOptions") {
    val optionsHome = layout.buildDirectory.dir("searchable-options-home").get().asFile
    systemProperty("aiaps.home", optionsHome.absolutePath)
    systemProperty("user.home", optionsHome.absolutePath)
}
