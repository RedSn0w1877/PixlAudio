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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") {
            content {
                includeGroup("com.github.FaceOnLive")
                includeGroup("com.github.philburk")
                includeGroup("com.github.racra")
                includeGroup("com.github.TeamNewPipe")
            }
        }
    }
}

rootProject.name = "PixelPlay"
include(":app")
include(":shared")
include(":wear")
include(":baselineprofile")
include(":brain")
include(":songtap")
// The Music Brain's offline evaluation and tuning tool (tools/eval/README.md). JVM only, uses :brain, never the app.
include(":eval")
project(":eval").projectDir = file("tools/eval")
