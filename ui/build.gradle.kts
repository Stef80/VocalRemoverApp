plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.gms.google-services")
}

android {
    namespace = "com.example.vocalremover.ui"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.stef.vocalremover"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk { abiFilters += "arm64-v8a" }
    }

    // Benchmark helpers shared with :engine's JVM tests: they never ship in the app APK.
    sourceSets {
        getByName("androidTest").java.srcDir("../engine/src/benchmarkShared/java")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.all { test ->
            // Robolectric downloads android-all (~200 MB) through java.io.tmpdir:
            // a small /tmp runs out of space, so a folder under build/ is used.
            val tmp = layout.buildDirectory.dir("tmp/unitTest").get().asFile
            test.doFirst { tmp.mkdirs() }
            test.systemProperty("java.io.tmpdir", tmp.absolutePath)
        }
    }

    androidResources {
        // The app loads vocal_remover_fp32.onnx; vocal_remover.onnx in :engine's assets is only an
        // input for convert_fp16.py. The leading entries are AGP's default ignore pattern.
        ignoreAssetsPattern =
            "!.svn:!.git:!.ds_store:!*.scc:.*:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*~:!vocal_remover.onnx"
    }

    // Exclude conflicting native libs (e.g. between onnxruntime and other dependencies)
    packaging {
        jniLibs {
            pickFirsts += listOf("**/libc++_shared.so")
            // QNN: libraries must be extracted to disk so that the DSP can load the Skels.
            useLegacyPackaging = true
            // Unused QNN backends (DSP, GPU): only the HTP backend is used.
            excludes += listOf("**/libQnnDsp*.so", "**/libQnnGpu*.so")
        }
    }
}

dependencies {
    implementation(project(":engine"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.savedstate)

    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))

    testImplementation("junit:junit:4.13.2")
    // Tests only: Robolectric for ImportViewModel's Uris, coroutines-test for the Main dispatcher
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    // On-device benchmark (BackendBenchmarkTest): runnable locally or on Firebase Test Lab
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
