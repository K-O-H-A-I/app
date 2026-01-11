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
    }
}

rootProject.name = "sitta-app"

include(
    ":app",
    ":core:common",
    ":core:data",
    ":core:domain",
    ":core:vision",
    ":feature:track_a",
    ":feature:track_b",
    ":feature:track_c",
    ":feature:track_d",
)
