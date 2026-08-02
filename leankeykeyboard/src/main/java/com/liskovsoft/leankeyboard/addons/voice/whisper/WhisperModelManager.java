package com.liskovsoft.leankeyboard.addons.voice.whisper;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps the whisper models in the app's private storage and downloads them on demand.
 */
public class WhisperModelManager {
    private static final String TAG = WhisperModelManager.class.getSimpleName();
    private static final String MODEL_DIR = "whisper";
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final int MAX_REDIRECTS = 5;

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean mCancelled = new AtomicBoolean();
    private volatile boolean mDownloading;

    public interface DownloadListener {
        /**
         * @param percent 0..100, or -1 when the total size is unknown
         */
        void onProgress(int percent);

        void onDone(File model);

        void onError(String message);
    }

    public WhisperModelManager(Context context) {
        mContext = context.getApplicationContext();
    }

    public File getModelFile(WhisperModel model) {
        return new File(getModelDir(), model.getFileName());
    }

    public boolean isDownloaded(WhisperModel model) {
        File file = getModelFile(model);

        return file.exists() && file.length() > 0;
    }

    public boolean isDownloading() {
        return mDownloading;
    }

    public void cancel() {
        mCancelled.set(true);
    }

    public boolean delete(WhisperModel model) {
        return getModelFile(model).delete();
    }

    /**
     * Download in the background. All callbacks land on the main thread.
     */
    public void download(final WhisperModel model, final DownloadListener listener) {
        if (mDownloading) {
            return;
        }

        mDownloading = true;
        mCancelled.set(false);

        new Thread(() -> {
            String error = null;
            File target = getModelFile(model);
            // download aside so an interrupted transfer never looks like a valid model
            File partial = new File(target.getPath() + ".part");

            try {
                downloadTo(model, partial, listener);

                if (mCancelled.get()) {
                    error = "cancelled";
                } else if (!partial.renameTo(target)) {
                    error = "can't store the model";
                }
            } catch (Exception e) {
                Log.e(TAG, "model download failed", e);
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            } finally {
                mDownloading = false;

                if (error != null) {
                    partial.delete();
                }
            }

            final String result = error;
            mHandler.post(() -> {
                if (listener == null) {
                    return;
                }

                if (result == null) {
                    listener.onDone(target);
                } else {
                    listener.onError(result);
                }
            });
        }, "whisper-model-download").start();
    }

    private void downloadTo(WhisperModel model, File target, DownloadListener listener) throws Exception {
        File dir = getModelDir();

        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("can't create " + dir);
        }

        HttpURLConnection connection = openWithRedirects(model.getUrl());

        try (InputStream input = connection.getInputStream();
             FileOutputStream output = new FileOutputStream(target)) {

            final int total = connection.getContentLength();
            final byte[] buffer = new byte[BUFFER_SIZE];
            long done = 0;
            int lastPercent = -1;
            int read;

            while ((read = input.read(buffer)) != -1) {
                if (mCancelled.get()) {
                    return;
                }

                output.write(buffer, 0, read);
                done += read;

                int percent = total > 0 ? (int) (done * 100 / total) : -1;

                if (percent != lastPercent && listener != null) {
                    lastPercent = percent;
                    final int reported = percent;
                    mHandler.post(() -> listener.onProgress(reported));
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * HttpURLConnection doesn't follow http -> https redirects on its own, and the model
     * host redirects to a cdn.
     */
    private HttpURLConnection openWithRedirects(String url) throws Exception {
        for (int i = 0; i < MAX_REDIRECTS; i++) {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);

            int code = connection.getResponseCode();

            if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                code == HttpURLConnection.HTTP_MOVED_TEMP ||
                code == HttpURLConnection.HTTP_SEE_OTHER ||
                code == 307 || code == 308) {
                url = connection.getHeaderField("Location");
                connection.disconnect();
                continue;
            }

            if (code != HttpURLConnection.HTTP_OK) {
                connection.disconnect();
                throw new IllegalStateException("http " + code);
            }

            return connection;
        }

        throw new IllegalStateException("too many redirects");
    }

    private File getModelDir() {
        return new File(mContext.getFilesDir(), MODEL_DIR);
    }
}
