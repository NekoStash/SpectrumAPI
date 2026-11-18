buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP built-in Kotlin must match the optional app's Compose compiler.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    id("com.android.library") version "9.5.1" apply false
    id("com.android.application") version "9.5.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}

// JitPack's VERSION is a tag/commit; an explicit -Pversion always wins.
group = "com.github.NekoStash.SpectrumAPI"
version = providers.gradleProperty("version")
    .orElse(providers.environmentVariable("VERSION"))
    .getOrElse("0.1.0-SNAPSHOT")
require(version.toString().matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]*"))) {
    "Publication version contains unsupported characters"
}
subprojects {
    group = rootProject.group
    version = rootProject.version
}

tasks.register("publishSdkToMavenLocal") {
    group = "publishing"
    description = "Publish only SpectrumAPI sdk and protocol (never Manager or native code)"
    dependsOn(":protocol:publishReleasePublicationToMavenLocal", ":sdk:publishReleasePublicationToMavenLocal")
}
