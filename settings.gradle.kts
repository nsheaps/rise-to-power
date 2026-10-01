pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Google's mirror of Maven Central, used as a fallback when Central rate-limits.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
    }
}
rootProject.name = "rise-to-power"
include(":core", ":app")
