plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.google.gms.google-services")
}

android {
    namespace = "com.example.vocalremover"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.vocalremover"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Codice del benchmark condiviso tra test JVM e androidTest: non finisce nell'APK dell'app.
    sourceSets {
        getByName("test").java.srcDir("src/benchmarkShared/java")
        getByName("androidTest").java.srcDir("src/benchmarkShared/java")
    }

    flavorDimensions += "backend"
    productFlavors {
        create("cpu") {
            dimension = "backend"
            applicationIdSuffix = ".cpu"
            buildConfigField("String", "EXECUTION_BACKEND", "\"cpu\"")
        }
        create("webgpu") {
            dimension = "backend"
            applicationIdSuffix = ".webgpu"
            buildConfigField("String", "EXECUTION_BACKEND", "\"webgpu\"")
        }
        create("qnn") {
            dimension = "backend"
            applicationIdSuffix = ".qnn"
            buildConfigField("String", "EXECUTION_BACKEND", "\"qnn\"")
        }
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
        viewBinding = true
        buildConfig = true
    }

    // Exclude conflicting native libs (es. tra onnxruntime e altre dipendenze)
    packaging {
        jniLibs {
            pickFirsts += listOf("**/libc++_shared.so")
        }
    }
}

androidComponents {
    onVariants(selector().withFlavor("backend" to "qnn")) { variant ->
        // QNN: le librerie devono essere estratte su disco perché il DSP carichi le Skel.
        variant.packaging.jniLibs.useLegacyPackaging.set(true)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // ONNX Runtime (sostituisce TensorFlow Lite: nessuna conversione TF richiesta,
    // usa modelli MDX-Net già pre-convertiti in formato .onnx dalla community UVR)
    // v1.23.0+ richiesto: allinea anche il wrapper JNI a pagine 16 KB
    // (obbligatorio per Google Play su Android 15+ dal 1° novembre 2025)
    // La variante qnn usa il pacchetto con QNN EP (stessa API Java, include la dipendenza
    // com.qualcomm.qti:qnn-runtime); i due AAR non possono convivere nello stesso APK.
    "cpuImplementation"("com.microsoft.onnxruntime:onnxruntime-android:1.24.3")
    "webgpuImplementation"("com.microsoft.onnxruntime:onnxruntime-android:1.24.3")
    "qnnImplementation"("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.24.3")


    // TarsosDSP rimosso: decodifica gestita con MediaExtractor/MediaCodec nativi

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Media3 / ExoPlayer
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    // Test unitari JVM (nessun test esisteva finora nel modulo)
    testImplementation("junit:junit:4.13.2")

    // Benchmark su dispositivo (BackendBenchmarkTest): eseguibile in locale o su Firebase Test Lab
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Import the Firebase BoM

    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))

}
