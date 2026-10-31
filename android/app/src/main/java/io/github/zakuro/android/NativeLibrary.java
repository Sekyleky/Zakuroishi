package io.github.zakuro.android;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.util.*;

/** Inspect exported ABI data without loading or executing untrusted native code. */
final class NativeLibrary {
    static final int ABI = 4;
    static final class Info {
        final int abi;
        final boolean fastMemory;
        Info(int abi, boolean fastMemory) { this.abi = abi; this.fastMemory = fastMemory; }
        String describe() { return "ABI " + abi + (fastMemory ? " with Pocket Native fast memory" : ""); }
        void requireCompatible() throws IOException {
            if (abi != ABI || fastMemory) throw new IOException("This title.so uses " + describe()
                    + ". Zakuro Android requires ABI " + ABI + " without fast memory. Rebuild this game's native code with Zakuro's build-title tool, then choose Replace native game code. The ROM and saves do not need replacing.");
        }
    }
    private static ByteBuffer bytes(SeekableByteChannel file, long offset, int size) throws IOException {
        return ByteBuffer.wrap(ImportPlan.read(file, offset, size)).order(ByteOrder.LITTLE_ENDIAN);
    }
    private static void bounds(long offset, long size, long limit) throws IOException {
        if (offset < 0 || size < 0 || offset > limit || size > limit - offset) throw new IOException("Invalid native-library table bounds");
    }
    static Info inspect(SeekableByteChannel file) throws IOException {
        if (!ImportPlan.arm64(file)) throw new IOException("Expected an Android ARM64 shared library");
        ByteBuffer header = bytes(file, 0, 64);
        long table = header.getLong(40); int stride = Short.toUnsignedInt(header.getShort(58));
        int count = Short.toUnsignedInt(header.getShort(60));
        if (stride != 64 || count < 1 || count > 512) throw new IOException("Native library has no usable symbol table");
        bounds(table, (long)stride * count, file.size());
        ByteBuffer sections = bytes(file, table, stride * count);
        Map<String, Long> exported = new HashMap<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < count; i++) {
            int at = i * stride;
            if (sections.getInt(at + 4) != 11) continue; // SHT_DYNSYM
            long offset = sections.getLong(at + 24), size = sections.getLong(at + 32), entrySize = sections.getLong(at + 56);
            int stringsAt = sections.getInt(at + 40);
            if (entrySize != 24 || size <= 0 || size % 24 != 0 || size > 4 * 1024 * 1024 || stringsAt < 0 || stringsAt >= count)
                throw new IOException("Invalid native-library exports");
            long stringsOffset = sections.getLong(stringsAt * stride + 24), stringsSize = sections.getLong(stringsAt * stride + 32);
            if (stringsSize <= 0 || stringsSize > 1024 * 1024) throw new IOException("Invalid native-library symbol names");
            bounds(offset, size, file.size()); bounds(stringsOffset, stringsSize, file.size());
            byte[] strings = bytes(file, stringsOffset, (int)stringsSize).array();
            ByteBuffer symbols = bytes(file, offset, (int)size);
            for (int symbol = 0; symbol < size; symbol += 24) {
                int nameAt = symbols.getInt(symbol), section = Short.toUnsignedInt(symbols.getShort(symbol + 6));
                int binding = (symbols.get(symbol + 4) & 255) >>> 4;
                if (section == 0 || section >= count || (binding != 1 && binding != 2) || nameAt < 0 || nameAt >= strings.length) continue;
                int visibility = symbols.get(symbol + 5) & 3;
                if (visibility != 0 && visibility != 3) continue;
                int end = nameAt;
                while (end < strings.length && strings[end] != 0) end++;
                if (end == strings.length) throw new IOException("Unterminated native-library symbol name");
                String name = new String(strings, nameAt, end - nameAt, java.nio.charset.StandardCharsets.UTF_8);
                if (!name.startsWith("recomp_")) continue;
                names.add(name);
                if (name.equals("recomp_abi")) {
                    long value = symbols.getLong(symbol + 8), address = sections.getLong(section * stride + 16);
                    long sectionOffset = sections.getLong(section * stride + 24), sectionSize = sections.getLong(section * stride + 32);
                    if (sections.getInt(section * stride + 4) == 8 || value < address) throw new IOException("Invalid ABI export");
                    bounds(value - address, 4, sectionSize); bounds(sectionOffset, sectionSize, file.size());
                    exported.put(name, sectionOffset + value - address);
                }
            }
        }
        for (String required : new String[]{"recomp_abi", "recomp_entries", "recomp_entry_count", "recomp_modules", "recomp_module_count"})
            if (!names.contains(required)) throw new IOException("Native library is missing " + required + ". Use title.so built by the matching Zakuro build-title tool.");
        Long abi = exported.get("recomp_abi");
        if (abi == null) throw new IOException("Cannot read the native-library ABI");
        return new Info(bytes(file, abi, 4).getInt(), names.contains("recomp_fastmem"));
    }
}
