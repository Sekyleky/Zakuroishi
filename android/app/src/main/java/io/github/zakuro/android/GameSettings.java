package io.github.zakuro.android;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import java.io.File;
import java.util.*;

/** Per-title typed settings. The advanced JSON keys are shared with the native frontend. */
final class GameSettings {
    static final String[] GROUPS = {"Display", "Performance", "Controls", "Button mapping", "Motion", "Audio", "System", "Advanced"};
    static final class Setting {
        final String key, label, group, help;
        final Object initial;
        final float min, max;
        final String[] choices;
        Setting(String group, String key, String label, Object initial, float min, float max,
                String choices, String help) {
            this.group = group; this.key = key; this.label = label; this.initial = initial;
            this.min = min; this.max = max; this.choices = choices == null ? null : choices.split("\\|");
            this.help = help;
        }
        Object normalize(Object value) {
            if (initial instanceof Boolean) return value instanceof Boolean ? value : initial;
            if (initial instanceof String) {
                if (value instanceof String && Arrays.asList(choices).contains(value)) return value;
                return initial;
            }
            if (!(value instanceof Number)) return initial;
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number)) return initial;
            number = Math.max(min, Math.min(max, number));
            if (initial instanceof Integer) return Integer.valueOf((int) Math.round(number));
            return Float.valueOf((float) number);
        }
    }
    private static Setting flag(String group, String key, String label, boolean value, String help) {
        return new Setting(group, key, label, value, 0, 1, null, help);
    }
    private static Setting choice(String group, String key, String label, String value, String choices, String help) {
        return new Setting(group, key, label, value, 0, 0, choices, help);
    }
    private static Setting number(String group, String key, String label, Object value, float min, float max, String help) {
        return new Setting(group, key, label, value, min, max, null, help);
    }
    static final Setting[] ALL = {
        flag("Display", "dual_display", "Bottom screen on second display", false, "Top screen on this display, bottom screen on a connected presentation display. Disconnecting restores the selected layout. Swap screens reverses the routing."),
        choice("Display", "layout", "Screen layout", "stacked", "stacked|side_by_side|large_top|top_only|bottom_only", "Choose how the two screens share the display."),
        flag("Display", "swap_screens", "Swap top and bottom screens", false, "Exchange their positions in the selected layout."),
        flag("Display", "external_stretch", "Fill second display", false, "Fill the physical second display independently of the main display. Can change the picture proportions."),
        choice("Display", "orientation", "Display orientation", "landscape", "auto|landscape|portrait", "Automatic follows device rotation. Landscape and Portrait follow the sensor within the selected orientation."),
        choice("Display", "filter", "Picture filter", "smooth", "smooth|sharp|pixels", "How the screens look when scaled."),
        number("Display", "top_width", "Top screen shape", 400f, 320, 800, "Original 5:3, 16:9 or 21:9. Wide shapes stretch the picture unless the game has a matching widescreen code."),
        flag("Display", "stretch", "Fill the available screen area", false, "Changes the picture's proportions to fill its area."),
        flag("Display", "integer_scale", "Whole-pixel scaling", false, "Use whole scaling steps where the layout allows."),
        flag("Display", "show_fps", "Show FPS", false, "Updates the in-game status overlay once a second."),
        flag("Display", "show_clock", "Show clock", true, "Show the device's local time during play."),
        flag("Display", "show_battery", "Show battery level", false, "Show battery percentage and charging status in the game HUD."),
        choice("Display", "stereo", "Stereoscopic 3D", "off", "off|anaglyph|side_by_side|half_side_by_side|interlaced", "Requires a game that draws both eyes. Anaglyph uses red/cyan glasses."),
        number("Display", "slider_3d", "3D depth", 0f, 0, 1, "The emulated console's 3D slider; leave at zero for a flat picture."),
        flag("Display", "stereo_swap", "Swap 3D eyes", false, "Exchange left and right eye images."),
        number("Performance", "speed_limit", "Game speed limit (%)", 100, 0, 400, "100% runs at the game's intended speed. Higher values allow faster emulation; 0 removes the limit. Actual speed depends on the device and game."),
        number("Performance", "fast_forward_speed", "Fast-forward speed (hold button, %)", 150, 0, 400, "Hold your mapped Fast-forward button for this speed. Release it to restore the normal speed setting. 0 is unlimited."),
        number("Performance", "frame_cap", "Frame rate cap", 60, 0, 240, "Limits pictures presented per second. Zero is unlimited. This does not speed up the game's clock."),
        flag("Performance", "vsync_pacing", "Pace to display refresh (vsync)", true, "At normal speed and the 60 FPS cap, present on each display refresh for even frame times; fast-forward still presents freely. Turn off for the 0.1.8 mailbox behavior. Takes effect immediately."),
        flag("Performance", "short_vsync_queue", "Vsync: show frames sooner", false, "With vsync pacing, keep fewer finished frames queued for the display. On a custom Turnip driver the picture shows about 17 ms sooner; the system driver gains nothing and stutters slightly more. Takes effect immediately."),
        flag("Performance", "interpolate", "Frame interpolation", false, "Draws a picture in between for games that run at 30 fps, so motion looks like 60 at normal game speed. Use a Turnip driver: on the stock driver games can slow down. Adds a little input delay. Needs hardware rendering; off while GPU readback is forced or the top screen is on the second display. May show artifacts. Turning this on turns off Frame generation."),
        flag("Performance", "frame_generation", "Frame generation (Lossless Scaling)", false, "Experimental. Shows pictures made between the game's own, so a 30 fps game looks much smoother. Needs a Turnip driver and your own copy of Lossless Scaling, a paid app that is not included: import its Lossless.dll below. Adds input delay, and text and menus can smear. Top screen only. Turning this on turns off Frame interpolation."),
        number("Performance", "frame_generation_multiplier", "Pictures per game picture", 4, 2, 4, "4× runs the panel at 120 Hz. If Android holds the screen at 60 Hz, 4× switches itself to 2×, which stays at 60 Hz."),
        number("Performance", "frame_generation_flow", "Motion search size", 100, 25, 100, "Smaller costs the GPU less and follows motion less closely."),
        flag("Performance", "warm_before_start", "Finish shader warm-up before the game starts", false, "Hold the game's first frame (up to 30 s) until the remembered vertex programs and GPU pipelines are ready, so the intro and first battle don't stutter. The game starts later. Needs the two warm-up switches. Requires restart."),
        flag("Performance", "warm_vertex_programs", "Warm up vertex programs at launch", true, "Remember the CPU vertex programs this game used and compile them in the background when it starts, so attacks and cutscenes seen before don't stutter. Turn off only to compare. Requires restart."),
        flag("Performance", "warm_pipelines", "Warm up GPU pipelines at launch", true, "Remember the GPU pipelines this game used and create them in the background when it starts. Turn off only to compare. Requires restart."),
        flag("Performance", "custom_driver", "Use selected custom Vulkan driver", false, "Requires an imported ARM64 Android Vulkan driver and packaged native driver support. Restart after changing. Driver compatibility depends on your GPU."),
        choice("Controls", "button_mapping", "Face button layout", "nintendo", "nintendo|xbox", "Choose Nintendo positions or Xbox button labels."),
        choice("Controls", "left_stick", "Left stick", "circle_pad", "circle_pad|dpad", "Map the physical left stick to the Circle Pad or D-pad."),
        choice("Controls", "right_stick", "Right stick", "c_stick", "c_stick|cursor", "Use the C-stick or move a cursor on the bottom screen."),
        number("Controls", "deadzone", "Stick dead zone", 0.15f, 0, 0.9f, "Ignore small stick movement around the center."),
        number("Controls", "cursor_speed", "Touch cursor speed", 1f, 0.1f, 5, "Speed of the right-stick touch cursor."),
        flag("Controls", "latch_short_presses", "Never miss a quick tap", true, "A button or touch pressed and released within one frame still reaches the game for that frame. Turn off only to compare. Requires restart."),
        flag("Controls", "touch_controls", "Show touch controls", false,"On-screen buttons and sticks. The unoccupied game surface still accepts bottom-screen touches."),
        flag("Controls", "touch_auto_hide", "Hide touch controls with a gamepad", true, "Automatically hide on-screen buttons and sticks while an Android gamepad or joystick is connected."),
        flag("Controls", "show_pause_button", "Show pause button", false, "An on-screen Pause button in the top-right corner. Back or the controller's menu button pauses either way."),
        flag("Motion", "motion_enabled", "Use device motion", true, "Use the handheld's accelerometer and gyroscope when available. Hold still briefly after resuming."),
        number("Motion", "gyro_sensitivity", "Gyroscope sensitivity", 1f, 0, 5, "Scales rotation input; does not change gravity."),
        flag("Motion", "gyro_invert_x", "Invert gyro pitch", false, "Reverse rotation around the X axis."),
        flag("Motion", "gyro_invert_y", "Invert gyro yaw", false, "Reverse rotation around the Y axis."),
        flag("Motion", "gyro_invert_z", "Invert gyro roll", false, "Reverse rotation around the Z axis."),
        flag("Motion", "stick_motion", "Use right stick for motion", false, "Use the right stick for tilt input."),
        number("Motion", "stick_motion_sensitivity", "Stick motion sensitivity", 1f, 0, 5, "Scales motion generated by the right stick."),
        flag("Motion", "stick_motion_gate", "Require motion button", true, "Hold the selected tilt button to use stick motion. Disable to use stick motion continuously."),
        choice("Motion", "tilt_button", "Stick-motion gate button", "l3", "always|l1|l2|r1|r2|l3|r3", "Used when Require motion button is enabled. A controller action bound to Motion gate can also activate it."),
        flag("Motion", "reverse_tilt_x", "Invert stick-motion horizontal tilt", false, "Reverse horizontal tilt from the right stick, independently of device gyroscope axes."),
        flag("Motion", "reverse_tilt_y", "Invert stick-motion vertical tilt", false, "Reverse vertical tilt from the right stick, independently of device gyroscope axes."),
        flag("Audio", "mute", "Mute game audio", false, "Silence audio without changing the saved volume."),
        number("Audio", "volume", "Game volume", 1f, 0, 1, "Volume within the device's media volume."),
        flag("Audio", "audio_queue_managed", "Keep sound in step with the picture", true, "Hold about 125 ms of sound, adjust playback a fraction of a percent to stay there, and stretch the sound instead of cutting out when the game falls behind. Off restores the 0.2.0 queue, which drifts up to 250 ms behind. Requires restart."),
        flag("Audio", "low_latency_audio", "Low-latency sound output", false, "Ask Android for its fast audio path, which plays sound sooner after the game makes it. On the AYN Thor a system equalizer blocks that path, so this changes nothing there. Requires restart."),
        number("System", "region", "Console region", -1, -1, 6, "Automatic uses the game's own region."),
        number("System", "language", "Console language", 1, 0, 11, "Games only provide languages included in their release."),
        flag("System", "new_3ds", "Emulate New Nintendo 3DS", false, "Changes the emulated console model and extra controls. Requires a restart."),
        flag("Advanced", "hardware_renderer", "Hardware 3D renderer", true, "Use GPU rendering. Disable for the upstream software 3D renderer. Vulkan still presents the screens; HD textures, resolution scaling and interpolation require hardware rendering."),
        flag("Advanced", "aot", "Use compiled native game code", true, "Recommended. Turn off to use the interpreter for troubleshooting; it can be considerably slower."),
        flag("Advanced", "skip_idle_loops", "Skip idle loops", true, "Upstream optimization for loops waiting for an event."),
        choice("Advanced", "shader_mode", "Vertex shader processing", "auto", "auto|gpu|cpu", "Automatic uses the upstream supported path. GPU runs vertex shaders on the GPU; CPU processes vertices on the CPU. Primarily affects the hardware 3D renderer. Requires restart."),
        flag("Advanced", "safe_gpu_shaders", "Guard GPU vertex shaders on the system driver", true, "The stock Qualcomm driver crashes with GPU vertex shaders, so they fall back to Auto unless a custom driver is used. Turn off only to test. Requires restart."),
        choice("Advanced", "shader_execution", "GPU vertex shader execution", "compiled", "compiled|interpreted", "Choose translated GPU vertex shaders or the GPU shader interpreter. This is separate from CPU shader compilation and compiled native game code. Requires restart."),
        flag("Advanced", "cpu_shader_jit", "Compile CPU vertex shaders", true, "Use the CPU vertex shader compiler instead of its interpreter when the CPU vertex path is active. Does not change GPU shader execution or compiled native game code. Requires restart."),
        choice("Advanced", "shader_compilation", "Specialized shader compilation", "async", "async|wait", "Asynchronous compilation uses the upstream fallback while specialized GPU pipelines compile. Wait blocks until a specialized pipeline is ready and can cause stutter. Requires restart."),
        flag("Advanced", "generic_shaders", "Use generic shaders", false, "Use upstream generic GPU shaders instead of specialized pipelines. A compatibility option for the hardware 3D renderer; may reduce performance. Requires restart."),
        flag("Advanced", "shade_ahead", "Shade draws on other threads", true, "Shade upcoming draws on other CPU cores ahead of the one drawing (CPU vertex path). Turn off only to compare. Requires restart."),
        flag("Advanced", "depth_blits", "Use supported depth blits", true, "Allow the hardware renderer's depth blit path only when supported by the GPU. Disabling it uses the upstream fallback. Requires restart."),
        flag("Advanced", "float_depth", "Prefer floating-point depth", false, "Prefer the upstream floating-point depth format, with supported-format fallback. A hardware-renderer compatibility option. Requires restart."),
        flag("Advanced", "static_blend", "Use static blend state", false, "Disable the upstream dynamic blend optimization and use static pipeline blend state for driver troubleshooting. May increase pipeline compilation. Requires restart."),
        flag("Advanced", "fast_texture_decode", "Fast texture decoding", true, "Decode compressed textures block by block and skip HD-pack lookups that cannot match. Turn off only to compare. Requires restart."),
        flag("Advanced", "async_pack_lookup", "HD textures: look up pictures in the background", true, "Match new textures against the HD pack on a background thread; an HD picture may appear a frame later. Turn off only to compare. Requires restart."),
        flag("Advanced", "pooled_uploads", "HD textures: pooled upload memory", true, "Reuse GPU memory for HD texture pictures instead of allocating it per picture. Reduces hitches when a battle starts. Turn off only to compare. Requires restart."),
        flag("Advanced", "upload_size_budget", "HD textures: limit upload size per frame", true, "Upload at most a frame's budget of HD picture data; the original texture shows for a frame or two meanwhile. Turn off only to compare. Requires restart."),
        flag("Advanced", "threaded_picture_copy", "HD textures: copy pictures in the background", true, "Copy HD pictures into upload memory on background threads instead of the emulation thread. Turn off only to compare. Requires restart."),
        flag("Advanced", "second_display_shared", "Bottom screen: full resolution on second display", false, "With the bottom screen on a second display, draw it from the GPU at the selected resolution on one shared Vulkan device. Off, that screen is read from the game's memory at native size and repeated. Requires restart."),
        flag("Advanced", "gpu_readback", "GPU screen readback", false, "Troubleshooting: copy the GPU screens back before presenting instead of sharing GPU images. Can reduce performance."),
        flag("Advanced", "turnip_sysmem", "Turnip: render in system memory", false, "Custom Mesa/Turnip drivers only (TU_DEBUG=sysmem). Testing option for a black block in a screen corner; may cost performance. Requires restart."),
    };
    static final String[] BINDING_TARGETS = {"fast_forward","a","b","x","y","l","r","zl","zr","start","select","up","down","left","right","none","motion_gate"};
    final String id;
    String name;
    String driver = "";
    int resolution;
    boolean textures;
    final Map<String, Object> values = new LinkedHashMap<>();
    final Map<String, String> controllerBindings = new LinkedHashMap<>();
    final Set<String> enhancements = new LinkedHashSet<>();

    private GameSettings(String id) { this.id = id; }
    static GameSettings from(Map<String, ?> prefs, String id) {
        if (id == null || !id.matches("[0-9A-Fa-f]{16}")) throw new IllegalArgumentException("Invalid game ID");
        GameSettings settings = new GameSettings(id);
        settings.name = prefs.get(id + ".name") instanceof String ? (String) prefs.get(id + ".name") : "";
        Object selectedDriver = prefs.get(id + ".driver");
        if (selectedDriver instanceof String && ((String)selectedDriver).matches("[0-9a-fA-F-]{36}")) settings.driver = (String)selectedDriver;
        Object scale = prefs.get(id + ".resolution");
        settings.resolution = scale instanceof Integer ? Math.max(1, Math.min(3, (Integer) scale)) : 1;
        Object texture = prefs.get(id + ".textures"); settings.textures = !(texture instanceof Boolean) || (Boolean) texture;
        for (Setting setting : ALL) settings.values.put(setting.key, setting.normalize(prefs.get(id + ".advanced." + setting.key)));
        Object enabled = prefs.get(id + ".advanced.enhancements");
        if (enabled instanceof Set<?>) for (Object value : (Set<?>) enabled)
            if (value instanceof String && !((String) value).isEmpty() && ((String) value).length() <= 120 && settings.enhancements.size() < 32)
                settings.enhancements.add((String) value);
        Object bindings = prefs.get(id + ".advanced.controller_bindings");
        if (bindings instanceof Set<?>) for (Object item : (Set<?>) bindings) {
            if (!(item instanceof String)) continue;
            String[] parts = ((String)item).split("=", 2);
            if (parts.length == 2 && parts[0].matches("[0-9]{1,4}") && Integer.parseInt(parts[0]) <= 1024 && Arrays.asList(BINDING_TARGETS).contains(parts[1]))
                settings.controllerBindings.put(parts[0],parts[1]);
        }
        return settings;
    }
    @SuppressWarnings("deprecation")
    static SharedPreferences preferences(Context context) {
        // Launcher and NativeActivity use separate processes, and only the foreground UI edits settings.
        return context.getSharedPreferences("games", Context.MODE_MULTI_PROCESS);
    }
    static GameSettings load(Context context, String id) { return from(preferences(context).getAll(), id); }
    GameSettings copy() {
        GameSettings result = new GameSettings(id); result.driver = driver; result.name = name; result.resolution = resolution; result.textures = textures;
        result.controllerBindings.putAll(controllerBindings); result.values.putAll(values); result.enhancements.addAll(enhancements); return result;
    }
    boolean flag(String key) { return Boolean.TRUE.equals(values.get(key)); }
    float number(String key) { return ((Number) values.get(key)).floatValue(); }
    void save(Context context) {
        SharedPreferences.Editor edit = preferences(context).edit();
        edit.putString(id + ".name", name.trim()).putInt(id + ".resolution", resolution).putBoolean(id + ".textures", textures);
        edit.putString(id + ".driver", driver);
        for (Setting setting : ALL) {
            String key = id + ".advanced." + setting.key; Object value = setting.normalize(values.get(setting.key));
            if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) edit.putInt(key, (Integer) value);
            else if (value instanceof Float) edit.putFloat(key, (Float) value);
            else edit.putString(key, (String) value);
        }
        Set<String> bindings = new HashSet<>();
        for (Map.Entry<String,String> binding : controllerBindings.entrySet()) bindings.add(binding.getKey() + "=" + binding.getValue());
        edit.putStringSet(id + ".advanced.controller_bindings", bindings);
        edit.putStringSet(id + ".advanced.enhancements", new HashSet<>(enhancements));
        // Complete before handing control to the separate game process or restarting its runtime.
        if (!edit.commit()) throw new IllegalStateException("Could not save game settings");
    }
    static boolean hasTextures(File version, String id) {
        return version != null && new File(version, "data/mods/" + id + "/textures").isDirectory();
    }
    void apply(Intent intent, File version) {
        intent.putExtra("game_id", id); intent.putExtra("game_name", name);
        intent.putExtra("resolution", resolution); intent.putExtra("texture_packs", textures && hasTextures(version, id));
        intent.removeExtra("driver_dir"); intent.removeExtra("driver_name");
        if (flag("custom_driver")) {
            File folder = new File(new File(version.getParentFile().getParentFile(),"drivers"),driver);
            try {
                if (driver.isEmpty() || !folder.isDirectory()) throw new java.io.IOException("Choose an imported Vulkan driver in Performance settings first.");
                String library = driverLibrary(folder);
                intent.putExtra("driver_dir",folder.getCanonicalPath()); intent.putExtra("driver_name",library);
            } catch (java.io.IOException | org.json.JSONException e) { throw new IllegalStateException("Custom Vulkan driver unavailable: " + e.getMessage(),e); }
        }
        intent.putExtra("advanced", json());
    }
    static String driverLibrary(File folder) throws java.io.IOException, org.json.JSONException {
        File metadata = new File(folder,"meta.json");
        if (!metadata.isFile() || metadata.length() > 65536) throw new java.io.IOException("Driver meta.json is missing or too large");
        org.json.JSONObject meta = new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(metadata.toPath()),java.nio.charset.StandardCharsets.UTF_8));
        String library=meta.getString("libraryName");
        int minimum=meta.has("minApi") ? meta.getInt("minApi") : meta.has("minApiVersion") ? meta.getInt("minApiVersion") : 0;
        int schema=meta.has("schemaVersion") ? meta.getInt("schemaVersion") : 1;
        validateDriverMetadata(library,minimum,schema,android.os.Build.VERSION.SDK_INT);
        if (!new File(folder,library).isFile()) throw new java.io.IOException("Driver libraryName must name a bundled .so file");
        return library;
    }
    static void validateDriverMetadata(String library, int minimum, int schema, int deviceApi) throws java.io.IOException {
        if (library == null || !library.matches("[A-Za-z0-9_.-]+\\.so")) throw new java.io.IOException("Driver libraryName must be a .so basename using letters, digits, dot, underscore or hyphen");
        if (minimum < 0 || minimum > deviceApi) throw new java.io.IOException("Driver requires Android API " + minimum + "; this device uses API " + deviceApi);
        if (schema != 1) throw new java.io.IOException("Unsupported driver metadata schemaVersion " + schema + "; expected 1");
    }
    String json() {
        StringBuilder out = new StringBuilder("{");
        for (Setting setting : ALL) {
            if (out.length() > 1) out.append(',');
            quote(out, setting.key); out.append(':'); Object value = setting.normalize(values.get(setting.key));
            if (value instanceof String) quote(out, (String) value); else out.append(value);
        }
        out.append(",\"controller_bindings\":{"); boolean bindingFirst = true;
        for (Map.Entry<String,String> binding : controllerBindings.entrySet()) {
            if (!bindingFirst) out.append(','); bindingFirst = false;
            quote(out,binding.getKey()); out.append(':'); quote(out,binding.getValue());
        }
        out.append("},\"enhancements\":["); boolean first = true;
        for (String enhancement : enhancements) {
            if (!first) out.append(','); first = false; quote(out, enhancement);
        }
        return out.append("]}").toString();
    }
    private static void quote(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 32 || Character.isSurrogate(c)) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            else out.append(c);
        }
        out.append('"');
    }
}
