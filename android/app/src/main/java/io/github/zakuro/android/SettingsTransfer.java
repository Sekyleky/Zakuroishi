package io.github.zakuro.android;

import android.content.Context;
import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Per-game settings, controller mappings and enhancements as one JSON file. A file to import is untrusted: only known keys with
 * valid values are accepted (the validation of {@link DebugSettings#apply}), and the whole file is refused on the first problem.
 * Driver choices, driver files, saves and ROMs are never part of it.
 */
final class SettingsTransfer {
    static final int MAX_BYTES = 1 << 20;
    static final String FORMAT = "zakuroishi-settings";
    private static final int MAX_GAMES = 64;
    private SettingsTransfer() {}

    /** The exported form as plain maps and lists: JSON is only read and written at the edges, so this is what the check round-trips. */
    static Map<String, Object> toMap(Collection<GameSettings> games) {
        Map<String, Object> all = new LinkedHashMap<>();
        for (GameSettings s : games) {
            Map<String, Object> advanced = new LinkedHashMap<>();
            // custom_driver names a driver that only exists on this device
            for (GameSettings.Setting setting : GameSettings.ALL) if (!setting.key.equals("custom_driver")) advanced.put(setting.key, s.values.get(setting.key));
            Map<String, Object> game = new LinkedHashMap<>();
            game.put("resolution", s.resolution); game.put("textures", s.textures); game.put("advanced", advanced);
            game.put("controller_bindings", new LinkedHashMap<>(s.controllerBindings)); game.put("enhancements", new ArrayList<>(s.enhancements));
            all.put(s.id, game);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", FORMAT); root.put("version", 1); root.put("games", all);
        return root;
    }

    /** Validates everything and returns the new settings per game; throws IllegalArgumentException and returns nothing otherwise. */
    static Map<String, GameSettings> parse(Map<String, Object> root, Function<String, GameSettings> current) {
        only(root, "settings file", "format", "version", "games");
        if (!FORMAT.equals(root.get("format"))) throw new IllegalArgumentException("This is not a Zakuroishi settings file.");
        if (!(root.get("version") instanceof Number) || ((Number) root.get("version")).doubleValue() != 1) throw new IllegalArgumentException("This settings file has an unsupported version.");
        Map<String, Object> games = object(root.get("games"), "games");
        if (games.size() > MAX_GAMES) throw new IllegalArgumentException("Too many games in the settings file.");
        Map<String, GameSettings> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : games.entrySet()) {
            String id = entry.getKey();
            if (!id.matches("[0-9A-F]{16}")) throw new IllegalArgumentException("Invalid game ID " + id);
            Map<String, Object> game = object(entry.getValue(), id);
            only(game, id, "resolution", "textures", "advanced", "controller_bindings", "enhancements");
            GameSettings s = current.apply(id).copy();
            Map<String, Object> changes = new LinkedHashMap<>();
            for (String key : new String[]{"resolution", "textures"}) if (game.containsKey(key)) changes.put(key, game.get(key));
            if (game.containsKey("advanced")) for (Map.Entry<String, Object> setting : object(game.get("advanced"), "advanced").entrySet()) {
                boolean known = false;
                for (GameSettings.Setting candidate : GameSettings.ALL) known |= candidate.key.equals(setting.getKey());
                if (!known || setting.getKey().equals("custom_driver")) throw new IllegalArgumentException("Unknown setting " + setting.getKey());
                changes.put(setting.getKey(), setting.getValue());
            }
            DebugSettings.apply(s, changes);
            if (game.containsKey("controller_bindings")) {
                Map<String, String> staged = new LinkedHashMap<>();
                for (Map.Entry<String, Object> binding : object(game.get("controller_bindings"), "controller_bindings").entrySet()) {
                    String code = binding.getKey();
                    if (!code.matches("[0-9]{1,4}") || Integer.parseInt(code) > 1024 || !(binding.getValue() instanceof String)
                            || !Arrays.asList(GameSettings.BINDING_TARGETS).contains(binding.getValue()))
                        throw new IllegalArgumentException("Invalid controller mapping " + code);
                    staged.put(code, (String) binding.getValue());
                }
                s.controllerBindings.clear(); s.controllerBindings.putAll(staged);
            }
            if (game.containsKey("enhancements")) {
                if (!(game.get("enhancements") instanceof List<?>) || ((List<?>) game.get("enhancements")).size() > 32) throw new IllegalArgumentException("Invalid enhancements list");
                Set<String> staged = new LinkedHashSet<>();
                for (Object name : (List<?>) game.get("enhancements")) {
                    if (!(name instanceof String) || ((String) name).isEmpty() || ((String) name).length() > 120) throw new IllegalArgumentException("Invalid enhancement name");
                    staged.add((String) name);
                }
                s.enhancements.clear(); s.enhancements.addAll(staged);
            }
            result.put(id, s);
        }
        return result;
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String what) {
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException(what + " must be an object");
        return (Map<String, Object>) value;
    }
    private static void only(Map<String, Object> object, String what, String... allowed) {
        for (String key : object.keySet()) if (!Arrays.asList(allowed).contains(key)) throw new IllegalArgumentException("Unknown entry " + key + " in " + what);
    }

    // JSON edges. org.json is a stub on the host JVM, so these are not covered by the host check.
    static String export(Context context) throws JSONException {
        List<GameSettings> games = new ArrayList<>();
        for (String id : GameImport.importedIds(context.getFilesDir())) games.add(GameSettings.load(context, id));
        return ((JSONObject) wrap(toMap(games))).toString(2);
    }
    /** Returns how many games were changed. Nothing is written unless the whole file is valid. */
    static int importJson(Context context, String text) throws IOException {
        Map<String, GameSettings> parsed;
        try { parsed = parse(object(unwrap(new JSONObject(text)), "file"), id -> GameSettings.load(context, id)); }
        catch (JSONException | IllegalArgumentException e) {
            throw new IOException("Nothing was changed. " + (e instanceof JSONException ? "This is not a valid settings file." : e.getMessage()), e);
        }
        // ponytail: games save one by one; a storage failure midway leaves the earlier games updated. One shared editor if it ever matters.
        for (GameSettings s : parsed.values()) s.save(context);
        return parsed.size();
    }
    private static Object wrap(Object value) throws JSONException {
        if (value instanceof Map<?, ?>) {
            JSONObject out = new JSONObject();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) out.put((String) entry.getKey(), wrap(entry.getValue()));
            return out;
        }
        if (value instanceof Collection<?>) {
            JSONArray out = new JSONArray();
            for (Object item : (Collection<?>) value) out.put(wrap(item));
            return out;
        }
        return value instanceof Float ? Double.valueOf(Float.toString((Float) value)) : value;
    }
    private static Object unwrap(Object value) throws JSONException {
        if (value instanceof JSONObject) {
            Map<String, Object> out = new LinkedHashMap<>(); JSONObject object = (JSONObject) value;
            for (Iterator<String> keys = object.keys(); keys.hasNext();) { String key = keys.next(); out.put(key, unwrap(object.get(key))); }
            return out;
        }
        if (value instanceof JSONArray) {
            List<Object> out = new ArrayList<>(); JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) out.add(unwrap(array.get(i)));
            return out;
        }
        return value == JSONObject.NULL ? null : value;
    }
}
