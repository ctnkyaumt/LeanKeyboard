package com.liskovsoft.leankeyboard.addons.voice;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.speech.RecognizerIntent;

import java.util.List;

/**
 * Speech recognition backed by FUTO Voice Input (on device, no network).
 * <a href="https://github.com/futo-org/voice-input">https://github.com/futo-org/voice-input</a>
 * <br/>
 * FUTO ships its recognition service disabled, so the supported way in is the
 * RECOGNIZE_SPEECH intent addressed explicitly to its package.
 */
public class FutoVoiceDialog implements VoiceDialog, ActivityListener {
    public static final String FUTO_PACKAGE = "org.futo.voiceinput";
    public static final String FUTO_PACKAGE_DEV = "org.futo.voiceinput.dev";
    private static final int SPEECH_REQUEST_CODE = 12;
    private final Activity mActivity;
    private final SearchCallback mCallback;

    FutoVoiceDialog(Activity activity, SearchCallback callback) {
        mActivity = activity;
        mCallback = callback;
    }

    /**
     * @return package name of the installed FUTO Voice Input or null when it isn't installed
     */
    public static String findPackage(Context context) {
        for (String pkg : new String[] {FUTO_PACKAGE, FUTO_PACKAGE_DEV}) {
            if (isInstalled(context, pkg)) {
                return pkg;
            }
        }

        return null;
    }

    public static boolean isInstalled(Context context) {
        return findPackage(context) != null;
    }

    private static boolean isInstalled(Context context, String pkg) {
        try {
            context.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    @Override
    public boolean displaySpeechRecognizer() {
        String pkg = findPackage(mActivity);

        if (pkg == null) {
            return false;
        }

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.setPackage(pkg);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);

        try {
            mActivity.startActivityForResult(intent, SPEECH_REQUEST_CODE);
        } catch (ActivityNotFoundException e) {
            e.printStackTrace();
            return false;
        }

        return true;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == SPEECH_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
            List<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);

            if (results != null && !results.isEmpty()) {
                mCallback.openSearchPage(results.get(0));
            }
        }
    }
}
