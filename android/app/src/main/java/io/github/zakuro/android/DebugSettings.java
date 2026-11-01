package io.github.zakuro.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.io.File;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Developer settings access over adb, guarded by the DUMP permission (adb shell/system only):
 * {@code adb shell am broadcast -n io.github.zakuro.android/.DebugSettings --es game ID [--es set '{"resolution":2}']}
 * or {@code --ez list true}. Result data is JSON with code 0, an error text with code 1 (invalid input, nothing written)
 * or code 2 (unknown game). Changes are stored like the UI does and apply at the next game launch: the game runs in
 * the separate :game process and reads its settings only when it starts.
 */
public final class DebugSettings extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!isOrderedBroadcast()) return;
        try {
            if (intent.getBooleanExtra("list", false)) { setResult(0, list(context), null); return; }
            String id = intent.getStringExtra("game");
            if (id == null || !id.matches("[0-9A-Fa-f]{16}") || !ids(context).contains(id = id.toUpperCase(Locale.ROOT))) {
                setResult(2, "Unknown game " + id, null); return;
            }
            GameSettings settings = GameSettings.load(context, id);
            String set = intent.getStringExtra("set");
            if (set != null) {
                JSONObject object = new JSONObject(set); Map<String, Object> changes = new LinkedHashMap<>();
                for (Iterator<String> keys = object.keys(); keys.hasNext();) { String key = keys.next(); changes.put(key, object.get(key)); }
                settings = settings.copy(); apply(settings, changes); settings.save(context);
            }
            setResult(0, json(settings), null);
        } catch (IllegalArgumentException | org.json.JSONException e) {
            setResult(1, String.valueOf(e.getMessage()), null);
        } catch (RuntimeException e) {
            setResult(1, e.toString(), null);
        }
    }
    private static Set<String> ids(Context context) {
        File[] files = new File(context.getFilesDir(), "imports").listFiles(); Set<String> found = new TreeSet<>();
        if (files != null) for (File file : files) if (file.getName().matches("[0-9A-F]{16}\\.active(\\.bak)?")) found.add(file.getName().substring(0, 16));
        return found;
    }
    private static String list(Context context) throws org.json.JSONException {
        JSONArray out = new JSONArray();
        for (String id : ids(context)) {
            String name = GameSettings.preferences(context).getString(id + ".name", "").trim();
            if (name.isEmpty()) try { name = GameMetadata.name(GameImport.active(context, id), id); } catch (Exception e) { name = "Game " + id.substring(8); }
            out.put(new JSONObject().put("id", id).put("name", name));
        }
        return out.toString();
    }
    /** One-line JSON of the settings a script can change. */
    static String json(GameSettings s) {
        StringBuilder out = new StringBuilder("{\"resolution\":").append(s.resolution).append(",\"textures\":").append(s.textures)
            .append(",\"driver\":\"").append(s.driver).append("\",\"advanced\":{");
        boolean first = true;
        for (GameSettings.Setting setting : GameSettings.ALL) {
            if (!first) out.append(','); first = false;
            Object value = s.values.get(setting.key);
            out.append('"').append(setting.key).append("\":");
            if (value instanceof String) out.append('"').append(value).append('"'); else out.append(value);
        }
        return out.append("}}").toString();
    }
    /** Validates every change first and only then mutates settings; on any problem throws IllegalArgumentException and leaves them untouched. */
    static void apply(GameSettings s, Map<String, Object> changes) {
        Map<String, Object> staged = new LinkedHashMap<>();
        for (Map.Entry<String, Object> change : changes.entrySet()) {
            String key = change.getKey(); Object value = change.getValue();
            switch (key) {
                case "resolution":
                    if (!(value instanceof Number) || ((Number) value).doubleValue() != Math.rint(((Number) value).doubleValue())
                        || ((Number) value).intValue() < 1 || ((Number) value).intValue() > 3) throw new IllegalArgumentException("resolution must be an integer 1-3");
                    staged.put(key, ((Number) value).intValue()); break;
                case "textures":
                    if (!(value instanceof Boolean)) throw new IllegalArgumentException("textures must be true or false");
                    staged.put(key, value); break;
                case "driver":
                    if (!(value instanceof String) || !(((String) value).isEmpty() || ((String) value).matches("[0-9a-fA-F-]{36}")))
                        throw new IllegalArgumentException("driver must be an imported driver id or empty");
                    staged.put(key, value); break;
                default: {
                    GameSettings.Setting setting = null;
                    for (GameSettings.Setting candidate : GameSettings.ALL) if (candidate.key.equals(key)) setting = candidate;
                    if (setting == null) throw new IllegalArgumentException("Unknown setting " + key);
                    staged.put(key, typed(setting, value));
                }
            }
        }
        for (Map.Entry<String, Object> change : staged.entrySet()) {
            switch (change.getKey()) {
                case "resolution": s.resolution = (Integer) change.getValue(); break;
                case "textures": s.textures = (Boolean) change.getValue(); break;
                case "driver": s.driver = (String) change.getValue(); break;
                default: s.values.put(change.getKey(), change.getValue());
            }
        }
    }
    private static Object typed(GameSettings.Setting setting, Object value) {
        String key = setting.key;
        if (setting.initial instanceof Boolean) {
            if (!(value instanceof Boolean)) throw new IllegalArgumentException(key + " must be true or false");
            return value;
        }
        if (setting.initial instanceof String) {
            if (!(value instanceof String) || !Arrays.asList(setting.choices).contains(value))
                throw new IllegalArgumentException(key + " must be one of " + String.join("|", setting.choices));
            return value;
        }
        if (!(value instanceof Number)) throw new IllegalArgumentException(key + " must be a number");
        double number = ((Number) value).doubleValue();
        if (!(number >= setting.min && number <= setting.max)) throw new IllegalArgumentException(key + " must be between " + setting.min + " and " + setting.max);
        if (setting.initial instanceof Integer) {
            if (number != Math.rint(number)) throw new IllegalArgumentException(key + " must be an integer");
            return Integer.valueOf((int) number);
        }
        return Float.valueOf((float) number);
    }
}
