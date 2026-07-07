plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.nosmaidetectiondemo"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.nosmaidetectiondemo"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The Nosmai SDK ships arm64-v8a only, so keep just that ABI. This drops
        // LiteRT's unusable x86 / x86_64 / armeabi-v7a .so (~22 MB) — the app can't
        // run on those anyway without the SDK's native lib.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    // Nosmai Detection SDK — prebuilt AAR (libs/nosmai-detection.aar): bundles the
    // native libnosmai_jni.so (NCNN statically linked) + libonnxruntime.so + model
    // assets. Visual inference is fully native (NCNN/Vulkan) — no Java ML runtime
    // dependency. To build the SDK from source instead, restore include(":sdk") in
    // settings.gradle.kts and swap this line back to implementation(project(":sdk")).
    implementation(files("libs/nosmai-detection.aar"))
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    // Agora RTC (live streaming). The frame observer taps captured frames and
    // forwards them to the Nosmai SDK for moderation.
    implementation("io.agora.rtc:full-sdk:4.5.2")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}