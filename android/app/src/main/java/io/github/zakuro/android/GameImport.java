package io.github.zakuro.android;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.system.Os;
import java.io.*;
import java.util.*;

/** Private immutable imports; only the small active pointer is replaced on commit. */
final class GameImport {
    static File child(File parent, String name) throws IOException {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0)
            throw new IOException("Unsafe document name");
        File file = new File(parent, name);
        if (!file.getCanonicalFile().getParentFile().equals(parent.getCanonicalFile()))
            throw new IOException("Document escapes import folder");
        return file;
    }
    static void mkdir(File f) throws IOException {
        if (!f.isDirectory() && !f.mkdirs()) throw new IOException("Cannot create " + f.getName());
    }
    private static void copy(ContentResolver resolver, Uri tree, String id, File out,
                             Set<String> ancestors, int depth, int[] count, boolean assets) throws Exception {
        if (depth > 64 || !ancestors.add(id)) throw new IOException("Folder nesting or cycle limit");
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
        String[] columns = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE};
        try (Cursor cursor = resolver.query(children, columns, null, null, null)) {
            if (cursor == null) throw new IOException("Cannot read selected folder");
            Set<String> names = new HashSet<>();
            while (cursor.moveToNext()) {
                if (++count[0] > 200000) throw new IOException("Too many documents");
                String name = cursor.getString(1);
                File dest = child(out, name);
                if (!names.add(name.toLowerCase(Locale.ROOT))) throw new IOException("Duplicate document name");
                String document = cursor.getString(0);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))) {
                    mkdir(dest);
                    copy(resolver, tree, document, dest, ancestors, depth + 1, count,
                            assets || name.equalsIgnoreCase("textures") || name.equalsIgnoreCase("sysdata"));
                } else {
                    if (!wanted(name, assets)) continue;
                    Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, document);
                    try (InputStream in = resolver.openInputStream(uri);
                         FileOutputStream stream = new FileOutputStream(dest)) {
                        if (in == null) throw new IOException("Cannot open " + name);
                        byte[] buffer = new byte[65536];
                        int n;
                        while ((n = in.read(buffer)) != -1) stream.write(buffer, 0, n);
                        stream.getFD().sync();
                    }
                }
            }
        } finally { ancestors.remove(id); }
    }
    static boolean wanted(String name, boolean assets) {
        String lower = name.toLowerCase(Locale.ROOT);
        return assets || lower.equals("title.so") || lower.endsWith(".3ds")
                || lower.endsWith(".cci") || lower.endsWith(".cxi");
    }
    private static void find(File dir, List<File> roms, List<File> code, List<File> textures) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) throw new IOException("Cannot inspect import");
        for (File f : files) {
            if (f.isDirectory()) {
                if (f.getName().equalsIgnoreCase("textures")) textures.add(f);
                find(f, roms, code, textures);
            } else {
                String name = f.getName().toLowerCase(Locale.ROOT);
                if (name.endsWith(".3ds") || name.endsWith(".cci") || name.endsWith(".cxi")) roms.add(f);
                if (name.equals("title.so") && arm64(f)) code.add(f);
            }
        }
    }
    static boolean arm64(File file) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            if (in.length() < 64) return false;
            byte[] h = new byte[64]; in.readFully(h);
            return h[0] == 127 && h[1] == 'E' && h[2] == 'L' && h[3] == 'F'
                    && h[4] == 2 && h[5] == 1 && h[16] == 3 && h[17] == 0
                    && (h[18] & 255) == 183 && h[19] == 0;
        }
    }
    static void installSysdata(File payload, File data) throws IOException {
        List<File> folders = new ArrayList<>();
        findSysdata(payload, folders);
        if (folders.size() > 1) throw new IOException("Multiple sysdata folders; select one built game");
        if (!folders.isEmpty()) java.nio.file.Files.move(folders.get(0).toPath(), new File(data, "sysdata").toPath());
    }
    private static void findSysdata(File dir, List<File> folders) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) throw new IOException("Cannot inspect sysdata");
        for (File file : files) if (file.isDirectory()) {
            if (file.getName().equalsIgnoreCase("sysdata")) folders.add(file);
            else if (!file.getName().equalsIgnoreCase("textures")) findSysdata(file, folders);
        }
    }
    static long little(RandomAccessFile f, long offset, int bytes) throws IOException {
        f.seek(offset); long value = 0;
        for (int i = 0; i < bytes; i++) value |= (long) f.readUnsignedByte() << (i * 8);
        return value;
    }
    static String title(File rom) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(rom, "r")) {
            long magic = little(f, 0x100, 4);
            long base = magic == 0x4453434eL ? little(f, 0x120, 4) * 512 : 0;
            if (base < 0 || base > f.length() - 512 || little(f, base + 0x100, 4) != 0x4843434eL)
                throw new IOException("Expected an original decrypted NCSD/NCCH ROM");
            if ((little(f, base + 0x18f, 1) & 4) == 0) throw new IOException("ROM is encrypted; decrypt it before importing");
            long id = little(f, base + 0x118, 8);
            if (id == 0) throw new IOException("ROM has no title ID");
            return String.format(Locale.ROOT, "%016X", id);
        }
    }
    static String install(android.content.Context context, Uri tree) throws Exception {
        File imports = new File(context.getFilesDir(), "imports"); mkdir(imports);
        File stage = new File(imports, UUID.randomUUID().toString()); mkdir(stage);
        boolean committed = false;
        try {
            File payload = new File(stage, "game"); mkdir(payload);
            copy(context.getContentResolver(), tree, DocumentsContract.getTreeDocumentId(tree), payload,
                    new HashSet<>(), 0, new int[1], false);
            List<File> roms = new ArrayList<>(), code = new ArrayList<>(), textures = new ArrayList<>();
            find(payload, roms, code, textures);
            if (roms.size() != 1 || code.size() != 1 || textures.size() > 1)
                throw new IOException("Select one built game: one original ROM, one ARM64 title.so, at most one textures folder");
            File rom = roms.get(0); String id = title(rom);
            File normalized = new File(payload, "game.3ds");
            if (!rom.equals(normalized) && (normalized.exists() || !rom.renameTo(normalized)))
                throw new IOException("Cannot normalize ROM path");
            File codeDir = new File(stage, "code"); mkdir(codeDir);
            File executable = new File(codeDir, "title.so");
            if (!code.get(0).renameTo(executable) || !executable.setReadOnly())
                throw new IOException("Cannot secure title.so");
            File data = new File(stage, "data"); mkdir(data);
            installSysdata(payload, data);
            File user = new File(context.getFilesDir(), "savedata/" + id + "/user"); mkdir(user);
            Os.symlink(user.getAbsolutePath(), new File(data, "user").getAbsolutePath());
            if (!textures.isEmpty()) {
                File mods = new File(data, "mods/" + id); mkdir(mods);
                if (!textures.get(0).renameTo(new File(mods, "textures"))) throw new IOException("Cannot install textures");
            }
            // AtomicFile keeps the previous complete import active if commit is interrupted.
            android.util.AtomicFile pointer = new android.util.AtomicFile(new File(imports, id + ".active"));
            FileOutputStream stream = null;
            try {
                stream = pointer.startWrite();
                stream.write(stage.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                pointer.finishWrite(stream);
            } catch (Exception e) { if (stream != null) pointer.failWrite(stream); throw e; }
            committed = true;
            return id;
        } finally { if (!committed) remove(stage); }
    }
    static void remove(File f) {
        // Never follow the user-data symlink during failed import cleanup.
        if (f.isDirectory() && !java.nio.file.Files.isSymbolicLink(f.toPath())) {
            File[] files = f.listFiles(); if (files != null) for (File c : files) remove(c);
        }
        f.delete();
    }
    static File active(android.content.Context context, String id) throws IOException {
        File imports = new File(context.getFilesDir(), "imports");
        android.util.AtomicFile pointer = new android.util.AtomicFile(new File(imports, id + ".active"));
        try (InputStream in = pointer.openRead()) {
            byte[] bytes = new byte[128]; int n = in.read(bytes);
            if (n < 1 || in.read() != -1) throw new IOException("Invalid import pointer");
            String name = new String(bytes, 0, n, java.nio.charset.StandardCharsets.UTF_8);
            if (!name.matches("[0-9a-f-]{36}")) throw new IOException("Invalid import pointer");
            File version = child(imports, name);
            if (!new File(version, "game/game.3ds").isFile() || !arm64(new File(version, "code/title.so")))
                throw new IOException("Imported files are missing; import this game again");
            return version;
        }
    }
}
