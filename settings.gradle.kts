pluginManagement {
    repositories {
        google()
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
        // Custom ONNX Runtime AAR (CPU + WebGPU + QNN EPs), built by scripts/build-ort-aar.sh.
        maven {
            url = uri(settingsDir.resolve("third_party/maven"))
            content { includeGroup("com.stef.vocalremover.thirdparty") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "VocalRemoverApp"
include(":engine")
include(":ui")
include(":connections")
