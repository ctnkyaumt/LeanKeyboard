package com.liskovsoft.leankeyboard.ime;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Intent;
import android.inputmethodservice.InputMethodService;
import android.os.Build.VERSION;
import android.os.Handler;
import android.os.Message;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.InputConnection;
import androidx.core.text.BidiFormatter;
import com.liskovsoft.leankeyboard.ime.LeanbackKeyboardController.InputListener;
import com.liskovsoft.leankeyboard.utils.LeanKeyPreferences;

public class LeanbackImeService extends KeyMapperImeService {
    private static final String TAG = LeanbackImeService.class.getSimpleName();
    private static final boolean DEBUG = false;
    public static final String IME_CLOSE = "com.google.android.athome.action.IME_CLOSE";
    public static final String IME_OPEN = "com.google.android.athome.action.IME_OPEN";
    public static final int MAX_SUGGESTIONS = 10;
    static final int MODE_FREE_MOVEMENT = 1;
    static final int MODE_TRACKPAD_NAVIGATION = 0;
    private static final int MSG_SUGGESTIONS_CLEAR = 123;
    private static final int SUGGESTIONS_CLEAR_DELAY = 1000;
    /** How many chars around the caret are inspected to detect rtl text */
    private static final int RTL_PROBE_LEN = 64;
    /** How much text before the caret feeds the next word predictor */
    private static final int PREDICTION_CONTEXT_LEN = 64;
    /** How much text is learned at once when the user submits a field */
    private static final int LEARN_CONTEXT_LEN = 512;
    private static final int MAX_PREDICTIONS = 5;
    private boolean mEnterSpaceBeforeCommitting;
    private View mInputView;
    private LeanbackKeyboardController mKeyboardController;
    private boolean mShouldClearSuggestions = true;
    private LeanbackSuggestionsFactory mSuggestionsFactory;
    public static final String COMMAND_RESTART = "restart";
    private boolean mForceShowKbd;
    private NextWordPredictor mPredictor;
    private boolean mNextWordEnabled;
    /** Index of the first predicted word in the suggestions strip */
    private int mPredictionStartIndex = Integer.MAX_VALUE;

    @SuppressLint("HandlerLeak")
    private final Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            if (msg.what == MSG_SUGGESTIONS_CLEAR && mShouldClearSuggestions) {
                mSuggestionsFactory.clearSuggestions();
                refreshSuggestions();
                mShouldClearSuggestions = false;
            }

        }
    };

    private InputListener mInputListener = this::handleTextEntry;

    @SuppressLint("NewApi")
    @SuppressWarnings("deprecation")
    public LeanbackImeService() {
        if (VERSION.SDK_INT < 21 && !enableHardwareAcceleration()) {
            Log.w("LbImeService", "Could not enable hardware acceleration");
        }
    }

    @Override
    public void onCreate() {
        //setupDensity();
        super.onCreate();

        Log.d(TAG, "onCreate");

        initSettings();
    }

    private void setupDensity() {
        float scale = LeanKeyPreferences.instance(this).getKeyboardScale();

        if (scale != LeanKeyPreferences.DEFAULT_KEYBOARD_SCALE) {
            DisplayMetrics metrics = LeanbackUtils.createMetricsFrom(this, scale);

            if (metrics != null) {
                getResources().getDisplayMetrics().setTo(metrics);
            }
        }
    }

    private void initSettings() {
        LeanKeyPreferences prefs = LeanKeyPreferences.instance(this);
        mForceShowKbd = prefs.getForceShowKeyboard();
        mNextWordEnabled = prefs.getNextWordSuggestionsEnabled();

        if (!mNextWordEnabled && mSuggestionsFactory != null) {
            mSuggestionsFactory.clearPredictions();
        }

        if (mKeyboardController != null) {
            mKeyboardController.setSuggestionsEnabled(prefs.getSuggestionsEnabled());
        }
    }

    private void clearSuggestionsDelayed() {
        if (!mSuggestionsFactory.shouldSuggestionsAmend()) {
            mHandler.removeMessages(MSG_SUGGESTIONS_CLEAR);
            mShouldClearSuggestions = true;
            mHandler.sendEmptyMessageDelayed(MSG_SUGGESTIONS_CLEAR, SUGGESTIONS_CLEAR_DELAY);
        }

    }

    private void handleTextEntry(final int type, final int keyCode, final CharSequence text) {
        final InputConnection connection = getCurrentInputConnection();
        if (connection != null) {
            boolean updateSuggestions;
            switch (type) {
                case InputListener.ENTRY_TYPE_STRING:
                    clearSuggestionsDelayed();
                    if (mEnterSpaceBeforeCommitting && mKeyboardController.enableAutoEnterSpace()) {
                        if (LeanbackUtils.isAlphabet(keyCode)) {
                            connection.commitText(" ", 1);
                        }

                        mEnterSpaceBeforeCommitting = false;
                    }

                    connection.commitText(text, 1);
                    updateSuggestions = true;
                    if (keyCode == LeanbackKeyboardView.ASCII_PERIOD) {
                        mEnterSpaceBeforeCommitting = true;
                    }
                    if (isWordSeparator(text)) {
                        learn(false);
                    }
                    break;
                case InputListener.ENTRY_TYPE_BACKSPACE:
                    clearSuggestionsDelayed();
                    if (LeanbackUtils.hasSelection(connection)) {
                        connection.commitText("", 1); // wipe the selected text instead of a single char
                    } else {
                        connection.deleteSurroundingText(1, 0);
                    }
                    mEnterSpaceBeforeCommitting = false;
                    updateSuggestions = true;
                    break;
                case InputListener.ENTRY_TYPE_SUGGESTION:
                    // a predicted word replaces the word being typed, it doesn't submit the field
                    if (isPrediction(keyCode)) {
                        clearSuggestionsDelayed();
                        commitPrediction(connection, text);
                        updateSuggestions = true;
                        break;
                    }
                case InputListener.ENTRY_TYPE_VOICE:
                    clearSuggestionsDelayed();
                    if (!mSuggestionsFactory.shouldSuggestionsAmend()) {
                        connection.deleteSurroundingText(LeanbackUtils.getCharLengthBeforeCursor(connection), LeanbackUtils.getCharLengthAfterCursor(connection));
                    } else {
                        int location = LeanbackUtils.getAmpersandLocation(connection);
                        connection.setSelection(location, location);
                        connection.deleteSurroundingText(0, LeanbackUtils.getCharLengthAfterCursor(connection));
                    }

                    connection.commitText(text, 1);
                    mEnterSpaceBeforeCommitting = true;
                case InputListener.ENTRY_TYPE_ACTION:  // User presses Go, Send, Search etc
                    learn(true); // remember what was submitted, it's likely to be typed again
                    boolean result = sendDefaultEditorAction(true);

                    if (result) {
                        hideWindow(); // SmartYouTubeTV: hide kbd on search page fix
                    } else {
                        LeanbackUtils.sendEnterKey(connection);
                    }

                    updateSuggestions = false;
                    break;
                case InputListener.ENTRY_TYPE_LEFT:
                case InputListener.ENTRY_TYPE_RIGHT:
                    if (moveCursor(connection, type == InputListener.ENTRY_TYPE_LEFT)) {
                        updateSuggestions = true;
                        break;
                    }

                    BidiFormatter formatter = BidiFormatter.getInstance();

                    CharSequence textBeforeCursor = connection.getTextBeforeCursor(LeanbackUtils.MAX_TEXT_LEN, 0);
                    int lenBefore = 0;
                    boolean isRtlBefore = false;
                    //int rtlLenBefore = 0;
                    if (textBeforeCursor != null) {
                        lenBefore = textBeforeCursor.length();
                        isRtlBefore = formatter.isRtl(textBeforeCursor);
                        //rtlLenBefore = LeanbackUtils.getRtlLenBeforeCursor(textBeforeCursor);
                    }

                    CharSequence textAfterCursor = connection.getTextAfterCursor(LeanbackUtils.MAX_TEXT_LEN, 0);
                    int lenAfter = 0;
                    //int rtlLenAfter = 0;
                    boolean isRtlAfter = false;
                    if (textAfterCursor != null) {
                        lenAfter = textAfterCursor.length();
                        isRtlAfter = formatter.isRtl(textAfterCursor);
                        //rtlLenAfter = LeanbackUtils.getRtlLenAfterCursor(textAfterCursor);
                    }

                    int index = lenBefore;
                    if (type == InputListener.ENTRY_TYPE_LEFT) {
                        if (lenBefore > 0) {
                            if (!isRtlBefore) {
                                index = lenBefore - 1;
                            } else {
                                if (lenAfter == 0) {
                                    index = 1;
                                } else if (lenAfter == 1) {
                                    index = 0;
                                } else {
                                    index = lenBefore + 1;
                                }
                            }
                        }

                        //Log.d(TAG, String.format("direction key: before: lenBefore=%s, lenAfter=%s, rtlLenBefore=%s, rtlLenAfter=%s", lenBefore, lenAfter, rtlLenBefore, rtlLenAfter));
                        Log.d(TAG, String.format("direction key: before: lenBefore=%s, lenAfter=%s, isRtlBefore=%s", lenBefore, lenAfter, isRtlBefore));
                    } else {
                        if (lenAfter > 0) {
                            if (!isRtlAfter) {
                                index = lenBefore + 1;
                            } else {
                                if (lenBefore == 0) {
                                    index = lenAfter - 1;
                                } else if (lenBefore == 1) {
                                    index = lenAfter + 1;
                                } else {
                                    index = lenBefore - 1;
                                }
                            }
                        }

                        //Log.d(TAG, String.format("direction key: after: lenBefore=%s, lenAfter=%s, rtlLenBefore=%s, rtlLenAfter=%s", lenBefore, lenAfter, rtlLenBefore, rtlLenAfter));
                        Log.d(TAG, String.format("direction key: after: lenBefore=%s, lenAfter=%s, isRtlAfter=%s", lenBefore, lenAfter, isRtlAfter));
                    }

                    Log.d(TAG, "direction key: index: " + index);

                    connection.setSelection(index, index);
                    updateSuggestions = true;
                    break;
                case InputListener.ENTRY_TYPE_DISMISS:
                    connection.performEditorAction(EditorInfo.IME_ACTION_NONE);
                    updateSuggestions = false;
                    break;
                case InputListener.ENTRY_TYPE_VOICE_DISMISS:
                    connection.performEditorAction(EditorInfo.IME_ACTION_GO);
                    updateSuggestions = false;
                    break;
                default:
                    updateSuggestions = true;
            }

            if (updateSuggestions) {
                refreshSuggestions();
            }
        }
    }

    /**
     * Push the current suggestions (regular ones plus predicted words) to the keyboard
     */
    private void refreshSuggestions() {
        if (!mKeyboardController.areSuggestionsEnabled()) {
            return;
        }

        updatePredictions();
        mPredictionStartIndex = mNextWordEnabled ? mSuggestionsFactory.getPredictionStartIndex() : Integer.MAX_VALUE;
        mKeyboardController.updateSuggestions(mSuggestionsFactory.getSuggestions());
    }

    private void updatePredictions() {
        if (!mNextWordEnabled) {
            mSuggestionsFactory.clearPredictions();
            return;
        }

        InputConnection connection = getCurrentInputConnection();
        CharSequence before = connection == null ? null : connection.getTextBeforeCursor(PREDICTION_CONTEXT_LEN, 0);

        mSuggestionsFactory.setPredictions(mPredictor.getSuggestions(before, MAX_PREDICTIONS));
    }

    /**
     * Feed the predictor with what the user typed
     * @param wholeField learn the whole field content instead of just the last word
     */
    private void learn(boolean wholeField) {
        if (!mNextWordEnabled) {
            return;
        }

        InputConnection connection = getCurrentInputConnection();

        if (connection == null) {
            return;
        }

        if (wholeField) {
            // bounded window, learning a whole document would be slow and add mostly noise
            mPredictor.learn(connection.getTextBeforeCursor(LEARN_CONTEXT_LEN, 0));
        } else {
            mPredictor.learnLastWord(connection.getTextBeforeCursor(PREDICTION_CONTEXT_LEN, 0));
        }
    }

    private static boolean isWordSeparator(CharSequence text) {
        if (TextUtils.isEmpty(text)) {
            return false;
        }

        char last = text.charAt(text.length() - 1);

        return !Character.isLetterOrDigit(last) && last != '\'' && last != '-';
    }

    private boolean isPrediction(int suggestionIndex) {
        return mNextWordEnabled && suggestionIndex >= mPredictionStartIndex;
    }

    /**
     * Replace the word being typed with the picked prediction and append a space.
     */
    private void commitPrediction(InputConnection connection, CharSequence word) {
        if (TextUtils.isEmpty(word)) {
            return;
        }

        CharSequence before = connection.getTextBeforeCursor(PREDICTION_CONTEXT_LEN, 0);
        String current = NextWordPredictor.getCurrentWord(before);

        if (!current.isEmpty()) {
            connection.deleteSurroundingText(current.length(), 0);
        }

        connection.commitText(word + " ", 1);
        mEnterSpaceBeforeCommitting = false;
    }

    /**
     * Move the caret one char left/right using absolute offsets reported by the editor.<br/>
     * The old implementation derived the caret from {@code getTextBeforeCursor(1000)} which pinned
     * the caret around char #1000 in longer texts.
     * @return whether the move was performed (false means the editor doesn't support extraction)
     */
    private boolean moveCursor(InputConnection connection, boolean left) {
        ExtractedText extracted = LeanbackUtils.getExtractedText(connection);

        if (extracted == null || extracted.text == null || extracted.selectionStart < 0 || extracted.selectionEnd < 0) {
            return false;
        }

        CharSequence text = extracted.text;
        int len = text.length();
        int selStart = Math.min(extracted.selectionStart, extracted.selectionEnd);
        int selEnd = Math.max(extracted.selectionStart, extracted.selectionEnd);

        // in rtl text the visually left key means "logically forward"
        boolean backward = left != isRtlAround(text, selStart);

        int pos;
        if (selStart != selEnd) {
            pos = backward ? selStart : selEnd; // collapse the selection to one of its edges
        } else {
            pos = backward ? selStart - 1 : selStart + 1;

            // don't land in the middle of a surrogate pair
            if (pos > 0 && pos < len && Character.isLowSurrogate(text.charAt(pos))) {
                pos += backward ? -1 : 1;
            }
        }

        pos = Math.max(0, Math.min(pos, len));

        int absolute = extracted.startOffset + pos;
        connection.setSelection(absolute, absolute);

        return true;
    }

    private static boolean isRtlAround(CharSequence text, int pos) {
        int from = Math.max(0, pos - RTL_PROBE_LEN);
        int to = Math.min(text.length(), pos + RTL_PROBE_LEN);

        return from < to && BidiFormatter.getInstance().isRtl(text.subSequence(from, to));
    }

    @Override
    public View onCreateInputView() {
        mInputView = mKeyboardController.getView();
        mInputView.requestFocus();

        return mInputView;
    }

    @Override
    public void onDisplayCompletions(CompletionInfo[] infos) {
        if (mKeyboardController.areSuggestionsEnabled()) {
            mShouldClearSuggestions = false;
            mHandler.removeMessages(MSG_SUGGESTIONS_CLEAR);
            mSuggestionsFactory.onDisplayCompletions(infos);
            refreshSuggestions();
        }

    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false; // don't change it (true shows edit dialog above kbd)
    }

    /**
     * At this point, decision whether to show kbd taking place<br/>
     * <a href="https://stackoverflow.com/questions/7449283/is-it-possible-to-have-both-physical-keyboard-and-soft-keyboard-active-at-the-sa">More info</a>
     * @return whether to show kbd
     */
    @Override
    public boolean onEvaluateInputViewShown() {
        Log.d(TAG, "onEvaluateInputViewShown");
        return mForceShowKbd || super.onEvaluateInputViewShown();
    }

    // FireTV fix
    @Override
    public boolean onShowInputRequested(int flags, boolean configChange) {
        Log.d(TAG, "onShowInputRequested");
        return mForceShowKbd || super.onShowInputRequested(flags, configChange);
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        sendBroadcast(new Intent(IME_CLOSE));
        mSuggestionsFactory.clearSuggestions();
        mSuggestionsFactory.clearPredictions();
        mPredictor.save();
        mKeyboardController.releaseVoiceResources();

        // NOTE: Trying to fix kbd without UI bug (telegram)
        reInitKeyboard();
    }

    @SuppressLint("NewApi")
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        return isInputViewShown() &&
                (event.getSource() & InputDevice.SOURCE_TOUCH_NAVIGATION) == InputDevice.SOURCE_TOUCH_NAVIGATION &&
                mKeyboardController.onGenericMotionEvent(event) || super.onGenericMotionEvent(event);
    }

    @SuppressLint("WrongConstant")
    public void hideIme() {
        requestHideSelf(InputMethodService.BACK_DISPOSITION_DEFAULT);
    }

    @Override
    public void onInitializeInterface() {
        mKeyboardController = new LeanbackKeyboardController(this, mInputListener);
        mKeyboardController.setHideWhenPhysicalKeyboardUsed(!mForceShowKbd);
        mEnterSpaceBeforeCommitting = false;
        mSuggestionsFactory = new LeanbackSuggestionsFactory(this, MAX_SUGGESTIONS);
        mPredictor = new NextWordPredictor(this);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        //// DOESN'T WORK!!!
        //// Hide keyboard on ESC key: https://github.com/yuliskov/SmartYouTubeTV/issues/142
        //event = mapEscToBack(event);
        //keyCode = mapEscToBack(keyCode);

        // Hide keyboard on ESC key: https://github.com/yuliskov/SmartYouTubeTV/issues/142
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            hideIme();
            return true;
        }

        return isInputViewShown() && mKeyboardController.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        //// DOESN'T WORK!!!
        //// Hide keyboard on ESC key: https://github.com/yuliskov/SmartYouTubeTV/issues/142
        //event = mapEscToBack(event);
        //keyCode = mapEscToBack(keyCode);

        return isInputViewShown() && mKeyboardController.onKeyUp(keyCode, event) || super.onKeyUp(keyCode, event);
    }

    private KeyEvent mapEscToBack(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
            // pay attention, you must pass the same action
            event = new KeyEvent(event.getAction(), KeyEvent.KEYCODE_BACK);
        }
        return event;
    }

    private int mapEscToBack(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            keyCode = KeyEvent.KEYCODE_BACK;
        }
        return keyCode;
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        if (intent != null) {
            Log.d(TAG, "onStartCommand: " + intent.toUri(0));

            if (intent.getBooleanExtra(COMMAND_RESTART, false)) {
                Log.d(TAG, "onStartCommand: trying to restart service");

                reInitKeyboard();

                return Service.START_REDELIVER_INTENT;
            }
        }

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onStartInput(EditorInfo info, boolean restarting) {
        super.onStartInput(info, restarting);
        mEnterSpaceBeforeCommitting = false;
        mSuggestionsFactory.onStartInput(info);
        mKeyboardController.onStartInput(info);
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);

        mKeyboardController.onStartInputView();
        sendBroadcast(new Intent(IME_OPEN));
        if (mKeyboardController.areSuggestionsEnabled()) {
            mSuggestionsFactory.createSuggestions();
            refreshSuggestions();

            // NOTE: FileManager+ rename item fix: https://t.me/LeanKeyboard/931
            // NOTE: Code below deletes text that has selection.
            //InputConnection connection = getCurrentInputConnection();
            //if (connection != null) {
            //    String text = LeanbackUtils.getEditorText(connection);
            //    connection.deleteSurroundingText(LeanbackUtils.getCharLengthBeforeCursor(connection), LeanbackUtils.getCharLengthAfterCursor(connection));
            //    connection.commitText(text, 1);
            //}
        }
    }

    private void reInitKeyboard() {
        initSettings();

        if (mKeyboardController != null) {
            mKeyboardController.initKeyboards();
        }
    }
}
