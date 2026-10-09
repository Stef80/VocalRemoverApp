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
        applicationId = "com.example.vocalremover"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Helper del benchmark condivisi con i test JVM di :engine: non finiscono nell'APK dell'app.
    sourceSets {
        getByName("androidTest").java.srcDir("../engine/src/benchmarkShared/java")
    }

    // Stessa dimensione/nomi dei flavor di :engine: ogni variante dell'app usa il backend omonimo.
    flavorDimensions += "backend"
    productFlavors {
        create("cpu") {
            dimension = "backend"
            applicationIdSuffix = ".cpu"
        }
        create("webgpu") {
            dimension = "backend"
            applicationIdSuffix = ".webgpu"
        }
        create("qnn") {
            dimension = "backend"
            applicationIdSuffix = ".qnn"
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
        compose = true
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
    implementation(project(":engine"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))

    // Benchmark su dispositivo (BackendBenchmarkTest): eseguibile in locale o su Firebase Test Lab
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
