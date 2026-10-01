import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// sherpa-onnx (the on-device speech runtime) publishes its Android AAR on GitHub releases only,
// not on Maven, and at 50 MB it does not belong in git. Fetch it once, at configuration time,
// so a fresh clone builds with a plain `./gradlew assembleDebug`.
val sherpaVersion: String = libs.versions.sherpaOnnx.get()
val sherpaAar = layout.projectDirectory.file("libs/sherpa-onnx-$sherpaVersion.aar").asFile
if (!sherpaAar.exists()) {
    sherpaAar.parentFile.mkdirs()
    val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar"
    logger.lifecycle("Downloading $url")
    val tmp = File(sherpaAar.parentFile, sherpaAar.name + ".part")
    URI(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
    tmp.renameTo(sherpaAar)
}

android {
    namespace = "app.sotto"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.sotto"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // Every phone this targets is 64-bit ARM; one ABI keeps the APK ~30 MB instead of ~110 MB.
        // Pass -Pabis=arm64-v8a,x86_64 to also run on an x86 emulator.
        ndk {
            abiFilters += ((project.findProperty("abis") as String?) ?: "arm64-v8a").split(",")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
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
        // The AAR also ships its C and C++ API libraries; the Kotlin bindings only load the JNI one.
        jniLibs.excludes += listOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
        resources.excludes += listOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }

    testOptions.unitTests.isReturnDefaultValues = true
}

dependencies {
    implementation(files(sherpaAar))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.media3.exoplayer)
    // The media session: lock screen and notification controls, playback with the app in the background.
    implementation(libs.androidx.media3.session)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // Unpacks the .tar.bz2 model archives sherpa-onnx publishes.
    implementation(libs.commons.compress)

    testImplementation(libs.junit)
}
