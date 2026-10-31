package io.github.zakuro.android;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.drawable.*;
import android.text.InputFilter;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;

/** One short category at a time, with native focus, font scaling and controller navigation. */
final class SettingsDialog {
    private final Activity activity;
    private final File version;
    private final GameSettings settings;
    private final String title;
    private final int surface, ink, muted, accent, border, tint;
    private final Runnable saved, closed, repair, textures;
    private final boolean inGame;
    private boolean saving;
    private AlertDialog rootDialog;

    static void show(Activity activity, String id, File version, String title, boolean inGame,
                     Runnable saved, Runnable closed, Runnable repair, Runnable textures) {
        new SettingsDialog(activity, id, version, title, inGame, saved, closed, repair, textures).root();
    }
    private SettingsDialog(Activity activity, String id, File version, String title, boolean inGame,
                           Runnable saved, Runnable closed, Runnable repair, Runnable textures) {
        this.activity = activity; this.version = version; this.title = title; this.inGame = inGame;
        this.saved = saved; this.closed = closed; this.repair = repair; this.textures = textures;
        settings = GameSettings.load(activity, id);
        boolean dark = (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        surface = dark ? 0xff1e2924 : 0xfffcfdfa; ink = dark ? 0xffeef3eb : 0xff192a23;
        muted = dark ? 0xffaabcb1 : 0xff52665a; accent = dark ? 0xffb5d2ab : 0xff365b41;
        border = dark ? 0xff41574b : 0xffb9c9bb; tint = dark ? 0xff314c3b : 0xffdcebd8;
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private LinearLayout column() {
        LinearLayout content = new LinearLayout(activity); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), dp(16)); content.setBackgroundColor(surface); return content;
    }
    private TextView text(String message, boolean secondary) {
        TextView view = new TextView(activity); view.setText(message); view.setTextColor(secondary ? muted : ink);
        view.setTextSize(secondary ? 14 : 17); view.setPadding(0, dp(6), 0, dp(10)); return view;
    }
    private GradientDrawable shape(int fill, int width) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(fill); shape.setCornerRadius(dp(10));
        shape.setStroke(dp(width), width > 1 ? accent : border); return shape;
    }
    private Button button(String label) {
        Button button = new Button(activity); button.setText(label); button.setTextColor(ink); button.setTextSize(16);
        button.setAllCaps(false); button.setMinHeight(dp(52)); button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setPadding(dp(14), dp(12), dp(14), dp(12));
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused}, shape(tint, 3));
        background.addState(new int[]{android.R.attr.state_pressed}, shape(tint, 3));
        background.addState(new int[]{}, shape(surface, 1)); button.setBackground(background);
        return button;
    }
    private void addButton(LinearLayout content, Button button) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2); size.setMargins(0, dp(5), 0, dp(5)); content.addView(button, size);
    }
    private AlertDialog.Builder dialog(String heading, LinearLayout content) {
        ScrollView scroll = new ScrollView(activity); scroll.setFillViewport(true); scroll.addView(content);
        return new AlertDialog.Builder(activity).setTitle(heading).setView(scroll);
    }
    private void show(AlertDialog dialog) {
        dialog.setOnShowListener(d -> {
            dialog.getWindow().setBackgroundDrawable(shape(surface, 1));
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            for (int which : new int[]{DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_NEUTRAL}) {
                Button button = dialog.getButton(which);
                if (button != null) { button.setAllCaps(false); button.setTextColor(accent); button.setMinHeight(dp(48)); }
            }
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
            if (code == KeyEvent.KEYCODE_BUTTON_B) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    WindowInsets insets = dialog.getWindow().getDecorView().getRootWindowInsets();
                    if (insets != null && insets.isVisible(WindowInsets.Type.ime())) dialog.getWindow().getInsetsController().hide(WindowInsets.Type.ime());
                    else dialog.cancel();
                }
                return true;
            }
            return false;
        });
        dialog.show();
    }
    private void root() {
        LinearLayout content = column();
        content.addView(text(inGame ? "Changes apply after Save & restart. In-game saves are preserved; unsaved progress restarts." : "Settings are saved for this game and apply the next time you play.", true));
        content.addView(text("Game name", true));
        EditText name = new EditText(activity); name.setText(settings.name.isEmpty() ? title : settings.name);
        name.setSingleLine(true); name.setTextColor(ink); name.setMinHeight(dp(48)); name.setTextSize(17);
        name.setContentDescription("Game name"); name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(100)}); content.addView(name);
        for (String group : GameSettings.GROUPS) {
            Button button = button(group); button.setContentDescription(group + " settings for " + title);
            button.setOnClickListener(v -> group(group)); addButton(content, button);
        }
        if (!inGame) {
            if (textures != null) {
                Button attach = button("Import texture pack…"); attach.setOnClickListener(v -> textures.run()); addButton(content, attach);
                content.addView(text("Optional. Attach a pack without copying your game again.", true));
            }
            if (repair != null) {
                Button replace = button("Replace native game code…"); replace.setOnClickListener(v -> repair.run()); addButton(content, replace);
                content.addView(text("Choose a trusted Android ABI 4 title.so. The ROM, textures and saves stay in place.", true));
            }
        }
        if (!inGame && activity instanceof Launcher) {
            Button driver = button("Import Vulkan driver ZIP…");
            driver.setOnClickListener(v -> {
                AlertDialog trust=new AlertDialog.Builder(activity).setTitle("Import a trusted driver?")
                    .setMessage("A custom Vulkan driver runs native code inside this app and can access its games and saves. Only choose a ZIP from a source you trust.")
                    .setPositiveButton("Choose ZIP",(d,w) -> { rootDialog.dismiss(); ((Launcher)activity).importDriver(settings.id); })
                    .setNegativeButton("Cancel",null).create(); show(trust);
            }); addButton(content,driver);
            content.addView(text("Only import drivers you trust: they run native code inside this app. Driver support must be included in the app build.",true));
        }
        content.addView(text("Game ID: " + settings.id, true));
        AlertDialog.Builder builder = dialog("Game settings", content).setNegativeButton("Cancel", null);
        if (inGame) builder.setNeutralButton("Save for next start", null);
        AlertDialog root = builder
                .setPositiveButton(inGame ? "Save & restart" : "Save", null).create();
        rootDialog = root;
        root.setOnDismissListener(d -> { if (!saving && closed != null) closed.run(); });
        show(root);
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
    private void group(String group) {
        GameSettings draft = settings.copy(); LinearLayout content = column();
        if (group.equals("Display")) {
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
            if (setting.key.equals("shader_mode") || setting.key.equals("custom_driver")) {
                TextView heading=text(setting.key.equals("shader_mode") ? "Shader and depth troubleshooting" : "Runtime and presentation",false);
                heading.setAccessibilityHeading(true); content.addView(heading);
            }
            control(content, draft, setting);
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
                AlertDialog picker=new AlertDialog.Builder(activity).setTitle("Vulkan driver")
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
            content.addView(text("Controller bindings", false));
            content.addView(text("Bind a physical controller button to a console action. Back cancels capture. Touch buttons keep their printed actions.", true));
            for (String target : GameSettings.BINDING_TARGETS) {
                Button bind = button("Bind " + label(target) + "…");
                bind.setOnClickListener(v -> {
                    AlertDialog capture = new AlertDialog.Builder(activity).setTitle("Bind " + label(target))
                            .setMessage("Press a physical controller button. Back cancels.").setNegativeButton("Cancel", null).create();
                    capture.setOnKeyListener((d, code, event) -> {
                        if (code == KeyEvent.KEYCODE_BACK || code < 0 || code > 1024) return false;
                        if ((event.getSource() & android.view.InputDevice.SOURCE_GAMEPAD) != android.view.InputDevice.SOURCE_GAMEPAD
                                && (event.getSource() & android.view.InputDevice.SOURCE_DPAD) != android.view.InputDevice.SOURCE_DPAD) return false;
                        if (event.getAction() == KeyEvent.ACTION_UP) {
                            draft.controllerBindings.put(Integer.toString(code),target);
                            bind.setText(label(target) + " · " + KeyEvent.keyCodeToString(code)); d.dismiss();
                        }
                        return true;
                    }); capture.show();
                }); addButton(content,bind);
            }
            Button reset = button("Reset controller bindings");
            reset.setOnClickListener(v -> { draft.controllerBindings.clear(); Toast.makeText(activity,"Default controller bindings restored in this draft",Toast.LENGTH_SHORT).show(); }); addButton(content,reset);
        }
        if (group.equals("Motion")) content.addView(text("To recalibrate during play, open the game menu and choose Recalibrate motion, then hold the device still.", true));
        if (group.equals("System")) enhancements(content, draft);
        AlertDialog dialog = dialog(group, content).setNegativeButton("Cancel", null).setPositiveButton("Use these settings", (d, which) -> {
            settings.driver=draft.driver;
            settings.controllerBindings.clear(); settings.controllerBindings.putAll(draft.controllerBindings);
            settings.values.clear(); settings.values.putAll(draft.values); settings.resolution = draft.resolution; settings.textures = draft.textures;
            settings.enhancements.clear(); settings.enhancements.addAll(draft.enhancements);
        }).create(); show(dialog);
    }
    private Switch toggle(String label, boolean checked) {
        Switch view = new Switch(activity); view.setText(label); view.setTextColor(ink); view.setTextSize(16);
        view.setMinHeight(dp(56)); view.setPadding(0, dp(8), 0, dp(8)); view.setChecked(checked);
        view.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}}, new int[]{accent, muted}));
        view.setTrackTintList(ColorStateList.valueOf(border)); return view;
    }
    private static String label(String value) {
        switch (value) {
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
            toggle.setOnCheckedChangeListener((button, checked) -> draft.values.put(setting.key, checked)); content.addView(toggle);
        } else if (setting.choices != null) {
            Button choose = button(setting.label + " · " + label((String) value));
            choose.setOnClickListener(v -> {
                String[] names = Arrays.stream(setting.choices).map(SettingsDialog::label).toArray(String[]::new);
                AlertDialog choices = new AlertDialog.Builder(activity).setTitle(setting.label)
                        .setSingleChoiceItems(names, Arrays.asList(setting.choices).indexOf(draft.values.get(setting.key)), (d, which) -> {
                            draft.values.put(setting.key, setting.choices[which]); choose.setText(setting.label + " · " + names[which]); d.dismiss();
                        }).setNegativeButton("Cancel", null).create(); show(choices);
            }); addButton(content, choose);
        } else if (setting.key.equals("top_width") || setting.key.equals("frame_cap") || setting.key.equals("region") || setting.key.equals("language")) {
            float[] values; String[] names;
            switch (setting.key) {
                case "top_width": values = new float[]{400, 426.66666f, 560}; names = new String[]{"Original · 5:3", "16:9", "21:9"}; break;
                case "frame_cap": values = new float[]{0, 30, 60, 90, 120, 144, 240}; names = new String[]{"Unlimited", "30 FPS", "60 FPS", "90 FPS", "120 FPS", "144 FPS", "240 FPS"}; break;
                case "region": values = new float[]{-1, 0, 1, 2, 3, 4, 5, 6}; names = new String[]{"Automatic", "Japan", "USA", "Europe", "Australia", "China", "Korea", "Taiwan"}; break;
                default: values = new float[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11}; names = new String[]{"Japanese", "English", "French", "German", "Italian", "Spanish", "Chinese (Simplified)", "Korean", "Dutch", "Portuguese", "Russian", "Chinese (Traditional)"};
            }
            int index = -1; for (int i = 0; i < values.length; i++) if (Math.abs(values[i] - draft.number(setting.key)) < 0.01f) index = i;
            Button choose = button(setting.label + " · " + (index >= 0 ? names[index] : draft.values.get(setting.key)));
            choose.setOnClickListener(v -> numbers(draft, choose, setting, setting.label, values, names)); addButton(content, choose);
        } else {
            float step = setting.max <= 1 ? 0.05f : setting.key.equals("cursor_speed") ? 0.1f : 0.25f;
            TextView caption = text(setting.label + " · " + format(draft.number(setting.key)), false); content.addView(caption);
            SeekBar slider = new SeekBar(activity); slider.setMinHeight(dp(48)); slider.setProgressTintList(ColorStateList.valueOf(accent));
            slider.setThumbTintList(ColorStateList.valueOf(accent)); slider.setMax(Math.round((setting.max - setting.min) / step));
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
        AlertDialog choices = new AlertDialog.Builder(activity).setTitle(heading).setSingleChoiceItems(names, selected, (d, which) -> {
            if (setting == null) draft.resolution = (int) values[which];
            else draft.values.put(setting.key, setting.normalize(values[which]));
            button.setText(heading + " · " + names[which]); d.dismiss();
        }).setNegativeButton("Cancel", null).create(); show(choices);
    }
    private void enhancements(LinearLayout content, GameSettings draft) {
        TextView heading = text("Game enhancements", false); heading.setAccessibilityHeading(true); content.addView(heading);
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
