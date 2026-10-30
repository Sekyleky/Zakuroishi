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
import java.util.*;

/** Validate source documents first, then stage only the immutable import plan. */
final class GameImport {
    private static final Object POINTER_LOCK = new Object();
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
            ParcelFileDescriptor descriptor = resolver.openFileDescriptor(DocumentsContract.buildDocumentUriUsingTree(tree, id), "r");
            if (descriptor == null) throw new IOException("Cannot open source document");
            ParcelFileDescriptor.AutoCloseInputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
            try {
                SeekableByteChannel channel = stream.getChannel();
                channel.position(0);
                return channel;
            } catch (IOException e) {
                stream.close();
                throw new IOException("Cannot inspect this storage directly. Use a folder on device storage or the SD card.", e);
            }
        }
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
                try (SeekableByteChannel copied = Files.newByteChannel(dest.toPath())) { item.checkHeader(copied); }
            }
        }
    }
    static String install(android.content.Context context, Uri tree, Progress progress) throws Exception {
        DocumentsSource source = new DocumentsSource(context.getContentResolver(), tree);
        ImportPlan plan = ImportPlan.inspect(source, DocumentsContract.getTreeDocumentId(tree));
        File imports = new File(context.getFilesDir(), "imports");
        File stage = new File(imports, UUID.randomUUID().toString());
        plan.checkDestination(stage);
        long available = new StatFs(context.getFilesDir().getAbsolutePath()).getAvailableBytes();
        if (plan.bytes > Math.max(0, available - 16L * 1024 * 1024))
            throw new IOException("Not enough free space for this game and its selected files. No files were copied.");
        // No staging or save directories are created until all source checks pass.
        mkdir(imports); Files.createDirectory(stage.toPath());
        boolean keepStage = false;
        try {
            copy(source, plan, stage, progress);
            File rom = new File(stage, "game/game.3ds"), executable = new File(stage, "code/title.so");
            if (!plan.titleId.equals(title(rom)) || !arm64(executable)) throw new IOException("The source changed during import");
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
