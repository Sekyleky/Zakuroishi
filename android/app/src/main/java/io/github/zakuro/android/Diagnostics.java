package io.github.zakuro.android;

import android.content.Context;
import android.os.Build;
import android.os.StatFs;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** A text report for bug reports: app and device facts plus this app's own log. No ROM, save or texture data, and nothing leaves the device by itself. */
final class Diagnostics {
    private static final int LOG_LINES = 3000, LOG_BYTES = 1 << 20;
    // Folder names inside document-picker URIs can be personal, so the whole URI goes (file:// paths carry the same names).
    private static final Pattern URI = Pattern.compile("(?:content|file)://\\S*");
    private Diagnostics() {}

    static String redact(String text) { return URI.matcher(text).replaceAll("<uri removed>"); }

    static String build(Context context) throws Exception {
        StringBuilder out = new StringBuilder("Zakuroishi diagnostics\n");
        android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        out.append("App version: ").append(info.versionName).append(" (").append(info.getLongVersionCode()).append(")\n");
        out.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        out.append("SoC: ").append(Build.VERSION.SDK_INT >= 31 ? Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL : Build.HARDWARE).append('\n');
        out.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        out.append("Free storage: ").append(StorageDialog.size(new StatFs(context.getFilesDir().getAbsolutePath()).getAvailableBytes())).append('\n');
        out.append("Imported title IDs: ").append(String.join(", ", GameImport.importedIds(context.getFilesDir()))).append('\n');
        out.append("\nThe log below is this app's own (both the library and game processes). Folder addresses are removed,\n"
                + "but file names can still appear: read it before you share it.\n\n");
        return out.append(redact(log())).toString();
    }

    /** An app can read its own uid's log without any permission; both of our processes share that uid. */
    private static String log() {
        try {
            Process process = new ProcessBuilder("logcat", "-d", "-t", String.valueOf(LOG_LINES)).redirectErrorStream(true).start();
            ByteArrayOutputStream log = new ByteArrayOutputStream();
            try (InputStream in = process.getInputStream()) {
                byte[] buffer = new byte[8192]; int n;
                while (log.size() < LOG_BYTES && (n = in.read(buffer)) > 0) log.write(buffer, 0, n);
            }
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroy();
            return log.toString("UTF-8");
        } catch (Exception e) { return "(Log unavailable: " + e.getClass().getSimpleName() + ")\n"; }
    }
}
