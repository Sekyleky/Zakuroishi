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
    private final boolean inGame, defaults; // defaults: the All games page, no version and no file actions
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
        settings = GameSettings.load(activity, id); defaults = GameSettings.DEFAULTS.equals(id);
        p = new Palette(activity);
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private LinearLayout column() {
        LinearLayout content = new LinearLayout(activity); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), dp(16)); content.setBackgroundColor(p.raised); return content;
    }
    private TextView text(String message, boolean secondary) {
        TextView view = new TextView(activity); view.setText(message); view.setTextColor(secondary ? p.muted : p.ink);
        view.setTextSize(secondary ? 16 : 17); view.setPadding(0, dp(6), 0, dp(10)); return view;
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
    private void focusCancel(AlertDialog dialog) { Button cancel = dialog.getButton(DialogInterface.BUTTON_NEGATIVE); cancel.post(cancel::requestFocusFromTouch); } // FromTouch: plain requestFocus lit nothing in touch mode, so confirms opened with no button selected
    // The Thor stays in touch mode under a controller: FromTouch leaves it, so the ring shows and A acts at once.
    private static void focusFirst(ViewGroup content) {
        for (int i = 0; i < content.getChildCount(); i++) {
            View child = content.getChildAt(i);
            if (child.isFocusable() && child.isEnabled() && child.getVisibility() == View.VISIBLE) { child.post(child::requestFocusFromTouch); return; }
        }
    }
    /** Choice lists open on the checked row. */
    private void choices(AlertDialog dialog, int checked) {
        show(dialog); ListView list = dialog.getListView();
        list.post(() -> { list.requestFocusFromTouch(); list.setSelection(Math.max(0, checked)); });
    }
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
        View[] pressed = new View[1]; // the view A went down on, so a focus move before release cannot redirect the click
        dialog.setOnKeyListener((d, code, event) -> {
            if (code == KeyEvent.KEYCODE_BUTTON_A) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) pressed[0] = dialog.getCurrentFocus();
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    ListView items = dialog.getListView();
                    int selected = items == null ? -1 : items.getSelectedItemPosition();
                    View target = pressed[0]; pressed[0] = null;
                    if (selected >= 0 && items.hasFocus()) items.performItemClick(items.getSelectedView(), selected, items.getItemIdAtPosition(selected));
                    else if (target != null && target.isAttachedToWindow() && target.isEnabled()) {
                        if (target instanceof EditText) { target.requestFocus(); activity.getSystemService(android.view.inputmethod.InputMethodManager.class).showSoftInput(target, 0); } // the dialog starts with the keyboard hidden; A opens it
                        else target.performClick();
                    }
                }
                return true;
            }
            Button positive = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            if (code == KeyEvent.KEYCODE_BUTTON_START && positive != null) { // a destructive confirm needs a deliberate press on its button: there Start does nothing
                if (event.getAction() == KeyEvent.ACTION_UP && !destructive) positive.performClick();
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
    /** The parts of the game's mods, one Remove each, and Remove all. */
    private void mods() {
        LinearLayout content = column(); content.addView(legend("A Remove · B Back · L/R Page"));
        AlertDialog[] list = new AlertDialog[1];
        for (String part : GameImport.modParts(version, settings.id)) {
            String label = GameImport.modPartLabel(part);
            Button remove = button("Remove " + label + " · " + String.format(Locale.ROOT, "%.1f MB", GameImport.size(GameImport.modsDir(version, settings.id), part) / 1048576.0));
            remove.setOnClickListener(v -> { list[0].dismiss(); removeMods(label, part); }); addButton(content, remove);
        }
        Button all = button("Remove all mods"); all.setOnClickListener(v -> { list[0].dismiss(); removeMods("all mods", GameImport.MOD_PARTS); }); addButton(content, all);
        list[0] = dialog("Mods · " + title, content).setNegativeButton("Back", null).create(); show(list[0]); focusFirst(content);
    }
    private void removeMods(String what, String... parts) {
        confirm("Remove " + what + " for " + title + "?", "Deletes it from the mods imported for this game. Other mod parts, its texture pack, ROM and saves are kept.", "Remove", "Cancel", () -> {
            String id = settings.id;
            new Thread(() -> {
                String failure = null;
                try { GameImport.removeMods(activity, id, parts); }
                catch (Exception e) { failure = e.getMessage() == null ? "Could not remove the mods." : e.getMessage(); }
                String result = failure;
                activity.runOnUiThread(() -> {
                    Toast.makeText(activity, result == null ? "Mods removed." : result, Toast.LENGTH_LONG).show();
                    if (result == null && rootDialog != null) closeAll();
                });
            }, "remove-mods").start();
        });
    }
    /** Cheats change a file the game reads at start, so not while an import or a game is running (the same wait as the storage screen). */
    private boolean cheatsBlocked() {
        if (inGame) return false; // the running game itself: only it reads the file, and only when told to reload
        String why = activity instanceof Launcher && ((Launcher) activity).busy() ? "An import is running. Wait for it to finish, then try again."
                : StorageDialog.gameRunning(activity) ? "A game is open. Exit it to the library (not just Home), then try again." : null;
        if (why != null) Toast.makeText(activity, why, Toast.LENGTH_LONG).show();
        return why != null;
    }
    private void removeAddons() {
        if (cheatsBlocked()) return;
        confirm("Remove updates and DLC for " + title + "?", "Deletes the update, DLC and Download Play files imported for this game. The game, its mods, cheats and saves are kept.", "Remove", "Cancel", () -> {
            try { GameImport.removeAddons(activity, settings.id); Toast.makeText(activity, "Updates and DLC removed.", Toast.LENGTH_LONG).show(); closeAll(); }
            catch (Exception e) { Toast.makeText(activity, e.getMessage() == null ? "Could not remove the files." : e.getMessage(), Toast.LENGTH_LONG).show(); }
        });
    }
    /** A name and code lines typed in; on Add the cheat file gains it and an open list is shown again. */
    private void addCheat(AlertDialog openList) {
        LinearLayout content = column();
        EditText name = new EditText(activity); name.setSingleLine(true); name.setHint("Name, like Infinite money"); name.setTextColor(p.ink); name.setMinHeight(dp(48));
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(64)}); content.addView(name);
        EditText codes = new EditText(activity); codes.setHint("0123ABCD 0123ABCD, one per line"); codes.setTextColor(p.ink); codes.setMinLines(4);
        codes.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS); content.addView(codes);
        content.addView(text("Cheats change the game's memory and can damage a save. Back up the save first.", true));
        name.requestFocus(); // first field has focus without a D-pad press; A opens the keyboard
        AlertDialog ask = dialog("Add cheat · " + title, content).setPositiveButton("Add", null).setNegativeButton("Cancel", null).create();
        show(ask);
        ask.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                GameImport.addCheat(activity, settings.id, name.getText().toString(), codes.getText().toString());
                if (activity instanceof Game) ((Game) activity).reloadCheats();
                ask.dismiss(); if (openList != null) openList.dismiss();
                Toast.makeText(activity, inGame ? "Cheat added and on." : "Cheat added. It applies the next time the game starts.", Toast.LENGTH_LONG).show();
                cheats();
            } catch (Exception e) { Toast.makeText(activity, e.getMessage() == null ? "Could not add the cheat." : e.getMessage(), Toast.LENGTH_LONG).show(); }
        });
    }
    private void removeCheats() {
        if (cheatsBlocked()) return;
        confirm("Remove cheats for " + title + "?", "Deletes the cheat file imported for this game. Its mods, texture pack, ROM and saves are kept. What a cheat already changed in a save stays there.", "Remove", "Cancel", () -> {
            String id = settings.id;
            new Thread(() -> {
                String failure = null;
                try { GameImport.removeCheats(activity, id); }
                catch (Exception e) { failure = e.getMessage() == null ? "Could not remove the cheats." : e.getMessage(); }
                String result = failure;
                activity.runOnUiThread(() -> {
                    Toast.makeText(activity, result == null ? "Cheats removed." : result, Toast.LENGTH_LONG).show();
                    if (result == null && rootDialog != null) closeAll();
                });
            }, "remove-cheats").start();
        });
    }
    /** One switch per cheat in the game's file; each change rewrites its marker line at once. */
    private void cheats() {
        if (cheatsBlocked()) return;
        String text; try { text = GameImport.readCheats(version, settings.id); } catch (IOException e) { text = null; }
        if (text == null) { Toast.makeText(activity, "This game has no readable cheat file.", Toast.LENGTH_LONG).show(); return; }
        LinearLayout content = column(); content.addView(legend("A Switch · B Back · L/R Page"));
        content.addView(text("Cheats change the game's memory and can damage a save. Back up the save first. " + (inGame ? "A switch applies at once." : "Changes apply the next time the game starts."), true));
        List<String> names = GameImport.cheatNames(text);
        AlertDialog[] list = new AlertDialog[1];
        Button add = button("Add cheat…"); add.setOnClickListener(v -> addCheat(list[0])); addButton(content, add);
        for (String name : names.subList(0, Math.min(names.size(), 200))) { // ponytail: 200 rows, real files have dozens; a huge list would stall the screen
            Switch use = toggle(name, GameImport.cheatEnabled(text, name));
            use.setOnCheckedChangeListener((button, checked) -> {
                try { GameImport.switchCheat(activity, settings.id, name, checked); if (activity instanceof Game) ((Game) activity).reloadCheats(); }
                catch (Exception e) { Toast.makeText(activity, e.getMessage() == null ? "Could not save the cheat." : e.getMessage(), Toast.LENGTH_LONG).show(); list[0].dismiss(); }
            });
            content.addView(use);
        }
        if (names.size() > 200) content.addView(text("Showing the first 200 of " + names.size() + " cheats.", true));
        list[0] = dialog("Cheats · " + title, content).setNegativeButton("Back", null).create(); show(list[0]); focusFirst(content);
    }
    private void root() {
        LinearLayout content = column();
        content.addView(legend("A Select · B Back · Start Save · L/R Page"));
        content.addView(text(defaults ? "Every game uses these unless its own settings change them." : inGame ? "Changes apply after Save & restart. In-game saves are preserved; unsaved progress restarts. Clock and battery switch without a restart." : "Settings are saved for this game and apply the next time you play. Settings marked changed differ from All games.", true));
        rootRows = new LinearLayout(activity); rootRows.setOrientation(LinearLayout.VERTICAL); content.addView(rootRows);
        fillRoot(rootRows);
        EditText name = new EditText(activity); name.setText(settings.name.isEmpty() && !defaults ? title : settings.name);
        name.setSingleLine(true); name.setTextColor(p.ink); name.setMinHeight(dp(48)); name.setTextSize(17);
        name.setContentDescription("Game name"); name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(100)});
        if (!defaults) { content.addView(text("Game name", true)); content.addView(name); content.addView(text("Game ID: " + settings.id, true)); }
        String start = snapshot(settings, name.getText().toString().trim());
        BooleanSupplier dirty = () -> !start.equals(snapshot(settings, name.getText().toString().trim()));
        AlertDialog.Builder builder = dialog(defaults ? title : "Game settings", content).setNegativeButton("Cancel", null);
        if (inGame) builder.setNeutralButton("Save for next start", null);
        AlertDialog root = builder
                .setPositiveButton(inGame ? "Save & restart" : "Save", null).create();
        rootDialog = root;
        root.setOnDismissListener(d -> { if (!saving && closed != null) closed.run(); });
        show(root, dirty);
        focusFirst(rootRows);
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
    private LinearLayout rootRows;
    private String presetLabel(boolean pack) { int now = settings.preset(pack); return "Preset · " + (now < 0 ? "Custom" : GameSettings.PRESETS[now]); }
    /** Keeps the preset row true while its values change on the simple page. */
    private void syncPreset(GameSettings draft) {
        Button preset = draft != settings || rootRows == null ? null : rootRows.findViewWithTag("preset");
        if (preset != null) preset.setText(presetLabel(!defaults && GameSettings.hasTextures(version, settings.id)));
    }
    /** The simple page: preset, picture, everyday settings, then the way down to everything else. Edits the root draft directly. */
    private void fillRoot(LinearLayout rows) {
        boolean pack = !defaults && GameSettings.hasTextures(version, settings.id);
        Button preset = button(presetLabel(pack)); preset.setTag("preset");
        preset.setOnClickListener(v -> {
            String[] shown = {"Battery · 1×, 30 FPS, original textures", "Balanced · 2×, 60 FPS, HD textures", "Quality · 3×, 60 FPS, HD textures"};
            int now = settings.preset(pack);
            AlertDialog list = new AlertDialog.Builder(activity, p.dialogTheme()).setTitle("Preset").setSingleChoiceItems(shown, now, (d, which) -> {
                settings.preset(which); d.dismiss(); refreshRoot();
            }).setNegativeButton("Cancel", null).create(); choices(list, now);
        }); addButton(rows, preset);
        rows.addView(text("Sets resolution, HD textures, frame rate cap and in-between pictures at once. Change any of them afterwards.", true));
        picture(rows, settings);
        for (GameSettings.Setting setting : GameSettings.ALL) if (GameSettings.SIMPLE.contains(setting.key)) control(rows, settings, setting);
        Button all = button("All settings…"); all.setTag("all"); all.setOnClickListener(v -> groups()); addButton(rows, all);
        Button mapping = button("Button mapping…"); mapping.setTag("mapping"); mapping.setOnClickListener(v -> group("Button mapping")); addButton(rows, mapping);
        if (!inGame && !defaults) { Button files = button("Game files…"); files.setTag("files"); files.setOnClickListener(v -> files()); addButton(rows, files); }
        // in game: switch the imported cheats; each switch reloads them in the running game, as the desktop's Reload cheats
        if (inGame && version != null && GameImport.cheatsFile(version, settings.id).isFile()) { Button cheats = button("Cheats…"); cheats.setTag("cheats"); cheats.setOnClickListener(v -> cheats()); addButton(rows, cheats); }
        if (!defaults) {
            Button reset = button("Use defaults for this game"); reset.setTag("reset");
            reset.setOnClickListener(v -> { settings.useDefaults(); refreshRoot(); Toast.makeText(activity, "This game now follows All games. Save to keep it.", Toast.LENGTH_LONG).show(); });
            addButton(rows, reset);
            rows.addView(text("Drops this game's own choices, marked changed, so it follows the settings for all games. Name, texture pack, driver and enhancements stay.", true));
        }
    }
    /** Rebuilds the simple page after the draft changed elsewhere, keeping the focus where it was when possible. */
    private void refreshRoot() {
        if (rootRows == null) return;
        View focused = rootDialog == null ? null : rootDialog.getCurrentFocus();
        Object tag = focused == null ? null : focused.getTag();
        rootRows.removeAllViews(); fillRoot(rootRows);
        View again = tag == null ? null : rootRows.findViewWithTag(tag);
        if (again != null) again.post(again::requestFocusFromTouch); else focusFirst(rootRows);
    }
    private void groups() {
        LinearLayout content = column(); content.addView(legend("A Open · B Back · L/R Page"));
        for (String group : GameSettings.GROUPS) {
            Button button = button(group); button.setContentDescription(group + " settings for " + title);
            button.setOnClickListener(v -> group(group)); addButton(content, button);
        }
        AlertDialog list = dialog("All settings", content).setNegativeButton("Back", null).create(); show(list); focusFirst(content);
    }
    private AlertDialog filesDialog;
    private static SettingsDialog open; // the dialog whose Game files page is showing
    /** Rebuilds an open Game files page so a fresh import shows. */
    static void refreshFiles() { SettingsDialog d = open; if (d != null && d.filesDialog != null && d.filesDialog.isShowing()) { d.filesDialog.dismiss(); d.files(); } }
    private void closeAll() { if (filesDialog != null) filesDialog.dismiss(); rootDialog.dismiss(); }
    /** Texture pack, mods, native code, saves, shader cache and driver: library only, one level below the simple page. */
    private void files() {
        LinearLayout content = column(); content.addView(legend("A Open · B Back · L/R Page"));
        if (version != null) {
            if (textures != null) {
                Button attach = button("Import texture pack…"); attach.setOnClickListener(v -> textures.run()); addButton(content, attach);
                content.addView(text("Optional. Attach a pack without copying your game again.", true));
            }
            if (mods != null) {
                Button mod = button("Import mod…"); mod.setOnClickListener(v -> importMod()); addButton(content, mod);
                content.addView(text("Optional. Choose a Luma3DS or Citra mod folder. You can add it to the current mods or replace them. A mod that changes game code runs best with a title.so built with it, and only one code mod can be active.", true));
                if (GameImport.hasMods(version, settings.id)) {
                    Button manage = button("Mods…"); manage.setOnClickListener(v -> mods()); addButton(content, manage);
                    content.addView(text("What this game's mods change, with their size. Remove one part, such as a code patch, or all of them. Its texture pack, ROM and saves stay.", true));
                }
            }
            if (activity instanceof Launcher) {
                Button cheats = button("Import cheats…"); cheats.setOnClickListener(v -> { if (!cheatsBlocked()) ((Launcher) activity).importCheats(settings.id); }); addButton(content, cheats);
                content.addView(text("Optional. Choose one text file of cheats in the common text cheat format. It replaces the cheats imported before, and is kept when the game is imported again. Cheats change game memory and can break a save: back it up first.", true));
                Button add = button("Add cheat…"); add.setOnClickListener(v -> { if (!cheatsBlocked()) addCheat(null); }); addButton(content, add);
                content.addView(text("Type a cheat's name and its code lines. It is added to this game's cheat file, switched on.", true));
                if (GameImport.cheatsFile(version, settings.id).isFile()) {
                    Button list = button("Cheats…"); list.setOnClickListener(v -> cheats()); addButton(content, list);
                    content.addView(text("Switch the imported cheats on or off.", true));
                    Button remove = button("Remove cheats"); remove.setOnClickListener(v -> removeCheats()); addButton(content, remove);
                    content.addView(text("Deletes the imported cheat file. Its mods, texture pack, ROM and saves stay.", true));
                }
            }
            if (activity instanceof Launcher) {
                Button addon = button("Import update or DLC…"); addon.setOnClickListener(v -> { if (!cheatsBlocked()) ((Launcher) activity).importAddon(settings.id); }); addButton(content, addon);
                content.addView(text("Optional. Choose one decrypted .cia: the game's update, a DLC, or its Download Play child. The newest update is used. An update that changes game code needs a title.so built with it, or the game runs in the interpreter.", true));
                List<String> addons = GameImport.addonNames(activity, settings.id);
                if (!addons.isEmpty()) {
                    content.addView(text("Imported: " + String.join(", ", addons.subList(0, Math.min(addons.size(), 20))) + (addons.size() > 20 ? " and " + (addons.size() - 20) + " more" : ""), true));
                    Button remove = button("Remove updates and DLC"); remove.setOnClickListener(v -> removeAddons()); addButton(content, remove);
                }
                Button camera = button("Camera picture…"); camera.setOnClickListener(v -> ((Launcher) activity).importCamera(settings.id)); addButton(content, camera);
                content.addView(text("Optional. A picture the game's cameras see instead of black, such as a QR code. It applies the next time the game starts.", true));
                if (GameImport.cameraFile(activity, settings.id).isFile()) {
                    Button drop = button("Remove camera picture"); drop.setOnClickListener(v -> {
                        Toast.makeText(activity, GameImport.cameraFile(activity, settings.id).delete() ? "Camera picture removed. The cameras see black from the next start." : "Could not remove the picture.", Toast.LENGTH_LONG).show(); closeAll(); });
                    addButton(content, drop);
                }
            }
            if (repair != null) {
                Button replace = button("Add or replace native game code…"); replace.setOnClickListener(v -> repair.run()); addButton(content, replace);
                content.addView(text("Choose a trusted Android ABI 8 title.so (ABI 4 to 7 still run). The ROM, textures and saves stay in place.", true));
            }
        }
        if (activity instanceof Launcher) {
            Button saves = button("Manage saves…");
            saves.setOnClickListener(v -> { closeAll(); ((Launcher)activity).saves(settings.id); }); addButton(content, saves);
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
                    .setPositiveButton("Choose ZIP",(d,w) -> { closeAll(); ((Launcher)activity).importDriver(settings.id); })
                    .setNegativeButton("Cancel",null).create(); show(trust);
                focusCancel(trust);
            }); addButton(content,driver);
            content.addView(text("Only import drivers you trust: they run native code inside this app. Driver support must be included in the app build.",true));
        }
        filesDialog = dialog("Game files", content).setNegativeButton("Back", null).create();
        // the listener runs after a refresh has built the next page: only the current page clears the fields
        filesDialog.setOnDismissListener(d -> { if (filesDialog == d) { filesDialog = null; if (open == this) open = null; } });
        open = this;
        show(filesDialog); focusFirst(content);
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
    private static String resolutionLabel(GameSettings draft) { return "Rendering resolution" + (draft.changedResolution() ? " · changed" : ""); }
    /** Resolution and the HD pack switch; the pack is per game, so All games has no switch. */
    private void picture(LinearLayout content, GameSettings draft) {
        Button resolution = button(resolutionLabel(draft) + " · " + draft.resolution + "×"); resolution.setTag("resolution");
        resolution.setOnClickListener(v -> numbers(draft, resolution, null, "Rendering resolution", new float[]{1, 2, 3, 4, 5}, new String[]{"1× · Best performance", "2× · Sharper picture", "3× · Highest detail", "4× · Needs a strong GPU", "5× · Needs a strong GPU"}));
        addButton(content, resolution);
        content.addView(text("4× and 5× need much more GPU memory and can slow the game.", true));
        if (defaults) return;
        boolean pack = GameSettings.hasTextures(version, settings.id);
        Switch use = toggle("HD texture pack (optional)", pack && draft.textures);
        use.setEnabled(pack);
        use.setOnCheckedChangeListener((button, checked) -> { draft.textures = checked; syncPreset(draft); }); content.addView(use);
        content.addView(text(pack ? "Uses the imported pack. Higher resolutions and large textures use more GPU memory." : "No texture pack attached. Original textures are used. Import a pack under Game files if you want one.", true));
    }
    private void group(String group) {
        GameSettings draft = settings.copy(); LinearLayout content = column();
        String start = snapshot(draft, draft.name);
        content.addView(legend(group.equals("Button mapping") ? "A Change · B Back · X Clears · Start Done · L/R Page" : "A Change · B Back · Start Done · L/R Page"));
        if (group.equals("Display")) {
            content.addView(heading("Picture quality"));
            picture(content, draft);
        }
        for (GameSettings.Setting setting : GameSettings.ALL) if (setting.group.equals(group)) {
            if (defaults && setting.key.equals("custom_driver")) continue; // the driver is chosen per game
            if (setting.key.startsWith("room_") && !GameImport.nightly(activity)) continue; // local wireless: nightly only
            String section;
            switch (setting.key) {
                case "dual_display": section = "Screens"; break;
                case "filter": section = "Scaling & shape"; break;
                case "show_fps": section = "On-screen info"; break;
                case "background": section = "Background"; break;
                case "top_x": section = "Custom layout · used by Screen layout → Custom"; break;
                case "stereo": section = "3D"; break;
                case "speed_limit": section = "Everyday"; break;
                case "frame_generation": section = "Frame generation"; break;
                case "warm_before_start": section = "Shader warm-up · fewer stutters"; break;
                case "custom_driver": section = "Custom Vulkan driver"; break;
                case "touch_controls": section = "Touch screen"; break;
                case "motion_enabled": section = "Device motion"; break;
                case "stick_motion": section = "Right stick as motion"; break;
                case "vsync_pacing": section = "For testing · leave at defaults"; break;
                case "hardware_renderer": section = "Core · leave at defaults unless troubleshooting"; break;
                case "shader_mode": section = "Shaders"; break;
                case "fast_texture_decode": section = "HD texture loading"; break;
                case "gpu_readback": section = "Driver workarounds"; break;
                case "room_mode": section = "Local wireless · nightly"; break;
                default: section = null;
            }
            if (section != null) {
                content.addView(heading(section));
            }
            control(content, draft, setting);
            if (setting.key.equals("frame_generation_flow")) {
                // in-game the activity is Game: casting it would end the running game
                if (activity instanceof Launcher) {
                    Button lossless = button("Import your Lossless.dll…");
                    lossless.setOnClickListener(v -> ((Launcher) activity).importLossless()); addButton(content, lossless);
                } else content.addView(text("Import Lossless.dll from the game's Settings in the library.", true));
                TextView state = text(losslessState(), true); content.addView(state);
                state.getViewTreeObserver().addOnWindowFocusChangeListener(focused -> state.setText(losslessState()));
            }
        }
        if (group.equals("Performance") && !defaults) {
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
                if(ids.isEmpty()) { Toast.makeText(activity,"Import a Vulkan driver ZIP under Game files in the library first.",Toast.LENGTH_LONG).show(); return; }
                AlertDialog picker=new AlertDialog.Builder(activity, p.dialogTheme()).setTitle("Vulkan driver")
                    .setSingleChoiceItems(names.toArray(new String[0]),ids.indexOf(draft.driver),(d,which) -> {
                        draft.driver=ids.get(which); choose.setText("Driver · "+names.get(which)); d.dismiss();
                    }).setNegativeButton("Cancel",null).create(); choices(picker, ids.indexOf(draft.driver));
            }); addButton(content,choose);
            content.addView(text("Select a driver and enable Use selected custom Vulkan driver. A missing or incompatible driver reports its error when the game starts.",true));
        }
        if (group.equals("Controls")) {
            Button resetTouch=button("Reset touch controls to defaults");
            resetTouch.setOnClickListener(v -> {
                for(GameSettings.Setting setting:GameSettings.ALL) if(setting.key.equals("touch_controls") || setting.key.equals("touch_auto_hide"))
                    ((Switch)content.findViewWithTag(setting.key)).setChecked((Boolean)draft.inherited(setting)); // the listener updates the draft
                Toast.makeText(activity,"Touch-control defaults restored in this draft",Toast.LENGTH_SHORT).show();
            }); addButton(content,resetTouch);
        }
        if (group.equals("Button mapping")) {
            content.addView(heading("Controller bindings" + (draft.changedBindings() ? " · changed" : "")));
            content.addView(text("Select an action, then press a physical controller button. Back cancels capture. Press X on an action here (or hold it) to clear its custom bindings; while capturing, choose Clear binding. Touch buttons keep their printed actions. Fast-forward restores normal speed when released. Quick save, Quick load, Swap screens, Screenshot, Pause menu and Cycle layout act once per press and never reach the game.", true));
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
            reset.setOnClickListener(v -> { draft.controllerBindings.clear(); draft.controllerBindings.putAll(draft.inheritedBindings()); refreshBindings(content,draft); Toast.makeText(activity,"Default controller bindings restored in this draft",Toast.LENGTH_SHORT).show(); }); addButton(content,reset);
        }
        if (group.equals("Motion")) content.addView(text("To recalibrate during play, open the game menu and choose Recalibrate motion, then hold the device still.", true));
        if (group.equals("System") && !defaults) enhancements(content, draft);
        AlertDialog dialog = dialog(group, content).setNegativeButton("Cancel", null).setPositiveButton("Done", (d, which) -> {
            settings.driver=draft.driver;
            settings.controllerBindings.clear(); settings.controllerBindings.putAll(draft.controllerBindings);
            settings.values.clear(); settings.values.putAll(draft.values); settings.resolution = draft.resolution; settings.textures = draft.textures;
            settings.enhancements.clear(); settings.enhancements.addAll(draft.enhancements);
            refreshRoot();
        }).create(); show(dialog, () -> !start.equals(snapshot(draft, draft.name)));
        focusFirst(content);
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
    /** The choice's label; "custom" is the picture shader except as the screen layout or background. */
    private static String label(GameSettings.Setting setting, String value) {
        return (setting.key.equals("layout") || setting.key.equals("background")) && value.equals("custom") ? "Custom" : label(value);
    }
    private static String label(String value) {
        switch (value) {
            case "fast_forward": return "Fast-forward (hold)";
            case "fast_forward_toggle": return "Fast-forward (toggle)";
            case "gpu": return "GPU";
            case "cpu": return "CPU";
            case "async": return "Asynchronous";
            case "wait": return "Wait for compilation";
            case "stacked": return "Top over bottom";
            case "side_by_side": return "Side by side";
            case "large_top": return "Large top, small bottom";
            case "hybrid": return "Hybrid";
            case "top_only": return "Top screen only";
            case "bottom_only": return "Bottom screen only";
            case "clockwise": return "Upright (90° clockwise)";
            case "counterclockwise": return "Upright (counter-clockwise)";
            case "circle_pad": return "Circle Pad";
            case "circle_up": return "Circle Pad up"; case "circle_down": return "Circle Pad down";
            case "circle_left": return "Circle Pad left"; case "circle_right": return "Circle Pad right";
            case "c_up": return "C-stick up"; case "c_down": return "C-stick down";
            case "c_left": return "C-stick left"; case "c_right": return "C-stick right";
            case "turbo": return "Turbo A/B/X/Y (hold)"; case "blow": return "Blow into microphone (hold)";
            case "device": return "Handheld microphone";
            case "c_stick": return "C-stick";
            case "cursor": return "Touch cursor";
            case "half_side_by_side": return "Side by side, half width";
            case "anaglyph": return "Red/cyan glasses";
            case "custom": return "Custom shader (shaders folder)";
            default: return Character.toUpperCase(value.charAt(0)) + value.substring(1).replace('_', ' ');
        }
    }
    /** The label, marked when this game overrides the value it would inherit from All games. */
    private static String name(GameSettings draft, GameSettings.Setting setting) { return setting.label + (draft.changed(setting) ? " · changed" : ""); }
    private void control(LinearLayout content, GameSettings draft, GameSettings.Setting setting) {
        Object value = draft.values.get(setting.key);
        if (value instanceof Boolean) {
            Switch toggle = toggle(name(draft, setting), (Boolean) value);
            toggle.setTag(setting.key);
            if (interpreterOnly(setting)) toggle.setEnabled(false);
            toggle.setOnCheckedChangeListener((button, checked) -> {
                draft.values.put(setting.key, checked); toggle.setText(name(draft, setting)); syncPreset(draft);
                // the two draw in-between pictures in different ways, only one can run
                String rival = setting.key.equals("frame_generation") ? "interpolate" : setting.key.equals("interpolate") ? "frame_generation" : null;
                if (checked && rival != null) {
                    draft.values.put(rival, false); Switch other = content.findViewWithTag(rival);
                    if (other != null) other.setChecked(false); syncPreset(draft);
                }
            }); content.addView(toggle);
        } else if (setting.choices != null) {
            Button choose = button(name(draft, setting) + " · " + label(setting, (String) value)); choose.setTag(setting.key);
            choose.setOnClickListener(v -> {
                String[] names = Arrays.stream(setting.choices).map(choice -> label(setting, choice)).toArray(String[]::new), shown = names.clone();
                for (int i = 0; i < shown.length; i++) if (setting.choices[i].equals(draft.inherited(setting))) shown[i] += " (default)";
                int checked = Arrays.asList(setting.choices).indexOf(draft.values.get(setting.key));
                AlertDialog list = new AlertDialog.Builder(activity, p.dialogTheme()).setTitle(setting.label)
                        .setSingleChoiceItems(shown, checked, (d, which) -> {
                            draft.values.put(setting.key, setting.choices[which]); choose.setText(name(draft, setting) + " · " + names[which]); d.dismiss();
                        }).setNegativeButton("Cancel", null).create(); choices(list, checked);
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
            Button choose = button(name(draft, setting) + " · " + (index >= 0 ? names[index] : draft.values.get(setting.key))); choose.setTag(setting.key);
            choose.setOnClickListener(v -> numbers(draft, choose, setting, setting.label, values, names)); addButton(content, choose);
        } else if (value instanceof String) {
            content.addView(text(name(draft, setting), false));
            EditText field = new EditText(activity); field.setText((String) value); field.setTag(setting.key);
            field.setSingleLine(true); field.setTextColor(p.ink); field.setMinHeight(dp(48)); field.setTextSize(17);
            field.setContentDescription(setting.label); field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(64)});
            if (setting.key.equals("room_password")) field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            field.addTextChangedListener(new android.text.TextWatcher() {
                public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
                public void onTextChanged(CharSequence text, int start, int before, int count) {}
                public void afterTextChanged(android.text.Editable text) { draft.values.put(setting.key, text.toString()); }
            }); content.addView(field);
            if (setting.key.equals("background_hex")) {
                Button pick = button("Pick colour"); pick.setOnClickListener(v -> pickColour(field)); addButton(content, pick);
            }
        } else {
            // whole steps for wide ranges (percent, gap): a D-pad press moves one unit, not a quarter
            float step = setting.max <= 1 ? 0.05f : setting.max - setting.min >= 10 ? 1f : setting.key.equals("cursor_speed") ? 0.1f : 0.25f;
            TextView caption = text(name(draft, setting) + " · " + format(setting, draft.number(setting.key)), false); content.addView(caption);
            SeekBar slider = new SeekBar(activity); slider.setTag(setting.key); slider.setMinHeight(dp(48)); slider.setPadding(dp(16), 0, dp(16), 0); focusRing(slider); slider.setProgressTintList(ColorStateList.valueOf(p.accent)); slider.setProgressBackgroundTintList(ColorStateList.valueOf(p.border));
            slider.setThumbTintList(ColorStateList.valueOf(p.accent)); slider.setMax(Math.round((setting.max - setting.min) / step));
            slider.setProgress(Math.round((draft.number(setting.key) - setting.min) / step)); slider.setKeyProgressIncrement(1);
            slider.setContentDescription(caption.getText()); slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                    if (!user) return;
                    float number = Math.min(setting.max, setting.min + progress * step); draft.values.put(setting.key, number);
                    caption.setText(name(draft, setting) + " · " + format(setting, number)); bar.setContentDescription(caption.getText());
                }
                public void onStartTrackingTouch(SeekBar bar) {}
                public void onStopTrackingTouch(SeekBar bar) {}
            }); content.addView(slider, new LinearLayout.LayoutParams(-1, -2));
        }
        content.addView(text(interpreterOnly(setting) ? "This game has no native code, so it runs in the interpreter. Add native code under Game files to run it at full speed." : setting.help, true));
    }
    /** Red, green and blue sliders for a "#RRGGBB" field, so a D-pad can set it; OK writes the field (and so the draft). */
    private void pickColour(EditText field) {
        int[] rgb = GameSettings.hexRgb(field.getText().toString().trim());
        LinearLayout box = column(); View swatch = new View(activity); box.addView(swatch, new LinearLayout.LayoutParams(-1, dp(48)));
        String[] names = {"Red", "Green", "Blue"};
        Runnable paint = () -> swatch.setBackgroundColor(android.graphics.Color.rgb(rgb[0], rgb[1], rgb[2]));
        paint.run();
        for (int i = 0; i < 3; i++) {
            int channel = i; TextView caption = text(names[i] + " · " + rgb[i], false); box.addView(caption);
            SeekBar slider = new SeekBar(activity); slider.setMax(255); slider.setProgress(rgb[i]); slider.setKeyProgressIncrement(1);
            slider.setMinHeight(dp(48)); slider.setPadding(dp(16), 0, dp(16), 0); focusRing(slider); slider.setContentDescription(names[i]);
            slider.setProgressTintList(ColorStateList.valueOf(p.accent)); slider.setProgressBackgroundTintList(ColorStateList.valueOf(p.border)); slider.setThumbTintList(ColorStateList.valueOf(p.accent));
            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar bar, int progress, boolean user) { rgb[channel] = progress; caption.setText(names[channel] + " · " + progress); paint.run(); }
                public void onStartTrackingTouch(SeekBar bar) {}
                public void onStopTrackingTouch(SeekBar bar) {}
            }); box.addView(slider, new LinearLayout.LayoutParams(-1, -2));
        }
        ScrollView scroll = new ScrollView(activity); scroll.addView(box);
        AlertDialog dialog = new AlertDialog.Builder(activity, p.dialogTheme()).setTitle("Background colour").setView(scroll)
            .setPositiveButton("OK", (d, w) -> field.setText(GameSettings.rgbHex(rgb[0], rgb[1], rgb[2]))).setNegativeButton("Cancel", null).create();
        show(dialog);
    }
    private boolean interpreterOnly(GameSettings.Setting setting) { return setting.key.equals("aot") && version != null && !GameSettings.hasNativeCode(version); }
    private static String format(GameSettings.Setting setting, float value) {
        return setting.max <= 1 ? Math.round(value * 100) + "%" : setting.max - setting.min >= 10 ? String.valueOf(Math.round(value)) : String.format(Locale.getDefault(), "%.2f", value);
    }
    private void numbers(GameSettings draft, Button button, GameSettings.Setting setting, String heading, float[] values, String[] names) {
        float current = setting == null ? draft.resolution : draft.number(setting.key);
        int selected = -1; for (int i = 0; i < values.length; i++) if (Math.abs(values[i] - current) < 0.01f) selected = i;
        float initial = setting == null ? draft.inheritedResolution() : ((Number) draft.inherited(setting)).floatValue();
        String[] shown = names.clone();
        for (int i = 0; i < values.length; i++) if (Math.abs(values[i] - initial) < 0.01f) shown[i] += " (default)";
        AlertDialog list = new AlertDialog.Builder(activity, p.dialogTheme()).setTitle(heading).setSingleChoiceItems(shown, selected, (d, which) -> {
            if (setting == null) draft.resolution = (int) values[which];
            else draft.values.put(setting.key, setting.normalize(values[which]));
            button.setText((setting == null ? resolutionLabel(draft) : name(draft, setting)) + " · " + names[which]); syncPreset(draft); d.dismiss();
        }).setNegativeButton("Cancel", null).create(); choices(list, selected);
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
