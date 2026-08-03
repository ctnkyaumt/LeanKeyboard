package com.liskovsoft.leankeyboard.addons.voice.whisper;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder.AudioSource;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
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
    /** Hard stop in real time, for mics that deliver much slower than realtime */
    private static final int MAX_RECORDING_WALL_MS = 25_000;
    /** Recording stops once the user has been quiet for this long */
    private static final int TRAILING_SILENCE_MS = 1_200;
    /** Give the user a chance to start talking before silence counts */
    private static final int LEADING_GRACE_MS = 2_500;
    private static final int MIN_RECORDING_MS = 500;
    /** Normalized rms above which we consider the user to be talking */
    private static final float SPEECH_THRESHOLD = 0.015f;
    private static final int MAX_THREADS = 4;
    /**
     * A transcription slower than this means the cpu can't keep up with whisper's fixed 30 s
     * window, and no amount of tuning turns that into a usable keyboard.
     */
    private static final long TOO_SLOW_MS = 30_000L;
    /** Give up on a transcription that runs longer than this */
    private static final long TRANSCRIBE_TIMEOUT_MS = 60_000L;
    /** How many empty reads in a row are tolerated before the mic counts as broken */
    private static final int MAX_EMPTY_READS = 20;
    /** Peak level under which the recording is treated as pure silence */
    private static final float SILENCE_PEAK = 0.003f;
    /** Whether the encoder context is trimmed to the audio length, see audioContextFor */
    private static final boolean SHRINK_AUDIO_CONTEXT = false;
    /** Encoder context whisper uses for a full 30 s window */
    private static final int FULL_AUDIO_CTX = 1500;
    private static final int MIN_AUDIO_CTX = 256;
    private static final int AUDIO_CTX_STEP = 128;
    private static final int WHISPER_WINDOW_MS = 30_000;

    private final Context mContext;
    private final WhisperModelManager mModels;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private volatile boolean mRecording;
    private volatile boolean mStopRequested;
    private volatile boolean mCancelled;
    private volatile boolean mBusy;
    /** Set when a transcription had to be abandoned, the engine can't be trusted afterwards */
    private volatile boolean mEngineUnusable;
    /** Set when the engine works but this cpu is far too slow to use it */
    private volatile boolean mTooSlow;
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
        return WhisperLib.isAvailable() && !mEngineUnusable && mModels.isDownloaded(model);
    }

    /**
     * Whether the engine had to be retired after a transcription that would not stop
     */
    public boolean isEngineUnusable() {
        return mEngineUnusable;
    }

    /**
     * Whether recognition works but is far too slow on this cpu to be worth offering
     */
    public boolean isTooSlow() {
        return mTooSlow;
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
        WhisperLib.requestAbort(true); // unwind a transcription that is already running
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
            // an ime thread otherwise inherits background scheduling, where ggml's spinning
            // workers get almost no cpu and the transcription looks like it hung
            Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND);

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

                // a mic that only ever returns silence would keep whisper busy for nothing
                if (peak(samples) < SILENCE_PEAK) {
                    Log.w(TAG, "mic delivered silence only, skipping transcription");
                    post(() -> listener.onError("no sound reached the microphone"));
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
     * Release the loaded model. Safe to call from the main thread: freeing has to wait for a
     * running transcription, so it is handed to a background thread instead of blocking here.
     */
    public void release() {
        cancel();

        new Thread(this::releaseBlocking, "whisper-release").start();
    }

    private synchronized void releaseBlocking() {
        WhisperLib.freeContext(mContextPtr);
        mContextPtr = 0L;
        mLoadedModel = null;
    }

    /**
     * Open the mic, trying the recognition tuned source first. Tv boxes route the remote's mic
     * through a hid device that only answers on some of these.
     */
    private AudioRecord openRecorder(int bufferSize) {
        IllegalStateException failure = null;

        for (int source : new int[] {AudioSource.VOICE_RECOGNITION, AudioSource.MIC, AudioSource.DEFAULT}) {
            AudioRecord recorder = null;

            try {
                recorder = new AudioRecord(source, SAMPLE_RATE, CHANNELS, ENCODING, bufferSize * 2);

                if (recorder.getState() == AudioRecord.STATE_INITIALIZED) {
                    Log.i(TAG, "mic opened with source " + source);
                    return recorder;
                }

                recorder.release();
            } catch (Exception e) { // SecurityException when the permission was revoked
                if (recorder != null) {
                    recorder.release();
                }

                failure = new IllegalStateException(e.getMessage(), e);
            }
        }

        throw failure != null ? failure : new IllegalStateException("can't open the mic, check the permission");
    }

    private short[] record(Listener listener) {
        int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNELS, ENCODING);

        if (minBuffer <= 0) {
            throw new IllegalStateException("mic is not available");
        }

        int bufferSize = Math.max(minBuffer, SAMPLE_RATE / 4 * 2); // at least 250 ms
        AudioRecord recorder = openRecorder(bufferSize);

        short[] chunk = new short[bufferSize / 2];
        short[] captured = new short[SAMPLE_RATE * MAX_RECORDING_MS / 1000];
        int total = 0;

        try {
            recorder.startRecording();
            mRecording = true;
            post(listener::onReadyForSpeech);

            long startedAt = System.currentTimeMillis();
            // silence is measured in captured audio, not wall clock: some tv mics deliver far
            // slower than realtime and a wall clock timer cuts the user off mid sentence
            int samplesAtLastSpeech = 0;
            boolean heardSpeech = false;
            int emptyReads = 0;

            while (mRecording && total < captured.length) {
                int read = recorder.read(chunk, 0, Math.min(chunk.length, captured.length - total));

                if (read <= 0) {
                    // some hal implementations hiccup, but a mic that never delivers is broken
                    if (read < 0 || ++emptyReads > MAX_EMPTY_READS) {
                        Log.w(TAG, "mic stopped delivering audio, read=" + read + " after " + total + " samples");
                        break;
                    }

                    continue;
                }

                emptyReads = 0;
                System.arraycopy(chunk, 0, captured, total, read);
                total += read;

                float level = rms(chunk, read);
                post(() -> listener.onLevel(toDisplayLevel(level)));

                if (level > SPEECH_THRESHOLD) {
                    heardSpeech = true;
                    samplesAtLastSpeech = total;
                }

                if (mStopRequested && msOf(total) > MIN_RECORDING_MS) {
                    break;
                }

                // a mic that never warms up would otherwise keep us here forever
                if (System.currentTimeMillis() - startedAt > MAX_RECORDING_WALL_MS) {
                    Log.w(TAG, "recording wall clock limit reached with " + msOf(total) + " ms of audio");
                    break;
                }

                boolean graceOver = heardSpeech || msOf(total) > LEADING_GRACE_MS;

                if (graceOver && msOf(total - samplesAtLastSpeech) > TRAILING_SILENCE_MS) {
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
        int audioCtx = audioContextFor(samples.length);

        // a wedged transcription used to keep the recognizer busy for good, which made every
        // later press of the mic do nothing at all
        Handler timeout = new Handler(Looper.getMainLooper());
        Runnable abort = () -> {
            Log.w(TAG, "transcription exceeded " + TRANSCRIBE_TIMEOUT_MS + " ms, aborting");
            WhisperLib.requestAbort(true);
            // whisper only notices the abort between compute steps, and it evidently can sit
            // somewhere that never checks. The stuck thread still holds the transcribe monitor
            // and the native context, so retiring the engine is the only safe move: the
            // microphone keeps working through the other backends instead of wedging again.
            mBusy = false;
            mCancelled = true;
            mEngineUnusable = true;
        };
        timeout.postDelayed(abort, TRANSCRIBE_TIMEOUT_MS);

        long startedAt = System.currentTimeMillis();
        String text;

        try {
            text = WhisperLib.transcribe(mContextPtr, audio, threads, normalizeLanguage(language), false, audioCtx);
        } finally {
            timeout.removeCallbacks(abort);
            WhisperLib.requestAbort(false);
        }

        long took = System.currentTimeMillis() - startedAt;
        Log.i(TAG, "transcribed " + msOf(samples.length) + " ms of audio in "
                + took + " ms (threads=" + threads + ", audio_ctx=" + audioCtx + ")");

        // whisper always works on a 30 s window, so anything slower than realtime here is
        // hopeless for a keyboard no matter how briefly the user spoke
        if (took > TOO_SLOW_MS) {
            Log.w(TAG, "this device needs " + took + " ms per transcription, retiring the local engine");
            mEngineUnusable = true;
            mTooSlow = true;
        }

        return text;
    }

    /**
     * Encoder context matching the audio length. whisper always pads to a 30 s window, so in
     * principle this saves most of the encoder cost on short commands.
     * <br/>
     * Disabled for now: a shrunken context is a non default whisper setting and was in play
     * while transcription was wedging, so it stays off until the engine is known good here.
     */
    private static int audioContextFor(int sampleCount) {
        if (!SHRINK_AUDIO_CONTEXT) {
            return 0; // whisper's own full window
        }

        // 20% headroom so the tail of the audio isn't clipped by a too tight context
        int needed = (int) Math.ceil(FULL_AUDIO_CTX * (msOf(sampleCount) * 1.2 / WHISPER_WINDOW_MS));
        int rounded = (needed + AUDIO_CTX_STEP - 1) / AUDIO_CTX_STEP * AUDIO_CTX_STEP;

        return Math.max(MIN_AUDIO_CTX, Math.min(FULL_AUDIO_CTX, rounded));
    }

    private static long msOf(int sampleCount) {
        return sampleCount * 1000L / SAMPLE_RATE;
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

    /**
     * Loudest normalized sample of the whole recording
     */
    private static float peak(short[] samples) {
        int max = 0;

        for (short sample : samples) {
            max = Math.max(max, Math.abs(sample));
        }

        return max / 32768.0f;
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
