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
    static void write(File file, String text) throws IOException {
        file.getParentFile().mkdirs(); Files.write(file.toPath(), text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    static String snapshot(File root) throws IOException {
        java.util.List<String> names = new java.util.ArrayList<>();
        try (java.util.stream.Stream<java.nio.file.Path> walk = Files.walk(root.toPath())) {
            walk.forEach(path -> names.add(root.toPath().relativize(path) + (Files.isRegularFile(path) ? "=" + path.toFile().length() : "")));
        }
        java.util.Collections.sort(names); return String.join("|", names);
    }
    /** C3: every failing step rolls everything back; a failing rollback is reported as uncertain. */
    static void checkSwap(File dir) throws Exception {
        String[] parts = {"romfs", "romfs_ext", "code.ips"};
        for (int fail = 1; fail <= 5; fail++) { // five moves: romfs 2, romfs_ext 2, code.ips 1
            File work = new File(dir, "swap" + fail), staged = new File(work, "staged"), parent = new File(work, "mods");
            write(new File(staged, "romfs/new.bin"), "new romfs"); write(new File(staged, "romfs_ext/new.bin"), "new ext");
            write(new File(parent, "romfs/old.bin"), "old romfs"); write(new File(parent, "romfs_ext/old.bin"), "old ext"); write(new File(parent, "code.ips"), "old ips");
            String before = snapshot(work);
            int[] calls = {0}; final int failAt = fail;
            GameImport.Mover failing = (from, to) -> { if (++calls[0] == failAt) throw new IOException("injected"); GameImport.MOVE.move(from, to); };
            boolean[] uncertain = {false};
            try { GameImport.swapParts(staged, parent, parts, true, failing, uncertain); throw new AssertionError("swap " + failAt + " should fail"); }
            catch (IOException expected) { require(expected.getMessage().equals("injected")); }
            require(!uncertain[0]);
            require(snapshot(work).equals(before));
        }
        File work = new File(dir, "swap-ok"), staged = new File(work, "staged"), parent = new File(work, "mods");
        write(new File(staged, "romfs/new.bin"), "new romfs"); write(new File(parent, "romfs/old.bin"), "old romfs"); write(new File(parent, "code.ips"), "old ips");
        GameImport.swapParts(staged, parent, parts, true, GameImport.MOVE, new boolean[1]);
        require(new File(parent, "romfs/new.bin").isFile() && !new File(parent, "romfs/old.bin").exists() && !new File(parent, "code.ips").exists());
        require(java.util.Arrays.stream(parent.list()).noneMatch(n -> n.contains(".previous-")));
        // keeping the old parts (textures) leaves them as .previous-*
        File keep = new File(dir, "swap-keep"); write(new File(keep, "staged/textures/a.png"), "n"); write(new File(keep, "mods/textures/b.png"), "o");
        GameImport.swapParts(new File(keep, "staged"), new File(keep, "mods"), new String[]{"textures"}, false, GameImport.MOVE, new boolean[1]);
        require(java.util.Arrays.stream(new File(keep, "mods").list()).anyMatch(n -> n.startsWith("textures.previous-")));
        // a rollback that fails as well keeps the staging folder: uncertain
        File bad = new File(dir, "swap-bad"); write(new File(bad, "staged/romfs/n"), "n"); write(new File(bad, "mods/romfs/o"), "o"); write(new File(bad, "staged/romfs_ext/n"), "n");
        int[] n = {0}; boolean[] uncertain = {false};
        try {
            GameImport.swapParts(new File(bad, "staged"), new File(bad, "mods"), new String[]{"romfs", "romfs_ext"}, true,
                    (from, to) -> { if (++n[0] >= 3) throw new IOException("injected"); GameImport.MOVE.move(from, to); }, uncertain);
            throw new AssertionError();
        } catch (IOException expected) { require(uncertain[0]); }
    }
    /** Adding a mod merges its romfs over the current one (case-insensitive, newer wins) and keeps old code; failures roll back. */
    static void checkAddMods(File dir) throws Exception {
        File work = new File(dir, "add"), stage = new File(work, "stage"), staged = new File(stage, "mods"), parent = new File(work, "mods");
        write(new File(parent, "romfs/Data/old.bin"), "old"); write(new File(parent, "romfs/Data/both.bin"), "old"); write(new File(parent, "code.ips"), "ips");
        write(new File(parent, "romfs_ext/x.ips"), "ext");
        write(new File(staged, "romfs/data/BOTH.bin"), "new"); write(new File(staged, "romfs/data/new.bin"), "new");
        String before = snapshot(parent);
        String[] parts = GameImport.mergeStaged(stage, staged, parent, GameImport.MOD_PARTS);
        require(java.util.Arrays.equals(parts, new String[]{"romfs"})); // no romfs_ext or code in the new mod: those stay
        require(snapshot(parent).equals(before)); // nothing live touched before the swap
        GameImport.swapParts(staged, parent, parts, true, GameImport.MOVE, new boolean[1]);
        File data = new File(parent, "romfs/Data");
        require(data.isDirectory() && new File(data, "old.bin").isFile() && new File(data, "new.bin").isFile());
        require(java.util.Arrays.asList(data.list()).contains("BOTH.bin") && !java.util.Arrays.asList(data.list()).contains("both.bin")); // listing, not exists(): Windows ignores case
        require(new File(parent, "code.ips").isFile() && new File(parent, "romfs_ext/x.ips").isFile());
        // removing keeps everything that is not a mod part
        write(new File(parent, "textures/a.png"), "png");
        GameImport.swapParts(new File(parent, ".none"), parent, GameImport.MOD_PARTS, true, GameImport.MOVE, new boolean[1]);
        File v = new File(work, "v"); require(!GameImport.hasMods(v, "ID")); write(new File(v, "data/mods/ID/exheader.bin"), "x"); require(GameImport.hasMods(v, "ID"));
        require(new File(parent, "textures/a.png").isFile() && !new File(parent, "romfs").exists() && !new File(parent, "code.ips").exists());
        require(java.util.Arrays.stream(parent.list()).noneMatch(n -> n.contains(".previous-")));
    }
    /** C1: what the storage screen may offer and delete. */
    static void checkStorage(File dir) throws Exception {
        File files = new File(dir, "storage-files"), imports = new File(files, "imports"), saves = new File(files, "savedata/00040000001B5100");
        String active = "11111111-1111-1111-1111-111111111111", old = "22222222-2222-2222-2222-222222222222", holds = "33333333-3333-3333-3333-333333333333",
                staging = "44444444-4444-4444-4444-444444444444", link = "55555555-5555-5555-5555-555555555555", u1 = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                u2 = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", u3 = "cccccccc-cccc-cccc-cccc-cccccccccccc";
        String mods = "/data/mods/00040000001B5100/";
        write(new File(saves, "user/save.bin"), "SAVE"); write(new File(saves, "user.previous/save.bin"), "SAVE0"); write(new File(saves, "user.retired-" + u1 + "/save.bin"), "SAVE1");
        write(new File(saves, "save-stage-x/user/save.bin"), "SAVE2"); write(new File(saves, "code/title.so.previous-" + u1), "bait");
        write(new File(imports, "00040000001B5100.active"), active);
        write(new File(imports, active + "/code/title.so"), "so"); write(new File(imports, active + "/code/title.so.previous-" + u1), "oldso");
        write(new File(imports, active + "/code/title.so.previous-nope"), "x"); write(new File(imports, active + "/code/title.so.bak"), "x");
        write(new File(imports, active + mods + "textures.previous-" + u2 + "/a.png"), "png");
        write(new File(imports, active + mods + "romfs.previous-" + u3 + "/a.bin"), "bin");
        write(new File(imports, active + mods + "textures/a.png"), "live");
        write(new File(imports, active + "/data/states/00040000001B5100/0.state"), "state");
        write(new File(imports, old + "/code/title.so"), "so"); write(new File(imports, old + "/data/states/00040000001B5100/0.state"), "state");
        write(new File(imports, holds + "/data/user/real.sav"), "REAL SAVE");
        write(new File(imports, "not-a-uuid/x"), "x");
        write(new File(files, "component-imports/" + staging + "/data/mods/x"), "stage");
        boolean links = true;
        try {
            Files.createSymbolicLink(new File(imports, old + "/data/user").toPath(), new File(saves, "user").toPath());
            Files.createSymbolicLink(new File(imports, active + "/data/user").toPath(), new File(saves, "user").toPath());
            Files.createSymbolicLink(new File(imports, link).toPath(), new File(files, "savedata").toPath());
        } catch (UnsupportedOperationException | IOException | SecurityException e) { links = false; System.out.println("(symlinks unavailable here; symlink cases skipped)"); }
        String savesBefore = snapshot(new File(files, "savedata"));
        java.util.Set<String> listed = new java.util.TreeSet<>();
        for (StorageDialog.Item item : StorageDialog.scan(files)) listed.add(files.getCanonicalFile().toPath().relativize(item.file.toPath()).toString().replace('\\', '/'));
        java.util.Set<String> expected = new java.util.TreeSet<>(java.util.Arrays.asList(
                "imports/" + old, "imports/" + active + "/code/title.so.previous-" + u1,
                "imports/" + active + mods + "textures.previous-" + u2, "imports/" + active + mods + "romfs.previous-" + u3,
                "component-imports/" + staging));
        require(listed.equals(expected));
        for (String path : listed) require(!path.startsWith("savedata") && !path.contains("/data/user") && !path.contains("user.") && !path.contains("/states"));
        for (StorageDialog.Item item : StorageDialog.scan(files)) if (item.file.getName().equals(old)) require(item.warning != null && item.warning.contains("quick save states") && item.bytes > 0);
        // refused: the active import, its live files, saves, the symlinked tree, a folder that may hold saves, a name that is not listed
        for (File refused : new File[]{new File(imports, active), new File(imports, active + "/code/title.so"), new File(imports, active + mods + "textures"),
                new File(files, "savedata"), saves, new File(saves, "user"), new File(saves, "code/title.so.previous-" + u1), new File(imports, holds), new File(imports, link), new File(imports, "not-a-uuid")}) {
            try { StorageDialog.delete(files, refused, () -> null); throw new AssertionError("Deleted " + refused); } catch (IOException expectedRefusal) { }
        }
        require(snapshot(new File(files, "savedata")).equals(savesBefore) && new File(imports, active + "/code/title.so").isFile() && new File(imports, holds + "/data/user/real.sav").isFile());
        try { StorageDialog.delete(files, new File(files.getCanonicalFile(), "imports/" + old), () -> "A game is open."); throw new AssertionError("Deleted while blocked"); }
        catch (IOException expectedRefusal) { require(expectedRefusal.getMessage().equals("A game is open.")); }
        require(new File(imports, old).isDirectory());
        for (String path : expected) StorageDialog.delete(files, new File(files.getCanonicalFile(), path), () -> null);
        for (String path : expected) require(!new File(files, path).exists());
        require(snapshot(new File(files, "savedata")).equals(savesBefore));
        require(new File(imports, active + "/code/title.so").isFile() && new File(imports, active + "/data/states/00040000001B5100/0.state").isFile());
        if (links) require(Files.isSymbolicLink(new File(imports, active + "/data/user").toPath()) && new File(saves, "user/save.bin").isFile());
        // a pointer that cannot be read means nothing is offered
        write(new File(imports, "00040000001B5100.active"), "garbage");
        try { StorageDialog.candidates(files); throw new AssertionError("Listed with a bad pointer"); } catch (IOException expectedRefusal) { }
    }
    /** J8: the exported form is accepted back unchanged; anything unknown or out of range refuses the whole file. */
    @SuppressWarnings("unchecked")
    static void checkSettings() throws Exception {
        java.util.function.Function<String, GameSettings> fresh = id -> GameSettings.from(java.util.Collections.emptyMap(), id);
        GameSettings a = fresh.apply("00040000001B5100");
        a.resolution = 3; a.textures = false; a.values.put("deadzone", 0.3f); a.values.put("filter", "sharp"); a.values.put("touch_controls", true); a.values.put("frame_cap", 144);
        a.controllerBindings.put("96", "a"); a.controllerBindings.put("97", "fast_forward"); a.enhancements.add("Some enhancement"); a.driver = "01234567-89ab-cdef-0123-456789abcdef";
        GameSettings b = fresh.apply("00040000001B5200"); b.values.put("custom_driver", true);
        java.util.Map<String, Object> exported = SettingsTransfer.toMap(java.util.Arrays.asList(a, b));
        require(!exported.toString().contains("01234567") && !exported.toString().contains("custom_driver"));
        java.util.Map<String, GameSettings> back = SettingsTransfer.parse(exported, fresh);
        GameSettings a2 = back.get("00040000001B5100");
        require(a2.resolution == 3 && !a2.textures && a2.values.equals(a.values) && a2.controllerBindings.equals(a.controllerBindings) && a2.enhancements.equals(a.enhancements));
        require(a2.driver.isEmpty() && !back.get("00040000001B5200").flag("custom_driver"));
        require(SettingsTransfer.toMap(back.values()).equals(exported));
        // the importing device keeps what the file does not say
        java.util.Map<String, Object> small = new java.util.LinkedHashMap<>(), games = new java.util.LinkedHashMap<>(), game = new java.util.LinkedHashMap<>(), advanced = new java.util.LinkedHashMap<>();
        advanced.put("volume", 0.5); game.put("advanced", advanced); games.put("00040000001B5100", game); small.put("format", "zakuroishi-settings"); small.put("version", 1); small.put("games", games);
        GameSettings current = fresh.apply("00040000001B5100"); current.resolution = 2; current.driver = "01234567-89ab-cdef-0123-456789abcdef";
        GameSettings merged = SettingsTransfer.parse(small, id -> current).get("00040000001B5100");
        require(merged.resolution == 2 && merged.driver.equals(current.driver) && merged.number("volume") == 0.5f && current.number("volume") == 1f);
        // each of these changes one thing in an otherwise valid export and must refuse the whole file
        String[] bad = {"surprise", "resolution=4", "volume=2", "filter=blurry", "touch_controls=1", "nonsense=1", "custom_driver=true", "resolution=1", "binding 99999=a", "binding 5=teleport", "binding x=a", "enhancements=", "id"};
        for (String change : bad) {
            java.util.Map<String, Object> allGames = new java.util.LinkedHashMap<>((java.util.Map<String, Object>) exported.get("games")), root = new java.util.LinkedHashMap<>(exported);
            java.util.Map<String, Object> first = new java.util.LinkedHashMap<>((java.util.Map<String, Object>) allGames.get("00040000001B5100"));
            java.util.Map<String, Object> adv = new java.util.LinkedHashMap<>((java.util.Map<String, Object>) first.get("advanced"));
            if (change.equals("surprise")) first.put("surprise", 1);
            else if (change.equals("resolution=4")) first.put("resolution", 4);
            else if (change.equals("enhancements=")) first.put("enhancements", java.util.Collections.singletonList(""));
            else if (change.startsWith("binding ")) first.put("controller_bindings", java.util.Collections.singletonMap(change.substring(8).split("=")[0], change.split("=")[1]));
            else if (change.equals("id")) { allGames.remove("00040000001B5100"); allGames.put("00040000001b5100", first); }
            else {
                String[] kv = change.split("=");
                adv.put(kv[0], kv[1].equals("true") ? (Object) Boolean.TRUE : kv[1].matches("[0-9.]+") ? (Object) Double.valueOf(kv[1]) : kv[1]); first.put("advanced", adv);
            }
            if (!change.equals("id")) allGames.put("00040000001B5100", first);
            root.put("games", allGames);
            try { SettingsTransfer.parse(root, fresh); throw new AssertionError("Accepted " + change); }
            catch (IllegalArgumentException expectedRefusal) { }
        }
        java.util.Map<String, Object> wrong = new java.util.LinkedHashMap<>(exported); wrong.put("format", "other");
        try { SettingsTransfer.parse(wrong, fresh); throw new AssertionError(); } catch (IllegalArgumentException expectedRefusal) { }
    }
    /** B1/B3/B4, C2, J3, J7. */
    static void checkSmallThings(File dir) throws Exception {
        require(GameImport.margin(0) == 256L << 20 && GameImport.margin(4L << 30) == 256L << 20 && GameImport.margin(10L << 30) == (10L << 30) / 20 && GameImport.margin(100L << 20) == 256L << 20);
        require(GameImport.outOfSpace(new IOException("write failed: ENOSPC (No space left on device)")) && GameImport.outOfSpace(new IOException("copy", new IOException("No space left on device")))
                && !GameImport.outOfSpace(new IOException("Source file changed")) && !GameImport.outOfSpace(new IOException()));
        String redacted = Diagnostics.redact("W/x: opened content://com.android.externalstorage.documents/tree/primary%3APrivate%20Folder/document/a and file:///storage/emulated/0/Roms/x.3ds done");
        require(!redacted.contains("Private") && !redacted.contains("Roms") && redacted.contains("opened <uri removed> and <uri removed> done"));
        File old = new File(dir, "old.so"), current = new File(dir, "current.so");
        Files.write(old.toPath(), NativeFixture.elf(4, false)); Files.write(current.toPath(), NativeFixture.elf(5, false, NativeLibrary.GENERATION));
        StringBuilder hex = new StringBuilder();
        for (byte value : java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(old.toPath()))) hex.append(String.format("%02x", value));
        require(GameImport.sha256(old).equals(hex.toString()) && hex.length() == 64);
        String report = GameImport.codeReport(old);
        require(report.contains(hex.substring(0, 32)) && report.contains(hex.substring(32)) && report.contains("zakuro-build-title") && report.contains("identifies the build") && report.contains("runs inside"));
        require(!GameImport.codeReport(current).contains("older generator"));
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
            Files.write(elf.toPath(), NativeFixture.elf(4, false));
            require(GameImport.arm64(elf));
            try (java.nio.channels.SeekableByteChannel source = Files.newByteChannel(elf.toPath())) {
                NativeLibrary.inspect(source).requireCompatible();
            }
            File rom = new File(dir, "game.3ds");
            byte[] ncch = NativeFixture.rom(0xA00, Long.parseUnsignedLong(id, 16));
            byte[] cartridge = new byte[512 + ncch.length];
            put32(cartridge, 0x100, 0x4453434e); put32(cartridge, 0x120, 1); put32(cartridge, 0x124, ncch.length / 512);
            System.arraycopy(ncch, 0, cartridge, 512, ncch.length); Files.write(rom.toPath(), cartridge);
            require(GameImport.title(rom).equals("00040000001B0000"));
            try (RandomAccessFile f = new RandomAccessFile(rom, "rw")) { f.seek(0x38f); f.write(0); }
            rejects(() -> GameImport.title(rom));
            byte[] headerOnly = java.util.Arrays.copyOf(ncch, 512); put32(headerOnly, 0x104, 1);
            put32(headerOnly, 0x180, 0); put32(headerOnly, 0x1a0, 0); put32(headerOnly, 0x1a4, 0);
            Files.write(rom.toPath(), headerOnly); rejects(() -> GameImport.title(rom));
            try (RandomAccessFile f = new RandomAccessFile(rom, "rw")) { f.setLength(10); }
            rejects(() -> GameImport.title(rom));
            File saves = new File(dir, "saves"); saves.mkdir();
            Files.write(new File(saves, "keep").toPath(), new byte[]{1});
            File stage = new File(dir, "stage"); stage.mkdir();
            // A normal directory named user must not prevent staging cleanup.
            new File(stage, "user").mkdir();
            GameImport.remove(stage); require(!stage.exists()); require(new File(saves, "keep").exists());
            checkSwap(dir); checkAddMods(dir); checkStorage(dir); checkSettings(); checkSmallThings(dir);
            System.out.println("Import boundary checks passed (swap rollback, storage selection, settings round trip, margin, hashes)");
        } finally { GameImport.remove(dir); }
    }
}
