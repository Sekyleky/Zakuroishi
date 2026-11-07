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

    static final String IN_USE = "Saves are in use. Exit the game to the library (not just Home), then try again.";
    // POSIX drops every lock a process holds on a file when any handle to it closes, so never open a second one.
    private static final Set<Path> HELD = new HashSet<>();

    static final class Guard implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;
        private final Path path;
        private Guard(FileChannel channel, FileLock lock, Path path) { this.channel = channel; this.lock = lock; this.path = path; }
        public void close() throws IOException {
            try { lock.release(); } finally { channel.close(); synchronized (HELD) { HELD.remove(path); } }
        }
    }

    static Guard lock(File filesDir, String titleId) throws IOException {
        Path parent = parent(filesDir, titleId);
        Path path = parent.resolve("save.lock");
        checkFile(path);
        synchronized (HELD) { if (!HELD.add(path)) throw new IOException(IN_USE); }
        FileChannel channel = null;
        try {
            channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException(IN_USE);
            return new Guard(channel, lock, path);
        } catch (IOException | RuntimeException e) {
            if (channel != null) channel.close();
            synchronized (HELD) { HELD.remove(path); }
            if (e instanceof OverlappingFileLockException) throw new IOException(IN_USE, e);
            throw e;
        }
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

    /** A file or folder of a game's in-game saves, by its path below user/; refuses paths that leave it or pass a link. */
    static Path saveFile(File filesDir, String titleId, String relative) throws IOException {
        Path user = parent(filesDir, titleId).resolve("user");
        checkDirectory(user);
        return below(user, relative);
    }

    private static Path below(Path root, String relative) throws IOException {
        if (relative.isEmpty()) return root;
        validateName("user/" + relative);
        Path path = root;
        for (String part : relative.split("/")) {
            path = path.resolve(part);
            if (Files.isSymbolicLink(path)) throw new IOException("Symlink in saves");
        }
        return path;
    }

    private static final int KEEP_BACKUPS = 3;
    private static final String BACKUP_PREFIX = "user.backup-";

    /** The game's backup folders, newest first. Only exact "user.backup-" + 15 digits names count; nothing else there is ours to touch. */
    static List<Path> backups(File filesDir, String titleId) throws IOException {
        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(parent(filesDir, titleId), BACKUP_PREFIX + "*")) {
            for (Path child : children)
                if (child.getFileName().toString().matches("user\\.backup-\\d{15}") && Files.isDirectory(child, NOFOLLOW)) found.add(child);
        }
        Collections.sort(found, Collections.reverseOrder());
        return found;
    }

    /** A file or folder inside a backup, by "NAME/path" (NAME = the 15 digits after "user.backup-"); same refusals as saveFile. */
    static Path backupFile(File filesDir, String titleId, String relative) throws IOException {
        int slash = relative.indexOf('/');
        String name = slash < 0 ? relative : relative.substring(0, slash);
        if (!name.matches("\\d{15}")) throw new IOException("Not a backup");
        Path root = parent(filesDir, titleId).resolve(BACKUP_PREFIX + name);
        if (!Files.isDirectory(root, NOFOLLOW)) throw new IOException("No such backup");
        return below(root, slash < 0 ? "" : relative.substring(slash + 1));
    }

    /**
     * Copies the in-game saves to a new user.backup-<millis> folder before an external editor changes them, keeping the
     * newest 3. Skipped when user is byte-identical to the newest one, so reopening can't rotate good copies out.
     * user.previous is left alone: recover() and restoreZip() own it.
     */
    static void backup(File filesDir, String titleId) throws IOException {
        Path parent = parent(filesDir, titleId), user = parent.resolve("user");
        List<Path> paths = new ArrayList<>();
        collect(user, user, paths, new long[2]);
        List<Path> old = backups(filesDir, titleId);
        if (!old.isEmpty() && sameTree(user, paths, old.get(0))) return;
        Path staged = Files.createTempDirectory(parent, "save-backup-");
        try {
            for (Path path : paths) {
                Path target = staged.resolve(user.relativize(path).toString());
                if (Files.isDirectory(path, NOFOLLOW)) Files.createDirectories(target);
                else Files.copy(path, target, LinkOption.NOFOLLOW_LINKS);
            }
            // Names sort by time; never go below the newest, even if the clock was set back.
            long stamp = System.currentTimeMillis();
            if (!old.isEmpty()) stamp = Math.max(stamp, Long.parseLong(old.get(0).getFileName().toString().substring(BACKUP_PREFIX.length())) + 1);
            Files.move(staged, parent.resolve(String.format(Locale.ROOT, BACKUP_PREFIX + "%015d", stamp)));
        } finally { cleanup(staged); }
        List<Path> all = backups(filesDir, titleId);
        for (int i = KEEP_BACKUPS; i < all.size(); i++) cleanup(all.get(i));
    }

    private static boolean sameTree(Path user, List<Path> paths, Path other) throws IOException {
        List<Path> theirs = new ArrayList<>();
        try { collect(other, other, theirs, new long[2]); } catch (IOException unreadable) { return false; }
        if (theirs.size() != paths.size()) return false;
        Map<String, Path> byName = new HashMap<>();
        for (Path path : theirs) byName.put(other.relativize(path).toString(), path);
        for (Path path : paths) {
            Path match = byName.get(user.relativize(path).toString());
            if (match == null || Files.isDirectory(path, NOFOLLOW) != Files.isDirectory(match, NOFOLLOW)) return false;
            if (!Files.isDirectory(path, NOFOLLOW) && !sameBytes(path, match)) return false;
        }
        return true;
    }

    private static boolean sameBytes(Path a, Path b) throws IOException {
        if (Files.size(a) != Files.size(b)) return false;
        try (InputStream x = Files.newInputStream(a, LinkOption.NOFOLLOW_LINKS); InputStream y = Files.newInputStream(b, LinkOption.NOFOLLOW_LINKS)) {
            byte[] p = new byte[32768], q = new byte[32768];
            int n;
            while ((n = x.read(p)) != -1) {
                int got = 0;
                while (got < n) { int r = y.read(q, got, n - got); if (r < 0) return false; got += r; }
                if (!Arrays.equals(Arrays.copyOf(p, n), Arrays.copyOf(q, n))) return false;
            }
            return true;
        }
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
            throw new IOException("Not a Zakuro save path: " + name + ". The ZIP must contain save-manifest.txt and the user folder from an exported backup.");
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
        String raw, name; long size, crc, offset; int type; boolean skip;
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
                entry.raw = new String(name, StandardCharsets.UTF_8);
                if (!Arrays.equals(name, entry.raw.getBytes(StandardCharsets.UTF_8))) throw new IOException("Invalid UTF-8");
                // Windows PowerShell's Compress-Archive writes backslash separators.
                entry.name = entry.raw.replace('\\', '/'); entry.type = type;
                bytes += entry.size;
                if (bytes > MAX_BYTES) throw new IOException("ZIP too large");
                file.seek(file.getFilePointer() + extra + comment);
                if (file.getFilePointer() > end) throw new IOException("Truncated ZIP directory");
                entries.add(entry);
            }
            if (file.getFilePointer() != end) throw new IOException("Invalid ZIP directory size");
            // Re-zipping the extracted folder itself adds one wrapper folder; strip it.
            String wrapper = "";
            boolean topManifest = false;
            for (CentralEntry entry : entries) if (entry.name.equals(MANIFEST)) topManifest = true;
            if (!topManifest) for (CentralEntry entry : entries)
                if (entry.name.endsWith("/" + MANIFEST) && entry.name.indexOf('/') == entry.name.length() - MANIFEST.length() - 1)
                    wrapper = entry.name.substring(0, entry.name.length() - MANIFEST.length());
            for (CentralEntry entry : entries) {
                String base = entry.name.substring(entry.name.lastIndexOf('/', entry.name.length() - 2) + 1);
                if (entry.name.startsWith("__MACOSX/") || base.equals(".DS_Store") || base.startsWith("._")
                        || entry.name.equals(wrapper) || !entry.name.startsWith(wrapper)) { entry.skip = true; continue; }
                entry.name = entry.name.substring(wrapper.length());
                validateName(entry.name);
                boolean directory = entry.name.endsWith("/");
                if ((entry.type == 0040000 && !directory) || (entry.type == 0100000 && directory)
                        || (directory && entry.size != 0)) throw new IOException("Invalid ZIP directory entry: " + entry.raw);
                String key = directory ? entry.name.substring(0, entry.name.length() - 1) : entry.name;
                if (!names.add(key.toLowerCase(Locale.ROOT))) throw new IOException("Duplicate ZIP path: " + entry.raw);
                if (entry.name.equals(MANIFEST) && entry.size > 128) throw new IOException("ZIP too large");
            }
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
                if (entry == null || !entry.getName().equals(expected.raw)) throw new IOException("ZIP headers disagree");
                if (expected.skip) { zip.closeEntry(); continue; }
                boolean isManifest = expected.name.equals(MANIFEST);
                ByteArrayOutputStream metadata = isManifest ? new ByteArrayOutputStream() : null;
                FileOutputStream output = null;
                if (!isManifest) {
                    String relative = expected.name.substring(5);
                    Path target = staged.resolve(relative).normalize();
                    if (!target.startsWith(staged)) throw new IOException("Unsafe ZIP destination");
                    if (expected.name.endsWith("/")) Files.createDirectories(target);
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
                    // Tolerate Notepad's CRLF if someone opened the manifest.
                    String found = new String(metadata.toByteArray(), StandardCharsets.UTF_8).replace("\r", "");
                    if (!found.equals(new String(manifest(title), StandardCharsets.UTF_8)))
                        throw new IOException("This backup belongs to a different game (or a newer app version). Export from this game first and put your save file into that ZIP.");
                    manifestSeen = true;
                }
                zip.closeEntry();
            }
            if (zip.getNextEntry() != null) throw new IOException("Incomplete save archive");
            if (!manifestSeen) throw new IOException("Not a Zakuro save backup: save-manifest.txt is missing. Start from a ZIP made with Export ZIP.");
            if (!meaningful) throw new IOException("The backup contains no save files.");
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
