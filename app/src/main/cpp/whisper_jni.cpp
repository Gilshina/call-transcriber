// Thin JNI bridge between Kotlin (il.calltranscriber.whisper.NativeWhisper)
// and whisper.cpp. Kept deliberately small: load model, run a chunk of 16 kHz
// mono float PCM, stream segments/progress back, support cancellation.

#include <jni.h>

#include <atomic>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include "whisper.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "CallTranscriber", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "CallTranscriber", __VA_ARGS__)
#else
#include <cstdio>
#define LOGI(...) do { std::fprintf(stderr, "[I] "); std::fprintf(stderr, __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#define LOGE(...) do { std::fprintf(stderr, "[E] "); std::fprintf(stderr, __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#endif

namespace {

struct Engine {
    whisper_context *ctx = nullptr;
    std::atomic<bool> abort_flag{false};
};

struct CallbackState {
    JNIEnv *env = nullptr;
    jobject callback = nullptr;
    jmethodID on_progress = nullptr;
    jmethodID on_segment = nullptr;
};

void clear_pending_exception(JNIEnv *env) {
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
}

void new_segment_cb(whisper_context *ctx, whisper_state * /*state*/, int n_new, void *user_data) {
    auto *cb = static_cast<CallbackState *>(user_data);
    JNIEnv *env = cb->env;
    const int n_segments = whisper_full_n_segments(ctx);
    for (int i = n_segments - n_new; i < n_segments; ++i) {
        if (i < 0) continue;
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (text == nullptr) continue;
        // whisper timestamps are in 10 ms units.
        const jlong t0_ms = static_cast<jlong>(whisper_full_get_segment_t0(ctx, i)) * 10;
        const jlong t1_ms = static_cast<jlong>(whisper_full_get_segment_t1(ctx, i)) * 10;
        // Hand the text over as raw UTF-8 bytes: a token boundary can, in rare
        // cases, leave a split multi-byte sequence, which NewStringUTF() does
        // not tolerate. Kotlin decodes with replacement instead.
        const jsize len = static_cast<jsize>(std::strlen(text));
        jbyteArray bytes = env->NewByteArray(len);
        if (bytes == nullptr) {
            clear_pending_exception(env);
            continue;
        }
        env->SetByteArrayRegion(bytes, 0, len, reinterpret_cast<const jbyte *>(text));
        env->CallVoidMethod(cb->callback, cb->on_segment, t0_ms, t1_ms, bytes);
        clear_pending_exception(env);
        env->DeleteLocalRef(bytes);
    }
}

void progress_cb(whisper_context * /*ctx*/, whisper_state * /*state*/, int progress, void *user_data) {
    auto *cb = static_cast<CallbackState *>(user_data);
    cb->env->CallVoidMethod(cb->callback, cb->on_progress, static_cast<jint>(progress));
    clear_pending_exception(cb->env);
}

bool abort_cb(void *user_data) {
    auto *engine = static_cast<Engine *>(user_data);
    return engine->abort_flag.load(std::memory_order_relaxed);
}

std::string to_std_string(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *chars = env->GetStringUTFChars(s, nullptr);
    if (chars == nullptr) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_il_calltranscriber_whisper_NativeWhisper_nInit(JNIEnv *env, jobject /*thiz*/, jstring jpath) {
    const std::string path = to_std_string(env, jpath);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;  // CPU only: the GPU backends are not built.
    LOGI("Loading model: %s", path.c_str());
    whisper_context *ctx = whisper_init_from_file_with_params(path.c_str(), cparams);
    if (ctx == nullptr) {
        LOGE("whisper_init_from_file_with_params failed");
        return 0;
    }
    auto *engine = new Engine();
    engine->ctx = ctx;
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_il_calltranscriber_whisper_NativeWhisper_nFree(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    if (engine == nullptr) return;
    if (engine->ctx != nullptr) whisper_free(engine->ctx);
    delete engine;
}

JNIEXPORT void JNICALL
Java_il_calltranscriber_whisper_NativeWhisper_nCancel(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    if (engine != nullptr) engine->abort_flag.store(true);
}

// Returns whisper_full()'s status: 0 = ok, anything else = failure/aborted.
JNIEXPORT jint JNICALL
Java_il_calltranscriber_whisper_NativeWhisper_nTranscribe(
        JNIEnv *env, jobject /*thiz*/, jlong handle,
        jfloatArray jsamples, jint n_samples,
        jstring jlanguage, jint n_threads, jint beam_size,
        jstring jvad_model_path, jobject callback) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    if (engine == nullptr || engine->ctx == nullptr || jsamples == nullptr || callback == nullptr) {
        return -1;
    }

    jclass cb_class = env->GetObjectClass(callback);
    CallbackState cb;
    cb.env = env;
    cb.callback = callback;
    cb.on_progress = env->GetMethodID(cb_class, "onProgress", "(I)V");
    cb.on_segment = env->GetMethodID(cb_class, "onSegment", "(JJ[B)V");
    if (cb.on_progress == nullptr || cb.on_segment == nullptr) {
        clear_pending_exception(env);
        LOGE("callback methods not found");
        return -2;
    }

    std::vector<float> samples(static_cast<size_t>(n_samples));
    env->GetFloatArrayRegion(jsamples, 0, n_samples, samples.data());
    if (env->ExceptionCheck()) return -3;

    const std::string language = to_std_string(env, jlanguage);
    const std::string vad_path = to_std_string(env, jvad_model_path);

    const bool use_beam = beam_size > 1;
    whisper_full_params params = whisper_full_default_params(
            use_beam ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    if (use_beam) params.beam_search.beam_size = beam_size;

    params.n_threads = n_threads > 0 ? n_threads : 4;
    params.language = language.empty() ? "he" : language.c_str();
    params.detect_language = false;
    params.translate = false;
    params.print_special = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.suppress_blank = true;
    params.suppress_nst = true;

    if (!vad_path.empty()) {
        params.vad = true;
        params.vad_model_path = vad_path.c_str();
    }

    params.new_segment_callback = new_segment_cb;
    params.new_segment_callback_user_data = &cb;
    params.progress_callback = progress_cb;
    params.progress_callback_user_data = &cb;
    params.abort_callback = abort_cb;
    params.abort_callback_user_data = engine;

    return whisper_full(engine->ctx, params, samples.data(), static_cast<int>(samples.size()));
}

JNIEXPORT jstring JNICALL
Java_il_calltranscriber_whisper_NativeWhisper_nSystemInfo(JNIEnv *env, jobject /*thiz*/) {
    const char *info = whisper_print_system_info();
    return env->NewStringUTF(info != nullptr ? info : "");
}

}  // extern "C"
