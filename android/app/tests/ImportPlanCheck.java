package io.github.zakuro.android;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.util.*;

/** Host checks against the real preflight planner; no Android framework or output files. */
public final class ImportPlanCheck {
    private static final String TITLE = "00040000001B0000";
    private static final long FOUR_GIB = 4L * 1024 * 1024 * 1024;
    private static final byte[] PNG = NativeFixture.png();
    private interface Attempt { void run() throws Exception; }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void rejects(Attempt attempt, String label) throws Exception {
        try { attempt.run(); }
        catch (IOException expected) { return; }
        throw new AssertionError(label + " was accepted");
    }
    private static void u32(byte[] bytes, int at, long value) {
        for (int i = 0; i < 4; i++) bytes[at + i] = (byte) (value >>> (8 * i));
    }
    private static byte[] romHeader(long length) {
        return NativeFixture.rom(length, Long.parseUnsignedLong(TITLE, 16));
    }
    private static byte[] elf(int machine) {
        byte[] bytes = new byte[64];
        bytes[0] = 127; bytes[1] = 'E'; bytes[2] = 'L'; bytes[3] = 'F';
        bytes[4] = 2; bytes[5] = 1; bytes[6] = 1; bytes[16] = 3;
        bytes[18] = (byte) machine; bytes[19] = (byte) (machine >>> 8);
        bytes[20] = 1; bytes[52] = 64; bytes[54] = 56; bytes[58] = 64;
        return bytes;
    }
    private static void pngCrc(byte[] bytes) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32(); crc.update(bytes, 12, 17);
        ByteBuffer.wrap(bytes).putInt(29, (int) crc.getValue());
    }
    private static void reportedSize(Tree tree, String parent, String id, long size) {
        List<ImportPlan.Entry> entries = tree.folders.get(parent);
        for (int i = 0; i < entries.size(); i++) {
            ImportPlan.Entry entry = entries.get(i);
            if (entry.id.equals(id)) {
                entries.set(i, new ImportPlan.Entry(entry.id, entry.name, entry.directory, size)); return;
            }
        }
        throw new AssertionError("Missing fixture entry " + id);
    }
    private static final class Blob {
        byte[] prefix;
        long length;
        long read;
        long readLimit = 65536;
        int maxRequest;
        int opens;
        boolean readable = true;
        Blob(byte[] prefix, long length) { this.prefix = prefix; this.length = length; }
    }
    /** Sparse readable channel: a four-GB ROM occupies only a small executable prefix. */
    private static final class SparseChannel implements SeekableByteChannel {
        final Blob blob;
        long position;
        boolean open = true;
        SparseChannel(Blob blob) { this.blob = blob; }
        @Override public int read(ByteBuffer target) throws IOException {
            if (!open) throw new IOException("closed");
            if (!target.hasRemaining()) return 0;
            if (position >= blob.length) return -1;
            int count = (int) Math.min(target.remaining(), blob.length - position);
            blob.maxRequest = Math.max(blob.maxRequest, target.remaining());
            if (blob.read + count > blob.readLimit) throw new IOException("Preflight attempted to read a whole payload");
            for (int i = 0; i < count; i++, position++)
                target.put(position < blob.prefix.length ? blob.prefix[(int) position] : (byte) 0);
            blob.read += count;
            return count;
        }
        @Override public int write(ByteBuffer source) { throw new AssertionError("Preflight wrote to source"); }
        @Override public SeekableByteChannel truncate(long size) { throw new AssertionError("Preflight truncated source"); }
        @Override public long position() { return position; }
        @Override public SeekableByteChannel position(long value) {
            if (value < 0) throw new IllegalArgumentException();
            position = value; return this;
        }
        @Override public long size() { return blob.length; }
        @Override public boolean isOpen() { return open; }
        @Override public void close() { open = false; }
    }
    private static final class Tree implements ImportPlan.Source {
        final Map<String, List<ImportPlan.Entry>> folders = new LinkedHashMap<>();
        final Map<String, Blob> blobs = new LinkedHashMap<>();
        final Set<String> listed = new HashSet<>();
        int opens;
        Tree() { folders.put("root", new ArrayList<>()); }
        void directory(String parent, String id, String name) {
            folders.get(parent).add(new ImportPlan.Entry(id, name, true, 0));
            folders.put(id, new ArrayList<>());
        }
        void file(String parent, String id, String name, byte[] bytes) {
            file(parent, id, name, bytes, bytes.length);
        }
        void file(String parent, String id, String name, byte[] bytes, long size) {
            folders.get(parent).add(new ImportPlan.Entry(id, name, false, size));
            blobs.put(id, new Blob(bytes, size));
        }
        @Override public List<ImportPlan.Entry> children(String id) throws IOException {
            listed.add(id);
            List<ImportPlan.Entry> children = folders.get(id);
            if (children == null) throw new IOException("Unknown folder " + id);
            return children;
        }
        @Override public SeekableByteChannel open(String id) throws IOException {
            opens++;
            Blob blob = blobs.get(id);
            if (blob == null) throw new IOException("Unknown document " + id);
            blob.opens++;
            if (!blob.readable) throw new IOException("Unreadable " + id);
            return new SparseChannel(blob);
        }
    }
    private static Tree valid() {
        Tree tree = new Tree();
        tree.directory("root", "title-folder", TITLE);
        tree.file("title-folder", "rom", "game.3ds", romHeader(FOUR_GIB), FOUR_GIB);
        tree.directory("title-folder", "arm", "arm64");
        tree.file("arm", "code", "title.so", NativeFixture.elf(4, false));
        tree.directory("title-folder", "x86", "x86_64");
        tree.file("x86", "x86-code", "title.so", elf(62));
        tree.directory("title-folder", "textures", "textures");
        tree.file("textures", "pack", "pack.json", new byte[]{'{', '}'});
        tree.directory("textures", "texture-sub", "battle");
        tree.file("texture-sub", "texture", "tex.png", PNG.clone());
        tree.file("textures", "texture-junk", "backup.zip", new byte[]{1});
        tree.blobs.get("texture-junk").readable = false;
        tree.directory("title-folder", "sysdata", "sysdata");
        tree.file("sysdata", "font", "shared_font.bin", new byte[]{1, 2});
        tree.file("sysdata", "mii", "CFL_Res.dat", new byte[]{3, 4});
        tree.file("sysdata", "sys-junk", "unrelated.bin", new byte[]{1});
        tree.blobs.get("sys-junk").readable = false;
        tree.file("root", "junk", "backup.zip", new byte[]{1});
        tree.blobs.get("junk").readable = false;
        tree.file("root", "outside-png", "outside.png", new byte[]{1});
        tree.blobs.get("outside-png").readable = false;
        return tree;
    }
    private static void structuralReject(Tree tree, String label) throws Exception {
        rejects(() -> ImportPlan.inspect(tree, "root"), label);
        check(tree.opens == 0, label + " opened payload before validating the tree");
    }
    private static void validPlan() throws Exception {
        Tree tree = valid();
        ImportPlan plan = ImportPlan.inspect(tree, "root");
        check(TITLE.equals(plan.titleId), "Wrong title ID");
        check("rom".equals(plan.rom.id) && "code".equals(plan.code.id), "Wrong ROM/code selected");
        Map<String, String> selected = new TreeMap<>();
        long expectedBytes = 0;
        for (ImportPlan.Item item : plan.files) {
            check(selected.put(item.source.id, item.destination) == null, "Duplicate selected item");
            expectedBytes += item.source.size;
        }
        Map<String, String> expected = new TreeMap<>();
        expected.put("rom", "game/game.3ds"); expected.put("code", "code/title.so");
        expected.put("pack", "data/mods/" + TITLE + "/textures/pack.json");
        expected.put("texture", "data/mods/" + TITLE + "/textures/battle/tex.png");
        expected.put("font", "data/sysdata/shared_font.bin"); expected.put("mii", "data/sysdata/CFL_Res.dat");
        check(selected.equals(expected), "Wrong selected items: " + selected);
        check(plan.bytes == expectedBytes && plan.bytes > Integer.MAX_VALUE, "Incorrect long byte total");
        check(tree.blobs.get("rom").read <= 8192, "ROM preflight read beyond executable metadata");
        for (String id : new String[]{"junk", "outside-png", "texture-junk", "sys-junk"})
            check(tree.blobs.get(id).opens == 0, "Ignored junk opened: " + id);
        List<ImportPlan.Item> snapshot = new ArrayList<>(plan.files);
        tree.folders.get("title-folder").clear(); tree.folders.get("textures").clear();
        check(plan.files.equals(snapshot), "Plan changed with source metadata");
        try { plan.files.clear(); throw new AssertionError("Plan file list is mutable"); }
        catch (UnsupportedOperationException expectedImmutable) { }
    }
    private static void badStructure() throws Exception {
        Tree tree = valid(); tree.file("root", "other-rom", "other.cci", romHeader(512));
        structuralReject(tree, "duplicate ROMs");
        tree = valid(); tree.folders.get("arm").clear(); tree.folders.get("x86").clear();
        structuralReject(tree, "missing library");
        tree = valid(); tree.directory("root", "more-textures", "textures");
        structuralReject(tree, "multiple textures folders");
        tree = valid(); tree.directory("root", "more-sysdata", "sysdata");
        structuralReject(tree, "multiple sysdata folders");
        for (String name : new String[]{"..", ".", "../escape", "a/b", "a\\b", "", "a\0b"}) {
            tree = valid(); tree.file("root", "unsafe", name, new byte[0]);
            structuralReject(tree, "unsafe name " + name);
        }
        tree = valid(); tree.file("root", "duplicate1", "same.txt", new byte[0]);
        tree.file("root", "duplicate2", "SAME.txt", new byte[0]);
        structuralReject(tree, "case duplicate names");
        tree = valid(); tree.folders.get("title-folder").add(new ImportPlan.Entry("root", "loop", true, 0));
        structuralReject(tree, "directory cycle");
        tree = valid(); tree.file("root", "too-long", "é".repeat(128), new byte[0]);
        structuralReject(tree, "UTF-8 filename longer than 255 bytes");
        tree = valid(); String parent = "root";
        for (int depth = 0; depth < 65; depth++) {
            String next = "deep-" + depth; tree.directory(parent, next, "nested"); parent = next;
        }
        structuralReject(tree, "directory depth limit");
        tree = valid();
        List<ImportPlan.Entry> root = new ArrayList<>(tree.folders.get("root"));
        tree.folders.put("root", new AbstractList<ImportPlan.Entry>() {
            @Override public int size() { return 200001; }
            @Override public ImportPlan.Entry get(int index) {
                return index < root.size() ? root.get(index)
                        : new ImportPlan.Entry("junk-" + index, "junk-" + index + ".zip", false, 0);
            }
        });
        structuralReject(tree, "metadata item limit");
    }
    private static void badPayloads() throws Exception {
        Tree tree = valid(); tree.blobs.get("code").prefix = elf(62);
        final Tree wrongArch = tree; rejects(() -> ImportPlan.inspect(wrongArch, "root"), "no ARM64 library");
        tree = valid(); tree.blobs.get("code").prefix = new byte[64];
        final Tree notElf = tree; rejects(() -> ImportPlan.inspect(notElf, "root"), "bad ELF magic");
        tree = valid(); tree.blobs.get("rom").prefix[0x18f] = 0;
        final Tree encrypted = tree; rejects(() -> ImportPlan.inspect(encrypted, "root"), "encrypted ROM");
        tree = valid(); tree.blobs.get("rom").length = 511;
        final Tree truncated = tree; rejects(() -> ImportPlan.inspect(truncated, "root"), "truncated ROM");
        tree = valid(); u32(tree.blobs.get("rom").prefix, 0x104, FOUR_GIB / 512 + 1);
        final Tree oversized = tree; rejects(() -> ImportPlan.inspect(oversized, "root"), "declared ROM beyond file");
        tree = valid(); tree.blobs.get("texture").prefix[0] = 0;
        final Tree badPng = tree; rejects(() -> ImportPlan.inspect(badPng, "root"), "bad PNG signature");
        tree = valid(); Arrays.fill(tree.blobs.get("texture").prefix, 16, 20, (byte) 0);
        pngCrc(tree.blobs.get("texture").prefix);
        final Tree zeroWidth = tree; rejects(() -> ImportPlan.inspect(zeroWidth, "root"), "zero-width PNG");
        tree = valid(); tree.blobs.get("texture").prefix[20] = (byte) 128;
        pngCrc(tree.blobs.get("texture").prefix);
        final Tree negativeHeight = tree; rejects(() -> ImportPlan.inspect(negativeHeight, "root"), "negative-height PNG");
        tree = valid(); tree.blobs.get("texture").prefix[29] ^= 1;
        final Tree badCrc = tree; rejects(() -> ImportPlan.inspect(badCrc, "root"), "bad IHDR CRC");
        tree = valid(); tree.blobs.get("texture").prefix[11] = 12;
        pngCrc(tree.blobs.get("texture").prefix);
        final Tree badIhdrSize = tree; rejects(() -> ImportPlan.inspect(badIhdrSize, "root"), "bad IHDR length");
        tree = valid(); tree.blobs.get("font").readable = false;
        final Tree badFont = tree; rejects(() -> ImportPlan.inspect(badFont, "root"), "unreadable selected sysdata");
        tree = valid(); tree.blobs.get("pack").readable = false;
        final Tree badPack = tree; rejects(() -> ImportPlan.inspect(badPack, "root"), "unreadable selected pack manifest");
        tree = valid(); tree.file("arm", "another-arm64", "other.so", NativeFixture.elf(4, false));
        tree.directory("root", "second-arm64-dir", "other-build");
        tree.file("second-arm64-dir", "second-arm64", "title.so", NativeFixture.elf(4, false));
        final Tree duplicateArm64 = tree; rejects(() -> ImportPlan.inspect(duplicateArm64, "root"), "multiple ARM64 libraries");
        tree = valid(); reportedSize(tree, "title-folder", "rom", FOUR_GIB - 512);
        final Tree changedSize = tree; rejects(() -> ImportPlan.inspect(changedSize, "root"), "reported size mismatch");
        tree = valid(); tree.blobs.get("font").length = Long.MAX_VALUE;
        reportedSize(tree, "sysdata", "font", Long.MAX_VALUE);
        final Tree overflow = tree; rejects(() -> ImportPlan.inspect(overflow, "root"), "byte size overflow");
    }
    private static void incompatibleCode() throws Exception {
        for (byte[] bytes : new byte[][]{elf(183), NativeFixture.elf(5, true),
                NativeFixture.elf(6, false), NativeFixture.elf(4, true)}) {
            Tree tree = valid();
            tree.blobs.get("code").prefix = bytes;
            tree.blobs.get("code").length = bytes.length;
            reportedSize(tree, "arm", "code", bytes.length);
            rejects(() -> ImportPlan.inspect(tree, "root"), "incompatible native exports");
        }
    }
    private static void rejectedRom(byte[] bytes, String label) throws Exception {
        Tree tree = valid();
        tree.blobs.get("rom").prefix = bytes;
        tree.blobs.get("rom").length = bytes.length;
        reportedSize(tree, "title-folder", "rom", bytes.length);
        rejects(() -> ImportPlan.inspect(tree, "root"), label);
        check(tree.blobs.get("code").opens == 0 && tree.blobs.get("texture").opens == 0,
                label + " opened code/textures before rejecting ROM");
    }
    private static void executableStructure() throws Exception {
        byte[] headerOnly = Arrays.copyOf(romHeader(512), 512);
        u32(headerOnly, 0x180, 0); u32(headerOnly, 0x1a0, 0); u32(headerOnly, 0x1a4, 0);
        rejectedRom(headerOnly, "512-byte NCCH without exheader");
        byte[] bytes = romHeader(0xA00); u32(bytes, 0x180, 0x3ff);
        rejectedRom(bytes, "short declared exheader");
        bytes = romHeader(0xA00); u32(bytes, 0x180, 0x1000);
        rejectedRom(bytes, "exheader beyond content");
        bytes = romHeader(0xA00); u32(bytes, 0x1a0, 1);
        rejectedRom(bytes, "ExeFS overlaps exheader");
        bytes = romHeader(0xA00); u32(bytes, 0x1a4, 0);
        rejectedRom(bytes, "missing ExeFS");
        bytes = romHeader(0xA00); bytes[0x600] = 'x';
        rejectedRom(bytes, "missing .code entry");
        bytes = romHeader(0xA00); u32(bytes, 0x60c, 0);
        rejectedRom(bytes, "empty .code entry");
        bytes = romHeader(0xA00); u32(bytes, 0x608, 0x200);
        rejectedRom(bytes, ".code outside ExeFS");
        bytes = romHeader(0xA00); System.arraycopy(bytes, 0x600, bytes, 0x610, 16);
        rejectedRom(bytes, "duplicate .code entry");
        byte[] cartridge = new byte[0xC00];
        u32(cartridge, 0x100, 0x4453434e); u32(cartridge, 0x120, 1); u32(cartridge, 0x124, 5);
        System.arraycopy(romHeader(0xA00), 0, cartridge, 512, 0xA00);
        try (SeekableByteChannel file = new SparseChannel(new Blob(cartridge, cartridge.length))) {
            check(TITLE.equals(ImportPlan.title(file)), "Valid NCSD executable rejected");
        }
    }
    private static void rejectedPng(byte[] bytes, String label) throws Exception {
        Tree tree = valid(); tree.blobs.get("texture").prefix = bytes; tree.blobs.get("texture").length = bytes.length;
        reportedSize(tree, "texture-sub", "texture", bytes.length);
        rejects(() -> ImportPlan.inspect(tree, "root"), label);
        rejects(() -> ImportPlan.texturePack(tree, "textures", TITLE), label + " replacement");
    }
    private static void completePngs() throws Exception {
        rejectedPng(Arrays.copyOf(PNG, 33), "IHDR-only PNG");
        rejectedPng(Arrays.copyOf(PNG, PNG.length - 12), "PNG without IEND");
        rejectedPng(Arrays.copyOf(PNG, PNG.length - 1), "truncated IEND CRC");
        byte[] bytes = PNG.clone(); bytes[41] ^= 1;
        rejectedPng(bytes, "bad IDAT CRC");
        bytes = PNG.clone(); bytes[bytes.length - 1] ^= 1;
        rejectedPng(bytes, "bad IEND CRC");
        bytes = PNG.clone(); ByteBuffer.wrap(bytes).putInt(33, Integer.MAX_VALUE);
        rejectedPng(bytes, "chunk extends beyond file");
        bytes = new byte[45]; System.arraycopy(PNG, 0, bytes, 0, 33);
        System.arraycopy(PNG, PNG.length - 12, bytes, 33, 12);
        rejectedPng(bytes, "IEND without IDAT");
        rejectedPng(Arrays.copyOf(PNG, PNG.length + 1), "bytes after IEND");
        java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
        raw.write(PNG, 0, 33);
        NativeFixture.chunk(new java.io.DataOutputStream(raw), "vpAg", new byte[140000]);
        raw.write(PNG, 33, PNG.length - 33);
        Tree tree = valid(); Blob large = tree.blobs.get("texture");
        large.prefix = raw.toByteArray(); large.length = large.prefix.length; large.readLimit = 300000;
        reportedSize(tree, "texture-sub", "texture", large.length);
        check(ImportPlan.texturePack(tree, "textures", TITLE).files.size() == 2, "Large valid PNG chunk rejected");
        check(large.read >= large.length && large.maxRequest <= 65536, "PNG scan did not stream in bounded reads");
    }
    private static void optionalTextures() throws Exception {
        Tree noTextures = valid();
        noTextures.folders.get("title-folder").removeIf(entry -> entry.id.equals("textures"));
        check(ImportPlan.inspect(noTextures, "root").files.size() == 4, "Base game without textures rejected");
        Tree emptyTextures = valid();
        emptyTextures.folders.get("textures").clear();
        check(ImportPlan.inspect(emptyTextures, "root").files.size() == 4, "Empty textures folder rejected");
        Tree manifestOnly = valid();
        manifestOnly.folders.get("textures").removeIf(entry -> !entry.id.equals("pack"));
        manifestOnly.blobs.get("pack").readable = false;
        check(ImportPlan.inspect(manifestOnly, "root").files.size() == 4, "Manifest-only optional pack rejected");
        check(manifestOnly.blobs.get("pack").opens == 0, "Ignored manifest-only pack opened");
        Tree broken = valid();
        broken.blobs.get("pack").readable = false;
        broken.blobs.get("texture").prefix[0] = 0;
        ImportPlan plan = ImportPlan.inspect(broken, "root", false);
        check(plan.files.size() == 4, "Excluded textures entered plan");
        for (ImportPlan.Item item : plan.files)
            check(!item.destination.contains("/textures/"), "Texture destination included despite opt out");
        check(broken.blobs.get("pack").opens == 0 && broken.blobs.get("texture").opens == 0,
                "Excluded broken texture pack was opened");
        check(!broken.listed.contains("textures") && !broken.listed.contains("texture-sub"),
                "Excluded texture metadata was traversed");
        rejects(() -> ImportPlan.inspect(broken, "root"), "broken texture pack included by default");
    }
    private static void componentPlans() throws Exception {
        Tree nativeOnly = new Tree();
        byte[] code = NativeFixture.elf(4, false);
        nativeOnly.file("root", "code", "replacement.so", code);
        ImportPlan nativePlan = ImportPlan.nativeCode(nativeOnly,
                new ImportPlan.Entry("code", "replacement.so", false, code.length), TITLE);
        check(nativePlan.rom == null && nativePlan.files.size() == 1 && TITLE.equals(nativePlan.titleId),
                "Native replacement included unrelated components");
        check(nativePlan.code.id.equals("code") && nativePlan.bytes == code.length
                && item(nativePlan, "code").destination.equals("code/title.so"), "Wrong native replacement plan");
        check(nativeOnly.listed.isEmpty() && nativeOnly.opens == 1, "Native replacement scanned other source files");
        nativeOnly.blobs.get("code").prefix = NativeFixture.elf(5, true);
        rejects(() -> ImportPlan.nativeCode(nativeOnly,
                new ImportPlan.Entry("code", "replacement.so", false, code.length), TITLE), "ABI 5 replacement");

        Tree textures = valid();
        ImportPlan pack = ImportPlan.texturePack(textures, "textures", TITLE);
        check(pack.rom == null && pack.code == null && pack.files.size() == 2 && TITLE.equals(pack.titleId),
                "Texture replacement included game components");
        check(item(pack, "texture").destination.equals("data/mods/" + TITLE + "/textures/battle/tex.png")
                && item(pack, "pack").destination.equals("data/mods/" + TITLE + "/textures/pack.json"),
                "Wrong texture replacement destinations");
        check(pack.bytes == PNG.length + 2 && textures.blobs.get("rom").opens == 0
                && textures.blobs.get("code").opens == 0, "Texture replacement opened game payloads");
        try (SeekableByteChannel channel = textures.open("texture")) { item(pack, "texture").checkHeader(channel); }
        textures.blobs.get("texture").prefix[0] = 0;
        rejects(() -> ImportPlan.texturePack(textures, "textures", TITLE), "broken replacement PNG");
        Tree empty = new Tree();
        rejects(() -> ImportPlan.texturePack(empty, "root", TITLE), "empty explicit texture pack");
        empty.file("root", "manifest", "pack.json", new byte[]{'{', '}'});
        rejects(() -> ImportPlan.texturePack(empty, "root", TITLE), "manifest-only explicit texture pack");
    }
    private static void unknownSizes() throws Exception {
        Tree tree = valid();
        reportedSize(tree, "title-folder", "rom", -1);
        reportedSize(tree, "arm", "code", -1);
        reportedSize(tree, "texture-sub", "texture", -1);
        ImportPlan plan = ImportPlan.inspect(tree, "root");
        check(plan.rom.size == FOUR_GIB && plan.code.size == NativeFixture.elf(4, false).length,
                "Unknown sizes were not resolved");
        for (ImportPlan.Item item : plan.files) check(item.source.size >= 0, "Unknown size leaked into plan");
        long total = 0; for (ImportPlan.Item item : plan.files) total += item.source.size;
        check(plan.bytes == total, "Resolved sizes missing from byte total");
        check(tree.blobs.get("rom").read <= 8192, "Unknown size caused a full ROM read");
    }
    private static void directHeaders() throws Exception {
        Blob rom = new Blob(romHeader(FOUR_GIB), FOUR_GIB);
        try (SeekableByteChannel channel = new SparseChannel(rom)) {
            check(TITLE.equals(ImportPlan.title(channel)), "Direct title parsing failed");
        }
        check(rom.read <= 8192, "Direct title read unbounded data");
        for (int machine : new int[]{183, 62}) {
            try (SeekableByteChannel channel = new SparseChannel(new Blob(elf(machine), 64))) {
                check(ImportPlan.arm64(channel) == (machine == 183), "Wrong ELF machine accepted");
            }
        }
    }
    private static void destinationBounds() throws Exception {
        File stage = new File("import-plan-unused-stage-" + UUID.randomUUID());
        check(!stage.exists(), "Destination fixture already exists");
        Tree normal = valid();
        ImportPlan normalPlan = ImportPlan.inspect(normal, "root");
        int opened = normal.opens;
        normalPlan.checkDestination(stage);
        check(normal.opens == opened && !stage.exists(), "Destination check copied or created files");

        Tree deep = valid();
        String parent = "textures";
        for (int depth = 0; depth < 20; depth++) {
            String id = "long-path-" + depth;
            String name = "a".repeat(220) + depth;
            check(name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 255,
                    "Fixture component exceeds filename limit");
            deep.directory(parent, id, name); parent = id;
        }
        deep.file(parent, "deep-texture", "deep.png", PNG.clone());
        ImportPlan deepPlan = ImportPlan.inspect(deep, "root");
        ImportPlan.Item item = item(deepPlan, "deep-texture");
        check(item.destination.split("/").length < 64, "Fixture exceeds depth limit");
        check(item.destination.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4096,
                "Fixture does not exceed total path limit");
        int before = deep.opens;
        rejects(() -> deepPlan.checkDestination(stage), "combined destination path over 4096 bytes");
        check(deep.opens == before && !stage.exists(), "Long-path check copied or created files");
    }
    private static ImportPlan.Item item(ImportPlan plan, String id) {
        for (ImportPlan.Item item : plan.files) if (item.source.id.equals(id)) return item;
        throw new AssertionError("Missing planned item " + id);
    }
    private static void capturedHeaders() throws Exception {
        Tree tree = valid();
        ImportPlan plan = ImportPlan.inspect(tree, "root");
        ImportPlan.Item texture = item(plan, "texture");
        Blob source = tree.blobs.get("texture");
        byte[] original = source.prefix.clone();
        try (SeekableByteChannel channel = tree.open("texture")) { texture.checkHeader(channel); }

        // Same-size, independently valid IHDR changes after inspection must be detected.
        ByteBuffer.wrap(source.prefix).putInt(16, 2); pngCrc(source.prefix);
        check(source.length == texture.source.size, "Mutation changed file size");
        rejects(() -> {
            try (SeekableByteChannel channel = tree.open("texture")) { texture.checkHeader(channel); }
        }, "same-size source PNG change after inspection");
        System.arraycopy(original, 0, source.prefix, 0, original.length);
        try (SeekableByteChannel channel = tree.open("texture")) { texture.checkHeader(channel); }

        byte[] different = original.clone(); ByteBuffer.wrap(different).putInt(20, 2); pngCrc(different);
        Blob copySource = new Blob(different, source.length);
        rejects(() -> {
            try (SeekableByteChannel copyChannel = new SparseChannel(copySource)) { texture.checkHeader(copyChannel); }
        }, "copy channel differs from captured PNG header");
        Blob truncated = new Blob(original, 32);
        rejects(() -> {
            try (SeekableByteChannel channel = new SparseChannel(truncated)) { texture.checkHeader(channel); }
        }, "copy channel shorter than captured 33-byte PNG header");
        check(Arrays.equals(source.prefix, original), "Header check modified source PNG");
    }
    public static void main(String[] args) throws Exception {
        validPlan(); badStructure(); badPayloads(); unknownSizes(); directHeaders();
        destinationBounds(); capturedHeaders();
        incompatibleCode(); optionalTextures(); componentPlans();
        executableStructure(); completePngs();
        System.out.println("Import plan checks passed: preflight ordering, sparse ROM, strict ABI, optional textures, component replacements, destination bounds, captured headers");
    }
}
