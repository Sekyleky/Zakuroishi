package io.github.zakuro.android;

import java.util.*;

/** Host checks for the adb settings payload validation. */
public final class DebugSettingsCheck {
    private static void require(boolean value) { if (!value) throw new AssertionError(); }
    private static boolean rejected(GameSettings s, Object... pairs) {
        Map<String,Object> changes = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) changes.put((String) pairs[i], pairs[i + 1]);
        try { DebugSettings.apply(s, changes); return false; } catch (IllegalArgumentException e) { return true; }
    }
    public static void main(String[] args) {
        GameSettings s = GameSettings.from(new HashMap<>(), "0004000000123400");
        String before = DebugSettings.json(s);
        require(before.startsWith("{\"resolution\":1,\"textures\":true,\"driver\":\"\",\"advanced\":{\"dual_display\":false"));
        require(rejected(s, "resolution", 2, "layout", "bogus") && before.equals(DebugSettings.json(s)));
        require(rejected(s, "nope", true) && rejected(s, "resolution", 6) && rejected(s, "resolution", 0) && rejected(s, "resolution", 4.5) && rejected(s, "textures", "yes") && rejected(s, "aot", 1));
        require(rejected(s, "volume", 2) && rejected(s, "speed_limit", 1.5) && rejected(s, "driver", "x") && before.equals(DebugSettings.json(s)));
        require(!rejected(s, "resolution", 3L, "textures", false, "layout", "top_only", "aot", false, "speed_limit", 200, "volume", 0.5, "driver", ""));
        require(s.resolution == 3 && !s.textures && s.values.get("layout").equals("top_only") && s.values.get("speed_limit").equals(200));
        require(s.values.get("volume").equals(0.5f) && Boolean.FALSE.equals(s.values.get("aot")));
        require(!rejected(s, "resolution", 5) && s.resolution == 5 && !rejected(s, "resolution", 3) && s.resolution == 3);
        // bindings: the whole set is replaced; a bad key code or target changes nothing
        Map<String,Object> keys = new LinkedHashMap<>(); keys.put("131", "swap_screens"); keys.put("96", "a");
        require(!rejected(s, "controller_bindings", keys) && s.controllerBindings.size() == 2 && s.controllerBindings.get("131").equals("swap_screens"));
        require(rejected(s, "controller_bindings", Collections.singletonMap("131", "fly")) && rejected(s, "controller_bindings", Collections.singletonMap("x", "a"))
            && rejected(s, "controller_bindings", "a") && s.controllerBindings.size() == 2);
        require(!rejected(s, "controller_bindings", new HashMap<String,Object>()) && s.controllerBindings.isEmpty());
    }
}
