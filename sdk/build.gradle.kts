plugins {
    id("com.android.library")
    `maven-publish`
}

android {
    namespace = "hk.uwu.spectrumapi"
    enableKotlin = false
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    publishing {
        singleVariant("release") { withSourcesJar() }
    }

    defaultConfig {
        minSdk = 33
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":protocol"))
}

androidComponents {
    beforeVariants(selector().all()) { variant ->
        variant.hostTests.values.forEach { it.enable = false }
        variant.deviceTests.values.forEach { it.enable = false }
    }
}

// AGP creates the release component after evaluating its Android DSL.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "sdk"
                pom {
                    name.set("SpectrumAPI SDK")
                    description.set("Pure-Java Binder client for SpectrumAPI; protocol is an API dependency")
                    url.set("https://github.com/NekoStash/SpectrumAPI")
                    scm {
                        url.set("https://github.com/NekoStash/SpectrumAPI")
                        connection.set("scm:git:https://github.com/NekoStash/SpectrumAPI.git")
                    }
                    licenses {
                        license {
                            name.set("GNU General Public License v3.0 only")
                            url.set("https://www.gnu.org/licenses/gpl-3.0.html")
                            distribution.set("repo")
                        }
                    }
                }
            }
        }
    }
}
