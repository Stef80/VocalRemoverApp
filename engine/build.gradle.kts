plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.example.vocalremover"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    // Benchmark code shared between these JVM tests and :ui's androidTest: it never ships in the APK.
    sourceSets {
        getByName("test").java.srcDir("src/benchmarkShared/java")
    }

    flavorDimensions += "backend"
    productFlavors {
        create("cpu") {
            dimension = "backend"
            buildConfigField("String", "EXECUTION_BACKEND", "\"cpu\"")
        }
        create("webgpu") {
            dimension = "backend"
            buildConfigField("String", "EXECUTION_BACKEND", "\"webgpu\"")
        }
        create("qnn") {
            dimension = "backend"
            buildConfigField("String", "EXECUTION_BACKEND", "\"qnn\"")
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
        viewBinding = true
        buildConfig = true
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
}

dependencies {
    // External sources (song lyrics): :engine only sees interfaces and models.
    implementation(project(":connections"))
    implementation(libs.kotlinx.serialization.json)
    // Lyrics download in the background, even with the app closed.
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // ONNX Runtime (replaces TensorFlow Lite: no TF conversion needed,
    // uses MDX-Net models already converted to .onnx by the UVR community)
    // v1.23.0+ required: also aligns the JNI wrapper to 16 KB pages
    // (mandatory for Google Play on Android 15+ since November 1st, 2025)
    // The qnn variant uses the package with the QNN EP (same Java API, includes the
    // com.qualcomm.qti:qnn-runtime dependency); the two AARs cannot share one APK.
    "cpuImplementation"("com.microsoft.onnxruntime:onnxruntime-android:1.24.3")
    "webgpuImplementation"("com.microsoft.onnxruntime:onnxruntime-android:1.24.3")
    "qnnImplementation"("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.24.3")


    // TarsosDSP removed: decoding handled with native MediaExtractor/MediaCodec

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Media3 / ExoPlayer
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    // JVM unit tests (the module had no tests until now)
    testImplementation("junit:junit:4.13.2")
    // Tests only: Robolectric to create Uris on the JVM, coroutines-test for SongProcessor
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
