package io.github.zakuro.android;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.os.StatFs;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Supplier;

/**
 * Storage screen: free space, and what can be deleted by hand to get it back. Nothing is ever deleted automatically.
 *
 * What may be listed, and so deleted, is exactly this and nothing else: an import folder that no pointer in imports/ names,
 * title.so.previous-* in an active import's code folder, *.previous-* in its data/mods/ID folder, and unfinished staging folders.
 * Saves live in files/savedata/ID/ (user, user.previous, user.retired-*), which is not under imports/ and is never walked;
 * an import's data/user is only a symlink to it, which deleting an import removes without following.
 */
final class StorageDialog {
    private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final LinkOption[] NOFOLLOW = {LinkOption.NOFOLLOW_LINKS};
    private StorageDialog() {}

    static final class Item {
        final File file;
        final String label, detail, warning;
        long bytes;
        Item(File file, String label, String detail, String warning) { this.file = file; this.label = label; this.detail = detail; this.warning = warning; }
    }

    static String size(long bytes) {
        return bytes >= 1L << 30 ? String.format(Locale.getDefault(), "%.1f GB", bytes / (double) (1L << 30))
                : String.format(Locale.getDefault(), "%.0f MB", bytes / (double) (1L << 20));
    }

    /** Pointer targets (import folder name to title ID). Anything unreadable fails the whole listing: when unsure, nothing is offered. */
    private static Map<String, String> referenced(File imports) throws IOException {
        Map<String, String> names = new HashMap<>();
        File[] files = imports.listFiles();
        if (files != null) for (File file : files) {
            String name = file.getName();
            if (!name.matches("[0-9A-F]{16}\\.active(\\.bak)?")) continue;
            if (!file.isFile() || file.length() > 128) throw new IOException("Cannot read the list of imported games, so nothing can be safely deleted.");
            String target = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (!target.matches(UUID)) throw new IOException("Cannot read the list of imported games, so nothing can be safely deleted.");
            names.put(target, name.substring(0, 16));
        }
        return names;
    }
    private static boolean plainDir(File dir) { return Files.isDirectory(dir.toPath(), NOFOLLOW); }
    private static List<File> children(File dir, String pattern, boolean directories) {
        List<File> found = new ArrayList<>();
        File[] files = plainDir(dir) ? dir.listFiles() : null;
        if (files != null) for (File file : files) {
            if (!file.getName().matches(pattern) || Files.isSymbolicLink(file.toPath())) continue;
            if (directories ? plainDir(file) : Files.isRegularFile(file.toPath(), NOFOLLOW)) found.add(file);
        }
        return found;
    }
    /** An import whose data/user is a real folder may hold saves from before saves moved out of imports: never offer it. */
    private static boolean holdsSaves(File import_) {
        File[] data = new File(import_, "data").listFiles();
        if (data != null) for (File file : data) if (file.getName().startsWith("user") && !Files.isSymbolicLink(file.toPath())) return true;
        return false;
    }

    /** What may be deleted, without sizes. This is also what delete() re-checks a path against. */
    static List<Item> candidates(File filesDir) throws IOException {
        File root = filesDir.getCanonicalFile(), imports = new File(root, "imports"), staging = new File(root, "component-imports");
        List<Item> items = new ArrayList<>();
        if (imports.exists() && !imports.getCanonicalFile().equals(imports)) throw new IOException("The import folder is not where it should be, so nothing can be safely deleted.");
        Map<String, String> active = referenced(imports);
        for (File dir : children(imports, UUID, true)) {
            String id = active.get(dir.getName());
            if (id == null) {
                if (holdsSaves(dir)) continue;
                String title;
                try { title = GameMetadata.name(dir, GameImport.title(new File(dir, "game/game.3ds"))); } catch (Exception e) { title = "unknown game"; }
                items.add(new Item(dir, "Old import: " + title, "An earlier copy of the game's files (ROM, native code and textures) that is no longer used.",
                        plainDir(new File(dir, "data/states")) ? "Its quick save states are deleted with it. In-game saves are not." : null));
                continue;
            }
            String game = GameMetadata.name(dir, id);
            for (File old : children(new File(dir, "code"), "title\\.so\\.previous-" + UUID, false))
                items.add(new Item(old, "Previous native code: " + game, "The title.so this game used before it was last replaced. Deleting it means that replacement cannot be undone.", null));
            File mods = new File(dir, "data/mods/" + id);
            for (File old : children(mods, "[A-Za-z0-9_.]+\\.previous-" + UUID, true)) {
                String part = old.getName().substring(0, old.getName().indexOf(".previous-"));
                items.add(new Item(old, "Previous " + part + ": " + game, "The " + part + " this game used before it was last replaced. Deleting it means that replacement cannot be undone.", null));
            }
            for (File old : children(mods, "[A-Za-z0-9_.]+\\.previous-" + UUID, false))
                items.add(new Item(old, "Previous " + old.getName().substring(0, old.getName().indexOf(".previous-")) + ": " + game, "A file this game used before it was last replaced.", null));
        }
        for (File dir : children(staging, UUID, true)) items.add(new Item(dir, "Unfinished import", "Files left behind by an import or replacement that did not finish.", null));
        return items;
    }

    static long treeSize(File file) throws IOException {
        long[] total = {0};
        Files.walkFileTree(file.toPath(), new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) { if (attrs.isRegularFile()) total[0] += attrs.size(); return FileVisitResult.CONTINUE; }
        });
        return total[0];
    }
    static List<Item> scan(File filesDir) throws IOException {
        List<Item> items = candidates(filesDir);
        for (Item item : items) item.bytes = treeSize(item.file);
        items.sort((a, b) -> Long.compare(b.bytes, a.bytes));
        return items;
    }

    /**
     * Deletes one listed item. {@code blocked} says why deleting must wait (null if it need not); it is asked under the pointer lock,
     * immediately before the delete. Symlinks inside are removed, never followed.
     */
    static void delete(File filesDir, File target, Supplier<String> blocked) throws IOException {
        synchronized (GameImport.POINTER_LOCK) {
            String reason = blocked.get();
            if (reason != null) throw new IOException(reason);
            for (Item item : candidates(filesDir)) if (item.file.equals(target)) {
                Files.walkFileTree(item.file.toPath(), new SimpleFileVisitor<Path>() {
                    @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException { Files.delete(path); return FileVisitResult.CONTINUE; }
                    @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException { if (error != null) throw error; Files.delete(dir); return FileVisitResult.CONTINUE; }
                });
                return;
            }
        }
        throw new IOException("That item is no longer listed, so nothing was deleted.");
    }

    /** A game is running when the :game process is up (not merely cached) or any title's save lock is held, which also covers a game paused with Home. */
    static boolean gameRunning(android.content.Context context) {
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        List<ActivityManager.RunningAppProcessInfo> processes = manager == null ? null : manager.getRunningAppProcesses();
        if (processes != null) for (ActivityManager.RunningAppProcessInfo process : processes)
            if ((context.getPackageName() + ":game").equals(process.processName) && process.importance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED) return true;
        for (String id : GameImport.importedIds(context.getFilesDir())) {
            try (SaveArchive.Guard ignored = SaveArchive.lock(context.getFilesDir(), id)) { }
            catch (IOException e) { return true; }
        }
        return false;
    }
    private static String blockedReason(Launcher launcher) {
        if (launcher.busy()) return "An import is running. Wait for it to finish, then try again.";
        if (gameRunning(launcher)) return "A game is open. Exit it to the library (not just Home), then try again.";
        return null;
    }

    static void show(Launcher launcher) {
        if (launcher.busy()) { Toast.makeText(launcher, "An import is running. Wait for it to finish.", Toast.LENGTH_LONG).show(); return; }
        Toast.makeText(launcher, "Checking storage…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            List<Item> items = null; String problem = null;
            try { items = scan(launcher.getFilesDir()); } catch (Exception e) { problem = e.getMessage(); }
            List<Item> found = items; String failure = problem;
            launcher.runOnUiThread(() -> { if (!launcher.isFinishing()) list(launcher, found, failure); });
        }, "storage-scan").start();
    }

    private static void list(Launcher launcher, List<Item> items, String problem) {
        LinearLayout content = launcher.column(); content.setPadding(launcher.dp(24), launcher.dp(8), launcher.dp(24), launcher.dp(12));
        long free = new StatFs(launcher.getFilesDir().getAbsolutePath()).getAvailableBytes(), total = 0;
        content.addView(launcher.text("Free space: " + size(free), 16, launcher.p.ink));
        AlertDialog[] dialog = new AlertDialog[1];
        if (problem != null) content.addView(launcher.text(problem, 16, launcher.p.danger));
        else if (items.isEmpty()) content.addView(launcher.text("Nothing to clean up. Games in use, your saves and quick save states are never listed here.", 14, launcher.p.muted));
        else {
            for (Item item : items) total += item.bytes;
            content.addView(launcher.text(size(total) + " can be freed. Choose an item to delete it. Games in use and your in-game saves are never listed.", 14, launcher.p.muted));
            for (Item item : items) {
                Button row = launcher.button(item.label + "\n" + size(item.bytes), false, "storage." + item.file.getName());
                row.setOnClickListener(v -> { dialog[0].dismiss(); confirm(launcher, item); });
                LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2); gap.setMargins(0, launcher.dp(8), 0, 0); content.addView(row, gap);
            }
        }
        ScrollView scroll = new ScrollView(launcher); scroll.addView(content);
        dialog[0] = launcher.controllerDialog(new AlertDialog.Builder(launcher, launcher.p.dialogTheme()).setTitle("Storage").setView(scroll).setNegativeButton("Close", null).create(), false);
        dialog[0].show();
    }

    private static void confirm(Launcher launcher, Item item) {
        launcher.controllerDialog(new AlertDialog.Builder(launcher, launcher.p.dialogTheme()).setTitle("Delete " + item.label + "?")
                .setMessage(size(item.bytes) + " will be freed. " + item.detail + (item.warning == null ? "" : "\n\n" + item.warning)
                        + "\n\nThis cannot be undone. Your in-game saves are not touched.")
                .setPositiveButton("Delete", (d, w) -> delete(launcher, item))
                .setNegativeButton("Cancel", (d, w) -> show(launcher)).create(), true).show();
    }

    private static void delete(Launcher launcher, Item item) {
        new Thread(() -> {
            String problem = null;
            try { delete(launcher.getFilesDir(), item.file, () -> blockedReason(launcher)); } catch (Exception e) { problem = e.getMessage(); }
            String failure = problem;
            launcher.runOnUiThread(() -> {
                if (launcher.isFinishing()) return;
                Toast.makeText(launcher, failure == null ? "Deleted. " + size(item.bytes) + " freed." : failure, Toast.LENGTH_LONG).show();
                show(launcher);
            });
        }, "storage-delete").start();
    }
}
