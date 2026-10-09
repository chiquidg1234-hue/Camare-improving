pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "LumaCam"

// Lógica pura (curvas, looks, LUTs, zoom, fusión multi-frame). Es un build independiente
// para poder compilarlo y probarlo sin Android SDK: `./gradlew -p core test`.
includeBuild("core")
include(":app")
