import org.gradle.api.tasks.Copy
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val remoteBrokerUrl = providers.gradleProperty("ossmRemoteBrokerUrl")
    .orElse("https://ossm-remote-2.ossm-remote.workers.dev")
    .get()

tasks.register<Copy>("assembleNamedDebugApk") {
    dependsOn("assembleDebug")
    val debugVersionName = android.defaultConfig.versionName ?: "dev"
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(layout.buildDirectory.dir("outputs/named-apk"))
    rename { "OSSM-Remote-v$debugVersionName-debug.apk" }
}

android {
    namespace = "com.ossm.remote"
    compileSdk = 35
    // build-tools/35.0.0 on this PC is a Linux copy (no aapt.exe); AGP 8.9 accepts 36.0.0.
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.ossm.remote"
        minSdk = 26
        targetSdk = 35
        versionCode = 190
        versionName = "2.3.42"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Voix 2.3.27: the on-device models (sherpa-onnx, LiteRT-LM) only target the S25 Ultra.
        ndk {
            abiFilters += "arm64-v8a"
        }
        resValue("string", "app_name", "OSSM V$versionName")
        buildConfigField("String", "REMOTE_BROKER_BASE_URL", "\"$remoteBrokerUrl\"")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // Uncompressed, page-aligned native libraries (16 KB pages).
            useLegacyPackaging = false
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

// LiteRT-LM 0.18 brings kotlin-stdlib/coroutines compiled with Kotlin 2.4: Hilt 2.58's processor
// must be able to read their metadata (kotlin-metadata-jvm 2.3.x reads up to 2.4).
configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin" && requested.name == "kotlin-metadata-jvm") {
            useVersion("2.3.21")
            because("Read Kotlin 2.4 metadata from LiteRT-LM dependencies")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Low-latency USB host transport for the OSSM CP210x bridge.
    implementation(libs.usb.serial)
    implementation(libs.okhttp)

    // Voix 2.3.27: on-device speech (sherpa-onnx, local AAR) and LLM (LiteRT-LM). No network.
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    implementation(libs.litertlm.android)

    // Local OTA 2.3.32: pairing QR generated and scanned on the phone (ZXing, Apache 2.0, no Google services).
    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Gson (JSON parsing)
    implementation(libs.gson)

    // Tests
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

