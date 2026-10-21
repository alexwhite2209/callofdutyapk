package com.winlator.autosetup;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Point;
import android.view.Display;
import android.view.WindowManager;

import com.winlator.container.GraphicsDrivers;
import com.winlator.core.DefaultVersion;
import com.winlator.core.GPUHelper;

import java.util.Locale;

/**
 * Scans the phone hardware (GPU, RAM, CPU cores, display) and recommends
 * settings under which Call of Duty 4 runs smoothly.
 */
public class DeviceProfile {
    public enum Tier {LOW, MID, HIGH}

    public final String gpuRenderer;
    public final short adrenoModelId;
    public final long totalRamMB;
    public final int cpuCores;
    public final int displayWidth;
    public final int displayHeight;
    public final Tier tier;

    private DeviceProfile(Context context) {
        gpuRenderer = GPUHelper.glGetRenderer(context);
        adrenoModelId = GPUHelper.getAdrenoModelId(context);

        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        ((ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(memoryInfo);
        totalRamMB = memoryInfo.totalMem / (1024 * 1024);

        cpuCores = Runtime.getRuntime().availableProcessors();

        Point size = new Point();
        Display display = ((WindowManager)context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        display.getRealSize(size);
        displayWidth = Math.max(size.x, size.y);
        displayHeight = Math.min(size.x, size.y);

        tier = detectTier();
    }

    public static DeviceProfile detect(Context context) {
        return new DeviceProfile(context);
    }

    private Tier detectTier() {
        Tier result;
        if (adrenoModelId >= 700) result = Tier.HIGH;
        else if (adrenoModelId >= 640) result = Tier.MID;
        else result = Tier.LOW; // Adreno 6xx low-end (e.g. 618 in Redmi Note 12 Pro 4G), Mali, Xclipse

        // Memory reported by Android is a bit below the marketed size (8 GB ~ 7400 MB)
        if (totalRamMB < 5000 && result != Tier.LOW) result = Tier.values()[result.ordinal() - 1];
        return result;
    }

    public boolean isAdreno() {
        return adrenoModelId > 0;
    }

    public int getRecommendedQuality() {
        return tier.ordinal();
    }

    public int getRecommendedFPS() {
        return tier == Tier.LOW ? 30 : 60;
    }

    /** Standard 16:9 mode: CoD4 shows a black menu when the desktop has a resolution it does not list. */
    public String getScreenSize() {
        return "1280x720";
    }

    public String getVulkanDriver() {
        return isAdreno() ? GraphicsDrivers.TURNIP : GraphicsDrivers.VORTEK;
    }

    public String getGraphicsDriver() {
        return getVulkanDriver()+","+GraphicsDrivers.DEFAULT_OPENGL_DRIVER;
    }

    /** DXVK 1.10.3 has lower overhead on older Adreno 6xx and is enough for a D3D9 game. */
    public String getDXVKVersion() {
        return tier == Tier.LOW ? DefaultVersion.MINOR_DXVK : DefaultVersion.DXVK(getVulkanDriver());
    }

    public String getVideoMemory() {
        return totalRamMB >= 7000 ? "2048" : "1024";
    }

    public String getTierDisplayName() {
        switch (tier) {
            case HIGH: return "мощный";
            case MID: return "средний";
            default: return "начальный";
        }
    }

    public String describe() {
        String gpu = gpuRenderer.isEmpty() ? "неизвестна" : gpuRenderer;
        return String.format(Locale.US, "Видеокарта: %s\nПамять: %.1f ГБ\nЯдер процессора: %d\nЭкран: %dx%d\n\nУровень телефона: %s",
            gpu, totalRamMB / 1024.0f, cpuCores, displayWidth, displayHeight, getTierDisplayName());
    }
}
