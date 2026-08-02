package com.liskovsoft.leankeyboard.addons.voice.whisper;

/**
 * Downloadable whisper model. Quantized multilingual variants are used by default:
 * they are small enough for a tv box and still cover every language the keyboard ships.
 */
public enum WhisperModel {
    TINY_Q5("tiny-q5_1", "ggml-tiny-q5_1.bin", 32),
    TINY("tiny", "ggml-tiny.bin", 78),
    BASE_Q5("base-q5_1", "ggml-base-q5_1.bin", 60),
    BASE("base", "ggml-base.bin", 148),
    SMALL_Q5("small-q5_1", "ggml-small-q5_1.bin", 190);

    private static final String BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/";

    private final String mId;
    private final String mFileName;
    private final int mSizeMb;

    WhisperModel(String id, String fileName, int sizeMb) {
        mId = id;
        mFileName = fileName;
        mSizeMb = sizeMb;
    }

    public static WhisperModel fromId(String id) {
        for (WhisperModel model : values()) {
            if (model.mId.equals(id)) {
                return model;
            }
        }

        return TINY_Q5;
    }

    public String getId() {
        return mId;
    }

    public String getFileName() {
        return mFileName;
    }

    /**
     * Approximate download size, used for the settings label
     */
    public int getSizeMb() {
        return mSizeMb;
    }

    public String getUrl() {
        return BASE_URL + mFileName + "?download=true";
    }
}
