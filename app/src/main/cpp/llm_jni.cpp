// The whole of Alyo's bridge to llama.cpp: load a GGUF chat model, count tokens, and answer one
// system + user prompt, streaming the reply back a piece at a time.
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "alyo-llm"

namespace {

struct Session {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    std::atomic<bool> cancel{false};
};

void quiet_log(enum ggml_log_level level, const char *text, void *) {
    if (level == GGML_LOG_LEVEL_ERROR) __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", text);
}

std::string to_string(JNIEnv *env, jstring s) {
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

std::vector<llama_token> tokenize(const Session *s, const std::string &text, bool special) {
    int n = -llama_tokenize(s->vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, special, special);
    std::vector<llama_token> tokens(n);
    llama_tokenize(s->vocab, text.c_str(), (int32_t) text.size(), tokens.data(), n, special, special);
    return tokens;
}

// The prompt in the model's own chat format, which it carries inside the GGUF.
std::string chat_prompt(const Session *s, const std::string &system, const std::string &user) {
    const char *tmpl = llama_model_chat_template(s->model, nullptr);
    llama_chat_message messages[2] = {{"system", system.c_str()}, {"user", user.c_str()}};
    std::vector<char> buffer(system.size() + user.size() + 1024);
    int n = llama_chat_apply_template(tmpl, messages, 2, true, buffer.data(), (int32_t) buffer.size());
    if (n > (int) buffer.size()) {
        buffer.resize(n + 1);
        n = llama_chat_apply_template(tmpl, messages, 2, true, buffer.data(), (int32_t) buffer.size());
    }
    if (n < 0) return system + "\n\n" + user + "\n\n";
    return std::string(buffer.data(), n);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_app_alyo_llm_Llama_nativeLoad(JNIEnv *env, jclass, jstring path, jint n_ctx, jint n_threads) {
    static bool started = false;
    if (!started) {
        llama_log_set(quiet_log, nullptr);
        llama_backend_init();
        started = true;
    }
    auto *s = new Session();
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    s->model = llama_model_load_from_file(to_string(env, path).c_str(), mp);
    if (!s->model) {
        delete s;
        return 0;
    }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = n_ctx;
    cp.n_batch = 256;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    s->ctx = llama_init_from_model(s->model, cp);
    if (!s->ctx) {
        llama_model_free(s->model);
        delete s;
        return 0;
    }
    s->vocab = llama_model_get_vocab(s->model);
    return (jlong) s;
}

JNIEXPORT jint JNICALL
Java_app_alyo_llm_Llama_nativeCountTokens(JNIEnv *env, jclass, jlong handle, jstring text) {
    auto *s = (Session *) handle;
    return (jint) tokenize(s, to_string(env, text), false).size();
}

// Returns 0 on a normal finish, 1 if stopped by the caller, negative on failure.
JNIEXPORT jint JNICALL
Java_app_alyo_llm_Llama_nativeGenerate(JNIEnv *env, jclass, jlong handle, jstring system, jstring user,
                                        jstring opening, jstring grammar, jint max_tokens, jobject sink) {
    auto *s = (Session *) handle;
    s->cancel = false;
    jclass sinkClass = env->GetObjectClass(sink);
    jmethodID onProgress = env->GetMethodID(sinkClass, "onProgress", "(II)V");
    jmethodID onPiece = env->GetMethodID(sinkClass, "onPiece", "([B)V");

    // The reply is started for the model: text placed after the prompt is text it must continue.
    std::vector<llama_token> prompt = tokenize(s, chat_prompt(s, to_string(env, system), to_string(env, user)) + to_string(env, opening), true);
    const int n_ctx = (int) llama_n_ctx(s->ctx);
    if ((int) prompt.size() + max_tokens > n_ctx) return -2;

    llama_memory_clear(llama_get_memory(s->ctx), true);
    const int batch = (int) llama_n_batch(s->ctx);
    for (int i = 0; i < (int) prompt.size(); i += batch) {
        if (s->cancel) return 1;
        int n = std::min(batch, (int) prompt.size() - i);
        if (llama_decode(s->ctx, llama_batch_get_one(prompt.data() + i, n)) != 0) return -3;
        env->CallVoidMethod(sink, onProgress, i + n, (jint) prompt.size());
    }

    // Low temperature: a summary should be the likely reading of the text, not a creative one.
    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    // A grammar makes anything outside the wanted shape impossible to write, rather than merely
    // discouraged: tokens that would break it are removed before one is chosen.
    const std::string rules = to_string(env, grammar);
    if (!rules.empty()) {
        llama_sampler *shape = llama_sampler_init_grammar(s->vocab, rules.c_str(), "root");
        if (shape) {
            llama_sampler_chain_add(sampler, shape);
        } else {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "grammar did not parse; generating without it");
        }
    }
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.3f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(1234));

    int result = 0;
    for (int produced = 0; produced < max_tokens; produced++) {
        if (s->cancel) {
            result = 1;
            break;
        }
        llama_token token = llama_sampler_sample(sampler, s->ctx, -1);
        if (llama_vocab_is_eog(s->vocab, token)) break;
        char piece[256];
        int n = llama_token_to_piece(s->vocab, token, piece, sizeof(piece), 0, false);
        if (n > 0) {
            // Bytes, not a string: a multi-byte character can be split across two tokens.
            jbyteArray bytes = env->NewByteArray(n);
            env->SetByteArrayRegion(bytes, 0, n, (const jbyte *) piece);
            env->CallVoidMethod(sink, onPiece, bytes);
            env->DeleteLocalRef(bytes);
        }
        if (llama_decode(s->ctx, llama_batch_get_one(&token, 1)) != 0) {
            result = -3;
            break;
        }
    }
    llama_sampler_free(sampler);
    return result;
}

JNIEXPORT void JNICALL
Java_app_alyo_llm_Llama_nativeCancel(JNIEnv *, jclass, jlong handle) {
    ((Session *) handle)->cancel = true;
}

JNIEXPORT void JNICALL
Java_app_alyo_llm_Llama_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto *s = (Session *) handle;
    llama_free(s->ctx);
    llama_model_free(s->model);
    delete s;
}

}  // extern "C"
