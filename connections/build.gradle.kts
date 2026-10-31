plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.stef.vocalremover.connections"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        // Contact in the User-Agent required by LRCLIB: set it with the Gradle
        // property lrclib.contact (e.g. in ~/.gradle/gradle.properties), never in the code.
        val contact = providers.gradleProperty("lrclib.contact").getOrElse("")
        buildConfigField("String", "LRCLIB_CONTACT", "\"$contact\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.all { test ->
            val tmp = layout.buildDirectory.dir("tmp/unitTest").get().asFile
            test.doFirst { tmp.mkdirs() }
            test.systemProperty("java.io.tmpdir", tmp.absolutePath)
        }
    }
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
