package com.liskovsoft.leankeyboard.fragments.settings;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.leanback.widget.GuidanceStylist.Guidance;
import com.liskovsoft.leankeyboard.utils.LeanKeyPreferences;
import com.liskovsoft.leankeykeyboard.R;

public class KbSizeFragment extends BaseSettingsFragment {
    private static final float SCALE_EPSILON = 0.001f;
    private Context mContext;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mContext = context;

        initRadioItems();
    }

    @NonNull
    @Override
    public Guidance onCreateGuidance(Bundle savedInstanceState) {
        String title = getActivity().getResources().getString(R.string.kb_size);
        String desc = getActivity().getResources().getString(R.string.kb_size_desc);
        Drawable icon = ContextCompat.getDrawable(getActivity(), R.drawable.ic_launcher);

        return new Guidance(
                title,
                desc,
                "",
                icon
        );
    }

    private void initRadioItems() {
        String[] scales = mContext.getResources().getStringArray(R.array.keyboard_scale_values);

        LeanKeyPreferences prefs = LeanKeyPreferences.instance(mContext);
        float currentScale = prefs.getKeyboardScale();

        for (String scale : scales) {
            String[] split = scale.split("\\|");
            String label = split[0];
            float factor = Float.parseFloat(split[1]);

            addRadioAction(label,
                    () -> Math.abs(currentScale - factor) < SCALE_EPSILON,
                    (checked) -> prefs.setKeyboardScale(factor));
        }
    }
}
