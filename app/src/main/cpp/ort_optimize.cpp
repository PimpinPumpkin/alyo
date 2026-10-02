// Has ONNX Runtime load a model, optimize its graph, and write the result out. Loading a model
// that was saved this way skips most of that work, which is the bulk of the wait before the
// first words appear: on a Pixel 4a 5G the English live model goes from 3.6 s to 2.1 s, and the
// German one from 8.0 s to 4.2 s, with identical output.
//
// The runtime is the one sherpa-onnx already brings (libonnxruntime.so). It is looked up when
// this is called rather than linked, so nothing here depends on how that library is packaged.

#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>

#include "onnxruntime_c_api.h"

#define LOG(...) __android_log_print(ANDROID_LOG_WARN, "AlyoOrt", __VA_ARGS__)

extern "C" JNIEXPORT jboolean JNICALL
Java_app_alyo_asr_ModelOptimizer_nativeOptimize(JNIEnv *env, jclass, jstring input, jstring output) {
    void *lib = dlopen("libonnxruntime.so", RTLD_NOW);
    if (lib == nullptr) {
        LOG("runtime not found: %s", dlerror());
        return JNI_FALSE;
    }
    auto get_base = reinterpret_cast<const OrtApiBase *(*)()>(dlsym(lib, "OrtGetApiBase"));
    const OrtApi *api = get_base != nullptr ? get_base()->GetApi(ORT_API_VERSION) : nullptr;
    if (api == nullptr) {
        LOG("runtime is older than the header this was built with");
        return JNI_FALSE;
    }

    const char *in = env->GetStringUTFChars(input, nullptr);
    const char *out = env->GetStringUTFChars(output, nullptr);
    OrtEnv *ort = nullptr;
    OrtSessionOptions *options = nullptr;
    OrtSession *session = nullptr;
    auto failed = [&](OrtStatus *status) {
        if (status == nullptr) return false;
        LOG("%s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        return true;
    };
    // "Extended" is the highest level whose result does not depend on the processor, so a model
    // saved on one phone would still be right on another.
    bool ok = !failed(api->CreateEnv(ORT_LOGGING_LEVEL_ERROR, "alyo-optimize", &ort))
        && !failed(api->CreateSessionOptions(&options))
        && !failed(api->SetIntraOpNumThreads(options, 1))
        && !failed(api->SetSessionGraphOptimizationLevel(options, ORT_ENABLE_EXTENDED))
        && !failed(api->SetOptimizedModelFilePath(options, out))
        && !failed(api->CreateSession(ort, in, options, &session));
    if (session != nullptr) api->ReleaseSession(session);
    if (options != nullptr) api->ReleaseSessionOptions(options);
    if (ort != nullptr) api->ReleaseEnv(ort);
    env->ReleaseStringUTFChars(input, in);
    env->ReleaseStringUTFChars(output, out);
    return ok ? JNI_TRUE : JNI_FALSE;
}
