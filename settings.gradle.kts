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
        maven("https://shoalapps.github.io/shoal-common/") {
            content { includeGroup("io.github.shoalapps.common") }
        }
    }
}

rootProject.name = "Squill"
include(":app")
include(":core")
include(":desktop")
