// JNI bridge between WhisperLib.java and whisper.cpp.
// Deliberately thin: model lifecycle and transcription only, everything else stays in Java.

#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <string>
#include <vector>

#include "whisper.h"

#define TAG "LeanKeyWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define JNI_METHOD(name) Java_com_liskovsoft_leankeyboard_addons_voice_whisper_WhisperLib_##name

extern "C" {

JNIEXPORT jlong JNICALL
JNI_METHOD(nativeInitContext)(JNIEnv *env, jclass, jstring modelPath) {
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
                             jint threads, jstring language, jboolean translate) {
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

} // extern "C"
