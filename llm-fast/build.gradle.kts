plugins {
    alias(libs.plugins.android.library)
}

// The summary engine a second time, for newer processors. It is the same source as the copy in
// the app module (app/src/main/cpp), compiled to use the dot-product and half-float instructions
// that 64-bit ARM chips have had since about 2018. Those make it markedly faster, and would crash
// a chip without them, so both copies ship and Llama.kt loads the one the phone can run.
android {
    namespace = "app.alyo.llmfast"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 29
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static", "-DALYO_LLM_VARIANT=dotprod")
                (project.findProperty("llamaSrc") as String?)?.let { arguments += "-DLLAMA_SRC=$it" }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../app/src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}
