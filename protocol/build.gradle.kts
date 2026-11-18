plugins {
    id("com.android.library")
    `maven-publish`
}

android {
    namespace = "hk.uwu.spectrumapi.ipc"
    enableKotlin = false
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    publishing {
        singleVariant("release") { withSourcesJar() }
    }

    defaultConfig {
        minSdk = 33
    }

    buildFeatures {
        aidl = true
        buildConfig = false
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
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "protocol"
                pom {
                    name.set("SpectrumAPI protocol")
                    description.set("Pure-Java AIDL Binder protocol for SpectrumAPI")
                    licenses {
                        license {
                            name.set("GNU General Public License v3.0 only")
                            url.set("https://www.gnu.org/licenses/gpl-3.0.html")
                            distribution.set("repo")
                        }
                    }
                    url.set("https://github.com/NekoStash/SpectrumAPI")
                    scm {
                        url.set("https://github.com/NekoStash/SpectrumAPI")
                        connection.set("scm:git:https://github.com/NekoStash/SpectrumAPI.git")
                    }
                }
            }
        }
    }
}
