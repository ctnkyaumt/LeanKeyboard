package com.liskovsoft.leankeyboard.ime;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;
import com.liskovsoft.leankeyboard.utils.LeanKeyPreferences;
import com.liskovsoft.leankeykeyboard.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Word completion and next word prediction.<br/>
 * Combines a static seed vocabulary shipped with the app with a bigram model learned
 * from what the user actually types. The learned part is persisted to the preferences.
 */
public class NextWordPredictor {
    private static final String TAG = "NextWordPredictor";
    /** Max amount of distinct words tracked, oldest/rarest are dropped beyond that */
    private static final int MAX_WORDS = 2000;
    /** Max amount of successors remembered per word */
    private static final int MAX_SUCCESSORS = 8;
    private static final String ENTRY_SEPARATOR = "\n";
    private static final String FIELD_SEPARATOR = " ";

    private final LeanKeyPreferences mPrefs;
    private final Context mContext;
    /** word -> how often it was typed */
    private final Map<String, Integer> mUnigrams = new HashMap<>();
    /** word -> successors ordered by how often they followed it */
    private final Map<String, Map<String, Integer>> mBigrams = new HashMap<>();
    private final List<String> mSeedWords = new ArrayList<>();
    private boolean mLoaded;
    private boolean mDirty;

    public NextWordPredictor(Context context) {
        mContext = context;
        mPrefs = LeanKeyPreferences.instance(context);
    }

    /**
     * Suggestions for the word being typed, or - when the caret sits right after a word
     * separator - the words likely to come next.
     * @param textBeforeCursor recent text typed by the user
     * @param maxCount max amount of returned words
     */
    public List<String> getSuggestions(CharSequence textBeforeCursor, int maxCount) {
        load();

        String prefix = getCurrentWord(textBeforeCursor);
        String previous = getPreviousWord(textBeforeCursor);

        Set<String> result = new LinkedHashSet<>();

        if (prefix.isEmpty()) {
            addAll(result, getSuccessors(previous), null, maxCount);
        } else {
            // complete the word being typed, successors of the previous word come first
            addAll(result, getSuccessors(previous), prefix, maxCount);
            addAll(result, sortedByCount(mUnigrams), prefix, maxCount);
            addAll(result, mSeedWords, prefix, maxCount);
        }

        return applyCaseOf(prefix, result);
    }

    /**
     * Words are stored lowercased. Give them back in the case the user is typing in,
     * so "Ber" proposes "Berlin" rather than "berlin".
     */
    private static List<String> applyCaseOf(String prefix, Set<String> words) {
        List<String> result = new ArrayList<>(words.size());
        boolean capitalize = !prefix.isEmpty() && Character.isUpperCase(prefix.charAt(0));

        for (String word : words) {
            if (capitalize && !word.isEmpty()) {
                result.add(Character.toUpperCase(word.charAt(0)) + word.substring(1));
            } else {
                result.add(word);
            }
        }

        return result;
    }

    /**
     * Learn from the text the user has just committed
     */
    public void learn(CharSequence text) {
        if (TextUtils.isEmpty(text)) {
            return;
        }

        load();

        String[] words = splitWords(text);
        String previous = null;

        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }

            increment(mUnigrams, word);

            if (previous != null) {
                Map<String, Integer> successors = mBigrams.get(previous);

                if (successors == null) {
                    successors = new HashMap<>();
                    mBigrams.put(previous, successors);
                }

                increment(successors, word);
                trim(successors, MAX_SUCCESSORS);
            }

            previous = word;
        }

        mDirty |= words.length > 0;
    }

    /**
     * Learn only the word that was just finished plus its predecessor.<br/>
     * Used while typing, where re-learning the whole context on every key would skew the counts.
     */
    public void learnLastWord(CharSequence textBeforeCursor) {
        if (TextUtils.isEmpty(textBeforeCursor)) {
            return;
        }

        String[] words = splitWords(textBeforeCursor);

        if (words.length == 0) {
            return;
        }

        learn(words.length > 1 ? words[words.length - 2] + " " + words[words.length - 1] : words[words.length - 1]);
    }

    /**
     * Persist the learned data. Cheap when nothing changed.
     */
    public void save() {
        if (!mDirty) {
            return;
        }

        trim(mUnigrams, MAX_WORDS);

        StringBuilder result = new StringBuilder();

        for (Map.Entry<String, Integer> entry : mUnigrams.entrySet()) {
            result.append(entry.getKey()).append(FIELD_SEPARATOR).append(entry.getValue());

            Map<String, Integer> successors = mBigrams.get(entry.getKey());

            if (successors != null) {
                for (Map.Entry<String, Integer> successor : successors.entrySet()) {
                    result.append(FIELD_SEPARATOR).append(successor.getKey()).append(FIELD_SEPARATOR).append(successor.getValue());
                }
            }

            result.append(ENTRY_SEPARATOR);
        }

        mPrefs.setWordHistory(result.toString());
        mDirty = false;
    }

    public void clear() {
        mUnigrams.clear();
        mBigrams.clear();
        mDirty = true;
        save();
    }

    private void load() {
        if (mLoaded) {
            return;
        }

        mLoaded = true;

        Collections.addAll(mSeedWords, mContext.getResources().getStringArray(R.array.common_words));

        String history = mPrefs.getWordHistory();

        if (TextUtils.isEmpty(history)) {
            return;
        }

        try {
            for (String line : history.split(ENTRY_SEPARATOR)) {
                String[] fields = line.split(FIELD_SEPARATOR);

                if (fields.length < 2) {
                    continue;
                }

                mUnigrams.put(fields[0], Integer.parseInt(fields[1]));

                if (fields.length > 2) {
                    Map<String, Integer> successors = new HashMap<>();

                    for (int i = 2; i + 1 < fields.length; i += 2) {
                        successors.put(fields[i], Integer.parseInt(fields[i + 1]));
                    }

                    mBigrams.put(fields[0], successors);
                }
            }
        } catch (NumberFormatException e) { // corrupted store, start over
            Log.w(TAG, "Can't parse the word history, resetting it", e);
            mUnigrams.clear();
            mBigrams.clear();
        }
    }

    private List<String> getSuccessors(String word) {
        if (word == null) {
            return Collections.emptyList();
        }

        Map<String, Integer> successors = mBigrams.get(word);

        return successors == null ? Collections.<String>emptyList() : sortedByCount(successors);
    }

    private static void addAll(Set<String> result, List<String> words, String prefix, int maxCount) {
        for (String word : words) {
            if (result.size() >= maxCount) {
                return;
            }

            if (prefix == null || (word.length() > prefix.length() && startsWithIgnoreCase(word, prefix))) {
                result.add(word);
            }
        }
    }

    private static boolean startsWithIgnoreCase(String word, String prefix) {
        return word.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static List<String> sortedByCount(Map<String, Integer> counts) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        Collections.sort(entries, (left, right) -> right.getValue() - left.getValue());

        List<String> result = new ArrayList<>(entries.size());
        for (Map.Entry<String, Integer> entry : entries) {
            result.add(entry.getKey());
        }

        return result;
    }

    private static void increment(Map<String, Integer> counts, String key) {
        Integer count = counts.get(key);
        counts.put(key, count == null ? 1 : count + 1);
    }

    /**
     * Drop the least used entries so the store doesn't grow forever
     */
    private static void trim(Map<String, Integer> counts, int maxSize) {
        if (counts.size() <= maxSize) {
            return;
        }

        List<String> sorted = sortedByCount(counts);

        for (int i = maxSize; i < sorted.size(); i++) {
            counts.remove(sorted.get(i));
        }
    }

    /**
     * The (possibly incomplete) word the caret sits in
     */
    static String getCurrentWord(CharSequence text) {
        if (text == null) {
            return "";
        }

        int start = text.length();

        while (start > 0 && isWordChar(text.charAt(start - 1))) {
            start--;
        }

        return text.subSequence(start, text.length()).toString();
    }

    /**
     * The last complete word before the one being typed
     */
    static String getPreviousWord(CharSequence text) {
        if (text == null) {
            return null;
        }

        int end = text.length() - getCurrentWord(text).length();

        while (end > 0 && !isWordChar(text.charAt(end - 1))) {
            end--;
        }

        int start = end;
        while (start > 0 && isWordChar(text.charAt(start - 1))) {
            start--;
        }

        return start == end ? null : normalize(text.subSequence(start, end).toString());
    }

    private static String[] splitWords(CharSequence text) {
        List<String> words = new ArrayList<>();
        int start = -1;

        for (int i = 0; i <= text.length(); i++) {
            boolean wordChar = i < text.length() && isWordChar(text.charAt(i));

            if (wordChar && start == -1) {
                start = i;
            } else if (!wordChar && start != -1) {
                words.add(normalize(text.subSequence(start, i).toString()));
                start = -1;
            }
        }

        return words.toArray(new String[0]);
    }

    /**
     * Locale independent on purpose: the store outlives any locale change, so the same word
     * must always normalize to the same key.
     */
    private static String normalize(String word) {
        return word.toLowerCase(Locale.ROOT);
    }

    /**
     * Unicode aware, so cyrillic, greek, arabic, thai and cjk words are learned the same way
     * as latin ones.
     */
    private static boolean isWordChar(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '\'' || ch == '-';
    }
}
