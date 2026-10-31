package io.github.zakuro.android;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.util.Arrays;

/** Host-only ABI inspection checks: synthetic metadata is read, never loaded. */
public final class NativeLibraryCheck {
    private interface Attempt { void run() throws Exception; }
    private static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); }
    private static void rejects(Attempt attempt, String label) throws Exception {
        try { attempt.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Accepted " + label);
    }
    private static final class Source implements SeekableByteChannel {
        final byte[] data;
        long position;
        int read;
        boolean open = true;
        Source(byte[] data) { this.data = data; }
        public int read(ByteBuffer target) {
            if (position >= size()) return -1;
            int count = (int) Math.min(target.remaining(), size() - position);
            // The rest of this sparse 4 GiB object represents code, not metadata.
            check(position + count <= 1152, "Parser read the library body");
            read += count; check(read <= 4096, "Unbounded metadata reads");
            target.put(data, (int) position, count); position += count; return count;
        }
        public int write(ByteBuffer source) { throw new AssertionError("Source write"); }
        public SeekableByteChannel truncate(long size) { throw new AssertionError("Source truncate"); }
        public long position() { return position; }
        public SeekableByteChannel position(long value) { position = value; return this; }
        public long size() { return 4L * 1024 * 1024 * 1024; }
        public boolean isOpen() { return open; }
        public void close() { open = false; }
    }
    private static NativeLibrary.Info inspect(byte[] bytes) throws Exception {
        byte[] original = bytes.clone();
        try (Source source = new Source(bytes)) {
            NativeLibrary.Info info = NativeLibrary.inspect(source);
            check(Arrays.equals(bytes, original), "Inspection changed source");
            return info;
        }
    }
    private static void malformed(String label, java.util.function.Consumer<ByteBuffer> change) throws Exception {
        byte[] bytes = NativeFixture.elf(4, false);
        change.accept(NativeFixture.buffer(bytes));
        rejects(() -> inspect(bytes), label);
    }
    public static void main(String[] args) throws Exception {
        NativeLibrary.Info good = inspect(NativeFixture.elf(4, false));
        check(good.abi == 4 && !good.fastMemory, "ABI data was not decoded");
        check(good.describe().contains("ABI 4"), "Missing ABI description");
        good.requireCompatible();
        inspect(NativeFixture.visibility(3)).requireCompatible();
        for (int visibility : new int[]{1, 2}) {
            rejects(() -> inspect(NativeFixture.visibility(visibility)), "internal/hidden required exports");
            for (int i = 1; i <= 5; i++) {
                byte[] hidden = NativeFixture.elf(4, false);
                hidden[NativeFixture.SYMBOLS + i * 24 + 5] = (byte)visibility;
                rejects(() -> inspect(hidden), "non-visible required export " + NativeFixture.EXPORTS[i - 1]);
            }
        }
        NativeLibrary.Info newer = inspect(NativeFixture.elf(5, true));
        check(newer.abi == 5 && newer.fastMemory, "ABI 5/fast memory not reported");
        check(newer.describe().contains("fast memory"), "Missing fast-memory description");
        rejects(newer::requireCompatible, "ABI 5 fast memory");
        rejects(inspect(NativeFixture.elf(5, false))::requireCompatible, "ABI 5 alone");
        rejects(inspect(NativeFixture.elf(4, true))::requireCompatible, "ABI 4 fast memory");
        byte[] importedFastMemory = NativeFixture.elf(4, true);
        NativeFixture.buffer(importedFastMemory).putShort(NativeFixture.SYMBOLS + 6 * 24 + 6, (short) 0);
        check(!inspect(importedFastMemory).fastMemory, "Undefined fast-memory import treated as export");
        for (int i = 0; i < 5; i++) {
            byte[] missing = NativeFixture.elf(4, false);
            NativeFixture.buffer(missing).putShort(NativeFixture.SYMBOLS + (i + 1) * 24 + 6, (short) 0);
            rejects(() -> inspect(missing), "undefined PLT/import in place of " + NativeFixture.EXPORTS[i]);
            byte[] absent = NativeFixture.elf(4, false);
            NativeFixture.buffer(absent).putInt(NativeFixture.SYMBOLS + (i + 1) * 24, 0);
            rejects(() -> inspect(absent), "missing " + NativeFixture.EXPORTS[i]);
        }
        byte[] wrong = NativeFixture.elf(4, false); NativeFixture.buffer(wrong).putShort(18, (short) 62);
        rejects(() -> inspect(wrong), "x86 library");
        byte[] bss = NativeFixture.elf(4, false);
        NativeFixture.buffer(bss).putInt(NativeFixture.SECTIONS + 3 * 64 + 4, 8);
        rejects(() -> inspect(bss), "ABI in BSS");
        for (long offset : new long[]{-1, Long.MAX_VALUE, 4L * 1024 * 1024 * 1024 - 64}) {
            byte[] invalid = NativeFixture.elf(4, false); NativeFixture.buffer(invalid).putLong(40, offset);
            rejects(() -> inspect(invalid), "section table bounds");
        }
        for (int count : new int[]{0, 513, 65535}) {
            byte[] invalid = NativeFixture.elf(4, false); NativeFixture.buffer(invalid).putShort(60, (short) count);
            rejects(() -> inspect(invalid), "section count " + count);
        }
        for (long size : new long[]{0, -1, Long.MAX_VALUE, 1024 * 1024 + 1}) {
            byte[] invalid = NativeFixture.elf(4, false);
            NativeFixture.buffer(invalid).putLong(NativeFixture.SECTIONS + 2 * 64 + 32, size);
            rejects(() -> inspect(invalid), "malicious string size");
        }
        byte[] invalid = NativeFixture.elf(4, false);
        NativeFixture.buffer(invalid).putLong(NativeFixture.SYMBOLS + 24 + 8, NativeFixture.ADDRESS + 126);
        rejects(() -> inspect(invalid), "ABI storage crosses section end");
        malformed("symbol table outside file", b -> b.putLong(NativeFixture.SECTIONS + 64 + 24, Long.MAX_VALUE));
        malformed("partial symbol entry", b -> b.putLong(NativeFixture.SECTIONS + 64 + 32, 25));
        malformed("oversized symbol table", b -> b.putLong(NativeFixture.SECTIONS + 64 + 32, 24L * 200000));
        malformed("invalid symbol stride", b -> b.putLong(NativeFixture.SECTIONS + 64 + 56, 1));
        malformed("invalid string-table link", b -> b.putInt(NativeFixture.SECTIONS + 64 + 40, 4));
        malformed("string table outside file", b -> b.putLong(NativeFixture.SECTIONS + 128 + 24, Long.MAX_VALUE));
        malformed("unterminated export name", b -> b.putLong(NativeFixture.SECTIONS + 128 + 32, 5));
        malformed("ABI address before section", b -> b.putLong(NativeFixture.SYMBOLS + 24 + 8, NativeFixture.ADDRESS - 1));
        malformed("ABI section outside file", b -> b.putLong(NativeFixture.SECTIONS + 192 + 24, Long.MAX_VALUE));
        malformed("local ABI symbol", b -> b.put(NativeFixture.SYMBOLS + 24 + 4, (byte) 1));
        System.out.println("Native library checks passed: ABI, exports, architecture, bounds, read-only sparse metadata");
    }
}
