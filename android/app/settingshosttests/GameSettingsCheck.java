package io.github.zakuro.android;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/** Pure host checks: no Android runtime or test framework required. */
public final class GameSettingsCheck {
    private static void require(boolean value) { if (!value) throw new AssertionError(); }
    private static Set<String> keysOf() { Set<String> keys=new HashSet<>(); for(GameSettings.Setting s:GameSettings.ALL) keys.add(s.key); return keys; }
    static Map<String,Object> persisted;
    static boolean commitOK = true;
    public static final class HostContext extends android.content.ContextWrapper {
        HostContext() { super(null); }
        @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode) {
            require(name.equals("games"));
            Map<String,Object> pending=new HashMap<>();
            android.content.SharedPreferences.Editor editor=(android.content.SharedPreferences.Editor)java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),new Class[]{android.content.SharedPreferences.Editor.class},(proxy,method,args) -> {
                    if(method.getName().equals("commit")) {
                        if(commitOK) for(Map.Entry<String,Object> e:pending.entrySet()) { if(e.getValue()==null) persisted.remove(e.getKey()); else persisted.put(e.getKey(),e.getValue()); }
                        return commitOK;
                    }
                    if(method.getName().startsWith("put")) { pending.put((String)args[0],args[1]); return proxy; }
                    if(method.getName().equals("remove")) { pending.put((String)args[0],null); return proxy; }
                    throw new UnsupportedOperationException(method.getName());
                });
            return (android.content.SharedPreferences)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{android.content.SharedPreferences.class},(proxy,method,args) -> {
                    if(method.getName().equals("edit")) return editor;
                    if(method.getName().equals("getAll")) return persisted;
                    throw new UnsupportedOperationException(method.getName());
                });
        }
    }
    public static void main(String[] args) throws Exception {
        byte[] pe = new byte[128]; pe[0] = 'M'; pe[1] = 'Z'; pe[60] = 64; pe[64] = 'P'; pe[65] = 'E';
        require(LosslessImport.looksLikePe(pe, pe.length) && !LosslessImport.looksLikePe(pe, 66) && !LosslessImport.looksLikePe(new byte[128], 128));
        pe[60] = -1; pe[63] = -1; require(!LosslessImport.looksLikePe(pe, pe.length));
        String id = "0004000000123400";
        Map<String,Object> prefs = new HashMap<>();
        prefs.put(id + ".name", "Existing title");
        prefs.put(id + ".resolution", 3);
        prefs.put(id + ".textures", false);
        GameSettings original = GameSettings.from(prefs,id);
        require(original.name.equals("Existing title") && original.resolution == 3 && !original.textures);
        require(original.values.get("shader_mode").equals("auto") && original.values.get("shader_execution").equals("compiled"));
        require(original.values.get("shader_compilation").equals("async"));
        require(original.flag("cpu_shader_jit") && original.flag("depth_blits") && !original.flag("generic_shaders") && !original.flag("float_depth") && !original.flag("static_blend"));
        GameSettings shaders=original.copy();
        shaders.values.put("shader_mode","cpu"); shaders.values.put("shader_execution","interpreted"); shaders.values.put("cpu_shader_jit",false);
        require(shaders.json().contains("\"shader_execution\":\"interpreted\"") && shaders.json().contains("\"cpu_shader_jit\":false"));
        require(original.values.get("shader_execution").equals("compiled") && original.flag("cpu_shader_jit"));
        require(original.flag("aot") && original.flag("motion_enabled"));
        require(original.values.get("speed_limit").equals(100) && original.json().contains("\"speed_limit\":100"));
        require(original.values.get("fast_forward_speed").equals(150));
        GameSettings hotkey=original.copy();
        hotkey.controllerBindings.put("107","fast_forward");
        require(hotkey.json().contains("\"107\":\"fast_forward\""));
        GameSettings.Setting speedLimit = null;
        for (GameSettings.Setting setting : GameSettings.ALL) if (setting.key.equals("speed_limit")) speedLimit = setting;
        require(speedLimit != null && speedLimit.group.equals("Performance") && speedLimit.initial.equals(100));
        require(speedLimit.normalize(-1).equals(0) && speedLimit.normalize(999).equals(400));
        require(speedLimit.normalize(0).equals(0) && speedLimit.normalize(400).equals(400));
        GameSettings cancelled = original.copy();
        cancelled.values.put("volume",0f); cancelled.resolution=1; cancelled.textures=true;
        cancelled.enhancements.add("test");
        cancelled.controllerBindings.put("96","b");
        require(original.controllerBindings.isEmpty());
        require(original.number("volume")==1f && original.resolution==3 && !original.textures && original.enhancements.isEmpty());
        require(prefs.size()==3);
        // Allocate without android.jar's intentionally throwing framework constructor.
        java.lang.reflect.Field field=sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        HostContext context=(HostContext)((sun.misc.Unsafe)field.get(null)).allocateInstance(HostContext.class);
        persisted=new HashMap<>(prefs);
        Map<String,Object> shaderValues=new LinkedHashMap<>();
        shaderValues.put("shader_mode","cpu"); shaderValues.put("shader_execution","interpreted");
        shaderValues.put("cpu_shader_jit",false); shaderValues.put("shader_compilation","wait");
        shaderValues.put("generic_shaders",true); shaderValues.put("depth_blits",false);
        shaderValues.put("float_depth",true); shaderValues.put("static_blend",true);
        shaders.values.putAll(shaderValues); shaders.save(context);
        GameSettings shaderRoundtrip=GameSettings.from(persisted,id);
        for(Map.Entry<String,Object> entry:shaderValues.entrySet()) {
            require(shaderRoundtrip.values.get(entry.getKey()).equals(entry.getValue()));
            String encoded=entry.getValue() instanceof String ? "\""+entry.getValue()+"\"" : entry.getValue().toString();
            require(shaderRoundtrip.json().contains("\""+entry.getKey()+"\":"+encoded));
        }
        require(shaderRoundtrip.flag("aot"));
        shaders.values.put("speed_limit", 400);
        shaders.save(context);
        require(persisted.get(id + ".advanced.speed_limit").equals(400));
        require(GameSettings.from(persisted,id).json().contains("\"speed_limit\":400"));
        for(GameSettings.Setting setting:GameSettings.ALL) if(shaderValues.containsKey(setting.key))
            require(setting.normalize("invalid").equals(setting.initial));
        original.save(context);
        require(persisted.get(id+".resolution").equals(3) && persisted.get(id+".textures").equals(false));
        // diff-only: a value equal to the inherited one is not stored
        require(persisted.get(id+".name").equals("Existing title") && !persisted.containsKey(id+".advanced.volume"));
        require(!persisted.containsKey(id+".advanced.aot") && !persisted.containsKey(id+".advanced.controller_bindings"));
        cancelled.save(context);
        require(!persisted.containsKey(id+".resolution") && persisted.get(id+".textures").equals(true));
        require(persisted.get(id+".advanced.volume").equals(0f));
        GameSettings roundtrip=GameSettings.from(persisted,id);
        require(roundtrip.controllerBindings.get("96").equals("b"));
        require(roundtrip.json().contains("\"controller_bindings\":{\"96\":\"b\"}"));
        Map<String,Object> hotkeys=new HashMap<>(persisted); // every hotkey target survives a reload, an unknown one is dropped
        hotkeys.put(id+".advanced.controller_bindings",new HashSet<>(Arrays.asList("1=quick_save","2=quick_load","3=swap_screens","4=screenshot","5=pause_menu","6=teleport")));
        require(GameSettings.from(hotkeys,id).controllerBindings.keySet().equals(new HashSet<>(Arrays.asList("1","2","3","4","5"))));
        commitOK=false;
        try { original.save(context); throw new AssertionError("Failed commit accepted"); }
        catch(IllegalStateException expected) {}
        commitOK=true;
        // All games: inherit, override, reset, migration
        String D=GameSettings.DEFAULTS, other="0004000000BEEF00";
        persisted=new HashMap<>();
        GameSettings all=GameSettings.load(context,D);
        require(all.resolution==1 && all.number("volume")==1f && !all.changed(GameSettings.ALL[0]) && Boolean.TRUE.equals(persisted.get(GameSettings.MIGRATED)));
        all.resolution=2; all.values.put("frame_cap",30); all.values.put("layout","side_by_side"); all.controllerBindings.put("99","a"); all.save(context);
        require(persisted.get(D+".resolution").equals(2) && persisted.get(D+".advanced.frame_cap").equals(30) && !persisted.containsKey(D+".advanced.volume"));
        GameSettings game=GameSettings.load(context,other);
        require(game.resolution==2 && game.values.get("frame_cap").equals(30) && game.values.get("layout").equals("side_by_side"));
        require(game.controllerBindings.equals(Collections.singletonMap("99","a")) && !game.changedResolution() && !game.changedBindings());
        game.values.put("frame_cap",60); game.values.put("layout","side_by_side"); game.save(context);
        require(persisted.get(other+".advanced.frame_cap").equals(60) && !persisted.containsKey(other+".advanced.layout") && !persisted.containsKey(other+".resolution"));
        GameSettings.Setting cap=null; for(GameSettings.Setting s:GameSettings.ALL) if(s.key.equals("frame_cap")) cap=s;
        game=GameSettings.load(context,other); require(game.values.get("frame_cap").equals(60) && game.changed(cap));
        all=GameSettings.load(context,D); all.values.put("layout","top_only"); all.save(context);
        require(GameSettings.load(context,other).values.get("layout").equals("top_only")); // defaults still reach the game
        game.useDefaults(); require(game.values.get("frame_cap").equals(30) && !game.changed(cap)); game.save(context);
        require(!persisted.containsKey(other+".advanced.frame_cap"));
        // presets: one-shot edits of the draft, matched back by value
        require(GameSettings.SIMPLE.size()==12 && keysOf().containsAll(GameSettings.SIMPLE));
        GameSettings tuned=game.copy();
        for(int i=0;i<GameSettings.PRESETS.length;i++) { tuned.preset(i); require(tuned.preset(true)==i && tuned.preset(false)==i); }
        tuned.preset(0); require(tuned.resolution==1 && !tuned.textures && tuned.values.get("frame_cap").equals(30));
        tuned.textures=true; require(tuned.preset(true)==-1 && tuned.preset(false)==0);
        tuned.values.put("interpolate",true); require(tuned.preset(false)==-1);
        // custom_driver stays per game
        all.values.put("custom_driver",true); all.save(context); require(!GameSettings.load(context,other).flag("custom_driver"));
        // migration: old saves wrote every value; ones equal to their initial are dropped once, real choices stay
        persisted=new HashMap<>();
        persisted.put(other+".resolution",1); persisted.put(other+".advanced.volume",1f); persisted.put(other+".advanced.frame_cap",30);
        persisted.put(other+".advanced.controller_bindings",new HashSet<String>()); persisted.put(other+".name","Kept"); persisted.put(other+".textures",true);
        persisted.put(D+".advanced.frame_cap",60);
        GameSettings migrated=GameSettings.load(context,other);
        require(persisted.get(other+".resolution").equals(1) && !persisted.containsKey(other+".advanced.volume") && !persisted.containsKey(other+".advanced.controller_bindings"));
        require(persisted.get(other+".advanced.frame_cap").equals(30) && persisted.get(other+".name").equals("Kept") && persisted.containsKey(other+".textures"));
        require(persisted.get(D+".advanced.frame_cap").equals(60) && migrated.values.get("frame_cap").equals(30));
        persisted.put(other+".advanced.volume",1f); GameSettings.load(context,other);
        require(persisted.containsKey(other+".advanced.volume")); // runs once only
        persisted=new HashMap<>(prefs);
        prefs.put(id + ".advanced.volume",Float.NaN);
        prefs.put(id + ".advanced.deadzone",99f);
        prefs.put(id + ".advanced.layout","invalid");
        prefs.put(id + ".advanced.speed_limit", 999);
        GameSettings bounded=GameSettings.from(prefs,id);
        require(bounded.number("volume")==1f && bounded.number("deadzone")==0.9f);
        require(bounded.values.get("speed_limit").equals(400));
        require(bounded.values.get("layout").equals("stacked"));
        bounded.enhancements.add("Quoted \"name\"\n");
        String json=bounded.json();
        require(json.contains("\"aot\":true") && json.contains("\"frame_cap\":60"));
        require(json.contains("Quoted \\\"name\\\"\\u000a"));
        // ROM-only import: no title.so, so native always gets the interpreter
        require(bounded.flag("aot") && bounded.json(false).contains("\"aot\":false") && bounded.json(true).contains("\"aot\":true"));
        Set<String> groups=new HashSet<>(Arrays.asList(GameSettings.GROUPS));
        Set<String> keys=new HashSet<>();
        for(GameSettings.Setting setting:GameSettings.ALL) {
            require(groups.contains(setting.group) && keys.add(setting.key));
            require(setting.normalize(setting.initial).equals(setting.initial));
        }
        File version=Files.createTempDirectory("settings-pack-").toFile();
        File pack=new File(version,"data/mods/"+id+"/textures");
        require(!GameSettings.hasTextures(version,id)); require(pack.mkdirs());
        require(GameSettings.hasTextures(version,id));
        require(!GameSettings.hasNativeCode(version) && !GameSettings.hasNativeCode(null));
        File code=new File(version,"code/title.so"); require(code.getParentFile().mkdirs() && code.createNewFile());
        require(GameSettings.hasNativeCode(version)); code.delete(); code.getParentFile().delete();
        require(original.textures==false); // Presence never changes a saved opt-out.
        pack.delete(); pack.getParentFile().delete(); pack.getParentFile().getParentFile().delete();
        new File(version,"data").delete(); version.delete();
        try { GameSettings.from(prefs,"../bad"); throw new AssertionError(); }
        catch(IllegalArgumentException expected) {}
        System.out.println("GameSettings host checks passed: legacy preferences, draft isolation/cancel, types, JSON, optional pack, eight shader settings round-trip, all-games defaults (inherit, override, reset, migration, diff-only save), presets, ROM-only aot.");
    }
}
