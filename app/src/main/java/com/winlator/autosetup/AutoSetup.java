package com.winlator.autosetup;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.winlator.MainActivity;
import com.winlator.R;
import com.winlator.ShortcutsFragment;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.contentdialog.ContentDialog;
import com.winlator.core.AppUtils;
import com.winlator.core.DownloadProgressDialog;
import com.winlator.core.FileUtils;
import com.winlator.xenvironment.RootFS;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * First-run setup for Call of Duty 4: finds the game (folder or zip in Downloads),
 * creates a container tuned for the detected phone and puts launch shortcuts on the home screen.
 */
public abstract class AutoSetup {
    public static final String CONTAINER_NAME = "Call of Duty 4";
    private static final String PREF_CONTAINER_ID = "cod4_container_id";
    private static final String GAME_DIR_NAME = "CoD4";
    private static final String SP_EXE = "iw3sp.exe";
    private static final String MP_EXE = "iw3mp.exe";
    private static final AtomicBoolean running = new AtomicBoolean(false);

    public static void run(final MainActivity activity) {
        if (!RootFS.find(activity).isValid() || !running.compareAndSet(false, true)) return;

        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        final ContainerManager manager = new ContainerManager(activity);
        Container existing = manager.getContainerById(preferences.getInt(PREF_CONTAINER_ID, 0));
        if (existing != null) {
            running.set(false);
            return;
        }

        File downloadsDir = new File(AppUtils.DIRECTORY_DOWNLOADS);
        File gameExe = findGameExe(downloadsDir, 3);
        if (gameExe != null) {
            createContainer(activity, manager, preferences, gameExe);
            return;
        }

        final File zipFile = findGameZip(downloadsDir);
        if (zipFile == null) {
            running.set(false);
            ContentDialog.alert(activity, R.string.cod4_game_not_found, null);
            return;
        }

        final File dstDir = new File(downloadsDir, GAME_DIR_NAME);
        final DownloadProgressDialog dialog = new DownloadProgressDialog(activity);
        dialog.show(R.string.cod4_extracting);
        AppUtils.keepScreenOn(activity);
        Executors.newSingleThreadExecutor().execute(() -> {
            boolean success = extractZip(activity, zipFile, dstDir, dialog);
            dialog.closeOnUiThread();
            activity.runOnUiThread(() -> {
                File exe = success ? findGameExe(dstDir, 3) : null;
                if (exe != null) {
                    createContainer(activity, manager, preferences, exe);
                }
                else {
                    running.set(false);
                    ContentDialog.alert(activity, R.string.cod4_extract_failed, null);
                }
            });
        });
    }

    private static void createContainer(final MainActivity activity, ContainerManager manager, final SharedPreferences preferences, final File gameExe) {
        final DeviceProfile profile = DeviceProfile.detect(activity);

        JSONObject data = new JSONObject();
        try {
            data.put("name", CONTAINER_NAME);
            data.put("envVars", Container.DEFAULT_ENV_VARS);
            data.put("audioDriver", Container.DEFAULT_AUDIO_DRIVER);
            data.put("wincomponents", Container.DEFAULT_WINCOMPONENTS);
            data.put("drives", Container.DEFAULT_DRIVES);
            data.put("startupSelection", Container.STARTUP_SELECTION_ESSENTIAL);
        }
        catch (JSONException e) {}

        activity.preloaderDialog.show(R.string.creating_container);
        manager.createContainerAsync(data, (container) -> {
            activity.preloaderDialog.close();
            running.set(false);
            if (container == null) {
                AppUtils.showToast(activity, R.string.unable_to_install_system_files);
                return;
            }

            createShortcut(container, CONTAINER_NAME, gameExe);
            File mpExe = new File(gameExe.getParentFile(), MP_EXE);
            if (mpExe.isFile()) createShortcut(container, CONTAINER_NAME+" Multiplayer", mpExe);
            GameSettings.recommended(profile).apply(activity, container, profile);

            preferences.edit()
                .putInt(PREF_CONTAINER_ID, container.id)
                .putBoolean("show_shortcuts_first", true)
                .apply();

            activity.showFragment(new ShortcutsFragment());
            ContentDialog dialog = new ContentDialog(activity);
            dialog.setTitle(R.string.cod4_ready);
            dialog.setMessage(profile.describe()+"\n\n"+activity.getString(R.string.cod4_ready_hint));
            dialog.show();
        });
    }

    /** The CoD4 container created by the auto setup, or null if it has not run yet. */
    public static Container findContainer(Context context) {
        int id = PreferenceManager.getDefaultSharedPreferences(context).getInt(PREF_CONTAINER_ID, 0);
        return id > 0 ? new ContainerManager(context).getContainerById(id) : null;
    }

    /** Forget the previous setup so the next run scans the phone and the game folder again. */
    public static void reset(Context context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().remove(PREF_CONTAINER_ID).apply();
    }

    private static void createShortcut(Container container, String name, File exeFile) {
        File desktopDir = new File(container.getUserDir(), "Desktop");
        desktopDir.mkdirs();

        String content = "[Desktop Entry]\n" +
            "Name="+name+"\n" +
            "Exec=env WINEPREFIX=\"/home/"+RootFS.USER+"/.wine\" wine "+escapeDesktopPath(toDOSPath(exeFile))+"\n" +
            "Type=Application\n" +
            "StartupNotify=true\n" +
            "StartupWMClass="+exeFile.getName().toLowerCase()+"\n" +
            "\n[Extra Data]\n" +
            "forceFullscreen=1\n";
        FileUtils.writeString(new File(desktopDir, name+".desktop"), content);
    }

    /** Same double escaping Wine writes into .desktop files, reversed by StringUtils.unescapeDOSPath. */
    private static String escapeDesktopPath(String path) {
        return path.replace("\\", "\\\\\\\\").replace(" ", "\\\\ ");
    }

    /** Downloads folder is mounted as drive D: (see Container.DEFAULT_DRIVES). */
    private static String toDOSPath(File file) {
        String relative = FileUtils.toRelativePath(AppUtils.DIRECTORY_DOWNLOADS, file.getAbsolutePath());
        if (relative.startsWith("/")) relative = relative.substring(1);
        return "D:\\"+relative.replace("/", "\\");
    }

    private static File findGameExe(File dir, int depth) {
        if (dir == null || !dir.isDirectory() || depth < 0) return null;
        File exe = new File(dir, SP_EXE);
        if (exe.isFile()) return exe;

        File[] files = dir.listFiles();
        if (files == null) return null;
        // Look at likely folders first so a full Downloads scan is rarely needed
        for (File file : files) {
            String name = file.getName().toLowerCase();
            if (file.isDirectory() && (name.contains("cod") || name.contains("call") || name.contains("duty"))) {
                File result = findGameExe(file, depth - 1);
                if (result != null) return result;
            }
        }
        for (File file : files) {
            if (file.isDirectory() && !file.getName().startsWith(".")) {
                File result = findGameExe(file, depth - 1);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static File findGameZip(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File file : files) {
            String name = file.getName().toLowerCase();
            if (file.isFile() && name.endsWith(".zip") && (name.contains("cod4") || name.matches(".*call.?of.?duty.?4.*"))) return file;
        }
        return null;
    }

    private static boolean extractZip(MainActivity activity, File zipFile, File dstDir, DownloadProgressDialog dialog) {
        try (ZipFile zip = new ZipFile(zipFile)) {
            long totalSize = 0;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) totalSize += Math.max(0, entries.nextElement().getSize());

            String dstPath = dstDir.getCanonicalPath();
            byte[] buffer = new byte[256 * 1024];
            long extracted = 0;
            int lastProgress = -1;
            entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                File file = new File(dstDir, entry.getName());
                if (!file.getCanonicalPath().startsWith(dstPath)) continue;

                if (entry.isDirectory()) {
                    file.mkdirs();
                    continue;
                }
                file.getParentFile().mkdirs();

                try (InputStream in = zip.getInputStream(entry); OutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        extracted += read;
                        final int progress = totalSize > 0 ? (int)(extracted * 100 / totalSize) : 0;
                        if (progress != lastProgress) {
                            lastProgress = progress;
                            activity.runOnUiThread(() -> dialog.setProgress(progress));
                        }
                    }
                }
            }
            return true;
        }
        catch (IOException e) {
            return false;
        }
    }
}
