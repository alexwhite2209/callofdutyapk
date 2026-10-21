package com.winlator;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.winlator.autosetup.AutoSetup;
import com.winlator.autosetup.DeviceProfile;
import com.winlator.autosetup.GameSettings;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.Shortcut;
import com.winlator.contentdialog.ContentDialog;
import com.winlator.core.AppUtils;

/** Simple, plain-language settings screen for Call of Duty 4 that replaces the emulator options. */
public class GameSettingsFragment extends Fragment {
    private static final int[] QUALITY_IDS = {R.id.RBQualityFast, R.id.RBQualityBalanced, R.id.RBQualityBeautiful};
    private DeviceProfile profile;
    private Container container;

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ((AppCompatActivity)getActivity()).getSupportActionBar().setTitle(R.string.gs_title);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        final Context context = getContext();
        View view = inflater.inflate(R.layout.game_settings_fragment, container, false);
        profile = DeviceProfile.detect(context);
        this.container = AutoSetup.findContainer(context);

        TextView tvDeviceInfo = view.findViewById(R.id.TVDeviceInfo);
        tvDeviceInfo.setText(profile.describe());

        markRecommended(view.findViewById(QUALITY_IDS[profile.getRecommendedQuality()]));
        markRecommended(view.findViewById(profile.getRecommendedFPS() == 30 ? R.id.RBFPS30 : R.id.RBFPS60));

        if (this.container == null) {
            AutoSetup.showGameNotFoundDialog((MainActivity)getActivity());
            showSettings(view, GameSettings.recommended(profile));
        }
        else showSettings(view, GameSettings.load(this.container, profile));

        view.findViewById(R.id.BTAuto).setOnClickListener((v) -> {
            showSettings(view, GameSettings.recommended(profile));
            AppUtils.showToast(context, R.string.gs_auto_done);
        });

        view.findViewById(R.id.BTPlay).setOnClickListener((v) -> {
            if (this.container == null) {
                AutoSetup.showGameNotFoundDialog((MainActivity)getActivity());
                return;
            }
            readSettings(view).apply(context, this.container, profile);
            launchGame(context);
        });

        view.findViewById(R.id.BTAdvanced).setOnClickListener((v) ->
            ContentDialog.confirm(context, R.string.gs_advanced_warning, () -> ((MainActivity)getActivity()).showFragment(new ContainersFragment())));
        return view;
    }

    private void markRecommended(RadioButton radioButton) {
        radioButton.setText(radioButton.getText()+" "+getString(R.string.gs_recommended));
    }

    private void showSettings(View view, GameSettings settings) {
        ((RadioGroup)view.findViewById(R.id.RGQuality)).check(QUALITY_IDS[settings.quality]);
        ((RadioGroup)view.findViewById(R.id.RGFPS)).check(settings.fps == 30 ? R.id.RBFPS30 : R.id.RBFPS60);
        ((CheckBox)view.findViewById(R.id.CBTouchControls)).setChecked(settings.touchControls);
        ((CheckBox)view.findViewById(R.id.CBFullscreen)).setChecked(settings.fullscreen);
        ((CheckBox)view.findViewById(R.id.CBFewerCrashes)).setChecked(settings.fewerCrashes);
        ((CheckBox)view.findViewById(R.id.CBShowFPS)).setChecked(settings.showFPS);
    }

    private GameSettings readSettings(View view) {
        GameSettings settings = new GameSettings();
        int qualityId = ((RadioGroup)view.findViewById(R.id.RGQuality)).getCheckedRadioButtonId();
        settings.quality = qualityId == R.id.RBQualityBeautiful ? GameSettings.QUALITY_BEAUTIFUL : (qualityId == R.id.RBQualityBalanced ? GameSettings.QUALITY_BALANCED : GameSettings.QUALITY_FAST);
        settings.fps = ((RadioGroup)view.findViewById(R.id.RGFPS)).getCheckedRadioButtonId() == R.id.RBFPS30 ? 30 : 60;
        settings.touchControls = ((CheckBox)view.findViewById(R.id.CBTouchControls)).isChecked();
        settings.fullscreen = ((CheckBox)view.findViewById(R.id.CBFullscreen)).isChecked();
        settings.fewerCrashes = ((CheckBox)view.findViewById(R.id.CBFewerCrashes)).isChecked();
        settings.showFPS = ((CheckBox)view.findViewById(R.id.CBShowFPS)).isChecked();
        return settings;
    }

    private void launchGame(Context context) {
        for (Shortcut shortcut : new ContainerManager(context).loadShortcuts(null)) {
            if (shortcut.container.id == container.id && shortcut.name.equals(AutoSetup.CONTAINER_NAME)) {
                Intent intent = new Intent(context, XServerDisplayActivity.class);
                intent.putExtra("container_id", shortcut.container.id);
                intent.putExtra("shortcut_path", shortcut.file.getPath());
                startActivity(intent);
                return;
            }
        }
        ((MainActivity)getActivity()).showFragment(new ShortcutsFragment());
    }
}
