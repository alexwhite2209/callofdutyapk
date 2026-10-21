package com.winlator.autosetup;

import android.content.Context;

import java.io.File;

import com.winlator.box64.Box64Preset;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.DXWrappers;
import com.winlator.container.GraphicsDrivers;
import com.winlator.container.Shortcut;
import com.winlator.core.DefaultVersion;
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
    /** Graphics variants the player can cycle through when the game shows a black screen. */
    public static final int GRAPHICS_DEFAULT = 0;
    public static final int GRAPHICS_OTHER_DXVK = 1;
    public static final int GRAPHICS_SYSTEM_DRIVER = 2;
    public static final int GRAPHICS_COMPATIBLE = 3;
    public static final int TOUCH_CONTROLS_PROFILE_ID = 4; // built-in "FPS" profile
    /** Bump when the mapping to emulator options changes, so existing installs get re-applied on update. */
    public static final String VERSION = "7";
    /** Balanced: no shadows, glow or dynamic lights. */
    private static final String BALANCED_GRAPHICS_ARGS = "+set sm_enable 0 +set r_glow_allowed 0 +set r_glow 0 +set r_dlightLimit 0";
    /** Minimum: on top of balanced, lowest textures, no specular, water, decals or clouds, aggressive model LOD. */
    private static final String MINIMAL_GRAPHICS_ARGS = BALANCED_GRAPHICS_ARGS+
        " +set r_picmip_manual 1 +set r_picmip 3 +set r_picmip_bump 3 +set r_picmip_spec 3"+
        " +set r_specular 0 +set r_drawWater 0 +set r_detail 0 +set r_texFilterAnisoMax 1"+
        " +set fx_marks 0 +set fx_drawClouds 0 +set cg_brass 0 +set r_lodScaleRigid 4 +set r_lodScaleSkinned 4";

    public int quality;
    public int fps;
    public boolean fewerCrashes;
    public boolean fullscreen;
    public boolean showFPS;
    public boolean touchControls;
    public int graphicsVariant;
    public boolean skipVideos;

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
        settings.skipVideos = container.getExtra("gsSkipVideos").equals("1");
        String variant = container.getExtra("gsGraphicsVariant");
        if (!variant.isEmpty()) settings.graphicsVariant = Integer.parseInt(variant);
        else settings.graphicsVariant = container.getExtra("gsCompatibleGraphics").equals("1") ? GRAPHICS_COMPATIBLE : GRAPHICS_DEFAULT;
        return settings;
    }

    public void apply(Context context, Container container, DeviceProfile profile) {
        String screenSize = profile.getScreenSize();
        container.setScreenSize(screenSize);
        container.setGraphicsDriver(graphicsVariant == GRAPHICS_SYSTEM_DRIVER ? GraphicsDrivers.VORTEK+","+GraphicsDrivers.DEFAULT_OPENGL_DRIVER : profile.getGraphicsDriver());

        if (graphicsVariant == GRAPHICS_COMPATIBLE) {
            // WineD3D renders through OpenGL; slower, but works when DXVK shows a black screen
            container.setDXWrapper(DXWrappers.WINED3D);
            container.setDXWrapperConfig("");
        }
        else {
            container.setDXWrapper(DXWrappers.DXVK);
            KeyValueSet dxvkConfig = new KeyValueSet();
            String dxvkVersion = profile.getDXVKVersion();
            if (graphicsVariant == GRAPHICS_OTHER_DXVK) dxvkVersion = dxvkVersion.equals(DefaultVersion.MINOR_DXVK) ? DefaultVersion.MAJOR_DXVK : DefaultVersion.MINOR_DXVK;
            else if (graphicsVariant == GRAPHICS_SYSTEM_DRIVER) dxvkVersion = DefaultVersion.MINOR_DXVK;
            dxvkConfig.put("version", dxvkVersion);
            // Old games may skip 3D paths on GPU vendors they do not know; present a card CoD4 was tested with
            if (graphicsVariant == GRAPHICS_OTHER_DXVK) dxvkConfig.put("customDevice", "0611:10de:NVIDIA GeForce 8800 GT");
            dxvkConfig.put("framerate", String.valueOf(fps));
            dxvkConfig.put("maxDeviceMemory", profile.getVideoMemory());
            container.setDXWrapperConfig(dxvkConfig.toString());
        }

        container.setBox64Preset(fewerCrashes ? Box64Preset.STABILITY : Box64Preset.PERFORMANCE);
        container.setHUDMode((byte)(showFPS ? FrameRating.Mode.SIMPLE : FrameRating.Mode.DISABLED).ordinal());

        container.putExtra("gsVersion", VERSION);
        container.putExtra("gsQuality", String.valueOf(quality));
        container.putExtra("gsFPS", String.valueOf(fps));
        container.putExtra("gsFewerCrashes", fewerCrashes ? "1" : "0");
        container.putExtra("gsFullscreen", fullscreen ? "1" : "0");
        container.putExtra("gsShowFPS", showFPS ? "1" : "0");
        container.putExtra("gsTouchControls", touchControls ? "1" : "0");
        container.putExtra("gsGraphicsVariant", String.valueOf(graphicsVariant));
        container.putExtra("gsCompatibleGraphics", null);
        container.putExtra("gsSkipVideos", skipVideos ? "1" : "0");
        container.saveData();

        for (Shortcut shortcut : new ContainerManager(context).loadShortcuts(null)) {
            if (shortcut.container.id != container.id || shortcut.file.isDirectory()) continue;
            shortcut.putExtra("forceFullscreen", fullscreen ? "1" : null);
            shortcut.putExtra("controlsProfile", touchControls ? String.valueOf(TOUCH_CONTROLS_PROFILE_ID) : null);
            shortcut.putExtra("execArgs", getLaunchArgs(screenSize));
            // Logs for the problem report: DXVK writes <exe>_d3d9.log next to the game, CoD4 writes main/console.log
            shortcut.putExtra("envVars", "DXVK_LOG_LEVEL=info");
            shortcut.saveData();

            File gameDir = AutoSetup.getGameDir(shortcut);
            if (gameDir != null) setVideosEnabled(gameDir, !skipVideos);
        }
    }

    /** Hiding the video folder makes CoD4 skip intro and loading cinematics. */
    private static void setVideosEnabled(File gameDir, boolean enabled) {
        File videoDir = new File(gameDir, "main/video");
        File hiddenDir = new File(gameDir, "main/video_skipped");
        if (enabled && hiddenDir.isDirectory() && !videoDir.exists()) hiddenDir.renameTo(videoDir);
        else if (!enabled && videoDir.isDirectory() && !hiddenDir.exists()) videoDir.renameTo(hiddenDir);
    }

    /**
     * Forces the game video mode to match the Wine desktop, otherwise the menu can stay black after the intro.
     * Windowed at desktop size (Winlator stretches it): in exclusive fullscreen CoD4 minimizes when it loses focus
     * during level load, DXVK then presents to a 160x24 minimized window and the game stays black with sound.
     * Soft particles and depth of field need an R32F "$floatz" texture that WineD3D on Adreno 6xx cannot create
     * ("Create2DTexture( $floatz ... ) failed: Invalid call"); they are also costly on low-end GPUs.
     */
    private String getLaunchArgs(String screenSize) {
        String args = "+set logfile 2 +set r_fullscreen 0 +set vid_xpos 0 +set vid_ypos 0 +set r_mode "+screenSize+" +set r_displayRefresh 60 +set r_aaSamples 1 +set r_vsync 0 +set com_maxfps "+fps+
            " +set r_zfeather 0 +set r_dof_enable 0 +set r_distortion 0";
        if (quality == QUALITY_FAST) args += " "+MINIMAL_GRAPHICS_ARGS;
        else if (quality == QUALITY_BALANCED) args += " "+BALANCED_GRAPHICS_ARGS;
        return args;
    }
}
