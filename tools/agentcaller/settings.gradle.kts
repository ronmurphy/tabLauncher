// A test caller for TabDesk's agent service (plan T9): a separate project, so TabDesk's own build never includes it.
// Build: ./gradlew -p tools/agentcaller --offline assembleDebug  (from the repo root)
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "AgentCaller"
