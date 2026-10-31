package io.github.zakuro.android;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public final class SaveArchiveCheck {
    private static final String TITLE = "000400000012ABCD";
    private static int checks;
    private interface Action { void run() throws Exception; }
    private static void require(boolean value) { checks++; if (!value) throw new AssertionError("Check " + checks); }
    private static void fails(Action action) throws Exception {
        try { action.run(); } catch (IOException expected) { checks++; return; }
        throw new AssertionError("Expected IOException");
    }
    private static byte[] text(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static byte[] archive(String title, String... names) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("save-manifest.txt"));
            zip.write(text("zakuro-save-archive=1\ntitle=" + title + "\n")); zip.closeEntry();
            for (String name : names) {
                ZipEntry entry = new ZipEntry(name);
                byte[] data = name.endsWith("/") ? new byte[0] : text("new save");
                CRC32 crc = new CRC32(); crc.update(data);
                entry.setMethod(ZipEntry.STORED); entry.setSize(data.length); entry.setCrc(crc.getValue());
                zip.putNextEntry(entry); zip.write(data); zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
    private static void restore(File root, byte[] bytes) throws IOException {
        SaveArchive.restoreZip(root, TITLE, new ByteArrayInputStream(bytes));
    }
    private static void replace(byte[] bytes, String before, String after) {
        byte[] a = text(before), b = text(after);
        require(a.length == b.length);
        for (int i = 0; i <= bytes.length - a.length; i++) {
            boolean match = true;
            for (int j = 0; j < a.length; j++) if (bytes[i + j] != a[j]) match = false;
            if (match) { System.arraycopy(b, 0, bytes, i, b.length); i += b.length - 1; }
        }
    }
    private static Map<String, String> snapshot(Path path) throws IOException {
        Map<String, String> result = new TreeMap<>();
        if (!Files.exists(path)) return result;
        try (java.util.stream.Stream<Path> walk = Files.walk(path)) {
            for (Path p : (Iterable<Path>)walk::iterator)
                result.put(path.relativize(p).toString(), Files.isDirectory(p) ? "dir" : Base64.getEncoder().encodeToString(Files.readAllBytes(p)));
        }
        return result;
    }
    private static void delete(Path path) throws IOException {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                for (Path child : children) delete(child);
            }
        }
        Files.deleteIfExists(path);
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 1) {
            fails(() -> { try (SaveArchive.Guard ignored = SaveArchive.lock(new File(args[0]), TITLE)) { } });
            return;
        }
        Path temp = Files.createTempDirectory("save-archive-check-").toRealPath();
        try { run(temp); } finally { delete(temp); }
        System.out.println("SaveArchive: " + checks + " checks passed");
    }
    private static void run(Path root) throws Exception {
        File files = root.toFile();
        fails(() -> SaveArchive.lock(files, "../../invalid"));
        fails(() -> SaveArchive.lock(files, TITLE.toLowerCase(Locale.ROOT)));
        Path parent = root.resolve("savedata/" + TITLE), user = parent.resolve("user"), previous = parent.resolve("user.previous");
        try (SaveArchive.Guard guard = SaveArchive.lock(files, TITLE)) {
            fails(() -> SaveArchive.lock(files, TITLE));
            String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
            Process process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                    SaveArchiveCheck.class.getName(), root.toString()).inheritIO().start();
            require(process.waitFor() == 0);
            fails(() -> SaveArchive.exportZip(files, TITLE, new ByteArrayOutputStream()));
            Files.createDirectories(user.resolve("empty/nested"));
            Files.write(user.resolve("zero"), new byte[0]);
            fails(() -> SaveArchive.exportZip(files, TITLE, new ByteArrayOutputStream()));
            Files.write(user.resolve("slot.bin"), text("original save"));
            Map<String, String> original = snapshot(user);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            SaveArchive.exportZip(files, TITLE, output);
            byte[] exported = output.toByteArray();
            restore(files, archive(TITLE, "user/new.bin"));
            require(snapshot(previous).equals(original));
            require(Files.readAllBytes(user.resolve("new.bin")).length > 0);
            restore(files, exported);
            require(snapshot(user).equals(original));
            require(Files.exists(previous.resolve("new.bin")));

            List<byte[]> invalid = new ArrayList<>();
            invalid.add(archive("000400000012ABCE", "user/new.bin"));
            invalid.add(archive(TITLE, "user/../escape"));
            invalid.add(archive(TITLE, "/user/absolute"));
            invalid.add(archive(TITLE, "user/back\\slash"));
            invalid.add(archive(TITLE, "user/C:drive"));
            invalid.add(archive(TITLE, "user/empty/"));
            invalid.add(archive(TITLE, "user/a", "user/a/child"));
            invalid.add(archive(TITLE, "user/A", "user/a"));
            invalid.add(archive(TITLE, "user/" + String.join("/", Collections.nCopies(33, "d")) + "/file"));
            byte[] duplicate = archive(TITLE, "user/one", "user/two"); replace(duplicate, "user/two", "user/one"); invalid.add(duplicate);
            byte[] corrupt = archive(TITLE, "user/new.bin"); replace(corrupt, "new save", "bad save"); invalid.add(corrupt);
            byte[] symlink = archive(TITLE, "user/link");
            for (int i = 0; i < symlink.length - 46; i++) {
                if (symlink[i] == 0x50 && symlink[i+1] == 0x4b && symlink[i+2] == 1 && symlink[i+3] == 2) {
                    symlink[i+40] = (byte)0xff; symlink[i+41] = (byte)0xa1; // Unix mode 0120777, high half of external attributes.
                }
            }
            invalid.add(symlink);
            for (int length : new int[]{0, 10, exported.length / 2, exported.length - 1, exported.length - 22})
                invalid.add(Arrays.copyOf(exported, length));
            Map<String, String> before = snapshot(user), backup = snapshot(previous);
            for (int i = 0; i < invalid.size(); i++) {
                byte[] bad = invalid.get(i);
                try { fails(() -> restore(files, bad)); }
                catch (AssertionError e) { throw new AssertionError("Invalid archive " + i + " was accepted", e); }
                require(snapshot(user).equals(before)); require(snapshot(previous).equals(backup));
            }
            fails(() -> SaveArchive.restoreZip(files, TITLE, new InputStream() {
                public int read() throws IOException { throw new IOException("Injected read failure"); }
            }));
            require(snapshot(user).equals(before)); require(snapshot(previous).equals(backup));

            // Crash after old -> previous: recovery restores the original, leaving staged data alone.
            delete(previous); Files.move(user, previous);
            Path abandoned = Files.createDirectories(parent.resolve("save-stage-abandoned/user"));
            Files.write(abandoned.resolve("candidate"), text("uncommitted"));
            fails(() -> restore(files, corrupt));
            require(!Files.exists(user)); require(snapshot(previous).equals(before));
            SaveArchive.recover(files, TITLE);
            require(snapshot(user).equals(before)); require(Files.exists(abandoned.resolve("candidate")));
            SaveArchive.recover(files, TITLE); require(snapshot(user).equals(before));
            restore(files, exported);
            SaveArchive.recover(files, TITLE);
            require(snapshot(user).equals(before)); require(snapshot(previous).equals(before));

            // A blocked backup path must fail without touching the live save.
            delete(previous); Files.write(previous, text("do not remove"));
            fails(() -> restore(files, exported)); require(snapshot(user).equals(before));
            require(Arrays.equals(Files.readAllBytes(previous), text("do not remove"))); Files.delete(previous);
            testLinks(files, root, user, exported);
            try (DirectoryStream<Path> children = Files.newDirectoryStream(parent, "user.retired-*")) {
                require(!children.iterator().hasNext());
            }
        }
        try (SaveArchive.Guard again = SaveArchive.lock(files, TITLE)) { require(true); }
    }
    private static void testLinks(File files, Path root, Path user, byte[] exported) throws Exception {
        Path outside = Files.createDirectory(root.resolve("outside"));
        Files.write(outside.resolve("safe"), text("untouched"));
        Path link = user.resolve("link");
        try { Files.createSymbolicLink(link, outside); }
        catch (IOException | UnsupportedOperationException | SecurityException unsupported) {
            System.out.println("SKIP filesystem symlinks: " + unsupported.getMessage()); return;
        }
        fails(() -> SaveArchive.exportZip(files, TITLE, new ByteArrayOutputStream()));
        Files.delete(link);
        // Android's filesDir is reached through a system symlink; that must be accepted.
        Path aliased = Files.createSymbolicLink(root.resolve("alias"), root);
        try (SaveArchive.Guard ignored = SaveArchive.lock(aliased.toFile(), TITLE)) { require(false); }
        catch (IOException inUse) { require(inUse.getMessage().equals("Saves are in use")); }
        Files.delete(aliased);
        Path previous = user.resolveSibling("user.previous");
        Files.createSymbolicLink(previous, outside);
        fails(() -> restore(files, exported)); fails(() -> SaveArchive.recover(files, TITLE));
        require(Arrays.equals(Files.readAllBytes(outside.resolve("safe")), text("untouched")));
        Files.delete(previous);
    }
}
