plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
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
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    }

    // Exclude conflicting native libs (es. tra onnxruntime e altre dipendenze)
    packaging {
        jniLibs {
            pickFirsts += listOf("**/libc++_shared.so")
        }
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
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.24.3")

    // TarsosDSP rimosso: decodifica gestita con MediaExtractor/MediaCodec nativi

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Media3 / ExoPlayer
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
}
