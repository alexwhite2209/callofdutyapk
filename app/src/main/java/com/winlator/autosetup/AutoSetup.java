package com.winlator.autosetup;

import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.widget.TextView;
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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

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
            if (!existing.getExtra("gsVersion").equals(GameSettings.VERSION)) {
                DeviceProfile profile = DeviceProfile.detect(activity);
                GameSettings.load(existing, profile).apply(activity, existing, profile);
            }
            running.set(false);
            return;
        }

        File downloadsDir = new File(AppUtils.DIRECTORY_DOWNLOADS);
        File gameExe = findGameExe(downloadsDir, 3);
        if (gameExe != null) {
            createContainer(activity, manager, preferences, gameExe);
            return;
        }

        File zipFile = findGameZip(downloadsDir);
        if (zipFile != null) {
            extractAndSetup(activity, manager, preferences, (dialog) -> extractZip(activity, zipFile, dialog));
        }
        else {
            running.set(false);
            showGameNotFoundDialog(activity);
        }
    }

    private interface Extractor {
        boolean extract(DownloadProgressDialog dialog);
    }

    /** Lets the player point at the game archive anywhere on the phone instead of copying it to Downloads. */
    public static void showGameNotFoundDialog(final MainActivity activity) {
        ContentDialog dialog = new ContentDialog(activity);
        dialog.setMessage(R.string.cod4_game_not_found, R.drawable.content_dialog_type_alert);
        ((TextView)dialog.findViewById(R.id.BTConfirm)).setText(R.string.cod4_pick_archive);
        dialog.setOnConfirmCallback(() -> pickArchive(activity));
        dialog.show();
    }

    public static void pickArchive(final MainActivity activity) {
        activity.setOpenFileCallback((uri) -> {
            if (!running.compareAndSet(false, true)) return;
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
            extractAndSetup(activity, new ContainerManager(activity), preferences, (dialog) -> {
                String path = FileUtils.getFilePathFromUri(uri);
                if (path != null && new File(path).isFile()) return extractZip(activity, new File(path), dialog);
                return extractZipStream(activity, uri, dialog);
            });
        });

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
        activity.startActivityForResult(intent, MainActivity.OPEN_FILE_REQUEST_CODE);
    }

    private static void extractAndSetup(final MainActivity activity, final ContainerManager manager, final SharedPreferences preferences, final Extractor extractor) {
        final File dstDir = getGameDir();
        final DownloadProgressDialog dialog = new DownloadProgressDialog(activity);
        dialog.show(R.string.cod4_extracting);
        AppUtils.keepScreenOn(activity);
        Executors.newSingleThreadExecutor().execute(() -> {
            boolean success = extractor.extract(dialog);
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

    private static File getGameDir() {
        return new File(AppUtils.DIRECTORY_DOWNLOADS, GAME_DIR_NAME);
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

    private static boolean extractZip(MainActivity activity, File zipFile, DownloadProgressDialog dialog) {
        try (ZipFile zip = new ZipFile(zipFile)) {
            long totalSize = 0;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) totalSize += Math.max(0, entries.nextElement().getSize());

            Progress progress = new Progress(activity, dialog, totalSize);
            File dstDir = getGameDir();
            entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                try (InputStream in = zip.getInputStream(entry)) {
                    writeEntry(entry, in, dstDir, progress);
                }
            }
            return true;
        }
        catch (IOException e) {
            return false;
        }
    }

    /** Archives picked from other apps (Files, Google Drive cache) come as content:// streams without a file path. */
    private static boolean extractZipStream(MainActivity activity, Uri uri, DownloadProgressDialog dialog) {
        long totalSize = 0;
        try (AssetFileDescriptor fd = activity.getContentResolver().openAssetFileDescriptor(uri, "r")) {
            if (fd != null) totalSize = fd.getLength();
        }
        catch (IOException e) {}

        try (CountingInputStream counter = new CountingInputStream(activity.getContentResolver().openInputStream(uri));
             ZipInputStream zip = new ZipInputStream(new BufferedInputStream(counter, 256 * 1024))) {
            // Progress follows compressed bytes read, since the archive size is all we know up front
            Progress progress = new Progress(activity, dialog, totalSize);
            progress.counter = counter;
            File dstDir = getGameDir();
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) writeEntry(entry, zip, dstDir, progress);
            return true;
        }
        catch (IOException | NullPointerException e) {
            return false;
        }
    }

    private static void writeEntry(ZipEntry entry, InputStream in, File dstDir, Progress progress) throws IOException {
        File file = new File(dstDir, entry.getName());
        if (!file.getCanonicalPath().startsWith(dstDir.getCanonicalPath())) return;

        if (entry.isDirectory()) {
            file.mkdirs();
            return;
        }
        file.getParentFile().mkdirs();

        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
            int read;
            while ((read = in.read(progress.buffer)) != -1) {
                out.write(progress.buffer, 0, read);
                progress.add(read);
            }
        }
    }

    private static class Progress {
        final byte[] buffer = new byte[256 * 1024];
        final MainActivity activity;
        final DownloadProgressDialog dialog;
        final long totalSize;
        CountingInputStream counter;
        long extracted = 0;
        int lastProgress = -1;

        Progress(MainActivity activity, DownloadProgressDialog dialog, long totalSize) {
            this.activity = activity;
            this.dialog = dialog;
            this.totalSize = totalSize;
        }

        void add(int bytes) {
            extracted += bytes;
            long done = counter != null ? counter.count : extracted;
            final int progress = totalSize > 0 ? (int)Math.min(100, done * 100 / totalSize) : 0;
            if (progress != lastProgress) {
                lastProgress = progress;
                activity.runOnUiThread(() -> dialog.setProgress(progress));
            }
        }
    }

    private static class CountingInputStream extends FilterInputStream {
        long count = 0;

        CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value != -1) count++;
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int read = super.read(b, off, len);
            if (read > 0) count += read;
            return read;
        }
    }
}
