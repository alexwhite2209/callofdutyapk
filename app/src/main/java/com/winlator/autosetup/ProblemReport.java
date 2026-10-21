package com.winlator.autosetup;

import android.content.Context;
import android.os.Build;

import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.Shortcut;
import com.winlator.core.AppUtils;
import com.winlator.core.FileUtils;

import java.io.File;
import java.util.ArrayList;

/** Plain-text summary of the device, settings and game/DXVK logs that the player can paste into a chat. */
public abstract class ProblemReport {
    private static final int MAX_LINES_PER_LOG = 80;
    private static final int MAX_LENGTH = 20000;

    public static String build(Context context, Container container) {
        StringBuilder report = new StringBuilder();
        report.append("=== CoD4 Winlator report ===\n");
        report.append("App version: ").append(AppUtils.getVersionCode(context)).append("\n");
        report.append("Phone: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append(", Android ").append(Build.VERSION.RELEASE).append("\n");
        report.append(DeviceProfile.detect(context).describe()).append("\n\n");

        if (container == null) {
            report.append("Game container: not created\n");
            return report.toString();
        }

        report.append("Screen: ").append(container.getScreenSize()).append("\n");
        report.append("Graphics driver: ").append(container.getGraphicsDriver()).append("\n");
        report.append("DX wrapper: ").append(container.getDXWrapper()).append(" [").append(container.getDXWrapperConfig()).append("]\n");
        report.append("Box64: ").append(container.getBox64Preset()).append("\n");
        report.append("Env: ").append(container.getEnvVars()).append("\n");
        for (String key : new String[]{"gsVersion", "gsQuality", "gsFPS", "gsGraphicsVariant", "gsSkipVideos", "gsFewerCrashes"}) {
            report.append(key).append("=").append(container.getExtra(key)).append(" ");
        }
        report.append("\n");

        for (Shortcut shortcut : new ContainerManager(context).loadShortcuts(null)) {
            if (shortcut.container.id != container.id || !shortcut.name.equals(AutoSetup.CONTAINER_NAME)) continue;
            report.append("Exec: ").append(shortcut.path).append(" ").append(shortcut.getExtra("execArgs")).append("\n");

            File gameDir = AutoSetup.getGameDir(shortcut);
            if (gameDir == null) break;
            report.append("Game dir: ").append(gameDir.getPath()).append(gameDir.isDirectory() ? "" : " (missing)").append("\n");
            report.append("Videos: ").append(new File(gameDir, "main/video").isDirectory() ? "on" : "off").append("\n");

            ArrayList<File> logs = new ArrayList<>();
            findLogs(gameDir, 2, logs);
            if (logs.isEmpty()) report.append("\nNo logs yet: start the game once, then make the report again.\n");
            for (File log : logs) appendTail(report, log);
            break;
        }

        return report.length() > MAX_LENGTH ? report.substring(0, MAX_LENGTH)+"\n[truncated]" : report.toString();
    }

    private static void findLogs(File dir, int depth, ArrayList<File> logs) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName().toLowerCase();
            if (file.isDirectory()) {
                if (depth > 0 && !name.equals("zone") && !name.startsWith("video")) findLogs(file, depth - 1, logs);
            }
            else if (name.endsWith(".log") && (name.startsWith("console") || name.contains("_d3d9") || name.contains("_dxgi"))) {
                logs.add(file);
            }
        }
    }

    private static void appendTail(StringBuilder report, File log) {
        ArrayList<String> lines = FileUtils.readLines(log);
        report.append("\n--- ").append(log.getName()).append(" (").append(lines.size()).append(" lines) ---\n");
        for (int i = Math.max(0, lines.size() - MAX_LINES_PER_LOG); i < lines.size(); i++) report.append(lines.get(i)).append("\n");
    }
}
