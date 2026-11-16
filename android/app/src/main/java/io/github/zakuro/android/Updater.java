package io.github.zakuro.android;

import android.app.Application;
import android.app.PendingIntent;
import android.content.*;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.Toast;
import java.io.*;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Self-update from the GitHub releases (nightly build only): find a newer release, download its APK to the cache, check it
 * against the release's SHA256SUMS and this app's package name and signing key, then hand it to PackageInstaller.
 * As a receiver it gets the install status. Only the Launcher (main process) calls it.
 */
public final class Updater extends BroadcastReceiver {
    static final String RELEASES = "https://api.github.com/repos/Sekyleky/Zakuroishi/releases";
    static final long MAX_APK = 64L << 20, DAY = 24L * 60 * 60 * 1000;
    private static final Pattern APK = Pattern.compile("Zakuroishi-(\\d+\\.\\d+\\.\\d+)(-nightly)?\\.apk"), NIGHTLY_TAG = Pattern.compile("nightly-(\\d{8})"),
        VERSION = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)(-nightly(?:\\.(\\d{8}))?)?"), SUM = Pattern.compile("([0-9a-fA-F]{64}) [ *](.+)");

    static final class Release {
        final String tag, name, apk, apkUrl, sumsUrl; final boolean draft; final long size;
        File file; // the verified download, once check() has it
        Release(String tag, String name, boolean draft, String apk, String apkUrl, long size, String sumsUrl) {
            this.tag = tag; this.name = name; this.draft = draft; this.apk = apk; this.apkUrl = apkUrl; this.size = size; this.sumsUrl = sumsUrl;
        }
        /** The versionName the APK carries: X.Y.Z, or X.Y.Z-nightly.YYYYMMDD with the date from a nightly-YYYYMMDD tag; null without an APK. */
        String version() {
            Matcher m = APK.matcher(apk == null ? "" : apk); if (!m.matches()) return null;
            if (m.group(2) == null) return m.group(1);
            Matcher date = NIGHTLY_TAG.matcher(tag); return m.group(1) + "-nightly" + (date.matches() ? "." + date.group(1) : "");
        }
    }

    /** {major, minor, patch, nightly 0/1, date or 0}; null when the name is not ours. */
    private static long[] parse(String name) {
        Matcher m = VERSION.matcher(name == null ? "" : name); if (!m.matches()) return null;
        return new long[]{Long.parseLong(m.group(1)), Long.parseLong(m.group(2)), Long.parseLong(m.group(3)), m.group(4) == null ? 0 : 1, m.group(5) == null ? 0 : Long.parseLong(m.group(5))};
    }
    /**
     * Whether candidate is an update for installed. A stable build only takes a higher stable X.Y.Z. A nightly takes a higher
     * X.Y.Z of either kind, or a later-dated nightly of the same X.Y.Z (a dateless nightly is older than any dated one).
     */
    static boolean newer(String installed, String candidate) {
        long[] a = parse(installed), b = parse(candidate);
        if (a == null || b == null || (a[3] == 0 && b[3] == 1)) return false;
        for (int i = 0; i < 3; i++) if (a[i] != b[i]) return b[i] > a[i];
        return a[3] == 1 && b[3] == 1 && b[4] > a[4];
    }
    /** The newest usable release: GitHub lists newest first; drafts and releases without our APK are skipped. */
    static Release pick(List<Release> releases) {
        for (Release r : releases) if (!r.draft && r.version() != null && r.apkUrl != null) return r;
        return null;
    }
    /** The lowercase SHA-256 a sha256sum-style file gives for file, or null. */
    static String sha256For(String sums, String file) {
        for (String line : sums.split("\n")) { Matcher m = SUM.matcher(line.trim()); if (m.matches() && m.group(2).equals(file)) return m.group(1).toLowerCase(Locale.ROOT); }
        return null;
    }
    /** HTTPS to GitHub's own hosts only, at every redirect. */
    static boolean allowed(URL url) {
        String host = url.getHost().toLowerCase(Locale.ROOT);
        return "https".equals(url.getProtocol()) && (url.getPort() == -1 || url.getPort() == 443)
            && (host.equals("github.com") || host.equals("api.github.com") || host.endsWith(".githubusercontent.com"));
    }

    static SharedPreferences preferences(Context context) { return context.getSharedPreferences("updates", Context.MODE_PRIVATE); }
    /** Nightly build, "Check for updates when the app opens" on, and no check in the last 24 hours. */
    static boolean due(Context context) {
        long last = preferences(context).getLong("checked", 0), now = System.currentTimeMillis();
        return GameImport.nightly(context) && preferences(context).getBoolean("auto", true) && (now - last >= DAY || now < last);
    }
    static String installed(Context context) {
        try { return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; } catch (PackageManager.NameNotFoundException e) { return "?"; }
    }

    /** A newer release, downloaded and verified into the cache, or null when this build is up to date. Worker thread. */
    static Release check(Context context) throws Exception {
        ByteArrayOutputStream json = new ByteArrayOutputStream();
        fetch(RELEASES + (GameImport.nightly(context) ? "?per_page=10" : "/latest"), 1 << 20, json);
        Release r = pick(releases(new String(json.toByteArray(), StandardCharsets.UTF_8)));
        if (r == null || !newer(installed(context), r.version())) return null;
        if (r.sumsUrl == null) throw new IOException("Release " + r.tag + " has no SHA256SUMS file, so it cannot be checked.");
        if (r.size > MAX_APK) throw new IOException("The update is larger than " + (MAX_APK >> 20) + " MiB.");
        ByteArrayOutputStream sums = new ByteArrayOutputStream(); fetch(r.sumsUrl, 64 << 10, sums);
        String want = sha256For(new String(sums.toByteArray(), StandardCharsets.UTF_8), r.apk);
        if (want == null) throw new IOException("The release's SHA256SUMS does not list " + r.apk + ".");
        File file = new File(context.getCacheDir(), "update.apk");
        try {
            String got; try (OutputStream out = new FileOutputStream(file)) { got = fetch(r.apkUrl, MAX_APK, out); }
            if (!got.equals(want)) throw new IOException("The download does not match its SHA-256 checksum. Nothing was installed.");
            verify(context, file);
            r.file = file; return r;
        } catch (Exception e) { file.delete(); throw e; }
    }
    private static List<Release> releases(String json) throws Exception {
        JSONArray list = json.trim().startsWith("[") ? new JSONArray(json) : new JSONArray().put(new JSONObject(json)); // /releases or /releases/latest
        List<Release> out = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject release = list.getJSONObject(i); JSONArray assets = release.optJSONArray("assets");
            String apk = null, apkUrl = null, sumsUrl = null; long size = 0;
            for (int j = 0; assets != null && j < assets.length(); j++) {
                JSONObject asset = assets.getJSONObject(j); String name = asset.optString("name");
                if (apk == null && APK.matcher(name).matches()) { apk = name; apkUrl = asset.optString("browser_download_url", null); size = asset.optLong("size"); }
                else if (name.startsWith("SHA256SUMS") && name.endsWith(".txt")) sumsUrl = asset.optString("browser_download_url", null);
            }
            out.add(new Release(release.optString("tag_name"), release.optString("name"), release.optBoolean("draft"), apk, apkUrl, size, sumsUrl));
        }
        return out;
    }
    /** Copies one GitHub response body to out, at most max bytes, and returns its SHA-256. Redirects are followed by hand so each host is checked. */
    private static String fetch(String address, long max, OutputStream out) throws Exception {
        URL url = new URL(address);
        for (int hop = 0; hop < 5; hop++) {
            if (!allowed(url)) throw new IOException("Refusing to download from " + url.getHost() + ".");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            try {
                c.setInstanceFollowRedirects(false); c.setConnectTimeout(15000); c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", "Zakuroishi-Updater"); // GitHub requires one; nothing about the device or user
                int code = c.getResponseCode(); String next = c.getHeaderField("Location");
                if (code / 100 == 3 && next != null) { url = new URL(url, next); continue; }
                if (code != 200) throw new IOException("GitHub answered HTTP " + code + ".");
                if (c.getContentLengthLong() > max) throw new IOException("The download is larger than expected.");
                MessageDigest sha = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[65536]; long total = 0;
                try (InputStream in = c.getInputStream()) {
                    for (int n; (n = in.read(buffer)) > 0; ) {
                        if ((total += n) > max) throw new IOException("The download is larger than expected.");
                        sha.update(buffer, 0, n); out.write(buffer, 0, n);
                    }
                }
                return String.format("%064x", new BigInteger(1, sha.digest()));
            } finally { c.disconnect(); }
        }
        throw new IOException("Too many redirects.");
    }
    /** Same package, same signing key as the installed app, and not an older version code. */
    private static void verify(Context context, File apk) throws IOException {
        PackageManager pm = context.getPackageManager(); PackageInfo got = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNING_CERTIFICATES), mine;
        try { mine = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES); } catch (PackageManager.NameNotFoundException e) { throw new IOException(e); }
        if (got == null || !context.getPackageName().equals(got.packageName)) throw new IOException("The download is not a Zakuroishi APK.");
        // Releases rotate keys (lineage): the installed app's current signer must be the download's signer or in its signing history.
        if (got.signingInfo == null || mine.signingInfo == null || !Arrays.asList(got.signingInfo.hasMultipleSigners() ? got.signingInfo.getApkContentsSigners()
                : got.signingInfo.getSigningCertificateHistory()).containsAll(Arrays.asList(mine.signingInfo.getApkContentsSigners())))
            throw new IOException("The download is not signed with this app's key. Nothing was installed.");
        if (got.getLongVersionCode() < mine.getLongVersionCode()) throw new IOException("The download is older than the installed app.");
    }
    /** Hands a verified APK to PackageInstaller; refuses in the game process or while a game holds its saves. Deletes the APK either way. Worker thread. */
    static void install(Context context, File apk) throws IOException {
        try {
            if (Application.getProcessName().endsWith(":game")) throw new IOException("Updates install from the library only.");
            File[] saves = new File(context.getFilesDir(), "savedata").listFiles();
            if (saves != null) for (File dir : saves) if (dir.getName().matches("[0-9A-F]{16}"))
                try (SaveArchive.Guard free = SaveArchive.lock(context.getFilesDir(), dir.getName())) { } // a running game holds this lock
            PackageInstaller installer = context.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(context.getPackageName());
            // Skips Android's own confirm once this app installed the current version (UPDATE_PACKAGES_WITHOUT_USER_ACTION); Install in our prompt is still needed.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            int id = installer.createSession(params);
            try (PackageInstaller.Session session = installer.openSession(id)) {
                try (OutputStream out = session.openWrite("base.apk", 0, apk.length())) { Files.copy(apk.toPath(), out); session.fsync(out); }
                catch (IOException | RuntimeException e) { session.abandon(); throw e; }
                int mutable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0; // the installer adds the status extras
                session.commit(PendingIntent.getBroadcast(context, id, new Intent(context, Updater.class), PendingIntent.FLAG_UPDATE_CURRENT | mutable).getIntentSender());
            }
        } finally { apk.delete(); }
    }
    /** Install status. On success Android restarts the app, so only the confirm step and failures arrive here. */
    @Override public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else if (status != PackageInstaller.STATUS_SUCCESS)
            Toast.makeText(context, status == PackageInstaller.STATUS_FAILURE_ABORTED ? "Update cancelled." : "Update failed: " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE), Toast.LENGTH_LONG).show();
    }
}
