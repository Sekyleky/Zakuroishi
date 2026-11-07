package io.github.zakuro.android;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Small ELF64 shared object containing data exports, never executable code. */
final class NativeFixture {
    static byte[] rom(long length, long titleId) {
        byte[] bytes = new byte[0xA00];
        ByteBuffer b = buffer(bytes);
        b.putInt(0x100, 0x4843434e); b.putInt(0x104, (int)(length / 512));
        b.putLong(0x118, titleId); b.putInt(0x180, 0x400);
        bytes[0x18d] = 2; bytes[0x18f] = 4;
        b.putInt(0x1a0, 3); b.putInt(0x1a4, 2);
        b.putInt(0x210, 0x100000); b.putInt(0x214, 1); b.putInt(0x218, 8);
        System.arraycopy(".code".getBytes(StandardCharsets.US_ASCII), 0, bytes, 0x600, 5);
        b.putInt(0x608, 0); b.putInt(0x60c, 8);
        return bytes;
    }
    static byte[] png() {
        try {
            java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out = new java.io.DataOutputStream(raw);
            out.write(new byte[]{(byte)137,80,78,71,13,10,26,10});
            byte[] ihdr = new byte[13]; ihdr[3] = 1; ihdr[7] = 1; ihdr[8] = 8; ihdr[9] = 6;
            chunk(out, "IHDR", ihdr);
            java.io.ByteArrayOutputStream compressed = new java.io.ByteArrayOutputStream();
            try (java.util.zip.DeflaterOutputStream z = new java.util.zip.DeflaterOutputStream(compressed)) {
                z.write(new byte[]{0,0,0,0,0});
            }
            chunk(out, "IDAT", compressed.toByteArray()); chunk(out, "IEND", new byte[0]);
            return raw.toByteArray();
        } catch (java.io.IOException e) { throw new AssertionError(e); }
    }
    static void chunk(java.io.DataOutputStream out, String type, byte[] data) throws java.io.IOException {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        out.writeInt(data.length); out.write(name); out.write(data);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32(); crc.update(name); crc.update(data);
        out.writeInt((int)crc.getValue());
    }
    static byte[] visibility(int visibility) {
        byte[] bytes = elf(4, false);
        for (int i = 1; i <= 5; i++) bytes[SYMBOLS + i * 24 + 5] = (byte)visibility;
        return bytes;
    }
    static final int SECTIONS = 64, SYMBOLS = 512, STRINGS = 768, DATA = 1024;
    static final long ADDRESS = 0x20000;
    static final String[] EXPORTS = {"recomp_abi", "recomp_entries", "recomp_entry_count",
            "recomp_modules", "recomp_module_count", "recomp_fastmem", "recomp_generation"};
    /** Where elf() stores recomp_entry_count and recomp_module_count (symbols 3 and 5, 8 bytes apart). */
    static final int ENTRY_COUNT = DATA + 16, MODULE_COUNT = DATA + 32;
    static ByteBuffer buffer(byte[] bytes) { return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); }
    static byte[] elf(int abi, boolean fastMemory) { return elf(abi, fastMemory, -1); }
    /** generation below 0 leaves recomp_generation out, as libraries made before it existed do. */
    static byte[] elf(int abi, boolean fastMemory, int generation) {
        byte[] bytes = new byte[2048];
        ByteBuffer b = buffer(bytes);
        b.put(new byte[]{127, 'E', 'L', 'F', 2, 1, 1});
        b.putShort(16, (short) 3); b.putShort(18, (short) 183); b.putInt(20, 1);
        b.putLong(40, SECTIONS); b.putShort(52, (short) 64);
        b.putShort(54, (short) 56); b.putShort(58, (short) 64); b.putShort(60, (short) 4);
        String[] names = new String[7]; int count = 0;
        for (int i = 0; i < 5; i++) names[count++] = EXPORTS[i];
        if (fastMemory) names[count++] = EXPORTS[5];
        int generationAt = generation >= 0 ? count : -1;
        if (generation >= 0) names[count++] = EXPORTS[6];
        section(b, 1, 11, 0, SYMBOLS, (count + 1) * 24, 2, 24);
        section(b, 3, 1, ADDRESS, DATA, 128, 0, 0);
        int nameAt = 1;
        for (int i = 0; i < count; i++) {
            byte[] name = names[i].getBytes(StandardCharsets.UTF_8);
            System.arraycopy(name, 0, bytes, STRINGS + nameAt, name.length);
            int symbol = SYMBOLS + (i + 1) * 24;
            b.putInt(symbol, nameAt); b.put(symbol + 4, (byte) 0x11);
            b.putShort(symbol + 6, (short) 3); b.putLong(symbol + 8, ADDRESS + i * 8);
            b.putLong(symbol + 16, i == 0 ? 4 : 8);
            nameAt += name.length + 1;
        }
        section(b, 2, 3, 0, STRINGS, nameAt, 0, 0);
        b.putInt(DATA, abi);
        if (generationAt >= 0) b.putInt(DATA + generationAt * 8, generation);
        return bytes;
    }
    private static void section(ByteBuffer b, int index, int type, long address,
                                long offset, long size, int link, long stride) {
        int at = SECTIONS + index * 64;
        b.putInt(at + 4, type); b.putLong(at + 8, type == 1 ? 3 : 2);
        b.putLong(at + 16, address); b.putLong(at + 24, offset); b.putLong(at + 32, size);
        b.putInt(at + 40, link); b.putLong(at + 48, 8); b.putLong(at + 56, stride);
    }
}
