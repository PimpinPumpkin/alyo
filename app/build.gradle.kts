import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Files the build needs that are too big for git. Each is tried from this project's own `models`
// release first, which keeps copies so the build does not depend on another project's release
// page staying as it is, and then from where it was originally published.
val mirror = "https://github.com/PimpinPumpkin/alyo/releases/download/models"
fun fetch(target: File, upstream: String) {
    target.parentFile.mkdirs()
    val tmp = File(target.parentFile, target.name + ".part")
    val urls = listOf("$mirror/build.${upstream.substringAfterLast('/')}", upstream).distinct()
    for ((index, url) in urls.withIndex()) {
        try {
            logger.lifecycle("Downloading $url")
            URI(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            break
        } catch (e: java.io.IOException) {
            if (index == urls.lastIndex) throw e
        }
    }
    tmp.renameTo(target)
}

// sherpa-onnx (the on-device speech runtime) publishes its Android AAR on GitHub releases only,
// not on Maven, and at 50 MB it does not belong in git. Fetch it once, at configuration time,
// so a fresh clone builds with a plain `./gradlew assembleDebug`.
val sherpaVersion: String = libs.versions.sherpaOnnx.get()
val sherpaAar = layout.projectDirectory.file("libs/sherpa-onnx-$sherpaVersion.aar").asFile
if (!sherpaAar.exists()) {
    fetch(sherpaAar, "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar")
}

// ONNX Runtime, rebuilt with only the operators Alyo's models use: half the size of the one in
// the AAR, which carries every operator there is. scripts/build-onnxruntime.sh builds it, and
// the result is kept on the `models` release. It takes the place of the AAR's copy at packaging
// (see pickFirsts below). If it cannot be fetched the build still works, with the larger one.
val ortSlim = layout.projectDirectory.file("libs/onnxruntime/arm64-v8a/libonnxruntime.so").asFile
if (!ortSlim.exists()) {
    try {
        fetch(ortSlim, "$mirror/libonnxruntime-1.28.2-alyo1-arm64-v8a.so")
    } catch (e: java.io.IOException) {
        logger.warn("Trimmed ONNX Runtime not available (${e.message}); packaging the full one from the AAR.")
    }
}

android {
    namespace = "app.alyo"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.alyo"
        minSdk = 29
        targetSdk = 35
        // CI passes -PappVersionCode / -PappVersionName, derived from the workflow run number so
        // every channel sits on one rising line. Local builds stay at 1 on purpose: lower than any
        // published build, so a phone that had a dev build can always take a real one.
        versionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("appVersionName") as String?) ?: "0.1.0-dev"
        // Every phone this targets is 64-bit ARM; one ABI keeps the APK small.
        // Pass -Pabis=arm64-v8a,x86_64 to also run on an x86 emulator.
        ndk {
            abiFilters += ((project.findProperty("abis") as String?) ?: "arm64-v8a").split(",")
        }
        // -PtestHooks lets a release build take the debug-only test options (dictate from a
        // file), so the optimized build is the one that gets profiled. Never set for a published build.
        buildConfigField("boolean", "TEST_HOOKS", project.hasProperty("testHooks").toString())
    }

    // Release signing comes from the environment (CI secrets, or exported by hand). Without it
    // the release build falls back to the debug key, which still installs for testing but cannot
    // update a properly signed copy.
    //   ALYO_KEYSTORE_PATH / ALYO_KEYSTORE_PASSWORD / ALYO_KEY_ALIAS
    signingConfigs {
        create("releaseFromEnv") {
            val path = System.getenv("ALYO_KEYSTORE_PATH")
            if (!path.isNullOrBlank() && File(path).exists()) {
                storeFile = File(path)
                storePassword = System.getenv("ALYO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ALYO_KEY_ALIAS") ?: "alyo"
                keyPassword = System.getenv("ALYO_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val fromEnv = signingConfigs.getByName("releaseFromEnv")
            signingConfig = if (fromEnv.storeFile?.exists() == true) fromEnv else signingConfigs.getByName("debug")
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

    sourceSets["main"].jniLibs.srcDir("libs/onnxruntime")

    packaging {
        // The AAR also ships its C and C++ API libraries; the Kotlin bindings only load the JNI one.
        jniLibs.excludes += listOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
        // Two copies of ONNX Runtime arrive, the trimmed one from libs/onnxruntime and the full
        // one inside the AAR. The app's own comes first.
        jniLibs.pickFirsts += "**/libonnxruntime.so"
        resources.excludes += listOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }

    testOptions.unitTests.isReturnDefaultValues = true
}

dependencies {
    implementation(files(sherpaAar))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
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

    implementation(libs.kotlinx.coroutines.android)
    // Unpacks the .tar.bz2 model archives sherpa-onnx publishes.
    implementation(libs.commons.compress)

    testImplementation(libs.junit)
}
