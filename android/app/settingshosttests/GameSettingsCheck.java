package io.github.zakuro.android;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/** Pure host checks: no Android runtime or test framework required. */
public final class GameSettingsCheck {
    private static void require(boolean value) { if (!value) throw new AssertionError(); }
    static Map<String,Object> persisted;
    static boolean commitOK = true;
    public static final class HostContext extends android.content.ContextWrapper {
        HostContext() { super(null); }
        @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode) {
            require(name.equals("games"));
            Map<String,Object> pending=new HashMap<>();
            android.content.SharedPreferences.Editor editor=(android.content.SharedPreferences.Editor)java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),new Class[]{android.content.SharedPreferences.Editor.class},(proxy,method,args) -> {
                    if(method.getName().equals("commit")) { if(commitOK) persisted.putAll(pending); return commitOK; }
                    if(method.getName().startsWith("put")) { pending.put((String)args[0],args[1]); return proxy; }
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
        require(persisted.get(id+".name").equals("Existing title") && persisted.get(id+".advanced.volume").equals(1f));
        cancelled.save(context);
        require(persisted.get(id+".resolution").equals(1) && persisted.get(id+".textures").equals(true));
        require(persisted.get(id+".advanced.volume").equals(0f));
        GameSettings roundtrip=GameSettings.from(persisted,id);
        require(roundtrip.controllerBindings.get("96").equals("b"));
        require(roundtrip.json().contains("\"controller_bindings\":{\"96\":\"b\"}"));
        commitOK=false;
        try { original.save(context); throw new AssertionError("Failed commit accepted"); }
        catch(IllegalStateException expected) {}
        commitOK=true;
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
        require(original.textures==false); // Presence never changes a saved opt-out.
        pack.delete(); pack.getParentFile().delete(); pack.getParentFile().getParentFile().delete();
        new File(version,"data").delete(); version.delete();
        try { GameSettings.from(prefs,"../bad"); throw new AssertionError(); }
        catch(IllegalArgumentException expected) {}
        System.out.println("GameSettings host checks passed: legacy preferences, draft isolation/cancel, types, JSON, optional pack, eight shader settings round-trip.");
    }
}
