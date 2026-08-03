// JNI bridge between WhisperLib.java and whisper.cpp.
// Deliberately thin: model lifecycle and transcription only, everything else stays in Java.

#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstring>
#include <string>
#include <vector>

#include "whisper.h"
#include "ggml.h"

#define TAG "LeanKeyWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define JNI_METHOD(name) Java_com_liskovsoft_leankeyboard_addons_voice_whisper_WhisperLib_##name

/** Set from java to interrupt a transcription that is taking too long */
static std::atomic<bool> g_abort(false);

static bool on_abort(void *) {
    return g_abort.load();
}

/** Proves whether the encoder is advancing or wedged */
static void on_progress(struct whisper_context *, struct whisper_state *, int progress, void *) {
    LOGI("transcribe progress %d%%", progress);
}

/**
 * whisper and ggml both log their internal phases. Forwarding them to logcat is the only way
 * to see which phase a transcription is sitting in, since the progress callback only fires
 * once the decoder is already running.
 */
static void on_log(enum ggml_log_level level, const char *text, void *) {
    if (text == nullptr) {
        return;
    }

    __android_log_print(level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO,
                        TAG, "%s", text);
}

extern "C" {

JNIEXPORT jlong JNICALL
JNI_METHOD(nativeInitContext)(JNIEnv *env, jclass, jstring modelPath) {
    whisper_log_set(on_log, nullptr);
    ggml_log_set(on_log, nullptr);

    const char *path = env->GetStringUTFChars(modelPath, nullptr);

    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false; // no reliable gpu backend on android tv boxes

    whisper_context *ctx = whisper_init_from_file_with_params(path, params);

    if (ctx == nullptr) {
        LOGE("failed to load the model from %s", path);
    } else {
        LOGI("model loaded from %s", path);
    }

    env->ReleaseStringUTFChars(modelPath, path);

    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
JNI_METHOD(nativeFreeContext)(JNIEnv *, jclass, jlong contextPtr) {
    auto *ctx = reinterpret_cast<whisper_context *>(contextPtr);

    if (ctx != nullptr) {
        whisper_free(ctx);
    }
}

/**
 * Transcribe mono 16 kHz samples normalized to [-1, 1].
 * @param language iso code, or "auto" to let whisper detect it
 * @return recognized text, never null (empty when nothing was recognized)
 */
JNIEXPORT jstring JNICALL
JNI_METHOD(nativeTranscribe)(JNIEnv *env, jclass, jlong contextPtr, jfloatArray audio,
                             jint threads, jstring language, jboolean translate, jint audioCtx) {
    auto *ctx = reinterpret_cast<whisper_context *>(contextPtr);

    if (ctx == nullptr) {
        return env->NewStringUTF("");
    }

    const jsize sampleCount = env->GetArrayLength(audio);
    jfloat *samples = env->GetFloatArrayElements(audio, nullptr);

    const char *lang = env->GetStringUTFChars(language, nullptr);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = translate == JNI_TRUE;
    params.language = lang;
    params.n_threads = threads;
    params.no_context = true;
    params.single_segment = false;
    params.suppress_nst = true; // drop "(music)" and similar non speech markers

    // whisper pads every input to a 30 s window, so a two second command costs a full
    // encoder pass. Shrinking the audio context to what the audio actually needs is the
    // difference between a usable and an unusable latency on a tv box.
    if (audioCtx > 0) {
        params.audio_ctx = audioCtx;
    }

    g_abort.store(false);
    params.abort_callback = on_abort;
    params.abort_callback_user_data = nullptr;
    params.progress_callback = on_progress;
    params.progress_callback_user_data = nullptr;

    LOGI("transcribe start: %d samples, %d threads, audio_ctx %d", sampleCount, threads, audioCtx);

    std::string result;

    whisper_reset_timings(ctx);

    if (whisper_full(ctx, params, samples, sampleCount) != 0) {
        LOGE("transcription failed");
    } else {
        const int segments = whisper_full_n_segments(ctx);

        for (int i = 0; i < segments; i++) {
            const char *text = whisper_full_get_segment_text(ctx, i);

            if (text != nullptr) {
                result.append(text);
            }
        }
    }

    env->ReleaseStringUTFChars(language, lang);
    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);

    // whisper prefixes segments with a space
    const size_t start = result.find_first_not_of(" \t\n");
    const size_t end = result.find_last_not_of(" \t\n");
    if (start == std::string::npos) {
        result.clear();
    } else {
        result = result.substr(start, end - start + 1);
    }

    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jstring JNICALL
JNI_METHOD(nativeSystemInfo)(JNIEnv *env, jclass) {
    return env->NewStringUTF(whisper_print_system_info());
}

/**
 * Ask a running transcription to stop. whisper checks this between compute steps, so the
 * call returns immediately and whisper_full unwinds shortly after.
 */
JNIEXPORT void JNICALL
JNI_METHOD(nativeRequestAbort)(JNIEnv *, jclass, jboolean abort) {
    g_abort.store(abort == JNI_TRUE);
    LOGI("abort requested: %d", abort == JNI_TRUE);
}

} // extern "C"
