package io.github.zakuro.android;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.StatFs;
import android.provider.DocumentsContract;
import android.system.Os;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.*;

/** Validate source documents first, then stage only the immutable import plan. */
final class GameImport {
    /** Held around every read or change of an import pointer, and around deleting anything the pointers could name. */
    static final Object POINTER_LOCK = new Object();
    static final String NO_SPACE = "Ran out of free space while copying. No game files were changed.";
    /** Free space that must remain after an import or replacement: the larger of 256 MiB and 5% of what is copied. */
    static long margin(long bytes) { return Math.max(256L << 20, bytes / 20); }
    /** Android reports a full disk as "write failed: ENOSPC (No space left on device)", sometimes wrapped. */
    static boolean outOfSpace(Throwable error) {
        for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause()) {
            String message = error.getMessage();
            if (message != null && (message.contains("ENOSPC") || message.contains("No space left on device"))) return true;
        }
        return false;
    }
    interface Progress { void update(long bytes, int files, String name); }
    static final class CopyProgress {
        private final Progress callback;
        long bytes;
        int files;
        private long last;
        private boolean reported;
        CopyProgress(Progress callback) { this.callback = callback; }
        void update(int added, boolean completed, String name) {
            bytes += added;
            if (completed) files++;
            report(System.nanoTime(), name);
        }
        void report(long now, String name) {
            if (callback != null && (!reported || now - last >= 250_000_000L)) {
                last = now; reported = true;
                callback.update(bytes, files, GameMetadata.clean(name));
            }
        }
    }
    static File child(File parent, String name) throws IOException {
        ImportPlan.safeName(name);
        File file = new File(parent, name);
        if (!file.getCanonicalFile().getParentFile().equals(parent.getCanonicalFile()))
            throw new IOException("Document escapes import folder");
        return file;
    }
    static void mkdir(File file) throws IOException {
        if (!file.isDirectory() && !file.mkdirs()) throw new IOException("Cannot create " + file.getName());
    }
    private static final class DocumentsSource implements ImportPlan.Source {
        final ContentResolver resolver;
        final Uri tree;
        DocumentsSource(ContentResolver resolver, Uri tree) { this.resolver = resolver; this.tree = tree; }
        public List<ImportPlan.Entry> children(String id) throws IOException {
            Uri uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
            String[] columns = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE};
            List<ImportPlan.Entry> entries = new ArrayList<>();
            try (Cursor cursor = resolver.query(uri, columns, null, null, null)) {
                if (cursor == null) throw new IOException("Cannot read selected folder");
                while (cursor.moveToNext()) {
                    if (entries.size() >= 200000) throw new IOException("Too many documents");
                    entries.add(new ImportPlan.Entry(cursor.getString(0), cursor.getString(1),
                        DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2)), cursor.isNull(3) ? -1 : cursor.getLong(3)));
                }
            }
            return entries;
        }
        public SeekableByteChannel open(String id) throws IOException {
            return openDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id));
        }
    }
    private static SeekableByteChannel openDocument(ContentResolver resolver, Uri uri) throws IOException {
        ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "r");
        if (descriptor == null) throw new IOException("Cannot open source document");
        ParcelFileDescriptor.AutoCloseInputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
        try { SeekableByteChannel channel = stream.getChannel(); channel.position(0); return channel; }
        catch (IOException e) { stream.close(); throw new IOException("Cannot inspect this storage directly. Use a folder on device storage or the SD card.", e); }
    }
    static boolean arm64(File file) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(file.toPath())) { return ImportPlan.arm64(channel); }
    }
    static String title(File file) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(file.toPath())) { return ImportPlan.title(channel); }
    }
    static String install(android.content.Context context, Uri tree) throws Exception { return install(context, tree, null); }
    private static String folderName(ContentResolver resolver, Uri tree) {
        Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        try (Cursor cursor = resolver.query(document, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            return cursor != null && cursor.moveToFirst() ? cursor.getString(0) : "";
        } catch (Exception ignored) { return ""; }
    }
    private static void copy(ImportPlan.Source source, ImportPlan plan, File stage, Progress callback) throws IOException {
        // Only the staging folder has been written at this point, so this sentence is true for every caller.
        try { copyFiles(source, plan, stage, callback); }
        catch (IOException e) { if (outOfSpace(e)) throw new IOException(NO_SPACE, e); throw e; }
    }
    private static void copyFiles(ImportPlan.Source source, ImportPlan plan, File stage, Progress callback) throws IOException {
        CopyProgress progress = new CopyProgress(callback);
        ByteBuffer buffer = ByteBuffer.allocate(65536);
        String root = stage.getCanonicalPath() + File.separator;
        for (ImportPlan.Item item : plan.files) {
            File dest = new File(stage, item.destination).getCanonicalFile();
            if (!dest.getPath().startsWith(root)) throw new IOException("Invalid import destination");
            try (SeekableByteChannel input = source.open(item.source.id)) {
                if (input.size() != item.source.size) throw new IOException("Source file changed: " + item.source.name);
                item.checkHeader(input);
                if (item.destination.equals("game/game.3ds") && !plan.titleId.equals(ImportPlan.title(input)))
                    throw new IOException("The game changed after inspection. Try adding it again.");
                if (item.destination.equals("code/title.so") && !ImportPlan.arm64(input))
                    throw new IOException("The native library changed after inspection.");
                if (item.destination.equals("code/title.so")) NativeLibrary.inspect(input).requireCompatible();
                input.position(0); mkdir(dest.getParentFile());
                try (FileOutputStream output = new FileOutputStream(dest)) {
                    long copied = 0;
                    for (;;) {
                        buffer.clear(); int n = input.read(buffer);
                        if (n < 0) break;
                        if (n == 0) throw new IOException("Cannot read " + item.source.name);
                        if (n > item.source.size - copied) throw new IOException("Source file grew: " + item.source.name);
                        output.write(buffer.array(), 0, n); copied += n;
                        progress.update(n, false, item.source.name);
                    }
                    if (copied != item.source.size || input.size() != item.source.size)
                        throw new IOException("Source file changed or was truncated: " + item.source.name);
                    output.getFD().sync(); progress.update(0, true, item.source.name);
                }
                try (SeekableByteChannel copied = Files.newByteChannel(dest.toPath())) {
                    item.checkHeader(copied);
                    if (item.destination.toLowerCase(Locale.ROOT).endsWith(".png")) ImportPlan.png(copied, item.source.name);
                }
            }
        }
    }
    static String install(android.content.Context context, Uri tree, Progress progress) throws Exception {
        return install(context, tree, true, progress);
    }
    static String install(android.content.Context context, Uri tree, boolean includeTextures, Progress progress) throws Exception {
        DocumentsSource source = new DocumentsSource(context.getContentResolver(), tree);
        ImportPlan plan = ImportPlan.inspect(source, DocumentsContract.getTreeDocumentId(tree), includeTextures);
        File imports = new File(context.getFilesDir(), "imports");
        File stage = new File(imports, UUID.randomUUID().toString());
        plan.checkDestination(stage);
        long available = new StatFs(context.getFilesDir().getAbsolutePath()).getAvailableBytes();
        if (plan.bytes > Math.max(0, available - margin(plan.bytes)))
            throw new IOException("Not enough free space for this game and its selected files. No files were copied.");
        // No staging or save directories are created until all source checks pass.
        mkdir(imports); Files.createDirectory(stage.toPath());
        boolean keepStage = false;
        try {
            copy(source, plan, stage, progress);
            File rom = new File(stage, "game/game.3ds"), executable = new File(stage, "code/title.so");
            if (!plan.titleId.equals(title(rom)) || !arm64(executable)) throw new IOException("The source changed during import");
            try (SeekableByteChannel channel = Files.newByteChannel(executable.toPath())) { NativeLibrary.inspect(channel).requireCompatible(); }
            if (!executable.setReadOnly()) throw new IOException("Cannot secure title.so");
            String name = GameMetadata.romTitle(rom);
            if (name.isEmpty()) name = GameMetadata.choose(plan.rom.name, folderName(context.getContentResolver(), tree), plan.titleId);
            GameMetadata.store(stage, name);
            File data = new File(stage, "data"); mkdir(data);
            File user = new File(context.getFilesDir(), "savedata/" + plan.titleId + "/user"); mkdir(user);
            Os.symlink(user.getAbsolutePath(), new File(data, "user").getAbsolutePath());
            android.util.AtomicFile pointer = new android.util.AtomicFile(new File(imports, plan.titleId + ".active"));
            synchronized (POINTER_LOCK) {
                FileOutputStream stream = null;
                try {
                    stream = pointer.startWrite();
                    byte[] expected = stage.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    stream.write(expected);
                    stream.getFD().sync();
                    // Once activation is attempted, retain its target if the outcome is uncertain.
                    keepStage = true;
                    pointer.finishWrite(stream);
                    stream = null;
                    try (InputStream saved = pointer.openRead()) {
                        for (byte value : expected) if (saved.read() != (value & 255)) throw new IOException("Could not confirm game activation");
                        if (saved.read() != -1) throw new IOException("Could not confirm game activation");
                    }
                } catch (Exception e) { if (stream != null) pointer.failWrite(stream); throw e; }
            }
            return plan.titleId;
        } finally { if (!keepStage) remove(stage); }
    }
    private static File componentStage(android.content.Context context, ImportPlan plan) throws IOException {
        return componentStage(context, plan, 0);
    }
    private static File componentStage(android.content.Context context, ImportPlan plan, long backupBytes) throws IOException {
        File parent = new File(context.getFilesDir(), "component-imports");
        File stage = new File(parent, UUID.randomUUID().toString()); plan.checkDestination(stage);
        long available = new StatFs(context.getFilesDir().getAbsolutePath()).getAvailableBytes();
        long budget = Math.max(0, available - margin(plan.bytes));
        if (backupBytes < 0 || backupBytes > budget || plan.bytes > budget - backupBytes) throw new IOException("Not enough free space for the replacement and backup. No files were copied.");
        mkdir(parent); Files.createDirectory(stage.toPath()); return stage;
    }
    private static void validId(String id) throws IOException {
        if (id == null || !id.matches("[0-9A-F]{16}")) throw new IOException("Invalid game ID");
    }
    static String replaceCode(android.content.Context context, String id, Uri uri, Progress progress) throws Exception {
        validId(id); File version = active(context, id);
        ContentResolver resolver = context.getContentResolver();
        ImportPlan.Source source = new ImportPlan.Source() {
            public List<ImportPlan.Entry> children(String ignored) { return Collections.emptyList(); }
            public SeekableByteChannel open(String ignored) throws IOException { return openDocument(resolver, uri); }
        };
        ImportPlan plan = ImportPlan.nativeCode(source, new ImportPlan.Entry("code", "title.so", false, -1), id);
        File stage = componentStage(context, plan, new File(version, "code/title.so").length());
        try {
            copy(source, plan, stage, progress);
            File code = new File(stage, "code/title.so");
            try (SeekableByteChannel channel = Files.newByteChannel(code.toPath())) { NativeLibrary.inspect(channel).requireCompatible(); }
            if (!code.setReadOnly()) throw new IOException("Cannot secure native game code");
            File target = new File(version, "code/title.so");
            synchronized (POINTER_LOCK) {
                if (!active(context, id).equals(version)) throw new IOException("The game changed while importing. Select it again.");
                // Android may deny hard links; copy only the old library so target replacement remains atomic.
                File backup = new File(target.getParentFile(), "title.so.previous-" + UUID.randomUUID());
                boolean committed = false;
                try {
                    try (FileInputStream input = new FileInputStream(target); FileOutputStream output = new FileOutputStream(backup)) {
                        byte[] buffer = new byte[65536]; int n;
                        while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
                        output.getFD().sync();
                    }
                    if (!backup.setReadOnly()) throw new IOException("Cannot secure native-code backup");
                    Os.rename(code.getAbsolutePath(), target.getAbsolutePath());
                    committed = true;
                } finally {
                    if (!committed) backup.delete();
                }
            }
            return id;
        } finally { remove(stage); }
    }
    static String replaceTextures(android.content.Context context, String id, Uri tree, Progress progress) throws Exception {
        validId(id); File version = active(context, id);
        DocumentsSource source = new DocumentsSource(context.getContentResolver(), tree);
        ImportPlan plan = ImportPlan.texturePack(source, DocumentsContract.getTreeDocumentId(tree), id);
        return swapIn(context, id, version, source, plan, progress, false, 0, false, "textures");
    }
    /** The parts of a game's mods folder that mods own; textures are kept apart and never touched here. */
    static final String[] MOD_PARTS = {"romfs", "romfs_ext", "code.ips", "code.bps", "code.bin", "exheader.bin"};
    /** Names that change the game's code: only one mod may carry any of them. */
    private static final List<String> CODE_PARTS = Arrays.asList("code.ips", "code.bps", "code.bin", "exheader.bin");
    /**
     * Whether the next replaceRomfs adds to the current mods (true) or replaces them (false). Set by the settings dialog
     * just before it starts the folder picker, because the launcher calls replaceRomfs without a mode.
     * ponytail: a static flag instead of a new parameter through Launcher; one import runs at a time.
     */
    static volatile boolean addToMods;
    static File modsDir(File version, String id) { return new File(version, "data/mods/" + id); }
    static boolean hasMods(File version, String id) {
        for (String part : MOD_PARTS) if (new File(modsDir(version, id), part).exists()) return true;
        return false;
    }
    static String replaceRomfs(android.content.Context context, String id, Uri tree, Progress progress) throws Exception {
        validId(id); File version = active(context, id);
        boolean add = addToMods; addToMods = false;
        String folder = folderName(context.getContentResolver(), tree);
        if (folder.matches("[0-9A-Fa-f]{16}") && !folder.equalsIgnoreCase(id))
            throw new IOException("This mod folder is for game " + folder.toUpperCase(Locale.ROOT) + ", not this one.");
        DocumentsSource source = new DocumentsSource(context.getContentResolver(), tree);
        ImportPlan plan = ImportPlan.romfsMod(source, DocumentsContract.getTreeDocumentId(tree), folder, id);
        return installMod(context, id, version, source, plan, progress, add);
    }
    /** Remove a game's mods (not its texture pack). All or nothing; the old parts are deleted once every part has gone. */
    static String removeMods(android.content.Context context, String id) throws Exception {
        validId(id); File version = active(context, id);
        File parent = modsDir(version, id); boolean[] uncertain = {false};
        synchronized (POINTER_LOCK) {
            if (!active(context, id).equals(version)) throw new IOException("The game changed. Open its settings again.");
            swapParts(new File(parent, ".none"), parent, MOD_PARTS, true, MOVE, uncertain);
        }
        return id;
    }
    private static String installMod(android.content.Context context, String id, File version, DocumentsSource source,
            ImportPlan plan, Progress progress, boolean add) throws Exception {
        File parent = modsDir(version, id);
        if (!add) // The old copy goes once replaced: mods can be large and the source folder still has it.
            return swapIn(context, id, version, source, plan, progress, true, 0, false, MOD_PARTS);
        String clash = codeClash(plan, parent);
        if (clash != null) throw new IOException(clash);
        // ponytail: the merged tree is hard-linked or copied beside the new files, so space for the old mods is reserved too.
        return swapIn(context, id, version, source, plan, progress, true, size(parent, MOD_PARTS), true, MOD_PARTS);
    }
    /** Two mods that both change the game's code cannot be combined meaningfully; say so instead of guessing. */
    static String codeClash(ImportPlan plan, File parent) {
        String incoming = null, existing = null;
        for (ImportPlan.Item item : plan.files) {
            String name = item.destination.substring(item.destination.lastIndexOf('/') + 1);
            if (item.destination.equals("data/mods/" + plan.titleId + "/" + name) && CODE_PARTS.contains(name)) { incoming = name; break; }
        }
        for (String name : CODE_PARTS) if (new File(parent, name).exists()) { existing = name; break; }
        return incoming == null || existing == null ? null
                : "Both the current mods and this one change the game's code (" + existing + " and " + incoming
                + "). Only one code mod can be active. Choose Replace current mods, or remove the mods first.";
    }
    static long size(File dir, String... parts) {
        long total = 0;
        for (String part : parts) total += size(new File(dir, part));
        return total;
    }
    private static long size(File file) {
        if (Files.isSymbolicLink(file.toPath())) return 0;
        if (!file.isDirectory()) return file.length();
        long total = 0; File[] children = file.listFiles();
        if (children != null) for (File child : children) total += size(child);
        return total;
    }
    /** Copy tree {@code from} to {@code to}, hard-linking where the filesystem allows. Symbolic links are skipped. */
    static void copyTree(File from, File to) throws IOException {
        if (Files.isSymbolicLink(from.toPath())) return;
        if (from.isDirectory()) {
            mkdir(to); File[] children = from.listFiles();
            if (children != null) for (File child : children) copyTree(child, new File(to, child.getName()));
            return;
        }
        mkdir(to.getParentFile());
        try { Files.createLink(to.toPath(), from.toPath()); }
        catch (IOException | UnsupportedOperationException | SecurityException e) { Files.copy(from.toPath(), to.toPath()); }
    }
    private static File named(File dir, String name) {
        String[] names = dir.list(); File same = null;
        if (names != null) for (String other : names) {
            if (other.equals(name)) return new File(dir, other);
            if (same == null && other.equalsIgnoreCase(name)) same = new File(dir, other);
        }
        return same;
    }
    /**
     * Lay the files of {@code from} over {@code into}: the newer file wins, folders merge. Names match ignoring case, as
     * mods made on Windows spell them either way and the game's own files are matched that way too.
     */
    static void mergeTree(File from, File into) throws IOException {
        File[] children = from.listFiles();
        if (children != null) for (File child : children) {
            File old = named(into, child.getName());
            if (child.isDirectory() && old != null && old.isDirectory()) { mergeTree(child, old); continue; }
            if (old != null) remove(old);
            Files.move(child.toPath(), new File(into, child.getName()).toPath());
        }
    }
    /** Stage the plan, then swap each part folder of the game's mods for its staged copy (or none). */
    private static String swapIn(android.content.Context context, String id, File version, DocumentsSource source,
            ImportPlan plan, Progress progress, boolean dropOld, long reserve, boolean add, String... parts) throws Exception {
        File stage = componentStage(context, plan, reserve); boolean[] uncertain = {false};
        try {
            copy(source, plan, stage, progress);
            File staged = new File(stage, "data/mods/" + id), parent = new File(version, "data/mods/" + id); mkdir(parent);
            if (add) parts = mergeStaged(stage, staged, parent, parts);
            synchronized (POINTER_LOCK) {
                if (!active(context, id).equals(version)) throw new IOException("The game changed while importing. Select it again.");
                swapParts(staged, parent, parts, dropOld, MOVE, uncertain);
            }
            return id;
        } finally { if (!uncertain[0]) remove(stage); }
    }
    /**
     * For adding: turn each staged romfs part into the current part with the new files over it, so the swap that follows
     * stays one all-or-nothing replacement. Returns the parts to swap: those the new mod carries (code files only when it
     * has them, so the current mods' code is left alone).
     */
    static String[] mergeStaged(File stage, File staged, File parent, String[] parts) throws IOException {
        List<String> swap = new ArrayList<>();
        for (String part : parts) {
            File fresh = new File(staged, part), old = new File(parent, part);
            if (!fresh.exists()) continue;
            if (fresh.isDirectory() && old.isDirectory()) {
                File merged = new File(stage, "merged/" + part);
                copyTree(old, merged); mergeTree(fresh, merged); remove(fresh);
                Files.move(merged.toPath(), fresh.toPath());
            }
            swap.add(part);
        }
        return swap.toArray(new String[0]);
    }
    interface Mover { void move(File from, File to) throws IOException; }
    static final Mover MOVE = (from, to) -> Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE);
    private static final class Swap { File target, backup, pack; boolean moved, installed; }
    /**
     * Replace each part of {@code parent} with its copy in {@code staged}, all or nothing: on any failure every part
     * already swapped is put back. {@code uncertain[0]} is set when a put-back failed too, so the caller keeps the staging folder.
     * Old parts are only deleted (dropOld) once every part has swapped.
     */
    static void swapParts(File staged, File parent, String[] parts, boolean dropOld, Mover move, boolean[] uncertain) throws IOException {
        List<Swap> done = new ArrayList<>();
        try {
            for (String part : parts) {
                Swap swap = new Swap(); swap.pack = new File(staged, part); swap.target = new File(parent, part);
                swap.backup = new File(parent, part + ".previous-" + UUID.randomUUID()); done.add(swap);
                if (swap.target.exists()) { move.move(swap.target, swap.backup); swap.moved = true; }
                if (swap.pack.exists()) { move.move(swap.pack, swap.target); swap.installed = true; }
            }
        } catch (Exception e) {
            for (int i = done.size() - 1; i >= 0; i--) {
                Swap swap = done.get(i);
                try {
                    if (swap.installed) move.move(swap.target, swap.pack);
                    if (swap.moved) move.move(swap.backup, swap.target);
                } catch (Exception restore) { uncertain[0] = true; e.addSuppressed(restore); }
            }
            throw e;
        }
        if (dropOld) for (Swap swap : done) if (swap.moved) remove(swap.backup);
    }
    static String sha256(File file) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        try (InputStream in = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[65536]; int n;
            while ((n = in.read(buffer)) > 0) digest.update(buffer, 0, n);
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", value));
        return hex.toString();
    }
    /** What the user sees after native code is imported: the hash to compare, why it matters, and a rebuild hint if it is old. */
    static String identity(String what, String hash) {
        return "SHA-256 of " + what + ":\n" + hash.substring(0, 32) + "\n" + hash.substring(32)
                + "\n\nThis identifies the build you imported; compare it with the hash published by whoever made it. "
                + "Imported native code runs inside Zakuroishi with the same access to its files and saves, so only keep builds you trust.";
    }
    static String codeReport(File code) throws IOException {
        NativeLibrary.Info info;
        try (SeekableByteChannel channel = Files.newByteChannel(code.toPath())) { info = NativeLibrary.inspect(channel); }
        String note = info.outdated() ? "This title.so was made by an older generator (generation " + info.generation + ", current " + NativeLibrary.GENERATION
                + "). It runs, but rebuild it with zakuro-build-title for better performance.\n\n" : "";
        return note + identity("title.so", sha256(code));
    }
    /** Title IDs that have an import pointer (the same rule the library uses). */
    static SortedSet<String> importedIds(File filesDir) {
        File[] files = new File(filesDir, "imports").listFiles(); SortedSet<String> found = new TreeSet<>();
        if (files != null) for (File file : files) if (file.getName().matches("[0-9A-F]{16}\\.active(\\.bak)?")) found.add(file.getName().substring(0, 16));
        return found;
    }
    static void remove(File file) {
        if (file.isDirectory() && !Files.isSymbolicLink(file.toPath())) {
            File[] files = file.listFiles(); if (files != null) for (File child : files) remove(child);
        }
        file.delete();
    }
    static File active(android.content.Context context, String id) throws IOException {
        File imports = new File(context.getFilesDir(), "imports");
        android.util.AtomicFile pointer = new android.util.AtomicFile(new File(imports, id + ".active"));
        String name;
        synchronized (POINTER_LOCK) {
            try (InputStream in = pointer.openRead()) {
                byte[] bytes = new byte[128]; int n = in.read(bytes);
                if (n < 1 || in.read() != -1) throw new IOException("Invalid import pointer");
                name = new String(bytes, 0, n, java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        if (!name.matches("[0-9a-f-]{36}")) throw new IOException("Invalid import pointer");
        File version = child(imports, name);
        if (!new File(version, "game/game.3ds").isFile() || !arm64(new File(version, "code/title.so")))
            throw new IOException("Imported files are missing; import this game again");
        return version;
    }
}
