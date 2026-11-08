package io.github.zakuro.android;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.text.InputFilter;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** One short category at a time, with native focus, font scaling and controller navigation. */
final class SettingsDialog {
    private final Activity activity;
    private final File version;
    private final GameSettings settings;
    private final String title;
    private final Palette p;
    private final Runnable saved, closed, repair, textures, mods;
    private final boolean inGame;
    private boolean saving;
    private AlertDialog rootDialog;

    static void show(Activity activity, String id, File version, String title, boolean inGame,
                     Runnable saved, Runnable closed, Runnable repair, Runnable textures, Runnable mods) {
        new SettingsDialog(activity, id, version, title, inGame, saved, closed, repair, textures, mods).root();
    }
    private SettingsDialog(Activity activity, String id, File version, String title, boolean inGame,
                           Runnable saved, Runnable closed, Runnable repair, Runnable textures, Runnable mods) {
        this.activity = activity; this.version = version; this.title = title; this.inGame = inGame;
        this.saved = saved; this.closed = closed; this.repair = repair; this.textures = textures; this.mods = mods;
        settings = GameSettings.load(activity, id);
        p = new Palette(activity);
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private LinearLayout column() {
        LinearLayout content = new LinearLayout(activity); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), dp(16)); content.setBackgroundColor(p.raised); return content;
    }
    private TextView text(String message, boolean secondary) {
        TextView view = new TextView(activity); view.setText(message); view.setTextColor(secondary ? p.muted : p.ink);
        view.setTextSize(secondary ? 14 : 17); view.setPadding(0, dp(6), 0, dp(10)); return view;
    }
    private TextView heading(String message) {
        TextView view = text(message, false); view.setTextColor(p.accent); view.setTextSize(17); view.setAccessibilityHeading(true); return view;
    }
    private View legend(String spec) { View chips = p.legend(spec); chips.setPadding(0, dp(6), 0, dp(10)); return chips; }
    private Button button(String label) {
        Button button = new Button(activity); button.setText(label); button.setTextColor(p.ink); button.setTextSize(16);
        button.setAllCaps(false); button.setMinHeight(dp(52)); button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setPadding(dp(14), dp(12), dp(14), dp(12));
        button.setBackground(p.row());
        return button;
    }
    private void focusRing(View view) { view.setBackground(p.row()); }
    private void addButton(LinearLayout content, Button button) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2); size.setMargins(0, dp(5), 0, dp(5)); content.addView(button, size);
    }
    private AlertDialog.Builder dialog(String heading, LinearLayout content) {
        ScrollView scroll = new ScrollView(activity); scroll.setFillViewport(true); scroll.setTag("page"); scroll.addView(content);
        return new AlertDialog.Builder(activity, p.dialogTheme()).setTitle(heading).setView(scroll);
    }
    private void show(AlertDialog dialog) { show(dialog, null, false); }
    private void show(AlertDialog dialog, BooleanSupplier dirty) { show(dialog, dirty, false); }
    private void focusCancel(AlertDialog dialog) { Button cancel = dialog.getButton(DialogInterface.BUTTON_NEGATIVE); cancel.post(cancel::requestFocus); }
    private String snapshot(GameSettings s, String name) { return s.json() + "|" + name + "|" + s.resolution + "|" + s.textures + "|" + s.driver; }
    private void confirm(String heading, String message, String yes, String no, Runnable action) {
        AlertDialog ask = new AlertDialog.Builder(activity, p.dialogTheme()).setTitle(heading).setMessage(message)
            .setPositiveButton(yes, (d, w) -> action.run()).setNegativeButton(no, null).create();
        show(ask, null, true); focusCancel(ask);
    }
    /** Cancel and B close at once, or ask first when the draft differs from where it started. */
    private void leave(AlertDialog dialog, BooleanSupplier dirty) {
        if (dirty != null && dirty.getAsBoolean()) confirm("Discard changes?", "Changes you made here will be lost.", "Discard", "Keep editing", dialog::cancel);
        else dialog.cancel();
    }
    private void show(AlertDialog dialog, BooleanSupplier dirty, boolean destructive) {
        dialog.setOnShowListener(d -> {
            if (dirty != null) {
                dialog.setCanceledOnTouchOutside(false);
                dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener(v -> leave(dialog, dirty));
            }
            p.dialog(dialog, destructive);
            ListView list = dialog.getListView();
            if (list != null) list.setSelector(p.row());
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        });
        dialog.setOnKeyListener((d, code, event) -> {
            if (code == KeyEvent.KEYCODE_BUTTON_A) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    ListView items = dialog.getListView();
                    int selected = items == null ? -1 : items.getSelectedItemPosition();
                    if (selected >= 0 && items.hasFocus()) items.performItemClick(items.getSelectedView(), selected, items.getItemIdAtPosition(selected));
                    else if (dialog.getCurrentFocus() != null) dialog.getCurrentFocus().performClick();
                }
                return true;
            }
            Button positive = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            if (code == KeyEvent.KEYCODE_BUTTON_START && positive != null) {
                if (event.getAction() == KeyEvent.ACTION_UP) positive.performClick();
                return true;
            }
            if (code == KeyEvent.KEYCODE_BUTTON_L1 || code == KeyEvent.KEYCODE_BUTTON_R1) {
                ScrollView page = dialog.getWindow().getDecorView().findViewWithTag("page");
                if (page == null) return false;
                if (event.getAction() == KeyEvent.ACTION_UP) page.pageScroll(code == KeyEvent.KEYCODE_BUTTON_L1 ? View.FOCUS_UP : View.FOCUS_DOWN);
                return true;
            }
            if (code == KeyEvent.KEYCODE_BUTTON_X) { // same path as a long press, e.g. clear a binding
                View focus = dialog.getCurrentFocus();
                if (focus == null) return false;
                if (event.getAction() == KeyEvent.ACTION_UP) focus.performLongClick();
                return true;
            }
            if (code == KeyEvent.KEYCODE_BUTTON_B || code == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    WindowInsets insets = dialog.getWindow().getDecorView().getRootWindowInsets();
                    if (insets != null && insets.isVisible(WindowInsets.Type.ime())) dialog.getWindow().getInsetsController().hide(WindowInsets.Type.ime());
                    else leave(dialog, dirty);
                }
                return true;
            }
            return false;
        });
        dialog.show();
    }
    /** Ask add or replace when the game already has mods, then open the folder picker. */
    private void importMod() {
        if (!GameImport.hasMods(version, settings.id)) { GameImport.addToMods = false; mods.run(); return; }
        AlertDialog ask = new AlertDialog.Builder(activity, p.dialogTheme()).setTitle("Import mod for " + title)
            .setMessage("This game already has mods. Add the new mod on top of them (its files win where they overlap), or replace them?")
            .setPositiveButton("Add to current mods", (d, w) -> { GameImport.addToMods = true; mods.run(); })
            .setNegativeButton("Replace current mods", (d, w) -> { GameImport.addToMods = false; mods.run(); })
            .setNeutralButton("Cancel", null).create();
        show(ask);
    }
    private void removeMods() {
        confirm("Remove mods for " + title + "?", "Deletes the mods imported for this game. Its texture pack, ROM and saves are kept.", "Remove", "Cancel", () -> {
            String id = settings.id;
            new Thread(() -> {
                String failure = null;
                try { GameImport.removeMods(activity, id); }
                catch (Exception e) { failure = e.getMessage() == null ? "Could not remove the mods." : e.getMessage(); }
                String result = failure;
                activity.runOnUiThread(() -> {
                    Toast.makeText(activity, result == null ? "Mods removed." : result, Toast.LENGTH_LONG).show();
                    if (result == null && rootDialog != null) rootDialog.dismiss();
                });
            }, "remove-mods").start();
        });
    }
    private void root() {
        LinearLayout content = column();
        content.addView(text(inGame ? "Changes apply after Save & restart. In-game saves are preserved; unsaved progress restarts." : "Settings are saved for this game and apply the next time you play.", true));
        Button firstGroup = null;
        for (String group : GameSettings.GROUPS) {
            Button button = button(group); button.setContentDescription(group + " settings for " + title);
            button.setOnClickListener(v -> group(group)); addButton(content, button);
            if (firstGroup == null) firstGroup = button;
        }
        if (!inGame) {
            if (textures != null) {
                Button attach = button("Import texture pack…"); attach.setOnClickListener(v -> textures.run()); addButton(content, attach);
                content.addView(text("Optional. Attach a pack without copying your game again.", true));
            }
            if (mods != null) {
                Button mod = button("Import mod…"); mod.setOnClickListener(v -> importMod()); addButton(content, mod);
                content.addView(text("Optional. Choose a Luma3DS or Citra mod folder. You can add it to the current mods or replace them. A mod that changes game code runs best with a title.so built with it, and only one code mod can be active.", true));
                if (GameImport.hasMods(version, settings.id)) {
                    Button remove = button("Remove mods"); remove.setOnClickListener(v -> removeMods()); addButton(content, remove);
                    content.addView(text("Removes this game's mods. Its texture pack, ROM and saves stay.", true));
                }
            }
            if (repair != null) {
                Button replace = button("Replace native game code…"); replace.setOnClickListener(v -> repair.run()); addButton(content, replace);
                content.addView(text("Choose a trusted Android ABI 5 title.so (ABI 4 still runs). The ROM, textures and saves stay in place.", true));
            }
        }
        if (!inGame && activity instanceof Launcher) {
            Button saves = button("Manage saves…");
            saves.setOnClickListener(v -> { rootDialog.dismiss(); ((Launcher)activity).saves(settings.id); }); addButton(content, saves);
            Button shaders = button("Clear shader cache");
            shaders.setOnClickListener(v -> {
                AlertDialog confirm = new AlertDialog.Builder(activity, p.dialogTheme()).setTitle("Clear shader cache for " + title + "?")
                    .setMessage("Removes compiled GPU shaders for this game. The next play recompiles them, which can cause brief stutters.")
                    .setPositiveButton("Clear", (d, w) -> {
                        // compiled pipelines, the pipelines a game used, and its vertex programs
                        String id = settings.id.toUpperCase(Locale.ROOT);
                        long bytes = 0; boolean any = false;
                        for (String file : new String[] {"pipelines/" + id + ".bin", "pipelines/" + id + ".keys", "programs/" + id + ".bin"}) {
                            File cache = new File(activity.getFilesDir(), "cache/zakuro/" + file);
                            long length = cache.length();
                            if (cache.isFile() && cache.delete()) { bytes += length; any = true; }
                        }
                        Toast.makeText(activity, any ? "Shader cache cleared (" + (bytes + 1023) / 1024 + " KB)." : "No shader cache to clear.", Toast.LENGTH_SHORT).show();
                    }).setNegativeButton("Cancel", null).create(); show(confirm, null, true);
                focusCancel(confirm);
            }); addButton(content, shaders);
            content.addView(text("Removes compiled GPU shaders for this game. The next play recompiles them, which can cause brief stutters.", true));
            Button driver = button("Import Vulkan driver ZIP…");
            driver.setOnClickListener(v -> {
                AlertDialog trust=new AlertDialog.Builder(activity, p.dialogTheme()).setTitle("Import a trusted driver for " + title + "?")
                    .setMessage("A custom Vulkan driver runs native code inside this app and can access its games and saves. Only choose a ZIP from a source you trust.")
                    .setPositiveButton("Choose ZIP",(d,w) -> { rootDialog.dismiss(); ((Launcher)activity).importDriver(settings.id); })
                    .setNegativeButton("Cancel",null).create(); show(trust);
                focusCancel(trust);
            }); addButton(content,driver);
            content.addView(text("Only import drivers you trust: they run native code inside this app. Driver support must be included in the app build.",true));
        }
        content.addView(text("Game name", true));
        EditText name = new EditText(activity); name.setText(settings.name.isEmpty() ? title : settings.name);
        name.setSingleLine(true); name.setTextColor(p.ink); name.setMinHeight(dp(48)); name.setTextSize(17);
        name.setContentDescription("Game name"); name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(100)}); content.addView(name);
        String start = snapshot(settings, name.getText().toString().trim());
        BooleanSupplier dirty = () -> !start.equals(snapshot(settings, name.getText().toString().trim()));
        content.addView(text("Game ID: " + settings.id, true));
        AlertDialog.Builder builder = dialog("Game settings", content).setNegativeButton("Cancel", null);
        if (inGame) builder.setNeutralButton("Save for next start", null);
        AlertDialog root = builder
                .setPositiveButton(inGame ? "Save & restart" : "Save", null).create();
        rootDialog = root;
        root.setOnDismissListener(d -> { if (!saving && closed != null) closed.run(); });
        show(root, dirty);
        // the Thor stays in touch mode under a controller; let the first group take focus anyway
        Button opening = firstGroup; opening.setFocusableInTouchMode(true); opening.post(opening::requestFocus);
        if (inGame) root.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(v -> {
            settings.name = name.getText().toString().trim();
            try { settings.save(activity); root.dismiss(); }
            catch (RuntimeException e) { Toast.makeText(activity, "Settings could not be saved. Please try again.", Toast.LENGTH_LONG).show(); }
        });
        root.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            settings.name = name.getText().toString().trim();
            try {
                settings.save(activity); saving = true; root.dismiss(); if (saved != null) saved.run();
            } catch (RuntimeException e) { Toast.makeText(activity, "Settings could not be saved. Please try again.", Toast.LENGTH_LONG).show(); }
        });
    }
    private String bindingLabel(GameSettings draft, String target) {
        List<String> keys=new ArrayList<>();
        for (Map.Entry<String,String> entry:draft.controllerBindings.entrySet())
            if(entry.getValue().equals(target)) keys.add(KeyEvent.keyCodeToString(Integer.parseInt(entry.getKey())).replace("KEYCODE_", "").replaceFirst("^BUTTON_", "").replace('_', ' '));
        return label(target) + " · " + (keys.isEmpty() ? "Default / unassigned" : String.join(", ",keys));
    }
    private void refreshBindings(LinearLayout content, GameSettings draft) {
        for(int i=0;i<content.getChildCount();i++) {
            View child=content.getChildAt(i);
            if(child instanceof Button && child.getTag() instanceof String) ((Button)child).setText(bindingLabel(draft,(String)child.getTag()));
        }
    }
    private void group(String group) {
        GameSettings draft = settings.copy(); LinearLayout content = column();
        String start = snapshot(draft, draft.name);
        content.addView(legend(group.equals("Button mapping") ? "A Change · B Back · X Clears · Start Done · L/R Page" : "A Change · B Back · Start Done · L/R Page"));
        if (group.equals("Display")) {
            content.addView(heading("Picture quality"));
            Button resolution = button("Rendering resolution · " + draft.resolution + "×");
            resolution.setOnClickListener(v -> numbers(draft, resolution, null, "Rendering resolution", new float[]{1, 2, 3}, new String[]{"1× · Best performance", "2× · Sharper picture", "3× · Highest detail"}));
            addButton(content, resolution);
            boolean pack = GameSettings.hasTextures(version, settings.id);
            Switch use = toggle("HD texture pack (optional)", pack && draft.textures);
            use.setEnabled(pack);
            use.setOnCheckedChangeListener((button, checked) -> draft.textures = checked); content.addView(use);
            content.addView(text(pack ? "Uses the imported pack. Higher resolutions and large textures use more GPU memory." : "No texture pack attached. Original textures are used. Import a pack from Game settings if you want one.", true));
        }
        for (GameSettings.Setting setting : GameSettings.ALL) if (setting.group.equals(group)) {
            String section;
            switch (setting.key) {
                case "dual_display": section = "Screens"; break;
                case "filter": section = "Scaling & shape"; break;
                case "show_fps": section = "On-screen info"; break;
                case "stereo": section = "3D"; break;
                case "speed_limit": section = "Everyday"; break;
                case "frame_generation": section = "Frame generation"; break;
                case "warm_before_start": section = "Shader warm-up · fewer stutters"; break;
                case "custom_driver": section = "Custom Vulkan driver"; break;
                case "touch_controls": section = "Touch screen"; break;
                case "motion_enabled": section = "Device motion"; break;
                case "stick_motion": section = "Right stick as motion"; break;
                case "audio_queue_managed": section = "Sound timing"; break;
                case "hardware_renderer": section = "Core · leave at defaults unless troubleshooting"; break;
                case "shader_mode": section = "Shaders"; break;
                case "fast_texture_decode": section = "HD texture loading"; break;
                case "gpu_readback": section = "Driver workarounds"; break;
                default: section = null;
            }
            if (section != null) {
                content.addView(heading(section));
            }
            control(content, draft, setting);
            if (setting.key.equals("frame_generation_flow")) {
                Button lossless = button("Import your Lossless.dll…");
                lossless.setOnClickListener(v -> ((Launcher) activity).importLossless()); addButton(content, lossless);
                TextView state = text(losslessState(), true); content.addView(state);
                state.getViewTreeObserver().addOnWindowFocusChangeListener(focused -> state.setText(losslessState()));
            }
        }
        if (group.equals("Performance")) {
            File home=new File(activity.getFilesDir(),"drivers");
            File[] folders=home.listFiles(f -> f.isDirectory() && f.getName().matches("[0-9a-fA-F-]{36}"));
            Button choose=button("Select Vulkan driver…");
            choose.setOnClickListener(v -> {
                List<String> ids=new ArrayList<>(), names=new ArrayList<>();
                if(folders!=null) for(File folder:folders) try {
                    String library=GameSettings.driverLibrary(folder); ids.add(folder.getName());
                    JSONObject meta=new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(folder,"meta.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
                    names.add(meta.optString("name",library)+" · "+folder.getName().substring(0,8));
                } catch(Exception ignored) {}
                if(ids.isEmpty()) { Toast.makeText(activity,"Import a Vulkan driver ZIP from Game settings first.",Toast.LENGTH_LONG).show(); return; }
                AlertDialog picker=new AlertDialog.Builder(activity, p.dialogTheme()).setTitle("Vulkan driver")
                    .setSingleChoiceItems(names.toArray(new String[0]),ids.indexOf(draft.driver),(d,which) -> {
                        draft.driver=ids.get(which); choose.setText("Driver · "+names.get(which)); d.dismiss();
                    }).setNegativeButton("Cancel",null).create(); show(picker);
            }); addButton(content,choose);
            content.addView(text("Select a driver and enable Use selected custom Vulkan driver. A missing or incompatible driver reports its error when the game starts.",true));
        }
        if (group.equals("Controls")) {
            Button resetTouch=button("Reset touch controls to defaults");
            resetTouch.setOnClickListener(v -> {
                draft.values.put("touch_controls",false); draft.values.put("touch_auto_hide",true);
                for(int i=0;i<content.getChildCount();i++) {
                    View child=content.getChildAt(i);
                    if(child instanceof Switch) {
                        Switch toggle=(Switch)child;
                        if(toggle.getText().toString().equals("Show touch controls")) toggle.setChecked(false);
                        if(toggle.getText().toString().equals("Hide touch controls with a gamepad")) toggle.setChecked(true);
                    }
                }
                Toast.makeText(activity,"Touch-control defaults restored in this draft",Toast.LENGTH_SHORT).show();
            }); addButton(content,resetTouch);
        }
        if (group.equals("Button mapping")) {
            content.addView(heading("Controller bindings"));
            content.addView(text("Select an action, then press a physical controller button. Back cancels capture. Press X on an action here (or hold it) to clear its custom bindings; while capturing, choose Clear binding. Touch buttons keep their printed actions. Fast-forward restores normal speed when released.", true));
            for (String target : GameSettings.BINDING_TARGETS) {
                Button bind = button(bindingLabel(draft,target));
                bind.setOnClickListener(v -> {
                    AlertDialog capture = new AlertDialog.Builder(activity, p.dialogTheme()).setTitle("Bind " + label(target))
                            .setMessage("Press a physical controller button. Back cancels.").setNegativeButton("Cancel", null)
                            .setNeutralButton("Clear binding", (d, w) -> { draft.controllerBindings.values().removeIf(target::equals); refreshBindings(content,draft); }).create();
                    show(capture);
                    capture.setOnKeyListener((d, code, event) -> {
                        if (code == KeyEvent.KEYCODE_BACK || code < 0 || code > 1024) return false;
                        if ((event.getSource() & android.view.InputDevice.SOURCE_GAMEPAD) != android.view.InputDevice.SOURCE_GAMEPAD
                                && (event.getSource() & android.view.InputDevice.SOURCE_DPAD) != android.view.InputDevice.SOURCE_DPAD) return false;
                        if (event.getAction() == KeyEvent.ACTION_UP) {
                            draft.controllerBindings.put(Integer.toString(code),target);
                            refreshBindings(content,draft); d.dismiss();
                        }
                        return true;
                    });
                });
                bind.setOnLongClickListener(v -> { draft.controllerBindings.values().removeIf(target::equals); refreshBindings(content,draft); return true; });
                bind.setTag(target); addButton(content,bind);
            }
            Button reset = button("Reset controller bindings");
            reset.setOnClickListener(v -> { draft.controllerBindings.clear(); refreshBindings(content,draft); Toast.makeText(activity,"Default controller bindings restored in this draft",Toast.LENGTH_SHORT).show(); }); addButton(content,reset);
        }
        if (group.equals("Motion")) content.addView(text("To recalibrate during play, open the game menu and choose Recalibrate motion, then hold the device still.", true));
        if (group.equals("System")) enhancements(content, draft);
        AlertDialog dialog = dialog(group, content).setNegativeButton("Cancel", null).setPositiveButton("Done", (d, which) -> {
            settings.driver=draft.driver;
            settings.controllerBindings.clear(); settings.controllerBindings.putAll(draft.controllerBindings);
            settings.values.clear(); settings.values.putAll(draft.values); settings.resolution = draft.resolution; settings.textures = draft.textures;
            settings.enhancements.clear(); settings.enhancements.addAll(draft.enhancements);
        }).create(); show(dialog, () -> !start.equals(snapshot(draft, draft.name)));
    }
    private String losslessState() {
        return LosslessImport.ready(activity.getFilesDir()) ? "Shaders imported. They apply to every game with Frame generation on." : "Not imported yet. Copy Lossless.dll from your own Lossless Scaling folder to this device and choose it here. It is translated on the device and the app keeps no copy of the file.";
    }
    private Switch toggle(String label, boolean checked) {
        Switch view = new Switch(activity); view.setText(label); view.setTextColor(p.ink); view.setTextSize(16);
        view.setMinHeight(dp(56)); view.setPadding(dp(8), dp(8), dp(8), dp(8)); view.setChecked(checked); focusRing(view);
        view.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}}, new int[]{p.accent, p.muted}));
        view.setTrackTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}}, new int[]{0x73000000 | (p.accent & 0xffffff), p.border})); return view;
    }
    private static String label(String value) {
        switch (value) {
            case "fast_forward": return "Fast-forward (hold)";
            case "gpu": return "GPU";
            case "cpu": return "CPU";
            case "async": return "Asynchronous";
            case "wait": return "Wait for compilation";
            case "stacked": return "Top over bottom";
            case "side_by_side": return "Side by side";
            case "large_top": return "Large top, small bottom";
            case "top_only": return "Top screen only";
            case "bottom_only": return "Bottom screen only";
            case "circle_pad": return "Circle Pad";
            case "c_stick": return "C-stick";
            case "cursor": return "Touch cursor";
            case "half_side_by_side": return "Side by side, half width";
            case "anaglyph": return "Red/cyan glasses";
            default: return Character.toUpperCase(value.charAt(0)) + value.substring(1).replace('_', ' ');
        }
    }
    private void control(LinearLayout content, GameSettings draft, GameSettings.Setting setting) {
        Object value = draft.values.get(setting.key);
        if (value instanceof Boolean) {
            Switch toggle = toggle(setting.label, (Boolean) value);
            toggle.setTag(setting.key);
            toggle.setOnCheckedChangeListener((button, checked) -> {
                draft.values.put(setting.key, checked);
                // the two draw in-between pictures in different ways, only one can run
                String rival = setting.key.equals("frame_generation") ? "interpolate" : setting.key.equals("interpolate") ? "frame_generation" : null;
                if (checked && rival != null) ((Switch) content.findViewWithTag(rival)).setChecked(false);
            }); content.addView(toggle);
        } else if (setting.choices != null) {
            Button choose = button(setting.label + " · " + label((String) value));
            choose.setOnClickListener(v -> {
                String[] names = Arrays.stream(setting.choices).map(SettingsDialog::label).toArray(String[]::new), shown = names.clone();
                for (int i = 0; i < shown.length; i++) if (setting.choices[i].equals(setting.initial)) shown[i] += " (default)";
                AlertDialog choices = new AlertDialog.Builder(activity, p.dialogTheme()).setTitle(setting.label)
                        .setSingleChoiceItems(shown, Arrays.asList(setting.choices).indexOf(draft.values.get(setting.key)), (d, which) -> {
                            draft.values.put(setting.key, setting.choices[which]); choose.setText(setting.label + " · " + names[which]); d.dismiss();
                        }).setNegativeButton("Cancel", null).create(); show(choices);
            }); addButton(content, choose);
        } else if (setting.key.equals("top_width") || setting.key.equals("frame_cap") || setting.key.equals("speed_limit") || setting.key.equals("fast_forward_speed") || setting.key.equals("region") || setting.key.equals("language") || setting.key.equals("frame_generation_multiplier") || setting.key.equals("frame_generation_flow")) {
            float[] values; String[] names;
            switch (setting.key) {
                case "top_width": values = new float[]{400, 426.66666f, 560}; names = new String[]{"Original · 5:3", "16:9", "21:9"}; break;
                case "frame_cap": values = new float[]{0, 30, 60, 90, 120, 144, 240}; names = new String[]{"Unlimited", "30 FPS", "60 FPS", "90 FPS", "120 FPS", "144 FPS", "240 FPS"}; break;
                case "fast_forward_speed":
                case "speed_limit": values = new float[]{0, 100, 150, 200, 300, 400}; names = new String[]{"Unlimited", "Normal · 100%", "1.5× · 150%", "2× · 200%", "3× · 300%", "4× · 400%"}; break;
                case "frame_generation_multiplier": values = new float[]{2, 4}; names = new String[]{"2×", "4×"}; break;
                case "frame_generation_flow": values = new float[]{25, 50, 75, 100}; names = new String[]{"25%", "50%", "75%", "100%"}; break;
                case "region": values = new float[]{-1, 0, 1, 2, 3, 4, 5, 6}; names = new String[]{"Automatic", "Japan", "USA", "Europe", "Australia", "China", "Korea", "Taiwan"}; break;
                default: values = new float[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11}; names = new String[]{"Japanese", "English", "French", "German", "Italian", "Spanish", "Chinese (Simplified)", "Korean", "Dutch", "Portuguese", "Russian", "Chinese (Traditional)"};
            }
            int index = -1; for (int i = 0; i < values.length; i++) if (Math.abs(values[i] - draft.number(setting.key)) < 0.01f) index = i;
            Button choose = button(setting.label + " · " + (index >= 0 ? names[index] : draft.values.get(setting.key)));
            choose.setOnClickListener(v -> numbers(draft, choose, setting, setting.label, values, names)); addButton(content, choose);
        } else {
            float step = setting.max <= 1 ? 0.05f : setting.key.equals("cursor_speed") ? 0.1f : 0.25f;
            TextView caption = text(setting.label + " · " + format(draft.number(setting.key)), false); content.addView(caption);
            SeekBar slider = new SeekBar(activity); slider.setMinHeight(dp(48)); slider.setPadding(dp(16), 0, dp(16), 0); focusRing(slider); slider.setProgressTintList(ColorStateList.valueOf(p.accent)); slider.setProgressBackgroundTintList(ColorStateList.valueOf(p.border));
            slider.setThumbTintList(ColorStateList.valueOf(p.accent)); slider.setMax(Math.round((setting.max - setting.min) / step));
            slider.setProgress(Math.round((draft.number(setting.key) - setting.min) / step)); slider.setKeyProgressIncrement(1);
            slider.setContentDescription(caption.getText()); slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                    if (!user) return;
                    float number = Math.min(setting.max, setting.min + progress * step); draft.values.put(setting.key, number);
                    caption.setText(setting.label + " · " + format(number)); bar.setContentDescription(caption.getText());
                }
                public void onStartTrackingTouch(SeekBar bar) {}
                public void onStopTrackingTouch(SeekBar bar) {}
            }); content.addView(slider, new LinearLayout.LayoutParams(-1, -2));
        }
        content.addView(text(setting.help, true));
    }
    private static String format(float value) { return String.format(Locale.getDefault(), "%.2f", value); }
    private void numbers(GameSettings draft, Button button, GameSettings.Setting setting, String heading, float[] values, String[] names) {
        float current = setting == null ? draft.resolution : draft.number(setting.key);
        int selected = -1; for (int i = 0; i < values.length; i++) if (Math.abs(values[i] - current) < 0.01f) selected = i;
        float initial = setting == null ? 1 : ((Number) setting.initial).floatValue();
        String[] shown = names.clone();
        for (int i = 0; i < values.length; i++) if (Math.abs(values[i] - initial) < 0.01f) shown[i] += " (default)";
        AlertDialog choices = new AlertDialog.Builder(activity, p.dialogTheme()).setTitle(heading).setSingleChoiceItems(shown, selected, (d, which) -> {
            if (setting == null) draft.resolution = (int) values[which];
            else draft.values.put(setting.key, setting.normalize(values[which]));
            button.setText(heading + " · " + names[which]); d.dismiss();
        }).setNegativeButton("Cancel", null).create(); show(choices);
    }
    private void enhancements(LinearLayout content, GameSettings draft) {
        content.addView(heading("Game enhancements"));
        File metadata = version == null ? null : new File(version, "data/available-enhancements.json");
        if (metadata == null || !metadata.isFile()) {
            content.addView(text("Start this game once to discover enhancements supported for this exact game revision.", true)); return;
        }
        try (InputStream input = new FileInputStream(metadata)) {
            byte[] bytes = new byte[65537]; int used = 0, n;
            while (used < bytes.length && (n = input.read(bytes, used, bytes.length - used)) != -1) used += n;
            if (used > 65536) throw new IOException("Enhancement metadata is too large");
            JSONArray entries = new JSONArray(new String(bytes, 0, used, java.nio.charset.StandardCharsets.UTF_8));
            if (entries.length() > 32) throw new IOException("Too many enhancements");
            if (entries.length() == 0) content.addView(text("No upstream enhancements are listed for this game revision.", true));
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.getJSONObject(i); String name = entry.getString("name");
                if (name.isEmpty() || name.length() > 120) continue;
                Switch enabled = toggle(name, draft.enhancements.contains(name));
                enabled.setOnCheckedChangeListener((button, checked) -> { if (checked) draft.enhancements.add(name); else draft.enhancements.remove(name); }); content.addView(enabled);
                content.addView(text(entry.optString("note", ""), true));
            }
        } catch (IOException | JSONException e) { content.addView(text("Enhancement information is unavailable. Start the game again to refresh it.", true)); }
    }
}
