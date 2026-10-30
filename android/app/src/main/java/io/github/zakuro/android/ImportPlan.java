package io.github.zakuro.android;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.CRC32;

/** Read-only source inspection. No destination or staging directory exists here. */
final class ImportPlan {
    static final class Entry {
        final String id, name;
        final boolean directory;
        final long size;
        Entry(String id, String name, boolean directory, long size) {
            this.id = id; this.name = name; this.directory = directory; this.size = size;
        }
    }
    interface Source {
        List<Entry> children(String id) throws IOException;
        SeekableByteChannel open(String id) throws IOException;
    }
    static final class Item {
        final Entry source;
        final String destination;
        private final byte[] header;
        Item(Entry source, String destination) { this(source, destination, null); }
        private Item(Entry source, String destination, byte[] header) {
            this.source = source; this.destination = destination; this.header = header == null ? null : header.clone();
        }
        void checkHeader(SeekableByteChannel file) throws IOException {
            if (header != null && !Arrays.equals(header, read(file, 0, header.length)))
                throw new IOException("Source header changed: " + source.name);
        }
    }
    final String titleId;
    final Entry rom, code;
    final List<Item> files;
    final long bytes;
    private ImportPlan(String titleId, Entry rom, Entry code, List<Item> files, long bytes) {
        this.titleId = titleId; this.rom = rom; this.code = code;
        this.files = Collections.unmodifiableList(new ArrayList<>(files)); this.bytes = bytes;
    }
    void checkDestination(File stage) throws IOException {
        String root = stage.getCanonicalPath() + File.separator;
        for (Item item : files) {
            if ((root + item.destination).getBytes(StandardCharsets.UTF_8).length >= 4096)
                throw new IOException("Import path is too long: " + item.source.name);
        }
    }
    static void safeName(String name) throws IOException {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0
                || name.getBytes(StandardCharsets.UTF_8).length > 255)
            throw new IOException("Unsafe or unsupported document name");
    }
    private static final class Scan {
        final List<Entry> roms = new ArrayList<>(), libraries = new ArrayList<>();
        final List<Item> assets = new ArrayList<>();
        int documents, textures, sysdata;
        void walk(Source source, String id, String kind, String relative, Set<String> ancestors, int depth) throws IOException {
            if (id == null || id.isEmpty() || depth > 64 || !ancestors.add(id)) throw new IOException("Folder nesting or cycle limit");
            try {
                List<Entry> entries = source.children(id);
                if (entries == null) throw new IOException("Cannot read selected folder");
                Set<String> names = new HashSet<>();
                for (Entry entry : entries) {
                    if (++documents > 200000) throw new IOException("Too many documents");
                    safeName(entry.name);
                    if (entry.id == null || entry.id.isEmpty()) throw new IOException("Missing document ID");
                    String lower = entry.name.toLowerCase(Locale.ROOT);
                    if (!names.add(lower)) throw new IOException("Duplicate document name: " + entry.name);
                    String path = relative + entry.name;
                    if (entry.directory) {
                        if (kind.isEmpty() && lower.equals("textures")) {
                            if (++textures > 1) throw new IOException("Multiple textures folders. Select one game's build folder.");
                            walk(source, entry.id, "textures", "", ancestors, depth + 1);
                        } else if (kind.isEmpty() && lower.equals("sysdata")) {
                            if (++sysdata > 1) throw new IOException("Multiple sysdata folders. Select one game's build folder.");
                            walk(source, entry.id, "sysdata", "", ancestors, depth + 1);
                        } else walk(source, entry.id, kind, kind.isEmpty() ? "" : path + "/", ancestors, depth + 1);
                    } else if (kind.equals("textures")) {
                        if (lower.endsWith(".png") || lower.equals("pack.json")) assets.add(new Item(entry, "textures/" + path));
                    } else if (kind.equals("sysdata")) {
                        if (relative.isEmpty() && (lower.equals("shared_font.bin") || lower.equals("cfl_res.dat")))
                            assets.add(new Item(entry, "sysdata/" + (lower.equals("cfl_res.dat") ? "CFL_Res.dat" : "shared_font.bin")));
                    } else if (lower.endsWith(".3ds") || lower.endsWith(".cci") || lower.endsWith(".cxi")) {
                        roms.add(entry);
                        if (roms.size() > 1) throw new IOException("Multiple games found. Select just one game's build folder.");
                    } else if (lower.equals("title.so")) libraries.add(entry);
                }
            } finally { ancestors.remove(id); }
        }
    }
    static ImportPlan inspect(Source source, String rootId) throws IOException {
        Scan scan = new Scan(); scan.walk(source, rootId, "", "", new HashSet<>(), 0);
        if (scan.roms.size() != 1) throw new IOException("No game found. Choose a folder with one decrypted .3ds, .cci or .cxi game.");
        if (scan.libraries.isEmpty()) throw new IOException("Missing Android ARM64 title.so. Choose the built game's folder.");
        int pngs = 0;
        for (Item item : scan.assets) if (item.destination.toLowerCase(Locale.ROOT).endsWith(".png")) pngs++;
        if (scan.textures != 0 && pngs == 0) throw new IOException("The textures folder has no PNG images. Extract the texture pack first.");
        Entry rom = scan.roms.get(0), code = null;
        String title;
        try (SeekableByteChannel file = source.open(rom.id)) {
            rom = sized(rom, file); title = title(file);
        }
        for (Entry candidate : scan.libraries) {
            try (SeekableByteChannel file = source.open(candidate.id)) {
                Entry sized = sized(candidate, file);
                if (!arm64(file)) continue;
                if (code != null) throw new IOException("Multiple ARM64 title.so files. Select one Android build.");
                code = sized;
            }
        }
        if (code == null) throw new IOException("No valid ARM64 title.so found. A desktop build cannot be used on Android.");
        List<Item> files = new ArrayList<>();
        files.add(new Item(rom, "game/game.3ds")); files.add(new Item(code, "code/title.so"));
        for (Item asset : scan.assets) {
            try (SeekableByteChannel file = source.open(asset.source.id)) {
                Entry entry = sized(asset.source, file);
                if (entry.size == 0) throw new IOException("Empty import file: " + entry.name);
                byte[] header = null;
                if (asset.destination.toLowerCase(Locale.ROOT).endsWith(".png")) header = png(file, entry.name);
                else read(file, 0, 1);
                String destination = asset.destination.startsWith("textures/")
                        ? "data/mods/" + title + "/" + asset.destination : "data/" + asset.destination;
                files.add(new Item(entry, destination, header));
            }
        }
        long total = 0;
        try { for (Item file : files) total = Math.addExact(total, file.source.size); }
        catch (ArithmeticException e) { throw new IOException("Import is too large", e); }
        return new ImportPlan(title, rom, code, files, total);
    }
    private static Entry sized(Entry entry, SeekableByteChannel file) throws IOException {
        long size = file.size();
        if (size < 0 || (entry.size >= 0 && entry.size != size)) throw new IOException("Source file changed: " + entry.name);
        return new Entry(entry.id, entry.name, false, size);
    }
    static byte[] read(SeekableByteChannel file, long offset, int count) throws IOException {
        if (offset < 0 || offset > file.size() || count > file.size() - offset) throw new IOException("Truncated source file");
        ByteBuffer buffer = ByteBuffer.allocate(count); file.position(offset);
        while (buffer.hasRemaining()) {
            int n = file.read(buffer);
            if (n < 0) throw new IOException("Truncated source file");
            if (n == 0) throw new IOException("Cannot read source file");
        }
        return buffer.array();
    }
    private static long little(byte[] bytes, int offset, int count) {
        long value = 0; for (int i = 0; i < count; i++) value |= (bytes[offset + i] & 255L) << (8 * i); return value;
    }
    private static boolean fits(long offset, long size, long limit) { return offset >= 0 && size >= 0 && offset <= limit && size <= limit - offset; }
    static String title(SeekableByteChannel file) throws IOException {
        byte[] header = read(file, 0, 512);
        long limit = file.size();
        if (little(header, 0x100, 4) == 0x4453434eL) {
            long base = little(header, 0x120, 4) * 512, size = little(header, 0x124, 4) * 512;
            if (base < 512 || size < 512 || !fits(base, size, limit)) throw new IOException("Invalid or truncated cartridge partition");
            header = read(file, base, 512); limit = size;
        }
        if (little(header, 0x100, 4) != 0x4843434eL) throw new IOException("Expected an original decrypted NCSD/NCCH game");
        long content = little(header, 0x104, 4) * 512;
        if (content < 512 || content > limit) throw new IOException("Game file is truncated or has an invalid size");
        if ((header[0x18f] & 4) == 0) throw new IOException("Game is encrypted. Decrypt your dump before adding it.");
        for (int offset : new int[]{0x1a0, 0x1b0}) {
            long start = little(header, offset, 4) * 512, size = little(header, offset + 4, 4) * 512;
            if (size != 0 && (start < 512 || !fits(start, size, content))) throw new IOException("Invalid game filesystem bounds");
        }
        long id = little(header, 0x118, 8);
        if (id == 0) throw new IOException("Game has no title ID");
        return String.format(Locale.ROOT, "%016X", id);
    }
    static boolean arm64(SeekableByteChannel file) throws IOException {
        if (file.size() < 64) return false;
        byte[] h = read(file, 0, 64);
        return h[0] == 127 && h[1] == 'E' && h[2] == 'L' && h[3] == 'F'
                && h[4] == 2 && h[5] == 1 && h[16] == 3 && h[17] == 0 && (h[18] & 255) == 183 && h[19] == 0;
    }
    private static byte[] png(SeekableByteChannel file, String name) throws IOException {
        byte[] header = read(file, 0, 33), signature = {(byte)137, 80, 78, 71, 13, 10, 26, 10};
        for (int i = 0; i < signature.length; i++) if (header[i] != signature[i]) throw new IOException("Invalid PNG: " + name);
        ByteBuffer bytes = ByteBuffer.wrap(header);
        CRC32 crc = new CRC32(); crc.update(header, 12, 17);
        if (bytes.getInt(8) != 13 || bytes.getInt(12) != 0x49484452 || bytes.getInt(16) <= 0 || bytes.getInt(20) <= 0
                || Integer.toUnsignedLong(bytes.getInt(29)) != crc.getValue()) throw new IOException("Invalid PNG header: " + name);
        return header;
    }
}
