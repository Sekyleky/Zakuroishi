package io.github.zakuro.android;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Lists each game's in-game saves in the system file picker, so save editors can open and
 * write them in place. Document ids are "TITLE_ID" or "TITLE_ID/path/below/user". Reads are
 * always allowed; a write takes the game's save lock (refused while the game runs), backs the
 * saves up to a timestamped backup first (the newest 3 are kept, shown read-only under each game's
 * ":backups" folder, so one can be copied out without adb), and holds the lock until the editor
 * closes the file. Files can't be created, renamed or deleted here, and quick save states are not listed.
 */
public final class SavesProvider extends DocumentsProvider {
    private static final String ROOT = "saves";
    // ':' can't occur in a real save name (SaveArchive.validateName), so this never shadows a save file.
    private static final String BACKUPS = ":backups";
    private static final String[] ROOT_COLUMNS = {Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_TITLE, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_ICON};
    private static final String[] DOCUMENT_COLUMNS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED};

    @Override public boolean onCreate() { return true; }

    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor roots = new MatrixCursor(projection != null ? projection : ROOT_COLUMNS);
        roots.newRow().add(Root.COLUMN_ROOT_ID, ROOT).add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY)
            .add(Root.COLUMN_TITLE, "Zakuroishi saves").add(Root.COLUMN_DOCUMENT_ID, ROOT).add(Root.COLUMN_ICON, getContext().getApplicationInfo().icon);
        return roots;
    }

    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        MatrixCursor result = new MatrixCursor(projection != null ? projection : DOCUMENT_COLUMNS);
        if (ROOT.equals(id)) row(result, ROOT, "Zakuroishi saves", Document.MIME_TYPE_DIR, 0, 0, 0);
        else add(result, id);
        return result;
    }

    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sortOrder) throws FileNotFoundException {
        MatrixCursor result = new MatrixCursor(projection != null ? projection : DOCUMENT_COLUMNS);
        try {
            if (ROOT.equals(parent)) {
                // one folder per game that has in-game saves
                Path saves = getContext().getFilesDir().toPath().toRealPath().resolve("savedata");
                if (!Files.isDirectory(saves, LinkOption.NOFOLLOW_LINKS)) return result;
                try (DirectoryStream<Path> games = Files.newDirectoryStream(saves)) {
                    for (Path game : games) {
                        String title = game.getFileName().toString();
                        if (title.matches("[0-9A-F]{16}") && Files.isDirectory(game.resolve("user"), LinkOption.NOFOLLOW_LINKS)) add(result, title);
                    }
                }
            } else {
                if (parent.endsWith("/" + BACKUPS)) {
                    for (Path backup : SaveArchive.backups(getContext().getFilesDir(), title(parent))) add(result, parent + "/" + backup.getFileName().toString().substring(12));
                    return result;
                }
                Path dir = path(parent);
                if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) throw new FileNotFoundException("Not a folder");
                if (parent.indexOf('/') < 0 && !SaveArchive.backups(getContext().getFilesDir(), parent).isEmpty()) add(result, parent + "/" + BACKUPS);
                try (DirectoryStream<Path> children = Files.newDirectoryStream(dir)) {
                    for (Path child : children) {
                        if (Files.isSymbolicLink(child)) continue;
                        add(result, parent + "/" + child.getFileName());
                    }
                }
            }
        } catch (IOException e) { throw notFound(e); }
        return result;
    }

    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        try {
            Path file = path(id);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new FileNotFoundException("Not a save file");
            int access = ParcelFileDescriptor.parseMode(mode);
            if (access == ParcelFileDescriptor.MODE_READ_ONLY) return ParcelFileDescriptor.open(file.toFile(), access);
            if (isBackup(id)) throw new FileNotFoundException("Backups are read-only");
            if ((access & ParcelFileDescriptor.MODE_CREATE) != 0 && !Files.exists(file, LinkOption.NOFOLLOW_LINKS)) throw new FileNotFoundException("New files can't be created");
            String title = title(id);
            File files = getContext().getFilesDir();
            SaveArchive.Guard guard = SaveArchive.lock(files, title);
            try {
                SaveArchive.backup(files, title);
                return ParcelFileDescriptor.open(file.toFile(), access, new Handler(Looper.getMainLooper()), failure -> {
                    try { guard.close(); } catch (IOException ignored) { /* the lock goes with the process */ }
                });
            } catch (IOException | RuntimeException e) { guard.close(); throw e; }
        } catch (IOException e) { throw notFound(e); }
    }

    private void add(MatrixCursor result, String id) throws FileNotFoundException {
        try {
            Path file = path(id);
            boolean dir = Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS);
            String name = id.contains("/") ? file.getFileName().toString() : gameName(id);
            if (id.endsWith("/" + BACKUPS)) name = "Backups (newest 3, read-only)";
            else if (isBackup(id) && !id.substring(id.indexOf(BACKUPS) + BACKUPS.length() + 1).contains("/"))
                name = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT).format(new java.util.Date(Long.parseLong(name.substring(12))));
            row(result, id, name, dir ? Document.MIME_TYPE_DIR : "application/octet-stream",
                dir || isBackup(id) ? 0 : Document.FLAG_SUPPORTS_WRITE, dir ? 0 : Files.size(file), Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toMillis());
        } catch (IOException e) { throw notFound(e); }
    }

    private static void row(MatrixCursor result, String id, String name, String mime, int flags, long size, long modified) {
        result.newRow().add(Document.COLUMN_DOCUMENT_ID, id).add(Document.COLUMN_DISPLAY_NAME, name).add(Document.COLUMN_MIME_TYPE, mime)
            .add(Document.COLUMN_FLAGS, flags).add(Document.COLUMN_SIZE, size).add(Document.COLUMN_LAST_MODIFIED, modified);
    }

    private String gameName(String title) {
        // the library's name, else the ROM's own (as DebugSettings), else just the title ID
        String name = GameSettings.preferences(getContext()).getString(title + ".name", "").trim();
        if (name.isEmpty()) try { name = GameMetadata.name(GameImport.active(getContext(), title), title); } catch (Exception e) { name = ""; }
        return name == null || name.isEmpty() ? title : name + " (" + title + ")";
    }

    private static boolean isBackup(String id) { return id.contains("/" + BACKUPS); }

    private static String title(String id) { int slash = id.indexOf('/'); return slash < 0 ? id : id.substring(0, slash); }

    private Path path(String id) throws IOException {
        if (id == null || ROOT.equals(id)) throw new IOException("Not a save");
        int slash = id.indexOf('/');
        String relative = slash < 0 ? "" : id.substring(slash + 1);
        if (relative.equals(BACKUPS)) return SaveArchive.saveFile(getContext().getFilesDir(), title(id), ""); // the listing itself is virtual
        if (relative.startsWith(BACKUPS + "/")) return SaveArchive.backupFile(getContext().getFilesDir(), title(id), relative.substring(BACKUPS.length() + 1));
        return SaveArchive.saveFile(getContext().getFilesDir(), title(id), relative);
    }

    private static FileNotFoundException notFound(IOException e) {
        if (e instanceof FileNotFoundException) return (FileNotFoundException) e;
        FileNotFoundException wrapped = new FileNotFoundException(e.getMessage());
        wrapped.initCause(e);
        return wrapped;
    }
}
