package com.liskovsoft.leankeyboard.addons.voice.whisper;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder.AudioSource;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.util.Locale;

/**
 * On device speech recognition: records from the mic and runs whisper.cpp over the result.
 * Nothing leaves the device and no companion app is needed.
 */
public class WhisperRecognizer {
    private static final String TAG = WhisperRecognizer.class.getSimpleName();
    /** whisper only accepts 16 kHz mono */
    public static final int SAMPLE_RATE = 16000;
    private static final int CHANNELS = AudioFormat.CHANNEL_IN_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int MAX_RECORDING_MS = 20_000;
    /** Recording stops once the user has been quiet for this long */
    private static final int TRAILING_SILENCE_MS = 1_200;
    /** Give the user a chance to start talking before silence counts */
    private static final int LEADING_GRACE_MS = 2_500;
    private static final int MIN_RECORDING_MS = 500;
    /** Normalized rms above which we consider the user to be talking */
    private static final float SPEECH_THRESHOLD = 0.015f;
    private static final int MAX_THREADS = 4;

    private final Context mContext;
    private final WhisperModelManager mModels;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private volatile boolean mRecording;
    private volatile boolean mStopRequested;
    private volatile boolean mCancelled;
    private volatile boolean mBusy;
    private long mContextPtr;
    private WhisperModel mLoadedModel;

    public interface Listener {
        /** The mic is open */
        void onReadyForSpeech();

        /** Loudness of the last audio chunk, 0..100 */
        void onLevel(int level);

        /** Recording finished, transcription is running */
        void onRecognizing();

        void onResult(String text);

        void onError(String message);
    }

    public WhisperRecognizer(Context context) {
        mContext = context.getApplicationContext();
        mModels = new WhisperModelManager(mContext);
    }

    public WhisperModelManager getModels() {
        return mModels;
    }

    /**
     * Whether recognition can run right now: native library present and model downloaded.
     */
    public boolean isReady(WhisperModel model) {
        return WhisperLib.isAvailable() && mModels.isDownloaded(model);
    }

    public boolean isBusy() {
        return mBusy;
    }

    /**
     * Stop recording early and transcribe what has been captured so far.
     */
    public void stop() {
        mStopRequested = true;
    }

    /**
     * Abort without producing a result.
     */
    public void cancel() {
        mCancelled = true;
        mStopRequested = true;
        mRecording = false;
    }

    /**
     * Record and transcribe. Every callback lands on the main thread.
     * @param language iso code or "auto"
     */
    public void start(final WhisperModel model, final String language, final Listener listener) {
        if (mBusy) {
            return;
        }

        if (!WhisperLib.isAvailable()) {
            post(() -> listener.onError("whisper is not supported on this device"));
            return;
        }

        if (!mModels.isDownloaded(model)) {
            post(() -> listener.onError("voice model is not downloaded"));
            return;
        }

        mBusy = true;
        mStopRequested = false;
        mCancelled = false;

        new Thread(() -> {
            try {
                short[] samples = record(listener);

                if (mCancelled) {
                    return;
                }

                // too short to hold a word, don't spend cpu on it
                if (samples.length < SAMPLE_RATE / 4) {
                    post(() -> listener.onResult(""));
                    return;
                }

                post(listener::onRecognizing);

                String text = transcribe(model, samples, language);
                post(() -> listener.onResult(text));
            } catch (Throwable e) {
                Log.e(TAG, "recognition failed", e);
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                post(() -> listener.onError(message));
            } finally {
                mRecording = false;
                mBusy = false;
            }
        }, "whisper-recognizer").start();
    }

    /**
     * Release the loaded model. Call it when the keyboard goes away.
     */
    public synchronized void release() {
        WhisperLib.freeContext(mContextPtr);
        mContextPtr = 0L;
        mLoadedModel = null;
    }

    private short[] record(Listener listener) {
        int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNELS, ENCODING);

        if (minBuffer <= 0) {
            throw new IllegalStateException("mic is not available");
        }

        int bufferSize = Math.max(minBuffer, SAMPLE_RATE / 4 * 2); // at least 250 ms
        AudioRecord recorder = new AudioRecord(AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, CHANNELS, ENCODING, bufferSize * 2);

        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release();
            throw new IllegalStateException("can't open the mic, check the permission");
        }

        short[] chunk = new short[bufferSize / 2];
        short[] captured = new short[SAMPLE_RATE * MAX_RECORDING_MS / 1000];
        int total = 0;

        try {
            recorder.startRecording();
            mRecording = true;
            post(listener::onReadyForSpeech);

            long startedAt = System.currentTimeMillis();
            long lastSpeechAt = startedAt;
            boolean heardSpeech = false;

            while (mRecording && total < captured.length) {
                int read = recorder.read(chunk, 0, Math.min(chunk.length, captured.length - total));

                if (read <= 0) {
                    break;
                }

                System.arraycopy(chunk, 0, captured, total, read);
                total += read;

                float level = rms(chunk, read);
                post(() -> listener.onLevel(toDisplayLevel(level)));

                long now = System.currentTimeMillis();

                if (level > SPEECH_THRESHOLD) {
                    heardSpeech = true;
                    lastSpeechAt = now;
                }

                long elapsed = now - startedAt;

                if (mStopRequested && elapsed > MIN_RECORDING_MS) {
                    break;
                }

                boolean graceOver = heardSpeech || elapsed > LEADING_GRACE_MS;

                if (graceOver && now - lastSpeechAt > TRAILING_SILENCE_MS) {
                    break;
                }
            }
        } finally {
            try {
                recorder.stop();
            } catch (IllegalStateException e) {
                Log.w(TAG, "can't stop the recorder", e);
            }
            recorder.release();
            mRecording = false;
        }

        short[] result = new short[total];
        System.arraycopy(captured, 0, result, 0, total);

        return result;
    }

    private synchronized String transcribe(WhisperModel model, short[] samples, String language) {
        if (mContextPtr == 0L || mLoadedModel != model) {
            WhisperLib.freeContext(mContextPtr);

            File file = mModels.getModelFile(model);
            mContextPtr = WhisperLib.initContext(file.getAbsolutePath());
            mLoadedModel = model;

            if (mContextPtr == 0L) {
                mLoadedModel = null;
                throw new IllegalStateException("can't load the voice model");
            }
        }

        float[] audio = new float[samples.length];

        for (int i = 0; i < samples.length; i++) {
            audio[i] = samples[i] / 32768.0f;
        }

        int threads = Math.max(1, Math.min(MAX_THREADS, Runtime.getRuntime().availableProcessors()));

        return WhisperLib.transcribe(mContextPtr, audio, threads, normalizeLanguage(language), false);
    }

    /**
     * whisper expects a bare iso 639-1 code, not a full locale
     */
    private static String normalizeLanguage(String language) {
        if (language == null || language.isEmpty() || "auto".equals(language)) {
            return "auto";
        }

        String code = language.replace('_', '-');
        int dash = code.indexOf('-');

        return (dash > 0 ? code.substring(0, dash) : code).toLowerCase(Locale.ROOT);
    }

    private static float rms(short[] samples, int count) {
        double sum = 0;

        for (int i = 0; i < count; i++) {
            double value = samples[i] / 32768.0;
            sum += value * value;
        }

        return count == 0 ? 0f : (float) Math.sqrt(sum / count);
    }

    /**
     * Map rms onto the 0..100 range the sound level view expects
     */
    private static int toDisplayLevel(float rms) {
        int level = (int) (rms * 800);

        return Math.max(0, Math.min(100, level));
    }

    private void post(Runnable action) {
        mHandler.post(action);
    }
}
