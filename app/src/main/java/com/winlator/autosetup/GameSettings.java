package com.winlator.autosetup;

import android.content.Context;

import com.winlator.box64.Box64Preset;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.DXWrappers;
import com.winlator.container.Shortcut;
import com.winlator.core.KeyValueSet;
import com.winlator.widget.FrameRating;

/**
 * The handful of plain-language game settings shown to the player.
 * Each one is translated into the underlying emulator options of the container and its shortcuts.
 */
public class GameSettings {
    public static final int QUALITY_FAST = 0;
    public static final int QUALITY_BALANCED = 1;
    public static final int QUALITY_BEAUTIFUL = 2;
    public static final int TOUCH_CONTROLS_PROFILE_ID = 4; // built-in "FPS" profile

    public int quality;
    public int fps;
    public boolean fewerCrashes;
    public boolean fullscreen;
    public boolean showFPS;
    public boolean touchControls;

    public static GameSettings recommended(DeviceProfile profile) {
        GameSettings settings = new GameSettings();
        settings.quality = profile.getRecommendedQuality();
        settings.fps = profile.getRecommendedFPS();
        settings.fewerCrashes = !profile.isAdreno();
        settings.fullscreen = true;
        settings.showFPS = false;
        settings.touchControls = true;
        return settings;
    }

    public static GameSettings load(Container container, DeviceProfile profile) {
        if (container.getExtra("gsQuality").isEmpty()) return recommended(profile);
        GameSettings settings = new GameSettings();
        settings.quality = Integer.parseInt(container.getExtra("gsQuality"));
        settings.fps = Integer.parseInt(container.getExtra("gsFPS", "60"));
        settings.fewerCrashes = container.getExtra("gsFewerCrashes").equals("1");
        settings.fullscreen = container.getExtra("gsFullscreen", "1").equals("1");
        settings.showFPS = container.getExtra("gsShowFPS").equals("1");
        settings.touchControls = container.getExtra("gsTouchControls", "1").equals("1");
        return settings;
    }

    public void apply(Context context, Container container, DeviceProfile profile) {
        container.setScreenSize(profile.getScreenSize(quality));
        container.setGraphicsDriver(profile.getGraphicsDriver());
        container.setDXWrapper(DXWrappers.DXVK);

        KeyValueSet dxvkConfig = new KeyValueSet();
        dxvkConfig.put("version", profile.getDXVKVersion());
        dxvkConfig.put("framerate", String.valueOf(fps));
        dxvkConfig.put("maxDeviceMemory", profile.getVideoMemory());
        container.setDXWrapperConfig(dxvkConfig.toString());

        container.setBox64Preset(fewerCrashes ? Box64Preset.STABILITY : Box64Preset.PERFORMANCE);
        container.setHUDMode((byte)(showFPS ? FrameRating.Mode.SIMPLE : FrameRating.Mode.DISABLED).ordinal());

        container.putExtra("gsQuality", String.valueOf(quality));
        container.putExtra("gsFPS", String.valueOf(fps));
        container.putExtra("gsFewerCrashes", fewerCrashes ? "1" : "0");
        container.putExtra("gsFullscreen", fullscreen ? "1" : "0");
        container.putExtra("gsShowFPS", showFPS ? "1" : "0");
        container.putExtra("gsTouchControls", touchControls ? "1" : "0");
        container.saveData();

        for (Shortcut shortcut : new ContainerManager(context).loadShortcuts(null)) {
            if (shortcut.container.id != container.id || shortcut.file.isDirectory()) continue;
            shortcut.putExtra("forceFullscreen", fullscreen ? "1" : null);
            shortcut.putExtra("controlsProfile", touchControls ? String.valueOf(TOUCH_CONTROLS_PROFILE_ID) : null);
            shortcut.saveData();
        }
    }
}
