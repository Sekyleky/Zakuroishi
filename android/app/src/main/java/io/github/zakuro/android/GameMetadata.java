package io.github.zakuro.android;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Small, bounded display metadata; names never participate in storage paths. */
public final class GameMetadata {
    private GameMetadata() {}

    public static String name(File version, String id) {
        try (InputStream in = new FileInputStream(new File(version, "display-name.txt"))) {
            byte[] bytes = new byte[1025];
            int used = 0, n;
            while (used < bytes.length && (n = in.read(bytes, used, bytes.length - used)) != -1)
                used += n;
            if (used <= 1024) {
                String text = clean(new String(bytes, 0, used, StandardCharsets.UTF_8));
                if (!text.isEmpty()) return text;
            }
        } catch (IOException ignored) { }
        String embedded = romTitle(new File(version, "game/game.3ds"));
        if (!embedded.isEmpty()) return embedded;
        return "Game " + id.substring(Math.max(0, id.length() - 6));
    }

    private static boolean fits(long offset, long size, long limit) {
        return offset >= 0 && size >= 0 && offset <= limit && size <= limit - offset;
    }
    private static long u32(byte[] bytes, int at) {
        return (bytes[at] & 255L) | (bytes[at + 1] & 255L) << 8
                | (bytes[at + 2] & 255L) << 16 | (bytes[at + 3] & 255L) << 24;
    }
    private static boolean magic(byte[] bytes, int at, String expected) {
        for (int i = 0; i < expected.length(); i++)
            if (bytes[at + i] != expected.charAt(i)) return false;
        return true;
    }
    private static String utf16(byte[] bytes, int at, int size) {
        int end = at;
        while (end < at + size && (bytes[end] != 0 || bytes[end + 1] != 0)) end += 2;
        return clean(new String(bytes, at, end - at, StandardCharsets.UTF_16LE));
    }

    /** Reads at most two container headers, one ExeFS header and the first `bytes` of the SMDH icon file; null when absent. */
    private static byte[] smdh(File rom, int bytes) {
        try (RandomAccessFile in = new RandomAccessFile(rom, "r")) {
            long length = in.length(), base = 0, partitionSize = length;
            if (length < 512) return null;
            byte[] header = new byte[512]; in.readFully(header);
            if (magic(header, 0x100, "NCSD")) {
                base = u32(header, 0x120) * 512;
                partitionSize = u32(header, 0x124) * 512;
                if (base < 512 || partitionSize < 512 || !fits(base, partitionSize, length)) return null;
                in.seek(base); in.readFully(header);
            }
            if (!magic(header, 0x100, "NCCH") || (header[0x18f] & 4) == 0) return null;
            long contentSize = u32(header, 0x104) * 512;
            long exefsOffset = u32(header, 0x1a0) * 512;
            long exefsSize = u32(header, 0x1a4) * 512;
            if (contentSize < 512 || contentSize > partitionSize || exefsOffset < 512
                    || exefsSize < 512 || !fits(exefsOffset, exefsSize, contentSize)) return null;
            in.seek(base + exefsOffset); in.readFully(header);
            for (int entry = 0; entry < 10; entry++) {
                int at = entry * 16;
                if (!magic(header, at, "icon\0\0\0\0")) continue;
                long iconOffset = 512 + u32(header, at + 8), iconSize = u32(header, at + 12);
                if (iconSize < bytes || !fits(iconOffset, iconSize, exefsSize)) return null;
                byte[] icon = new byte[bytes];
                in.seek(base + exefsOffset + iconOffset); in.readFully(icon);
                return magic(icon, 0, "SMDH") ? icon : null;
            }
        } catch (IOException ignored) { }
        return null;
    }

    static String romTitle(File rom) {
        byte[] icon = smdh(rom, 0x388);
        if (icon == null) return "";
        // English is language slot 1: short[0x80], long[0x100], publisher[0x80].
        String title = utf16(icon, 0x288, 0x100);
        return title.isEmpty() ? utf16(icon, 0x208, 0x80) : title;
    }

    static final int ICON = 48;
    /** The game's 48x48 icon as ARGB pixels, or null. */
    static int[] romIcon(File rom) {
        byte[] smdh = smdh(rom, 0x36c0);
        return smdh == null ? null : icon(smdh, 0x24c0);
    }
    /** RGB565 in 8x8 tiles, each tile in Z-order (x on even bits, y on odd bits). */
    static int[] icon(byte[] data, int at) {
        int[] out = new int[ICON * ICON];
        for (int i = 0; i < out.length; i++) {
            int in = i & 63, x = (i >> 6) % 6 * 8, y = (i >> 6) / 6 * 8;
            for (int bit = 0; bit < 3; bit++) { x += (in >> bit * 2 & 1) << bit; y += (in >> bit * 2 + 1 & 1) << bit; }
            int c = (data[at + i * 2] & 255) | (data[at + i * 2 + 1] & 255) << 8;
            int r = c >> 11, g = c >> 5 & 63, b = c & 31;
            out[y * ICON + x] = 0xff000000 | (r << 3 | r >> 2) << 16 | (g << 2 | g >> 4) << 8 | (b << 3 | b >> 2);
        }
        return out;
    }

    static String clean(String value) {
        if (value == null) return "";
        StringBuilder text = new StringBuilder();
        // Bound work even for an unusually large provider display name.
        for (int i = 0, count = 0; i < value.length() && count < 160; count++) {
            int c = value.codePointAt(i); i += Character.charCount(c);
            if (Character.isISOControl(c) || Character.isWhitespace(c) || c == '/' || c == '\\')
                text.append(' ');
            else if (Character.getType(c) != Character.FORMAT) text.appendCodePoint(c);
        }
        return text.toString().replaceAll("\\s+", " ").trim();
    }

    private static String meaningful(String value) {
        String text = clean(value).replace('_', ' ').replaceAll("\\s+", " ").trim();
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.isEmpty() || lower.matches("[0-9]+|[0-9a-f]{6,32}")
                || lower.matches("(?:game|rom|title|original|dump|built|build|output|import|pocket native(?: built)?)(?:[ ._-]*[0-9]+)?"))
            return "";
        return text;
    }

    static String choose(String romName, String folderName, String id) {
        String stem = romName == null ? "" : romName.replaceFirst("(?i)\\.(3ds|cci|cxi)$", "");
        String text = meaningful(stem);
        if (text.isEmpty()) text = meaningful(folderName);
        return text.isEmpty() ? "Game " + id.substring(Math.max(0, id.length() - 6)) : text;
    }

    static String choose(File rom, String folderName, String id) {
        String embedded = romTitle(rom);
        return embedded.isEmpty() ? choose(rom.getName(), folderName, id) : embedded;
    }

    static void store(File version, String title) throws IOException {
        try (FileOutputStream out = new FileOutputStream(new File(version, "display-name.txt"))) {
            out.write(clean(title).getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
    }
}
