pluginManagement {
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "hk.uwu.roxyhook") {
                useModule("com.github.NekoStash.RoxyHook:roxy-gradle-plugin:${requested.version}")
            }
        }
    }
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") {
            content { includeGroup("com.github.NekoStash.RoxyHook") }
        }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") {
            content { includeGroup("com.github.NekoStash.RoxyHook") }
        }
    }
}

rootProject.name = "SpectrumAPI"
// SDK publication must not configure the optional app, Roxy or app signing.
val sdkOnly = providers.gradleProperty("spectrumSdkOnly").orNull == "true"
include(":protocol", ":sdk")
if (!sdkOnly) include(":manager")
