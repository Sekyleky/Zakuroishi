package io.github.zakuro.android;

import java.net.URL;
import java.util.Arrays;

/** Host checks for the updater's decisions: which release, whether it is newer, its checksum line, which hosts. No network. */
public final class UpdaterCheck {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static Updater.Release release(String tag, String apk) {
        return new Updater.Release(tag, tag, apk, apk == null ? null : "https://github.com/x/" + apk, "https://github.com/x/SHA256SUMS.txt");
    }
    public static void main(String[] args) throws Exception {
        // Stable builds: higher stable X.Y.Z only; never a nightly, never the same or older.
        check(Updater.newer("0.6.0", "0.6.1") && Updater.newer("0.6.0", "0.10.0") && Updater.newer("0.9.9", "1.0.0"), "stable missed a newer stable");
        check(!Updater.newer("0.6.0", "0.6.0") && !Updater.newer("0.6.1", "0.6.0") && !Updater.newer("0.10.0", "0.9.0"), "stable offered a downgrade");
        check(!Updater.newer("0.6.0", "0.7.0-nightly.20261201") && !Updater.newer("0.6.0", "0.7.0-nightly"), "stable offered a nightly");
        // Nightly builds: a dateless nightly is older than any dated one; later date wins; higher stable is offered, same stable is not.
        check(Updater.newer("0.6.0-nightly", "0.6.0-nightly.20261117") && Updater.newer("0.6.0-nightly.20261117", "0.6.0-nightly.20261118"), "nightly missed a newer nightly");
        check(!Updater.newer("0.6.0-nightly.20261118", "0.6.0-nightly.20261117") && !Updater.newer("0.6.0-nightly.20261117", "0.6.0-nightly.20261117")
            && !Updater.newer("0.6.0-nightly.20261117", "0.6.0-nightly") && !Updater.newer("0.6.0-nightly", "0.6.0-nightly"), "nightly offered an older nightly");
        check(Updater.newer("0.6.0-nightly.20261117", "0.6.1") && Updater.newer("0.6.0-nightly", "0.7.0-nightly"), "nightly missed a higher version");
        check(!Updater.newer("0.6.0-nightly.20261117", "0.6.0") && !Updater.newer("0.7.0-nightly.20261117", "0.6.9-nightly.20261201"), "nightly offered a downgrade");
        // Several nightlies a day: no .N counts as 1.
        check(Updater.newer("0.6.0-nightly.20261117", "0.6.0-nightly.20261117.2") && Updater.newer("0.6.0-nightly.20261117.2", "0.6.0-nightly.20261117.3")
            && Updater.newer("0.6.0-nightly.20261117.9", "0.6.0-nightly.20261118") && Updater.newer("0.6.0-nightly", "0.6.0-nightly.20261117.2"), "nightly missed a newer build");
        check(!Updater.newer("0.6.0-nightly.20261117.3", "0.6.0-nightly.20261117.2") && !Updater.newer("0.6.0-nightly.20261118", "0.6.0-nightly.20261117.5")
            && !Updater.newer("0.6.0", "0.6.1-nightly.20261117.2") && !Updater.newer("0.6.0-nightly.20261117.2", "0.6.0-nightly.20261117"), "nightly offered an older build");
        for (String bad : new String[]{null, "", "0.6", "0.6.0-beta", "v0.6.0", "0.6.0-nightly.2026111", "0.6.0 "})
            check(!Updater.newer("0.6.0", bad) && !Updater.newer(bad, "9.9.9"), "unparsed version accepted: " + bad);

        // The version a release's APK carries, and the highest release with our APK.
        check("0.6.0".equals(release("android-v0.6.0", "Zakuroishi-0.6.0.apk").version()), "stable version");
        check("0.6.0-nightly.20261116".equals(release("nightly-20261116", "Zakuroishi-0.6.0-nightly.apk").version()), "nightly version from tag date");
        check("0.6.0-nightly".equals(release("odd-tag", "Zakuroishi-0.6.0-nightly.apk").version()), "nightly without a dated tag");
        check(release("android-v0.6.0", "Zakuroishi-0.6.0.apk.idsig").version() == null && release("android-v0.6.0", null).version() == null, "foreign asset taken as the APK");
        check("0.6.1-nightly.20261117.2".equals(release("nightly-20261117.2", "Zakuroishi-0.6.1-nightly.apk").version()), "nightly version with build number");
        Updater.Release second = release("nightly-20261117.2", "Zakuroishi-0.6.1-nightly.apk"), first = release("nightly-20261117", "Zakuroishi-0.6.1-nightly.apk");
        check(Updater.pick(Arrays.asList(first, second)) == second && Updater.pick(Arrays.asList(second, first)) == second, "pick missed the later build");
        Updater.Release newest = release("nightly-20261118", "Zakuroishi-0.6.0-nightly.apk");
        check(Updater.pick(Arrays.asList(release("source-only", null), release("nightly-20261117", "Zakuroishi-0.6.0-nightly.apk"), newest,
            release("android-v0.6.0", "Zakuroishi-0.6.0.apk"))) == newest, "pick skipped the wrong releases");
        // A stable release published after a nightly does not hide the higher nightly, and a higher stable beats an older nightly.
        Updater.Release nightly = release("nightly-20261120", "Zakuroishi-0.7.0-nightly.apk"), stable = release("android-v0.7.1", "Zakuroishi-0.7.1.apk");
        check(Updater.pick(Arrays.asList(release("android-v0.6.1", "Zakuroishi-0.6.1.apk"), nightly)) == nightly, "a later stable hid a higher nightly");
        check(Updater.pick(Arrays.asList(nightly, stable)) == stable && Updater.pick(Arrays.asList(stable, nightly)) == stable, "pick missed a higher stable");
        check(Updater.pick(Arrays.asList(release("source-only", null))) == null && Updater.pick(Arrays.asList()) == null, "pick found a release in nothing");

        // SHA256SUMS lines as sha256sum writes them (text and binary mode, CRLF tolerated); only the exact file name matches.
        String a = "2c05ea18746660212fc73fa932cf74f761c2fc88ec2e0204d6f7c27a0384a6e0", b = "C2F1746352BC90F43A638AA90B503F0BE4A236B3194F5F5FE7A55510369F5B85";
        String sums = a + "  Zakuroishi-0.5.0.apk\r\n" + b + " *Zakuroishi-0.6.0-nightly.apk\n" + "not a line\n";
        check(a.equals(Updater.sha256For(sums, "Zakuroishi-0.5.0.apk")), "text-mode line");
        check(b.toLowerCase().equals(Updater.sha256For(sums, "Zakuroishi-0.6.0-nightly.apk")), "binary-mode line or case");
        check(Updater.sha256For(sums, "Zakuroishi-0.5.0") == null && Updater.sha256For(sums, "Zakuroishi-0.6.0.apk") == null && Updater.sha256For("", "x") == null, "wrong file matched");
        check(Updater.sha256For(a.substring(1) + "  Zakuroishi-0.5.0.apk", "Zakuroishi-0.5.0.apk") == null, "short hash accepted");

        // Downloads stay on GitHub over HTTPS at every redirect.
        for (String ok : new String[]{"https://api.github.com/repos/x", "https://github.com/x/a.apk", "https://objects.githubusercontent.com/a", "https://release-assets.githubusercontent.com:443/a"})
            check(Updater.allowed(new URL(ok)), "refused " + ok);
        for (String no : new String[]{"http://github.com/x", "https://github.com.evil.example/x", "https://evilgithub.com/x", "https://githubusercontent.com.example/x",
            "https://github.com:8443/x", "https://example.com/github.com", "https://raw.githubusercontent.com/x/y/main/a.apk",
            "https://gist.githubusercontent.com/x/a.apk", "https://evilgithubusercontent.com/a", "http://objects.githubusercontent.com/a"})
            check(!Updater.allowed(new URL(no)), "allowed " + no);
    }
}
