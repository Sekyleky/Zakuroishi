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
    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("zakuro-import-check").toFile();
        try {
            require(GameImport.wanted("game.3ds", false));
            require(GameImport.wanted("title.so", false));
            require(GameImport.wanted("texture.png", true));
            require(!GameImport.wanted("unrelated-large.zip", false));
            require(!GameImport.wanted("save.bin", false));
            for (String name : new String[]{"..", ".", "../escape", "a/b", "a\\b", "", "x\0y"})
                rejects(() -> GameImport.child(dir, name));
            require(GameImport.child(dir, "texture.png").getParentFile().equals(dir));
            File payload = new File(dir, "payload");
            File sysdata = new File(payload, "nested/sysdata"); GameImport.mkdir(sysdata);
            Files.write(new File(sysdata, "shared_font.bin").toPath(), new byte[]{1, 2});
            File data = new File(dir, "data"); GameImport.mkdir(data);
            File user = new File(data, "user"); GameImport.mkdir(user);
            Files.write(new File(user, "save.bin").toPath(), new byte[]{7});
            GameImport.installSysdata(payload, data);
            require(new File(data, "sysdata/shared_font.bin").isFile());
            require(!sysdata.exists());
            require(Files.readAllBytes(new File(user, "save.bin").toPath())[0] == 7);
            GameImport.mkdir(new File(payload, "first/sysdata"));
            GameImport.mkdir(new File(payload, "second/sysdata"));
            rejects(() -> GameImport.installSysdata(payload, data));
            File elf = new File(dir, "title.so"); byte[] header = new byte[64];
            header[0] = 127; header[1] = 'E'; header[2] = 'L'; header[3] = 'F';
            header[4] = 2; header[5] = 1; header[16] = 3; header[18] = (byte) 183;
            Files.write(elf.toPath(), header); require(GameImport.arm64(elf));
            header[18] = 62; Files.write(elf.toPath(), header); require(!GameImport.arm64(elf));
            File rom = new File(dir, "game.3ds");
            try (RandomAccessFile f = new RandomAccessFile(rom, "rw")) {
                f.setLength(1024); f.seek(0x100); f.writeBytes("NCSD"); f.seek(0x120); f.write(1);
                f.seek(0x300); f.writeBytes("NCCH"); f.seek(0x318);
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
