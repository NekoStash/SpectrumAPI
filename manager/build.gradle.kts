import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("hk.uwu.roxyhook") version "24b1267db5"
}

// Fail before reading any credential if a caller overrides the root's cache
// policy. SDK-only publication never configures this project and is unaffected.
@Suppress("DEPRECATION")
val managerConfigurationCacheRequested = gradle.startParameter.isConfigurationCacheRequested
if (managerConfigurationCacheRequested) {
    throw GradleException("Manager builds require --no-configuration-cache so signing credentials are never serialized")
}

// Environment overrides the ignored local file, including an explicitly empty
// environment value (which must fail, not silently select a different identity).
val localSigning = Properties().apply {
    val source = rootProject.file("signing.properties")
    if (source.isFile) source.reader(Charsets.UTF_8).use { load(it) }
}
fun signingValue(name: String): String? = providers.environmentVariable(name).orNull
    ?: localSigning.getProperty(name)
require(!providers.environmentVariable("RELEASE_STORE_FILE").isPresent &&
    !providers.environmentVariable("SPECTRUM_SIGNING_TEST_MODE").isPresent) {
    "Signing overrides are unsupported; use the root production release-key.jks"
}
val releaseStore = rootProject.file("release-key.jks")
val releaseStorePassword = signingValue("RELEASE_STORE_PASSWORD")
val releaseKeyAlias = signingValue("RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingValue("RELEASE_KEY_PASSWORD")

// Deliberately execution-time: Debug, Release lint/R8 intermediates and library
// publishing remain usable without a production private key. Packaging cannot.
val validateReleaseIdentity = tasks.register("validateReleaseIdentity") {
    group = "verification"
    description = "Fail closed unless the root production signing identity is usable"
    outputs.upToDateWhen { false }
    doLast {
        val values = mapOf(
            "RELEASE_STORE_PASSWORD" to releaseStorePassword,
            "RELEASE_KEY_ALIAS" to releaseKeyAlias,
            "RELEASE_KEY_PASSWORD" to releaseKeyPassword,
        )
        val missing = values.filterValues { it.isNullOrBlank() }.keys
        if (missing.isNotEmpty()) throw GradleException("Release signing requires: ${missing.joinToString()}")
        if (!releaseStore.isFile || Files.isSymbolicLink(releaseStore.toPath())) {
            throw GradleException("Release signing requires the regular root release-key.jks; no unsigned/Debug fallback")
        }
        try {
            val store = KeyStore.getInstance("JKS")
            releaseStore.inputStream().use { store.load(it, releaseStorePassword!!.toCharArray()) }
            val key = store.getKey(releaseKeyAlias, releaseKeyPassword!!.toCharArray()) as? PrivateKey
                ?: throw IllegalStateException()
            val certificate = store.getCertificate(releaseKeyAlias) ?: throw IllegalStateException()
            val pinned = providers.environmentVariable("RELEASE_CERT_SHA256").orNull
                ?.replace(":", "")?.lowercase().orEmpty()
            if (pinned.isNotEmpty()) {
                val actual = MessageDigest.getInstance("SHA-256")
                    .digest(certificate.encoded).joinToString("") { "%02x".format(it) }
                check(pinned.matches(Regex("[a-f0-9]{64}")) && actual == pinned)
            }
            val algorithm = when (key.algorithm) {
                "RSA" -> "SHA256withRSA"
                "EC" -> "SHA256withECDSA"
                "DSA" -> "SHA256withDSA"
                else -> throw IllegalStateException()
            }
            val probe = "SpectrumAPI release signing identity check".toByteArray(Charsets.UTF_8)
            val signature = Signature.getInstance(algorithm)
            signature.initSign(key)
            signature.update(probe)
            val signed = signature.sign()
            signature.initVerify(certificate)
            signature.update(probe)
            check(signature.verify(signed))
        } catch (_: Exception) {
            // Never include provider exceptions, aliases, passwords or private-key data.
            throw GradleException("Release signing identity validation failed: check release-key.jks and all three credentials")
        }
    }
}

roxy {
    version.set("24b1267db5")
    autoDependencies.set(false)
    minApiVersion.set(102)
    targetApiVersion.set(102)
    staticScope.set(true)
    autoHotReload.set(false)
    scope.set(listOf("com.android.systemui"))
    entryPackage.set("hk.uwu.spectrumapi.manager.hook")
}

android {
    namespace = "hk.uwu.spectrumapi.manager"
    enableKotlin = true
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    signingConfigs {
        create("production") {
            storeFile = releaseStore
            storePassword = releaseStorePassword.orEmpty()
            keyAlias = releaseKeyAlias.orEmpty()
            keyPassword = releaseKeyPassword.orEmpty()
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("production")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    defaultConfig {
        applicationId = "hk.uwu.spectrumapi"
        minSdk = 33
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0"
        // Keep the existing arm64-only APK target; this filters prebuilt UI libraries,
        // not a local JNI/discovery build.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    beforeVariants(selector().all()) { variant ->
        variant.hostTests.values.forEach { it.enable = false }
        variant.deviceTests.values.forEach { it.enable = false }
    }
    onVariants(selector().all()) { variant ->
        val capital = variant.name.replaceFirstChar { it.uppercaseChar() }
        tasks.named<hk.uwu.roxyhook.gradle.ValidateRoxyEntryTask>("validate${capital}RoxyEntry") {
            // Roxy's filtered all-task collection realizes unrelated AGP IDE
            // Preview tasks. Bind only the actual KSP task; keep entry validation
            // intact without re-enabling host/device test variants for Preview.
            val kspTask = tasks.named("ksp${capital}Kotlin")
            setDependsOn(listOf(kspTask))
            generatedResources.setBuiltBy(listOf(kspTask))
        }
    }
}

val releaseType = android.buildTypes.getByName("release")
val writeReleaseBuildContract = tasks.register("writeReleaseBuildContract") {
    group = "verification"
    description = "Record the effective Release shrinker flags (no signing secrets)"
    dependsOn("minifyReleaseWithR8")
    val output = layout.buildDirectory.file("outputs/spectrum/release-build-contract.json")
    outputs.file(output)
    // Re-evaluate the effective DSL flags on every requested build.
    outputs.upToDateWhen { false }
    doLast {
        check(releaseType.isMinifyEnabled && releaseType.isShrinkResources) {
            "Release must enable both R8 minification and resource shrinking"
        }
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText("""{"variant":"release","minifyEnabled":true,"shrinkResources":true,"signingIdentityRole":"production"}""" + "\n")
        }
    }
}

// Attach to actual packaging/signing tasks, not preReleaseBuild: CI is allowed
// to execute minifyReleaseWithR8/lintRelease without packaging an unsigned APK.
tasks.configureEach {
    if (name in setOf("validateSigningRelease", "packageRelease", "packageReleaseBundle",
            "signReleaseBundle", "assembleRelease", "bundleRelease", "installRelease")) {
        dependsOn(validateReleaseIdentity)
        if (name != "validateSigningRelease") dependsOn(writeReleaseBuildContract)
        outputs.cacheIf { false }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":sdk"))
    implementation("com.github.NekoStash.RoxyHook:roxy-libxposed:24b1267db5")
    implementation("com.github.NekoStash.RoxyHook:roxy-annotations:24b1267db5")
    ksp("com.github.NekoStash.RoxyHook:roxy-ksp:24b1267db5")
    compileOnly("io.github.libxposed:api:102.0.0")

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.4")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
