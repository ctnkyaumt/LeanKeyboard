package com.liskovsoft.leankeyboard.fragments.settings;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.leanback.widget.GuidanceStylist.Guidance;
import com.liskovsoft.leankeyboard.addons.voice.whisper.WhisperLib;
import com.liskovsoft.leankeyboard.addons.voice.whisper.WhisperModel;
import com.liskovsoft.leankeyboard.addons.voice.whisper.WhisperModelManager;
import com.liskovsoft.leankeyboard.helpers.MessageHelpers;
import com.liskovsoft.leankeyboard.utils.LeanKeyPreferences;
import com.liskovsoft.leankeykeyboard.R;

import java.io.File;

/**
 * On device voice recognition setup: pick a model, download it, remove it.
 */
public class VoiceFragment extends BaseSettingsFragment {
    private Context mContext;
    private LeanKeyPreferences mPrefs;
    private WhisperModelManager mModels;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);

        mContext = context;
        mPrefs = LeanKeyPreferences.instance(context);
        mModels = new WhisperModelManager(context);

        addCheckedAction(R.string.enable_offline_voice, R.string.enable_offline_voice_desc,
                mPrefs::getWhisperEnabled, mPrefs::setWhisperEnabled);

        initModelItems();

        addNextAction(R.string.download_voice_model, this::downloadCurrentModel);
        addNextAction(R.string.delete_voice_model, this::deleteCurrentModel);
    }

    @NonNull
    @Override
    public Guidance onCreateGuidance(Bundle savedInstanceState) {
        String title = getActivity().getResources().getString(R.string.voice_settings);
        String desc = getActivity().getResources().getString(
                WhisperLib.isAvailable() ? R.string.voice_settings_desc : R.string.voice_unsupported);
        Drawable icon = ContextCompat.getDrawable(getActivity(), R.drawable.ic_launcher);

        return new Guidance(
                title,
                desc,
                "",
                icon
        );
    }

    private void initModelItems() {
        for (WhisperModel model : WhisperModel.values()) {
            String title = getString(R.string.voice_model_item, model.getId(), model.getSizeMb());

            addRadioAction(title,
                    () -> model.getId().equals(mPrefs.getWhisperModel()),
                    (checked) -> mPrefs.setWhisperModel(model.getId()));
        }
    }

    private WhisperModel getCurrentModel() {
        return WhisperModel.fromId(mPrefs.getWhisperModel());
    }

    private void downloadCurrentModel() {
        WhisperModel model = getCurrentModel();

        if (mModels.isDownloading()) {
            MessageHelpers.showMessage(mContext, getString(R.string.voice_model_downloading, 0));
            return;
        }

        if (mModels.isDownloaded(model)) {
            MessageHelpers.showMessage(mContext, getString(R.string.voice_model_ready));
            return;
        }

        MessageHelpers.showMessage(mContext, getString(R.string.voice_model_download_started, model.getSizeMb()));

        mModels.download(model, new WhisperModelManager.DownloadListener() {
            private int mLastReported = -1;

            @Override
            public void onProgress(int percent) {
                // toasts are the only ui a guided step gives us, keep them rare
                if (percent >= 0 && percent / 25 != mLastReported / 25) {
                    mLastReported = percent;
                    MessageHelpers.showMessage(mContext, getString(R.string.voice_model_downloading, percent));
                }
            }

            @Override
            public void onDone(File file) {
                MessageHelpers.showMessage(mContext, getString(R.string.voice_model_ready));
            }

            @Override
            public void onError(String message) {
                MessageHelpers.showLongMessage(mContext, getString(R.string.voice_model_failed, message));
            }
        });
    }

    private void deleteCurrentModel() {
        WhisperModel model = getCurrentModel();

        if (mModels.delete(model)) {
            MessageHelpers.showMessage(mContext, getString(R.string.voice_model_deleted));
        } else {
            MessageHelpers.showMessage(mContext, getString(R.string.voice_model_missing));
        }
    }
}
