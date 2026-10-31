package io.github.zakuro.android;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** In-game saves only. Callers hold Guard across recovery and every save/native operation. */
final class SaveArchive {
    static final int MAX_ENTRIES = 10000, MAX_DEPTH = 32;
    static final long MAX_BYTES = 1024L * 1024 * 1024, MAX_ZIP_BYTES = MAX_BYTES + 16 * 1024 * 1024;
    private static final String MANIFEST = "save-manifest.txt";
    private static final LinkOption[] NOFOLLOW = {LinkOption.NOFOLLOW_LINKS};

    static final class Guard implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;
        private Guard(FileChannel channel, FileLock lock) { this.channel = channel; this.lock = lock; }
        public void close() throws IOException { try { lock.release(); } finally { channel.close(); } }
    }

    static Guard lock(File filesDir, String titleId) throws IOException {
        Path parent = parent(filesDir, titleId);
        Path path = parent.resolve("save.lock");
        checkFile(path);
        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("Saves are in use");
            return new Guard(channel, lock);
        } catch (OverlappingFileLockException e) {
            channel.close(); throw new IOException("Saves are in use", e);
        } catch (IOException | RuntimeException e) { channel.close(); throw e; }
    }

    static void recover(File filesDir, String titleId) throws IOException {
        Path parent = parent(filesDir, titleId), user = parent.resolve("user"), previous = parent.resolve("user.previous");
        checkDirectory(user); checkDirectory(previous);
        if (!exists(user) && exists(previous)) Files.move(previous, user);
        // Unknown staging/retired directories may be the only surviving copy: never sweep them.
    }

    static void exportZip(File filesDir, String titleId, OutputStream out) throws IOException {
        recover(filesDir, titleId);
        Path user = parent(filesDir, titleId).resolve("user");
        List<Path> paths = new ArrayList<>();
        long[] totals = new long[2];
        collect(user, user, paths, totals);
        if (totals[1] == 0) throw new IOException("No nonempty save files");
        try (ZipOutputStream zip = new ZipOutputStream(new FilterOutputStream(out) {
            public void close() throws IOException { flush(); }
        })) {
            zip.putNextEntry(new ZipEntry(MANIFEST)); zip.write(manifest(titleId)); zip.closeEntry();
            byte[] buffer = new byte[32768];
            long bytes = 0;
            for (Path path : paths) {
                String name = "user/" + user.relativize(path).toString().replace(File.separatorChar, '/');
                if (Files.isDirectory(path, NOFOLLOW) && !name.endsWith("/")) name += "/";
                validateName(name);
                zip.putNextEntry(new ZipEntry(name));
                if (!name.endsWith("/")) {
                    checkFile(path);
                    try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                        int n;
                        while ((n = input.read(buffer)) != -1) {
                            bytes += n; if (bytes > MAX_BYTES) throw new IOException("Save data too large");
                            zip.write(buffer, 0, n);
                        }
                    }
                }
                zip.closeEntry();
            }
        }
    }

    static void restoreZip(File filesDir, String titleId, InputStream in) throws IOException {
        Path parent = parent(filesDir, titleId);
        Path work = Files.createTempDirectory(parent, "save-stage-");
        try {
            Path archive = work.resolve("archive.zip"), staged = work.resolve("user");
            try (FileOutputStream output = new FileOutputStream(archive.toFile())) {
                copy(in, output, MAX_ZIP_BYTES); output.getFD().sync();
            }
            List<CentralEntry> entries = centralDirectory(archive);
            Files.createDirectory(staged);
            extract(archive, staged, titleId, entries);
            // Validation finishes before recovery or any change to the live save/backup.
            recover(filesDir, titleId);
            Path user = parent.resolve("user"), previous = parent.resolve("user.previous");
            Path retired = null;
            boolean movedOld = false;
            try {
                if (exists(previous)) {
                    retired = parent.resolve("user.retired-" + UUID.randomUUID());
                    Files.move(previous, retired);
                }
                if (exists(user)) { Files.move(user, previous); movedOld = true; }
                Files.move(staged, user);
            } catch (IOException failure) {
                try {
                    if (movedOld && !exists(user)) Files.move(previous, user);
                    if (retired != null && !exists(previous)) Files.move(retired, previous);
                } catch (IOException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
            // Commit succeeded. A cleanup failure leaves an extra backup, not a failed restore.
            if (retired != null) cleanup(retired);
        } finally { cleanup(work); }
    }

    private static byte[] manifest(String title) {
        return ("zakuro-save-archive=1\ntitle=" + title + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static Path parent(File filesDir, String title) throws IOException {
        if (title == null || !title.matches("[0-9A-F]{16}")) throw new IOException("Invalid title ID");
        // Android's own filesDir goes through symlinks (/data/user/0 -> /data/data); only our folders must not.
        Path root = filesDir.toPath().toRealPath();
        Path saves = root.resolve("savedata"), parent = saves.resolve(title);
        for (Path dir : Arrays.asList(saves, parent)) {
            checkDirectory(dir);
            if (!exists(dir)) Files.createDirectory(dir);
        }
        return parent;
    }

    private static boolean exists(Path path) { return Files.exists(path, NOFOLLOW); }
    private static void checkDirectory(Path path) throws IOException {
        if (exists(path) && !Files.isDirectory(path, NOFOLLOW)) throw new IOException("Unsafe directory: " + path);
    }
    private static void checkFile(Path path) throws IOException {
        if (exists(path) && !Files.isRegularFile(path, NOFOLLOW)) throw new IOException("Unsafe file: " + path);
    }
    private static void collect(Path root, Path path, List<Path> paths, long[] totals) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Symlink in saves");
        if (!exists(path)) throw new IOException("No saves");
        String name = "user/" + root.relativize(path).toString().replace(File.separatorChar, '/');
        validateName(name);
        if (paths.size() >= MAX_ENTRIES - 1) throw new IOException("Too many save entries");
        paths.add(path);
        if (Files.isDirectory(path, NOFOLLOW)) {
            try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                for (Path child : children) collect(root, child, paths, totals);
            }
        } else {
            checkFile(path);
            long size = Files.size(path); totals[0] += size;
            if (totals[0] > MAX_BYTES) throw new IOException("Save data too large");
            if (size > 0) totals[1]++;
        }
    }

    private static void validateName(String name) throws IOException {
        if (name.equals(MANIFEST)) return;
        if (!name.startsWith("user/") || name.length() > 4096 || name.indexOf('\\') >= 0)
            throw new IOException("Unsafe ZIP path");
        String bare = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        String[] parts = bare.split("/", -1);
        if (parts.length > MAX_DEPTH + 1) throw new IOException("ZIP path too deep");
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" "))
                throw new IOException("Unsafe ZIP component");
            for (int i = 0; i < part.length(); i++)
                if (part.charAt(i) < 32 || ":*?\"<>|".indexOf(part.charAt(i)) >= 0)
                    throw new IOException("Unsafe ZIP character");
            if (part.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?"))
                throw new IOException("Reserved ZIP component");
        }
    }

    private static long copy(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buffer = new byte[32768]; long count = 0; int n;
        while ((n = in.read(buffer)) != -1) {
            count += n; if (count > limit) throw new IOException("Archive size limit exceeded");
            out.write(buffer, 0, n);
        }
        return count;
    }

    private static final class CentralEntry {
        String name; long size, crc, offset;
    }

    // ZipInputStream alone accepts missing central directories and hides Unix symlink attributes.
    private static List<CentralEntry> centralDirectory(Path archive) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(archive.toFile(), "r")) {
            long length = file.length(), end = -1;
            for (long p = length - 22; p >= Math.max(0, length - 65557); p--) {
                file.seek(p);
                if (u32(file) == 0x06054b50L) {
                    file.seek(p + 20);
                    if (p + 22 + u16(file) == length) { end = p; break; }
                }
            }
            if (end < 0) throw new IOException("Missing ZIP end record");
            file.seek(end + 4);
            if (u16(file) != 0 || u16(file) != 0) throw new IOException("Split ZIP unsupported");
            int count = u16(file);
            if (count != u16(file) || count == 0 || count > MAX_ENTRIES) throw new IOException("Invalid ZIP count");
            long size = u32(file), start = u32(file);
            if (start + size != end) throw new IOException("Invalid ZIP directory");
            file.seek(start);
            List<CentralEntry> entries = new ArrayList<>(); Set<String> names = new HashSet<>();
            long bytes = 0;
            for (int i = 0; i < count; i++) {
                if (u32(file) != 0x02014b50L) throw new IOException("Invalid ZIP entry");
                u16(file); u16(file);
                int flags = u16(file), method = u16(file);
                if ((flags & ~0x808) != 0 || (method != 0 && method != 8)) throw new IOException("Unsupported ZIP encoding");
                u32(file);
                CentralEntry entry = new CentralEntry(); entry.crc = u32(file);
                long compressed = u32(file); entry.size = u32(file);
                int nameLength = u16(file), extra = u16(file), comment = u16(file);
                if (u16(file) != 0) throw new IOException("Split ZIP entry");
                u16(file); long attributes = u32(file); entry.offset = u32(file);
                int type = (int)(attributes >>> 16) & 0170000;
                if (type != 0 && type != 0100000 && type != 0040000) throw new IOException("Non-regular ZIP entry");
                if (nameLength == 0 || nameLength > 4096 || compressed > MAX_ZIP_BYTES || entry.size > MAX_BYTES
                        || entry.offset >= start || (i == 0 && entry.offset != 0)
                        || (i > 0 && entry.offset <= entries.get(i - 1).offset)) throw new IOException("Invalid ZIP bounds");
                byte[] name = new byte[nameLength]; file.readFully(name);
                entry.name = new String(name, StandardCharsets.UTF_8);
                if (!Arrays.equals(name, entry.name.getBytes(StandardCharsets.UTF_8))) throw new IOException("Invalid UTF-8");
                validateName(entry.name);
                boolean directory = entry.name.endsWith("/");
                if ((type == 0040000 && !directory) || (type == 0100000 && directory)
                        || (directory && entry.size != 0)) throw new IOException("Invalid ZIP directory entry");
                String key = directory ? entry.name.substring(0, entry.name.length() - 1) : entry.name;
                if (!names.add(key.toLowerCase(Locale.ROOT))) throw new IOException("Duplicate ZIP path");
                bytes += entry.size;
                if (bytes > MAX_BYTES || (entry.name.equals(MANIFEST) && entry.size > 128)) throw new IOException("ZIP too large");
                file.seek(file.getFilePointer() + extra + comment);
                if (file.getFilePointer() > end) throw new IOException("Truncated ZIP directory");
                entries.add(entry);
            }
            if (file.getFilePointer() != end) throw new IOException("Invalid ZIP directory size");
            return entries;
        }
    }
    private static int u16(RandomAccessFile f) throws IOException { return f.readUnsignedByte() | (f.readUnsignedByte() << 8); }
    private static long u32(RandomAccessFile f) throws IOException { return u16(f) | ((long)u16(f) << 16); }

    private static void extract(Path archive, Path staged, String title, List<CentralEntry> entries) throws IOException {
        boolean manifestSeen = false, meaningful = false;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            byte[] buffer = new byte[32768];
            for (CentralEntry expected : entries) {
                ZipEntry entry = zip.getNextEntry();
                if (entry == null || !entry.getName().equals(expected.name)) throw new IOException("ZIP headers disagree");
                boolean isManifest = expected.name.equals(MANIFEST);
                ByteArrayOutputStream metadata = isManifest ? new ByteArrayOutputStream() : null;
                FileOutputStream output = null;
                if (!isManifest) {
                    String relative = expected.name.substring(5);
                    Path target = staged.resolve(relative).normalize();
                    if (!target.startsWith(staged)) throw new IOException("Unsafe ZIP destination");
                    if (entry.isDirectory()) Files.createDirectories(target);
                    else {
                        Files.createDirectories(target.getParent());
                        Files.createFile(target);
                        output = new FileOutputStream(target.toFile());
                    }
                }
                CRC32 crc = new CRC32(); long bytes = 0;
                try {
                    int n;
                    while ((n = zip.read(buffer)) != -1) {
                        bytes += n;
                        if (bytes > expected.size) throw new IOException("ZIP size mismatch");
                        crc.update(buffer, 0, n);
                        if (metadata != null) metadata.write(buffer, 0, n);
                        if (output != null) output.write(buffer, 0, n);
                    }
                    if (bytes != expected.size || crc.getValue() != expected.crc) throw new IOException("ZIP checksum mismatch");
                    if (output != null) { output.getFD().sync(); if (bytes > 0) meaningful = true; }
                } finally { if (output != null) output.close(); }
                if (isManifest) {
                    if (!Arrays.equals(metadata.toByteArray(), manifest(title))) throw new IOException("Wrong title or archive version");
                    manifestSeen = true;
                }
                zip.closeEntry();
            }
            if (zip.getNextEntry() != null || !manifestSeen || !meaningful) throw new IOException("Incomplete save archive");
        }
    }

    private static void cleanup(Path path) {
        try { deleteTree(path); } catch (IOException ignored) { /* Retain remnants if cleanup cannot finish. */ }
    }
    private static void deleteTree(Path path) throws IOException {
        if (Files.isDirectory(path, NOFOLLOW)) {
            try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                for (Path child : children) deleteTree(child);
            }
        }
        Files.deleteIfExists(path);
    }
}
