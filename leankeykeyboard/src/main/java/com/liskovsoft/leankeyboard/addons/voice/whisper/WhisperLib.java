package com.liskovsoft.leankeyboard.addons.voice.whisper;

import android.os.Build.VERSION;
import android.util.Log;

/**
 * Thin binding over whisper.cpp. The native library is loaded lazily so devices that can't
 * use it (too old, unsupported abi) keep working with the other voice backends.
 */
public final class WhisperLib {
    private static final String TAG = WhisperLib.class.getSimpleName();
    private static final String LIB_NAME = "leankey_whisper";
    /** The native code is built against api 21, older systems must never load it */
    private static final int MIN_SDK = 21;
    private static Boolean sAvailable;

    private WhisperLib() {
    }

    public static synchronized boolean isAvailable() {
        if (sAvailable == null) {
            sAvailable = loadLibrary();
        }

        return sAvailable;
    }

    private static boolean loadLibrary() {
        if (VERSION.SDK_INT < MIN_SDK) {
            Log.i(TAG, "whisper needs api " + MIN_SDK + ", running on " + VERSION.SDK_INT);
            return false;
        }

        try {
            System.loadLibrary(LIB_NAME);
            Log.i(TAG, "whisper loaded: " + nativeSystemInfo());
            return true;
        } catch (Throwable e) { // UnsatisfiedLinkError on unsupported abi
            Log.w(TAG, "can't load " + LIB_NAME, e);
            return false;
        }
    }

    /**
     * @return native context pointer, 0 when the model can't be loaded
     */
    public static long initContext(String modelPath) {
        return isAvailable() ? nativeInitContext(modelPath) : 0L;
    }

    public static void freeContext(long context) {
        if (context != 0L) {
            nativeFreeContext(context);
        }
    }

    /**
     * @param audio mono samples at 16 kHz normalized to [-1, 1]
     * @param language iso code or "auto"
     * @param audioCtx encoder context size, 0 keeps whisper's full 30 s window
     */
    public static String transcribe(long context, float[] audio, int threads, String language, boolean translate, int audioCtx) {
        if (context == 0L) {
            return "";
        }

        return nativeTranscribe(context, audio, threads, language, translate, audioCtx);
    }

    private static native long nativeInitContext(String modelPath);

    private static native void nativeFreeContext(long context);

    private static native String nativeTranscribe(long context, float[] audio, int threads, String language, boolean translate, int audioCtx);

    private static native String nativeSystemInfo();
}
