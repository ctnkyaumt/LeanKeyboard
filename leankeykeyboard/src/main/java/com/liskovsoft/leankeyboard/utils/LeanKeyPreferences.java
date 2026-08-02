package com.liskovsoft.leankeyboard.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

public final class LeanKeyPreferences {
    private static final String APP_RUN_ONCE = "appRunOnce";
    private static final String BOOTSTRAP_SELECTED_LANGUAGE = "bootstrapSelectedLanguage";
    private static final String APP_KEYBOARD_INDEX = "appKeyboardIndex";
    private static final String FORCE_SHOW_KEYBOARD = "forceShowKeyboard";
    private static final String ENLARGE_KEYBOARD = "enlargeKeyboard";
    private static final String KEYBOARD_SCALE = "keyboardScale";
    /** Scale applied by the legacy "enlarge keyboard" switch */
    public static final float LEGACY_ENLARGE_SCALE = 1.3f;
    public static final float DEFAULT_KEYBOARD_SCALE = 1.0f;
    private static final String KEYBOARD_THEME = "keyboardTheme";
    public static final String THEME_DEFAULT = "Default";
    public static final String THEME_DARK = "Dark";
    public static final String THEME_DARK2 = "Dark2";
    public static final String THEME_DARK3 = "Dark3";
    private static final String SUGGESTIONS_ENABLED = "suggestionsEnabled";
    private static final String CYCLIC_NAVIGATION_ENABLED = "cyclicNavigationEnabled";
    private static final String AUTODETECT_LAYOUT = "autodetectLayout";
    private static final String NEXT_WORD_SUGGESTIONS_ENABLED = "nextWordSuggestionsEnabled";
    private static final String VOICE_SEARCH_ENABLED = "voiceSearchEnabled";
    private static final String WORD_HISTORY = "wordHistory";
    private static final String WHISPER_ENABLED = "whisperEnabled";
    private static final String WHISPER_MODEL = "whisperModel";
    private static final String WHISPER_LANGUAGE = "whisperLanguage";
    public static final String WHISPER_LANGUAGE_AUTO = "auto";
    private static LeanKeyPreferences sInstance;
    private final Context mContext;
    private SharedPreferences mPrefs;

    public static LeanKeyPreferences instance(Context ctx) {
        if (sInstance == null)
            sInstance = new LeanKeyPreferences(ctx);
        return sInstance;
    }

    public LeanKeyPreferences(Context context) {
        mContext = context.getApplicationContext();
        mPrefs = PreferenceManager.getDefaultSharedPreferences(mContext);
    }

    public boolean isRunOnce() {
        return mPrefs.getBoolean(APP_RUN_ONCE, false);
    }

    public void setRunOnce(boolean runOnce) {
        mPrefs.edit()
                .putBoolean(APP_RUN_ONCE, runOnce)
                .apply();
    }

    public void setPreferredLanguage(String name) {
        mPrefs.edit()
                .putString(BOOTSTRAP_SELECTED_LANGUAGE, name)
                .apply();
    }

    public String getPreferredLanguage() {
        return mPrefs.getString(BOOTSTRAP_SELECTED_LANGUAGE, "");
    }

    public int getKeyboardIndex() {
        return mPrefs.getInt(APP_KEYBOARD_INDEX, 0);
    }

    public void setKeyboardIndex(int idx) {
        mPrefs.edit()
                .putInt(APP_KEYBOARD_INDEX, idx)
                .apply();
    }

    public boolean getForceShowKeyboard() {
        return mPrefs.getBoolean(FORCE_SHOW_KEYBOARD, true);
    }

    public void setForceShowKeyboard(boolean force) {
        mPrefs.edit()
                .putBoolean(FORCE_SHOW_KEYBOARD, force)
                .apply();
    }

    /**
     * Key size multiplier (1.0 == original size).<br/>
     * Falls back to the legacy "enlarge keyboard" switch while it hasn't been migrated yet.
     */
    public float getKeyboardScale() {
        float legacy = mPrefs.getBoolean(ENLARGE_KEYBOARD, false) ? LEGACY_ENLARGE_SCALE : DEFAULT_KEYBOARD_SCALE;

        return mPrefs.getFloat(KEYBOARD_SCALE, legacy);
    }

    public void setKeyboardScale(float scale) {
        mPrefs.edit()
                .putFloat(KEYBOARD_SCALE, scale)
                .remove(ENLARGE_KEYBOARD) // superseded by the scale value
                .apply();
    }


    public void setCurrentTheme(String theme) {
        mPrefs.edit()
                .putString(KEYBOARD_THEME, theme)
                .apply();
    }

    public String getCurrentTheme() {
        return mPrefs.getString(KEYBOARD_THEME, THEME_DARK3);
    }

    public void setSuggestionsEnabled(boolean enabled) {
        mPrefs.edit()
                .putBoolean(SUGGESTIONS_ENABLED, enabled)
                .apply();
    }

    public boolean getSuggestionsEnabled() {
        return mPrefs.getBoolean(SUGGESTIONS_ENABLED, true);
    }

    public void setCyclicNavigationEnabled(boolean enabled) {
        mPrefs.edit()
                .putBoolean(CYCLIC_NAVIGATION_ENABLED, enabled)
                .apply();
    }

    public boolean isCyclicNavigationEnabled() {
        return mPrefs.getBoolean(CYCLIC_NAVIGATION_ENABLED, false);
    }

    public boolean getAutodetectLayout() {
        return mPrefs.getBoolean(AUTODETECT_LAYOUT, false);
    }

    public void setNextWordSuggestionsEnabled(boolean enabled) {
        mPrefs.edit()
                .putBoolean(NEXT_WORD_SUGGESTIONS_ENABLED, enabled)
                .apply();
    }

    public boolean getNextWordSuggestionsEnabled() {
        return mPrefs.getBoolean(NEXT_WORD_SUGGESTIONS_ENABLED, true);
    }

    public void setVoiceSearchEnabled(boolean enabled) {
        mPrefs.edit()
                .putBoolean(VOICE_SEARCH_ENABLED, enabled)
                .apply();
    }

    public boolean getVoiceSearchEnabled() {
        return mPrefs.getBoolean(VOICE_SEARCH_ENABLED, true);
    }

    /**
     * Serialized word statistics used by the next word predictor
     */
    public void setWordHistory(String history) {
        mPrefs.edit()
                .putString(WORD_HISTORY, history)
                .apply();
    }

    public String getWordHistory() {
        return mPrefs.getString(WORD_HISTORY, "");
    }

    /**
     * Use the built in on device recognition instead of an external voice app
     */
    public void setWhisperEnabled(boolean enabled) {
        mPrefs.edit()
                .putBoolean(WHISPER_ENABLED, enabled)
                .apply();
    }

    public boolean getWhisperEnabled() {
        return mPrefs.getBoolean(WHISPER_ENABLED, true);
    }

    public void setWhisperModel(String modelId) {
        mPrefs.edit()
                .putString(WHISPER_MODEL, modelId)
                .apply();
    }

    public String getWhisperModel() {
        return mPrefs.getString(WHISPER_MODEL, "tiny-q5_1");
    }

    public void setWhisperLanguage(String language) {
        mPrefs.edit()
                .putString(WHISPER_LANGUAGE, language)
                .apply();
    }

    public String getWhisperLanguage() {
        return mPrefs.getString(WHISPER_LANGUAGE, WHISPER_LANGUAGE_AUTO);
    }
}
