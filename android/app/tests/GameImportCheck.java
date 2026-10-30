package io.github.zakuro.android;

import java.io.*;
import java.nio.file.Files;

/** Host-side boundary checks; run with the platform android.jar on the classpath. */
public final class GameImportCheck {
    interface Attempt { void run() throws Exception; }
    static void rejects(Attempt attempt) throws Exception {
        try { attempt.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Invalid input accepted");
    }
    static void require(boolean ok) { if (!ok) throw new AssertionError(); }
    static void put32(byte[] bytes, int at, long value) {
        for (int i = 0; i < 4; i++) bytes[at + i] = (byte) (value >>> (8 * i));
    }
    static void putText(byte[] bytes, int at, String text) {
        byte[] encoded = text.getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        System.arraycopy(encoded, 0, bytes, at, encoded.length);
    }
    static byte[] smdhRom(boolean cartridge, String longTitle, String shortTitle) {
        int base = cartridge ? 512 : 0;
        byte[] bytes = new byte[base + 4096];
        if (cartridge) {
            System.arraycopy(new byte[]{'N','C','S','D'}, 0, bytes, 0x100, 4);
            put32(bytes, 0x104, 9); put32(bytes, 0x120, 1); put32(bytes, 0x124, 8);
        }
        System.arraycopy(new byte[]{'N','C','C','H'}, 0, bytes, base + 0x100, 4);
        put32(bytes, base + 0x104, 8); bytes[base + 0x18f] = 4;
        put32(bytes, base + 0x1a0, 1); put32(bytes, base + 0x1a4, 6);
        int entry = base + 512 + 3 * 16;
        System.arraycopy(new byte[]{'i','c','o','n'}, 0, bytes, entry, 4);
        put32(bytes, entry + 8, 0x80); put32(bytes, entry + 12, 0x388);
        int icon = base + 1024 + 0x80;
        System.arraycopy(new byte[]{'S','M','D','H'}, 0, bytes, icon, 4);
        putText(bytes, icon + 0x288, longTitle); putText(bytes, icon + 0x208, shortTitle);
        return bytes;
    }
    static void checkSmdh(File dir, String id) throws Exception {
        File rom = new File(dir, "smdh-game.3ds");
        byte[] ncch = smdhRom(false, "Pokémon\nUltra Moon", "Short title");
        Files.write(rom.toPath(), ncch);
        require(GameMetadata.romTitle(rom).equals("Pokémon Ultra Moon"));
        require(GameMetadata.choose(rom, "Folder title", id).equals("Pokémon Ultra Moon"));
        byte[] ncsd = smdhRom(true, "Mario Kart 7", "Short title");
        Files.write(rom.toPath(), ncsd);
        require(GameMetadata.romTitle(rom).equals("Mario Kart 7"));
        Files.write(rom.toPath(), smdhRom(false, "", "English short"));
        require(GameMetadata.romTitle(rom).equals("English short"));
        for (int at : new int[]{0x104, 0x1a0, 0x1a4, 512 + 3 * 16 + 8, 512 + 3 * 16 + 12}) {
            byte[] invalid = ncch.clone(); put32(invalid, at, 0xffffffffL);
            Files.write(rom.toPath(), invalid); require(GameMetadata.romTitle(rom).isEmpty());
        }
        for (int at : new int[]{0x120, 0x124}) {
            byte[] invalid = ncsd.clone(); put32(invalid, at, 0xffffffffL);
            Files.write(rom.toPath(), invalid); require(GameMetadata.romTitle(rom).isEmpty());
        }
        for (int length : new int[]{0, 511, 512, 1023, 0x807}) {
            Files.write(rom.toPath(), java.util.Arrays.copyOf(ncch, length));
            require(GameMetadata.romTitle(rom).isEmpty());
        }
        for (int at : new int[]{0x100, 0x18f, 512 + 3 * 16 + 4, 1024 + 0x80}) {
            byte[] invalid = ncch.clone(); invalid[at] ^= 4;
            Files.write(rom.toPath(), invalid); require(GameMetadata.romTitle(rom).isEmpty());
        }
        Files.write(rom.toPath(), new byte[10]);
        require(GameMetadata.choose(rom, "Folder title", id).equals("smdh-game"));
        File version = new File(dir, "legacy-version");
        File game = new File(version, "game/game.3ds"); GameImport.mkdir(game.getParentFile());
        Files.write(game.toPath(), ncsd);
        require(GameMetadata.name(version, id).equals("Mario Kart 7"));
        require(!new File(version, "display-name.txt").exists());
        GameMetadata.store(version, "Stored title");
        require(GameMetadata.name(version, id).equals("Stored title"));
    }
    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("zakuro-import-check").toFile();
        try {
            String id = "00040000001B0000";
            checkSmdh(dir, id);
            require(GameMetadata.name(dir, id).equals("Game 1B0000"));
            require(GameMetadata.choose("Pokemon_Ultra_Moon.3DS", "Pocket Native", id).equals("Pokemon Ultra Moon"));
            require(GameMetadata.choose("game.3ds", "Mario Kart 7", id).equals("Mario Kart 7"));
            require(GameMetadata.choose(id + ".cci", "Pocket Native built", id).equals("Game 1B0000"));
            require(GameMetadata.choose("rom.cxi", id, id).equals("Game 1B0000"));
            GameMetadata.store(dir, "  Mario\nKart\u202e 7  ");
            require(GameMetadata.name(dir, id).equals("Mario Kart 7"));
            Files.write(new File(dir, "display-name.txt").toPath(), new byte[1025]);
            require(GameMetadata.name(dir, id).equals("Game 1B0000"));
            GameMetadata.store(dir, "x".repeat(10000));
            require(GameMetadata.name(dir, id).length() == 160);
            GameMetadata.store(dir, "\n\t");
            require(GameMetadata.name(dir, id).equals("Game 1B0000"));
            java.util.List<String> reports = new java.util.ArrayList<>();
            GameImport.CopyProgress throttle = new GameImport.CopyProgress(
                    (bytes, files, name) -> reports.add(bytes + ":" + files + ":" + name));
            throttle.report(1000, "game\n.3ds");
            throttle.report(250000999, "too soon");
            require(reports.size() == 1 && reports.get(0).equals("0:0:game .3ds"));
            throttle.report(250001000, "next");
            require(reports.size() == 2);
            GameImport.CopyProgress totals = new GameImport.CopyProgress(null);
            totals.update(65536, false, "game.3ds");
            totals.update(17, true, "game.3ds");
            totals.update(0, true, "empty texture");
            require(totals.bytes == 65553 && totals.files == 2);
            for (String name : new String[]{"..", ".", "../escape", "a/b", "a\\b", "", "x\0y"})
                rejects(() -> GameImport.child(dir, name));
            require(GameImport.child(dir, "texture.png").getParentFile().equals(dir));
            File elf = new File(dir, "title.so"); byte[] header = new byte[64];
            header[0] = 127; header[1] = 'E'; header[2] = 'L'; header[3] = 'F';
            header[4] = 2; header[5] = 1; header[16] = 3; header[18] = (byte) 183;
            Files.write(elf.toPath(), header); require(GameImport.arm64(elf));
            header[18] = 62; Files.write(elf.toPath(), header); require(!GameImport.arm64(elf));
            File rom = new File(dir, "game.3ds");
            try (RandomAccessFile f = new RandomAccessFile(rom, "rw")) {
                f.setLength(1024); f.seek(0x100); f.writeBytes("NCSD"); f.seek(0x120); f.write(1); f.seek(0x124); f.write(1);
                f.seek(0x300); f.writeBytes("NCCH"); f.seek(0x304); f.write(1); f.seek(0x318);
                f.write(new byte[]{0, 0, 0x1b, 0, 0, 0, 4, 0}); f.seek(0x38f); f.write(4);
            }
            require(GameImport.title(rom).equals("00040000001B0000"));
            try (RandomAccessFile f = new RandomAccessFile(rom, "rw")) { f.seek(0x38f); f.write(0); }
            rejects(() -> GameImport.title(rom));
            try (RandomAccessFile f = new RandomAccessFile(rom, "rw")) { f.setLength(10); }
            rejects(() -> GameImport.title(rom));
            File saves = new File(dir, "saves"); saves.mkdir();
            Files.write(new File(saves, "keep").toPath(), new byte[]{1});
            File stage = new File(dir, "stage"); stage.mkdir();
            // A normal directory named user must not prevent staging cleanup.
            new File(stage, "user").mkdir();
            GameImport.remove(stage); require(!stage.exists()); require(new File(saves, "keep").exists());
            System.out.println("Import boundary checks passed");
        } finally { GameImport.remove(dir); }
    }
}
