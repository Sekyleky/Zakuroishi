package io.github.zakuro.android;

import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Cross-language contract check against the actual source shipped in this checkout. */
public final class SettingsBridgeCheck {
    public static void main(String[] args) throws Exception {
        Path root=Paths.get(args[0]);
        String java=Files.readString(root.resolve("android/app/src/main/java/io/github/zakuro/android/Game.java"));
        String rust=Files.readString(root.resolve("crates/zakuro-android/src/bridge.rs"));
        String platform=Files.readString(root.resolve("crates/zakuro-android/src/platform.rs"));
        String settings=Files.readString(root.resolve("crates/zakuro-android/src/settings.rs"));
        String runtime=Files.readString(root.resolve("crates/zakuro-android/src/runtime.rs"));
        String hardware=Files.readString(root.resolve("crates/zakuro-gpu/src/raster/hardware.rs"));
        String jit=Files.readString(root.resolve("crates/zakuro-gpu/src/shader/jit.rs"));
        // the app runs from API 30: InputStream.readNBytes/readAllBytes/transferTo are API 33 (GameImport.readUpTo instead)
        try(java.util.stream.Stream<Path> sources=Files.list(root.resolve("android/app/src/main/java/io/github/zakuro/android"))) {
            for(Path source:(Iterable<Path>)sources::iterator) {
                java.util.regex.Matcher late=Pattern.compile("\\.readNBytes\\(|\\.transferTo\\(|(?<!Files)\\.readAllBytes\\(\\)").matcher(Files.readString(source));
                if(late.find()) throw new AssertionError(source.getFileName()+" calls an API 33 stream method: "+late.group());
            }
        }
        if(!runtime.contains("set_renderer_options(settings.renderer_options())"))
            throw new AssertionError("Runtime does not install advanced renderer options");
        if(!runtime.contains("request_wait_idle: settings.request_wait_idle"))
            throw new AssertionError("Runtime does not pass request_wait_idle to the core config");
        // local wireless: the Java choices are the Rust RoomMode, the runtime gives the game the room, stable never asks for one
        String launch=Files.readString(root.resolve("android/app/src/main/java/io/github/zakuro/android/GameLaunch.java"));
        for(GameSettings.Setting setting:GameSettings.ALL) if(setting.key.equals("room_mode") && !String.join("|",setting.choices).equals("off|join|host"))
            throw new AssertionError("room_mode choices differ from Rust RoomMode");
        if(!settings.contains("choice!(RoomMode, Off, Off, Join, Host);") || !runtime.contains("system.room = Some(room.link());")
                || !launch.contains("if (!GameImport.nightly(context)) settings.values.put(\"room_mode\",\"off\");"))
            throw new AssertionError("Room settings are not wired: RoomMode, runtime room link or the stable gate");
        if(!launch.contains("intent.putExtra(\"console_id\",consoleId(context))") || !platform.contains("extra(\"console_id\")") || !runtime.contains("system.config.console_id = id;"))
            throw new AssertionError("The install's console number does not reach the core in a room");
        String[][] shaderFields={{"shader_mode","gpu_shaders"},{"shader_execution","translate_shaders"},
            {"cpu_shader_jit","cpu_shader_jit"},{"shader_compilation","wait_for_shaders"},
            {"generic_shaders","generic_shaders"},{"depth_blits","depth_blits"},
            {"float_depth","float_depth"},{"static_blend","static_blend"}};
        for(String[] pair:shaderFields) {
            if(!Pattern.compile("\\b"+pair[1]+"\\s*:\\s*(?:match\\s+)?self\\."+pair[0]+"\\b").matcher(settings).find()
                    || !(hardware+jit).contains("options."+pair[1]))
                throw new AssertionError("Missing native shader consumer: "+pair[0]);
        }
        String[] commands={"Pause","Save","Load","Restart","Recalibrate","Swap","Resume","Exit","LeaveRoom","JoinRoom","ReloadCheats"};
        for(int i=0;i<commands.length;i++) {
            if(!Pattern.compile("\\b"+commands[i].toUpperCase(Locale.ROOT)+"\\s*=\\s*"+i+"\\b").matcher(java).find()
                    || !Pattern.compile("\\b"+i+"\\s*=>\\s*Command::"+commands[i]+"\\b").matcher(rust).find())
                throw new AssertionError("Java/Rust command mismatch: "+commands[i]);
        }
        for(String callback:new String[]{"updateNativeStats","showNativeMessage","showNativeError","showPauseMenu","closeSecondDisplay","showKeyboard","runHotkey"})
            if(!java.contains("void "+callback+"(") || !platform.contains("\""+callback+"\""))
                throw new AssertionError("Missing callback: "+callback);
        if (!java.contains("String prepareSaves()") || !platform.contains("\"prepareSaves\", \"()Ljava/lang/String;\""))
            throw new AssertionError("Missing save-access startup callback");
        if (!java.contains("void startPerformanceHints(int[] tids, long targetNanos)")
                || !platform.contains("\"([IJ)V\"") || !platform.contains("JValue::Long(target_nanos)"))
            throw new AssertionError("Performance hint target signature mismatch");
        for(GameSettings.Setting setting:GameSettings.ALL) {
            if(setting.key.equals("show_clock") || Arrays.asList("touch_controls","touch_auto_hide","show_battery","show_pause_button","orientation").contains(setting.key)) continue; // Java overlay settings.
            if(!Pattern.compile("pub\\s+"+setting.key+"\\s*:").matcher(settings).find())
                throw new AssertionError("No native advanced setting: "+setting.key);
        }
        // Binding targets: the Java list is exactly the Rust Binding variants, and every hotkey name has a case in Game.runHotkey.
        Matcher enumMatch=Pattern.compile("choice!\\(\\s*Binding,\\s*None,([^)]*)\\)").matcher(settings);
        if(!enumMatch.find()) throw new AssertionError("No Rust Binding enum");
        Set<String> variants=new TreeSet<>();
        for(String variant:enumMatch.group(1).split(",")) if(!variant.isBlank()) variants.add(variant.trim().replaceAll("(?<=.)([A-Z])","_$1").toLowerCase(Locale.ROOT)); // serde snake_case: CUp is c_up
        if(!variants.equals(new TreeSet<>(Arrays.asList(GameSettings.BINDING_TARGETS))))
            throw new AssertionError("Binding targets differ: Java "+new TreeSet<>(Arrays.asList(GameSettings.BINDING_TARGETS))+" Rust "+variants);
        Matcher hotkey=Pattern.compile("=> Some\\(\"([a-z_]+)\"\\)").matcher(settings); int hotkeys=0;
        while(hotkey.find()) { hotkeys++; if(!java.contains("case \""+hotkey.group(1)+"\":")) throw new AssertionError("Game.runHotkey does not handle "+hotkey.group(1)); }
        if(hotkeys!=5) throw new AssertionError("Expected five hotkeys, found "+hotkeys);
        for(String method:new String[]{"nativeCommand","nativeTouch","nativeStick","nativeButton","nativeSecondSurface","nativeSecondTouch","nativeKeyboard"})
            if(!java.contains("void "+method+"(") || !rust.contains("Game_"+method+"("))
                throw new AssertionError("Missing JNI export: "+method);
        System.out.println("Java/native contracts passed: eleven command IDs, JNI exports, platform callbacks, advanced keys, eight shader consumer mappings, binding targets, hotkeys and room settings.");
    }
}
