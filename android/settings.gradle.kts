pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "Kuber"
include(":app")
include(":core-model")
include(":core-market")
include(":core-risk")
include(":core-agents")
include(":core-broker")
include(":core-paper")
include(":core-execution")
