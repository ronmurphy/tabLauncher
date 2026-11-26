// A test app for the screen reader's pictures: a see-through window, to check what's under it never shows.
// Build: ./gradlew -p tools/seethrough --offline assembleDebug  (from the repo root)
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "SeeThrough"
