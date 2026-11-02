package io.github.zakuro.android;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.util.*;

/** Native widgets retain touch, D-pad, font scaling and accessibility behavior. */
public final class Launcher extends Activity {
    private static final int IMPORT = 1, REPLACE_CODE = 2, REPLACE_TEXTURES = 3, IMPORT_DRIVER = 4, EXPORT_SAVE = 5, RESTORE_SAVE = 6, EXPORT_FRONTEND = 7, READ_IISU = 8, WRITE_IISU = 9;
    private String pendingSaveId;
    private Uri pendingIisu;
    private String pendingCodeId, pendingTextureId, pendingDriverId;
    private boolean pendingIncludeTextures;
    private LinearLayout list;
    private ScrollView library;
    private TextView status, count;
    private ProgressBar progress;
    private Button add;
    private EditText search;
    private ImportTask task;
    private int background, surface, ink, muted, accent, border, tint;

    @Override public void onCreate(Bundle state) {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        setTheme(dark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(state);
        if (state != null) {
            String iisu=state.getString("pendingIisu"); if(iisu!=null) pendingIisu=Uri.parse(iisu);
            pendingCodeId = state.getString("pendingCodeId"); pendingTextureId = state.getString("pendingTextureId");
            pendingDriverId = state.getString("pendingDriverId");
            pendingSaveId = state.getString("pendingSaveId");
            pendingIncludeTextures = state.getBoolean("pendingIncludeTextures", false);
        }
        background = dark ? 0xff121917 : 0xfff4f6f2; surface = dark ? 0xff1e2924 : 0xfffcfdfa;
        ink = dark ? 0xffeef3eb : 0xff192a23; muted = dark ? 0xffaabcb1 : 0xff52665a;
        accent = dark ? 0xffb5d2ab : 0xff365b41; border = dark ? 0xff41574b : 0xffb9c9bb; tint = dark ? 0xff314c3b : 0xffdcebd8;
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().setDecorFitsSystemWindows(false);
        LinearLayout page = column(); page.setBackgroundColor(background);
        page.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            view.setPadding(dp(24) + bars.left, dp(12) + bars.top, dp(24) + bars.right, dp(8) + bars.bottom); return insets;
        });
        boolean compact = getResources().getConfiguration().screenWidthDp < 500 || getResources().getConfiguration().fontScale > 1.3f;
        LinearLayout heading = new LinearLayout(this); heading.setOrientation(compact ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL); heading.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout identity = new LinearLayout(this); identity.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this); logo.setImageResource(getApplicationInfo().logo);
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams logoSize = new LinearLayout.LayoutParams(dp(64),dp(64)); logoSize.setMargins(0,0,dp(12),0);
        identity.addView(logo,logoSize);
        LinearLayout wordmark = column(); wordmark.addView(text("Zakuroishi", 16, accent)); wordmark.addView(text("Your library", 30, ink)); identity.addView(wordmark);
        heading.addView(identity, compact ? new LinearLayout.LayoutParams(-1, -2) : new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout actions = new LinearLayout(this); actions.setGravity(Gravity.CENTER_VERTICAL);
        if (getResources().getConfiguration().fontScale > 1.3f) actions.setOrientation(LinearLayout.VERTICAL);
        Button help = button("Setup help", false, "help"); help.setOnClickListener(v -> help(false)); actions.addView(help);
        add = button("Add game", true, "add"); add.setOnClickListener(v -> help(true));
        LinearLayout.LayoutParams addSize = new LinearLayout.LayoutParams(-2, -2); addSize.setMargins(dp(8), dp(8), 0, dp(8)); actions.addView(add, addSize);
        Button frontend = button("Frontends", false, "frontends"); frontend.setOnClickListener(v -> frontends()); actions.addView(frontend);
        heading.addView(actions); page.addView(heading);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setIndeterminate(true);
        progress.setIndeterminateTintList(ColorStateList.valueOf(accent)); progress.setVisibility(View.GONE); page.addView(progress, new LinearLayout.LayoutParams(-1, dp(4)));
        status = text("", 14, muted); status.setPadding(0, dp(8), 0, dp(8)); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); page.addView(status);
        search = new EditText(this); search.setId(0x1001); search.setSingleLine(true); search.setTextColor(ink); search.setHintTextColor(muted); search.setHint("Find a game");
        search.setTextSize(16); search.setMinHeight(dp(48)); search.setContentDescription("Find a game in your library");
        search.setBackground(shape(surface, border, 1)); search.setPadding(dp(14), dp(8), dp(14), dp(8)); page.addView(search, new LinearLayout.LayoutParams(-1, -2));
        count = text("", 13, muted); count.setPadding(0, dp(16), 0, dp(10)); page.addView(count);
        list = column(); page.addView(list);
        library = new ScrollView(this); library.setFillViewport(true); library.setBackgroundColor(background); library.addView(page);
        setContentView(library); page.requestApplyInsets();
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) controller.setSystemBarsAppearance(dark ? 0 : WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        task = (ImportTask) getFragmentManager().findFragmentByTag("import");
        if (task == null) { task = new ImportTask(); getFragmentManager().beginTransaction().add(task, "import").commit(); }
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int n, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int n) { showLibrary(); }
            public void afterTextChanged(Editable s) {}
        });
        showLibrary(); update(); add.requestFocus();
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_A) {
            if (event.getAction() == KeyEvent.ACTION_UP && getCurrentFocus() != null) getCurrentFocus().performClick();
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B) {
            if (event.getAction() == KeyEvent.ACTION_UP && !hideKeyboard(getWindow())) {
                if (search.length() > 0) search.setText(""); else onBackPressed();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    private boolean hideKeyboard(Window window) {
        WindowInsets insets = window.getDecorView().getRootWindowInsets();
        if (insets != null && insets.isVisible(WindowInsets.Type.ime()) && window.getInsetsController() != null) {
            window.getInsetsController().hide(WindowInsets.Type.ime()); return true;
        }
        return false;
    }
    private AlertDialog controllerDialog(AlertDialog dialog) {
        dialog.setOnShowListener(window -> {
            dialog.getWindow().setBackgroundDrawable(shape(surface, border, 1));
            for (int which : new int[]{DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE}) {
                Button button = dialog.getButton(which);
                if (button != null) { button.setTextColor(accent); button.setAllCaps(false); button.setMinHeight(dp(48)); }
            }
        });
        dialog.setOnKeyListener((window, code, event) -> {
            if (code == KeyEvent.KEYCODE_BUTTON_A) {
                if (event.getAction() == KeyEvent.ACTION_UP && dialog.getCurrentFocus() != null) dialog.getCurrentFocus().performClick();
                return true;
            }
            if (code == KeyEvent.KEYCODE_BUTTON_B) {
                if (event.getAction() == KeyEvent.ACTION_UP && !hideKeyboard(dialog.getWindow())) dialog.cancel();
                return true;
            }
            return false;
        });
        return dialog;
    }
    private LinearLayout column() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private TextView text(String value, int size, int color) {
        TextView text = new TextView(this); text.setText(value); text.setTextSize(size); text.setTextColor(color);
        if (size >= 20) { text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); text.setAccessibilityHeading(true); } return text;
    }
    private GradientDrawable shape(int fill, int stroke, int width) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(fill); shape.setCornerRadius(dp(12)); shape.setStroke(dp(width), stroke); return shape;
    }
    private Button button(String label, boolean primary, String tag) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false); button.setTextSize(15); button.setTag(tag);
        button.setMinHeight(dp(52)); button.setMinWidth(dp(88)); button.setPadding(dp(18), dp(10), dp(18), dp(10));
        button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}}, new int[]{muted, primary ? background : ink}));
        StateListDrawable states = new StateListDrawable(); states.addState(new int[]{-android.R.attr.state_enabled}, shape(surface, border, 1));
        states.addState(new int[]{android.R.attr.state_focused}, shape(primary ? accent : tint, primary ? background : accent, 3));
        states.addState(new int[]{android.R.attr.state_pressed}, shape(primary ? accent : tint, primary ? background : accent, 3));
        states.addState(new int[]{}, shape(primary ? accent : surface, primary ? accent : border, 1)); button.setBackground(states); return button;
    }
    @Override protected void onResume() { super.onResume(); if (list != null) { showLibrary(); update(); } }
    @Override protected void onActivityResult(int request, int result, Intent intent) {
        super.onActivityResult(request, result, intent);
        if(request==READ_IISU && result==RESULT_OK && intent!=null && intent.getData()!=null) {
            pendingIisu=intent.getData();
            try {
                getContentResolver().takePersistableUriPermission(pendingIisu,Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent output=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE);
                output.putExtra(Intent.EXTRA_TITLE,"emuladores-zakuro.json"); startActivityForResult(output,WRITE_IISU);
            } catch(Exception e) { pendingIisu=null; task.error="Cannot retain access to the selected catalog: "+e.getMessage(); update(); }
        }
        if(request==WRITE_IISU) {
            Uri input=pendingIisu; pendingIisu=null;
            if(result==RESULT_OK && input!=null && intent!=null && intent.getData()!=null)
                task.prepareIisu(getApplicationContext(),input,intent.getData());
            else if(input!=null) try { getContentResolver().releasePersistableUriPermission(input,Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch(Exception ignored) { }
        }
        if(request==EXPORT_FRONTEND && result==RESULT_OK && intent!=null && intent.getData()!=null)
            task.exportFrontend(getApplicationContext(),intent.getData());
        if (request == EXPORT_SAVE || request == RESTORE_SAVE) {
            String id = pendingSaveId; pendingSaveId = null;
            if (result == RESULT_OK && id != null && intent != null && intent.getData() != null) {
                Uri uri = intent.getData();
                if (request == EXPORT_SAVE) task.transferSave(getApplicationContext(), id, uri, false);
                else controllerDialog(new AlertDialog.Builder(this).setTitle("Restore saves for " + name(id) + "?")
                        .setMessage("This replaces this game's in-game saves with the selected backup. Your current saves are kept as a local recovery copy. Close the game first. Quick save states are not included; start the game normally afterward.")
                        .setPositiveButton("Restore", (d, w) -> task.transferSave(getApplicationContext(), id, uri, true))
                        .setNegativeButton("Cancel", null).create()).show();
            }
        }
        if (request == IMPORT && result == RESULT_OK && intent != null && intent.getData() != null)
            task.start(getApplicationContext(), intent.getData(), pendingIncludeTextures);
        if (request == IMPORT_DRIVER) {
            String id = pendingDriverId; pendingDriverId = null;
            if (result == RESULT_OK && id != null && intent != null && intent.getData() != null)
                task.importDriver(getApplicationContext(), id, intent.getData());
        }
        if (request == REPLACE_CODE) {
            String id = pendingCodeId; pendingCodeId = null;
            if (result == RESULT_OK && id != null && intent != null && intent.getData() != null)
                task.replaceCode(getApplicationContext(), id, intent.getData());
        }
        if (request == REPLACE_TEXTURES) {
            String id = pendingTextureId; pendingTextureId = null;
            if (result == RESULT_OK && id != null && intent != null && intent.getData() != null)
                task.replaceTextures(getApplicationContext(), id, intent.getData());
        }
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        if(pendingIisu!=null) state.putString("pendingIisu",pendingIisu.toString());
        if (pendingDriverId != null) state.putString("pendingDriverId", pendingDriverId);
        if (pendingSaveId != null) state.putString("pendingSaveId", pendingSaveId);
        if (pendingCodeId != null) state.putString("pendingCodeId", pendingCodeId);
        if (pendingTextureId != null) state.putString("pendingTextureId", pendingTextureId);
        state.putBoolean("pendingIncludeTextures", pendingIncludeTextures);
        super.onSaveInstanceState(state);
    }
    void importDriver(String id) {
        if (task.busy) return;
        pendingDriverId = id;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, IMPORT_DRIVER);
    }
    void saves(String id) {
        if (task.busy) return;
        controllerDialog(new AlertDialog.Builder(this).setTitle("Saves · " + name(id))
                .setMessage("Export your in-game saves to Downloads, an SD card or cloud storage. The ZIP contains ordinary save files you can extract on a computer.\n\nSave in-game and exit to the library first. Restore accepts a Zakuroishi save ZIP for this game. Quick save states are separate and are not included.")
                .setPositiveButton("Export ZIP…", (d, w) -> pickSave(id, false))
                .setNeutralButton("Restore ZIP…", (d, w) -> pickSave(id, true))
                .setNegativeButton("Cancel", null).create()).show();
    }
    private void pickSave(String id, boolean restore) {
        if (task.busy) return;
        try (SaveArchive.Guard ignored = SaveArchive.lock(getFilesDir(), id)) {
            SaveArchive.recover(getFilesDir(), id);
        } catch (Exception e) {
            controllerDialog(new AlertDialog.Builder(this).setTitle("Saves unavailable").setMessage(e.getMessage()).setPositiveButton("OK", null).create()).show();
            return;
        }
        Intent intent = new Intent(restore ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType(restore ? "*/*" : "application/zip");
        intent.addFlags(restore ? Intent.FLAG_GRANT_READ_URI_PERMISSION : Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (!restore) intent.putExtra(Intent.EXTRA_TITLE, "Zakuroishi-" + id + "-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".zip");
        pendingSaveId = id;
        try { startActivityForResult(intent, restore ? RESTORE_SAVE : EXPORT_SAVE); }
        catch (ActivityNotFoundException e) { pendingSaveId = null; Toast.makeText(this, "No document picker is available on this device.", Toast.LENGTH_LONG).show(); }
    }
    private void replaceCode(String id) {
        if (task.busy) return;
        pendingCodeId = id;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*"); intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, REPLACE_CODE);
    }
    private void replaceTextures(String id) {
        if (task.busy) return;
        pendingTextureId = id;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REPLACE_TEXTURES);
    }
    // The game process edits these too; the shared accessor reloads cross-process changes.
    private android.content.SharedPreferences settings() { return GameSettings.preferences(this); }
    private String name(String id) {
        String saved = settings().getString(id + ".name", "").trim(); if (!saved.isEmpty()) return saved;
        try { return GameMetadata.name(GameImport.active(this, id), id); } catch (Exception e) { return "Game " + id.substring(8); }
    }
    private void help(boolean importing) {
        String body = "1. Choose one game's Android build folder. It needs the original decrypted game and its Android ARM64 title.so.\n\n"
                + "2. Texture packs are optional. Leave Include texture pack off to add just the game. You can attach a pack later from the game's Settings.\n\n"
                + "3. Add the folder, then select Play. Reimporting keeps this app's saves.\n\n"
                + "Native game code must be built for Zakuroishi Android ABI 5 (ABI 4 code still runs; rebuilding it runs faster). If an older build cannot start, open its Settings and choose Replace native game code. Select the rebuilt title.so; this keeps the ROM, textures and saves. See the project's Android build documentation for building compatible code.\n\n"
                + "Only add builds you trust: native game code can access this app's files and saves.\n\n"
                + "Use the D-pad to move and your controller's confirm button to select. Hold the handheld still briefly after a game resumes to calibrate motion.\n\n"
                + "Native library interface documentation:\nhttps://github.com/fearkov/3dsrecomp/blob/cec1270db38411f9c02d3f356fc3ed6c3f007424/docs/interface.md";
        LinearLayout content = column(); content.setPadding(dp(24), dp(8), dp(24), dp(12));
        TextView instructions = text(body, 16, ink); instructions.setAutoLinkMask(android.text.util.Linkify.WEB_URLS);
        instructions.setMovementMethod(android.text.method.LinkMovementMethod.getInstance()); content.addView(instructions);
        CheckBox include = new CheckBox(this); include.setText("Include texture pack (optional)"); include.setTextColor(ink); include.setMinHeight(dp(56));
        include.setChecked(false); if (importing) content.addView(include);
        ScrollView scroll = new ScrollView(this); scroll.addView(content);
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle(importing ? "Add a game" : "Getting started").setView(scroll);
        if (importing) dialog.setPositiveButton("Choose folder", (d, which) -> {
            pendingIncludeTextures = include.isChecked();
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE); intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION); startActivityForResult(intent, IMPORT);
        }).setNegativeButton("Cancel", null); else dialog.setPositiveButton("Got it", null); controllerDialog(dialog.create()).show();
    }
    private void showLibrary() {
        Object focus = getCurrentFocus() == null ? null : getCurrentFocus().getTag(); int scroll = library.getScrollY(); list.removeAllViews();
        File[] files = new File(getFilesDir(), "imports").listFiles(); Set<String> found = new HashSet<>();
        if (files != null) for (File file : files) if (file.getName().matches("[0-9A-F]{16}\\.active(\\.bak)?")) found.add(file.getName().substring(0, 16));
        List<String> ids = new ArrayList<>(found); Map<String, String> names = new HashMap<>(); for (String id : ids) names.put(id, name(id));
        ids.sort((a, b) -> names.get(a).compareToIgnoreCase(names.get(b))); String query = search.getText().toString().trim().toLowerCase(Locale.ROOT); int shown = 0;
        boolean wide = getResources().getConfiguration().screenWidthDp >= 760 && getResources().getConfiguration().fontScale <= 1.3f;
        for (String id : ids) {
            String title = names.get(id); if (!title.toLowerCase(Locale.ROOT).contains(query)) continue; shown++;
            LinearLayout row = column(); row.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(shape(surface, border, 1)); row.setPadding(dp(18), dp(16), dp(18), dp(16));
            LinearLayout info = column(); info.addView(text(title, 21, ink));
            String summary = Math.max(1, Math.min(3, settings().getInt(id + ".resolution", 1))) + "× resolution";
            try { if (new File(GameImport.active(this, id), "data/mods/" + id + "/textures").isDirectory()) summary += settings().getBoolean(id + ".textures", true) ? "  ·  HD textures on" : "  ·  HD textures off";
                else summary += "  ·  Original textures";
            } catch (Exception e) { summary = "Files unavailable. Add this game again to repair it."; }
            TextView detail = text(summary, 14, muted); detail.setPadding(0, dp(6), 0, wide ? 0 : dp(14)); info.addView(detail);
            row.addView(info, wide ? new LinearLayout.LayoutParams(0, -2, 1) : new LinearLayout.LayoutParams(-1, -2));
            LinearLayout actions = new LinearLayout(this);
            if (getResources().getConfiguration().fontScale > 1.3f || getResources().getConfiguration().screenWidthDp < 400) actions.setOrientation(LinearLayout.VERTICAL);
            Button play = button("Play", true, id + ".play"); play.setContentDescription("Play " + title);
            play.setEnabled(!task.busy); play.setOnClickListener(v -> play(id)); actions.addView(play);
            Button options = button("Settings", false, id + ".settings"); options.setContentDescription("Settings for " + title); options.setOnClickListener(v -> options(id));
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2); gap.setMargins(dp(10), 0, 0, 0); actions.addView(options, gap);
            Button saves = button("Saves", false, id + ".saves"); saves.setContentDescription("Manage saves for " + title);
            saves.setEnabled(!task.busy); saves.setOnClickListener(v -> saves(id));
            LinearLayout.LayoutParams saveGap = new LinearLayout.LayoutParams(-2, -2); saveGap.setMargins(dp(10), 0, 0, 0); actions.addView(saves, saveGap);
            LinearLayout.LayoutParams actionSize = new LinearLayout.LayoutParams(-2, -2); if (wide) actionSize.setMargins(dp(16), 0, 0, 0); row.addView(actions, actionSize);
            LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(-1, -2); spacing.setMargins(0, 0, 0, dp(12)); list.addView(row, spacing);
        }
        count.setText(query.isEmpty() ? ids.size() + (ids.size() == 1 ? " game" : " games") : shown + " of " + ids.size() + " games");
        if (ids.isEmpty()) {
            LinearLayout empty = column(); empty.setPadding(dp(8), dp(28), dp(8), dp(20)); empty.addView(text("Add your first game", 24, ink));
            TextView body = text("Choose Add game to import a game folder. Setup help explains what you'll need.", 17, muted); body.setPadding(0, dp(12), 0, dp(12)); empty.addView(body); list.addView(empty);
        } else if (shown == 0) list.addView(text("No matching games. Try a different name.", 17, muted));
        if (focus != null) { View restore = list.findViewWithTag(focus); if (restore != null) restore.requestFocus(); } library.post(() -> library.scrollTo(0, scroll));
    }
    private void options(String id) {
        try {
            SettingsDialog.show(this, id, GameImport.active(this, id), name(id), false,
                    this::showLibrary, null, () -> replaceCode(id), () -> replaceTextures(id));
        } catch (Exception e) {
            controllerDialog(new AlertDialog.Builder(this).setTitle("Settings unavailable")
                    .setMessage(e.getMessage()).setPositiveButton("OK", null).create()).show();
        }
    }
    private void frontends() {
        controllerDialog(new AlertDialog.Builder(this).setTitle("Frontend library")
            .setMessage("Export a small launch file for each imported game to a folder that Cocoon, iiSU or another frontend can scan. Games, textures and saves stay in Zakuroishi.\n\nChoose the exported Games folder in your frontend and configure Zakuroishi as its player. A Cocoon configuration and setup instructions are included. Some frontend versions need a custom player or the original ROM folder.\n\nExport again after adding games. Choose an empty folder the first time.")
            .setPositiveButton("Export library…",(d,w)->{
                if(task.busy) return;
                Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                startActivityForResult(pick,EXPORT_FRONTEND);
            }).setNeutralButton("Prepare iiSU config…",(d,w)->{
                if(task.busy) return;
                controllerDialog(new AlertDialog.Builder(this).setTitle("Choose iiSU's emuladores.json")
                    .setMessage("Choose your current iiSU emulator catalog. Zakuroishi will save an edited copy that adds its player and .zkr extension, preserving other entries. The original is unchanged. Then import the copy in iiSU's Updates settings.\n\nOn this device, iiSU normally stores it in Android/media/com.iisulauncher/iiSULauncher/Emuladores.")
                    .setPositiveButton("Choose catalog…",(confirm,which)->{
                        Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
                        pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                        startActivityForResult(pick,READ_IISU);
                    }).setNegativeButton("Cancel",null).create()).show();
            }).setNegativeButton("Cancel",null).create()).show();
    }
    private void play(String id) {
        if (task.busy) return;
        try {
            startActivity(GameLaunch.intent(this,id));
        } catch (Exception e) { controllerDialog(new AlertDialog.Builder(this).setTitle("Couldn't open this game").setMessage("Open Settings to replace native game code without copying the ROM.\n\n" + e.getMessage()).setPositiveButton("OK", null).create()).show(); }
    }
    private void update() {
        if (task == null || status == null) return;
        boolean arm64 = Arrays.asList(Build.SUPPORTED_64_BIT_ABIS).contains("arm64-v8a"); add.setEnabled(!task.busy && arm64); progress.setVisibility(task.busy ? View.VISIBLE : View.GONE);
        status.setAccessibilityLiveRegion(task.busy ? View.ACCESSIBILITY_LIVE_REGION_NONE : View.ACCESSIBILITY_LIVE_REGION_POLITE);
        progress.setContentDescription(task.message);
        status.setText(!arm64 ? "Playing games requires an ARM64 Android device." : task.message); status.setVisibility(status.getText().length() == 0 ? View.GONE : View.VISIBLE);
        if (task.error != null && !isFinishing()) { String error = task.error; task.error = null;
            controllerDialog(new AlertDialog.Builder(this).setTitle("Couldn't complete the operation").setMessage(error).setPositiveButton("OK", null).create()).show(); }
    }
    private static void installDriver(Context context, String id, Uri uri) throws Exception {
        File home = new File(context.getFilesDir(),"drivers");
        if (!home.isDirectory() && !home.mkdirs()) throw new java.io.IOException("Cannot create driver storage");
        String key = UUID.randomUUID().toString();
        File stage = new File(home,key+".stage"), target = new File(home,key);
        if (!stage.mkdir()) throw new java.io.IOException("Cannot stage driver");
        boolean installed = false;
        try {
            try (java.io.InputStream input = context.getContentResolver().openInputStream(uri)) {
                if (input == null) throw new java.io.IOException("Cannot read driver ZIP");
                extractDriver(input,stage);
            }
            String library = GameSettings.driverLibrary(stage);
            try (java.io.InputStream input = new java.io.FileInputStream(new File(stage,library))) {
                byte[] header = input.readNBytes(64);
                if (!arm64Driver(header)) throw new java.io.IOException("Driver library must be an Android ARM64 shared library");
            }
            if (!stage.renameTo(target)) throw new java.io.IOException("Cannot install driver");
            installed = true;
            if (!GameSettings.preferences(context).edit().putString(id+".driver",key).putBoolean(id+".advanced.custom_driver",true).commit())
                throw new java.io.IOException("Driver imported but selection could not be saved. Choose it in Performance settings.");
        } finally {
            if (!installed) try (java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.walk(stage.toPath())) {
                for (java.nio.file.Path path : (Iterable<java.nio.file.Path>)paths.sorted(Comparator.reverseOrder())::iterator) java.nio.file.Files.deleteIfExists(path);
            }
        }
    }
    static boolean arm64Driver(byte[] h) {
        return h.length >= 64 && h[0]==127 && h[1]=='E' && h[2]=='L' && h[3]=='F'
                && h[4]==2 && h[5]==1 && h[16]==3 && h[17]==0 && (h[18]&255)==183 && h[19]==0;
    }
    static void extractDriver(java.io.InputStream input, File stage) throws java.io.IOException {
        String root = stage.getCanonicalPath()+File.separator;
        long total=0; int count=0; byte[] buffer=new byte[32768];
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(input)) {
            java.util.zip.ZipEntry entry;
            while ((entry=zip.getNextEntry())!=null) {
                String name=entry.getName();
                if (++count>512 || name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.contains(":")) throw new java.io.IOException("Unsafe driver ZIP entry");
                File output=new File(stage,name);
                if (!output.getCanonicalPath().startsWith(root)) throw new java.io.IOException("Driver ZIP escapes its folder");
                if (entry.isDirectory()) { if(!output.isDirectory() && !output.mkdirs()) throw new java.io.IOException("Cannot create driver directory"); continue; }
                if (output.exists()) throw new java.io.IOException("Duplicate driver ZIP entry");
                if (!output.getParentFile().isDirectory() && !output.getParentFile().mkdirs()) throw new java.io.IOException("Cannot create driver directory");
                long size=0;
                try (java.io.FileOutputStream stream=new java.io.FileOutputStream(output)) {
                    if (!output.setReadOnly()) throw new java.io.IOException("Cannot protect imported driver file");
                    int n;
                    while ((n=zip.read(buffer))!=-1) {
                        total+=n; size+=n;
                        if (total>256L*1024*1024 || size>128L*1024*1024) throw new java.io.IOException("Driver ZIP exceeds size limits");
                        stream.write(buffer,0,n);
                    }
                    stream.getFD().sync();
                }
            }
        }
        if(count==0) throw new java.io.IOException("Driver ZIP is empty");
    }    /** Rotation retains the worker, never the Activity or its views. */
    public static final class ImportTask extends Fragment {
        boolean busy; String message = "", error;
        private final Handler main = new Handler(Looper.getMainLooper());
        @Override public void onCreate(Bundle state) { super.onCreate(state); setRetainInstance(true); }
        void start(Context context, Uri uri) {
            start(context, uri, false);
        }
        void start(Context context, Uri uri, boolean includeTextures) { run(context, null, uri, 0, includeTextures); }
        void importDriver(Context context, String id, Uri uri) { run(context, id, uri, 3, false); }
        void replaceCode(Context context, String id, Uri uri) { run(context, id, uri, 1, false); }
        void replaceTextures(Context context, String id, Uri uri) { run(context, id, uri, 2, true); }
        void transferSave(Context context, String id, Uri uri, boolean restore) { run(context, id, uri, restore ? 5 : 4, false); }
        void prepareIisu(Context context, Uri source, Uri destination) {
            if(busy) return; busy=true; error=null; message="Preparing iiSU catalog copy…"; refresh(false);
            new Thread(()->{
                String failure=null;
                try {
                    if(source.equals(destination)) throw new java.io.IOException("Choose a new output file; the original catalog must stay unchanged");
                    byte[] patched;
                    try(java.io.InputStream input=context.getContentResolver().openInputStream(source)) {
                        if(input==null) throw new java.io.IOException("Cannot read iiSU catalog");
                        patched=FrontendExport.patchIisu(input.readNBytes(1024*1024+1));
                    }
                    try(java.io.OutputStream output=context.getContentResolver().openOutputStream(destination,"wt")) {
                        if(output==null) throw new java.io.IOException("Cannot write iiSU catalog copy"); output.write(patched);
                    }
                } catch(Exception e) { failure=e.getMessage()==null?"Cannot prepare iiSU catalog":e.getMessage(); }
                finally { try { context.getContentResolver().releasePersistableUriPermission(source,Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch(Exception ignored) { } }
                final String result=failure;
                main.post(()->{ busy=false; error=result; message=result==null?"iiSU catalog copy saved. Import it in iiSU Settings > Updates > Import emuladores.json, then select Zakuroishi for your 3DS games.":"iiSU preparation failed. Do not import the output file."; refresh(false); });
            },"iisu-config").start();
        }
        void exportFrontend(Context context, Uri tree) {
            if(busy) return; busy=true; error=null; message="Exporting frontend library…"; refresh(false);
            new Thread(()->{
                String failure=null; int count=0;
                try { count=FrontendExport.export(context,tree); }
                catch(Exception e) { failure=e.getMessage()==null?"Frontend export failed":e.getMessage(); }
                final String result=failure; final int exported=count;
                main.post(()->{ busy=false; error=result; message=result==null?"Exported "+exported+" game references. Scan Zakuro frontend/Games; setup instructions are in README.txt.":"Frontend export failed; existing game files are unchanged."; refresh(false); });
            },"frontend-export").start();
        }
        private void run(Context context, String id, Uri uri, int operation, boolean includeTextures) {
            if (busy) return; busy = true; error = null;
            String action = operation == 0 ? "Adding game" : operation == 1 ? "Replacing code" : operation == 2 ? "Importing texture pack" : operation == 3 ? "Importing Vulkan driver" : operation == 4 ? "Exporting saves" : "Restoring saves";
            message = operation == 0 ? "Checking game folder… No files copied yet." : "Checking selected files…"; refresh(true);
            new Thread(() -> {
                String failure = null;
                try {
                    GameImport.Progress callback = (bytes, files, name) -> main.post(() -> {
                        message = String.format(Locale.getDefault(), action + " · %.1f MB copied · %d files", bytes / 1048576.0, files); refresh(false);
                    });
                    if (operation == 0) GameImport.install(context, uri, includeTextures, callback);
                    else if (operation == 1) GameImport.replaceCode(context, id, uri, callback);
                    else if (operation == 2) GameImport.replaceTextures(context, id, uri, callback);
                    else if (operation == 3) installDriver(context, id, uri);
                    else {
                        main.post(() -> { message = action + "…"; refresh(false); });
                        try (SaveArchive.Guard ignored = SaveArchive.lock(context.getFilesDir(), id)) {
                            SaveArchive.recover(context.getFilesDir(), id);
                            if (operation == 4) {
                                try (java.io.OutputStream output = context.getContentResolver().openOutputStream(uri, "w")) {
                                    if (output == null) throw new java.io.IOException("Cannot write the selected file.");
                                    SaveArchive.exportZip(context.getFilesDir(), id, output);
                                } catch (Exception e) {
                                    try { android.provider.DocumentsContract.deleteDocument(context.getContentResolver(), uri); } catch (Exception ignoredDelete) { }
                                    throw new java.io.IOException("Export failed. A partial ZIP may remain at the destination; do not use it. " + e.getMessage(), e);
                                }
                            } else try (java.io.InputStream input = context.getContentResolver().openInputStream(uri)) {
                                if (input == null) throw new java.io.IOException("Cannot read the selected backup.");
                                SaveArchive.restoreZip(context.getFilesDir(), id, input);
                            }
                        }
                    }
                } catch (Exception e) { failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
                final String outcome = failure;
                main.post(() -> { busy = false; error = outcome;
                    message = outcome == null ? (operation == 0 ? "Game added. You're ready to play." : operation == 1 ? "Native game code replaced. Restart the game to use the new code." : operation == 2 ? "Texture pack imported. Enable it in Display settings and restart the game." : operation == 3 ? "Vulkan driver imported and selected. Restart the game to use it." : operation == 4 ? "Save ZIP exported to your chosen location." : "Saves restored. Start the game normally; do not load an old quick save state.")
                            : action + " failed. See the error for recovery details."; refresh(true); });
            }, "game-import").start();
        }
        private void refresh(boolean libraryChanged) {
            Activity activity = getActivity(); if (activity instanceof Launcher && !activity.isFinishing()) {
                Launcher launcher = (Launcher) activity; launcher.update(); if (libraryChanged) launcher.showLibrary();
            }
        }
    }
}
