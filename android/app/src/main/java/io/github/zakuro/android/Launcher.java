package io.github.zakuro.android;

import android.app.*;
import android.content.*;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.text.format.DateUtils;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.util.*;

/** Native widgets retain touch, D-pad, font scaling and accessibility behavior. */
public final class Launcher extends Activity {
    private static final int IMPORT = 1, REPLACE_CODE = 2, REPLACE_TEXTURES = 3, IMPORT_DRIVER = 4, EXPORT_SAVE = 5, RESTORE_SAVE = 6, EXPORT_FRONTEND = 7, READ_IISU = 8, WRITE_IISU = 9, REPLACE_MODS = 10, IMPORT_LOSSLESS = 11, EXPORT_DIAGNOSTICS = 12, EXPORT_SETTINGS = 13, IMPORT_SETTINGS = 14;
    private String pendingSaveId;
    private Uri pendingIisu;
    private String pendingCodeId, pendingTextureId, pendingModId, pendingDriverId;
    private boolean pendingIncludeTextures;
    private LinearLayout list;
    private ScrollView library;
    private TextView status, count, details;
    private LinearLayout legend;
    // Library scan cache: search only filters this; showLibrary() rescans.
    private List<String> ids = new ArrayList<>();
    private final Map<String, String> names = new HashMap<>(), summaries = new HashMap<>();
    private final Map<String, Long> lastPlayed = new HashMap<>(), playMs = new HashMap<>();
    private final Set<String> interpreted = new HashSet<>(); // imported without native code (title.so)
    private String focused; // the game shown in the details line and on the bottom screen
    private Presentation second;
    private final DisplayManager.DisplayListener displayWatch = new DisplayManager.DisplayListener() {
        public void onDisplayAdded(int display) { showSecond(); }
        public void onDisplayRemoved(int display) { showSecond(); }
        public void onDisplayChanged(int display) { }
    };
    // ponytail: icons are 48x48 and never evicted; decoded on the UI thread at scan (one 14 KB read per new game). Move off-thread if a library reaches hundreds of games.
    private static final Map<String, android.graphics.Bitmap> icons = new HashMap<>();
    private ProgressBar progress;
    private Button add;
    private EditText search;
    private ImportTask task;
    Palette p;

    @Override public void onCreate(Bundle state) {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        setTheme(dark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(state);
        if (state != null) {
            String iisu=state.getString("pendingIisu"); if(iisu!=null) pendingIisu=Uri.parse(iisu);
            pendingCodeId = state.getString("pendingCodeId"); pendingTextureId = state.getString("pendingTextureId"); pendingModId = state.getString("pendingModId");
            pendingDriverId = state.getString("pendingDriverId");
            pendingSaveId = state.getString("pendingSaveId");
            pendingIncludeTextures = state.getBoolean("pendingIncludeTextures", false);
        }
        p = new Palette(this);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().setDecorFitsSystemWindows(false);
        LinearLayout page = column(); page.setBackgroundColor(p.background);
        page.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            view.setPadding(dp(24) + bars.left, dp(12) + bars.top, dp(24) + bars.right, dp(8) + bars.bottom); return insets;
        });
        boolean wide = getResources().getConfiguration().screenWidthDp >= 760 && getResources().getConfiguration().fontScale <= 1.3f;
        boolean compact = getResources().getConfiguration().screenWidthDp < 500 || getResources().getConfiguration().fontScale > 1.3f;
        LinearLayout heading = new LinearLayout(this); heading.setOrientation(compact ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL); heading.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout identity = new LinearLayout(this); identity.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this); logo.setImageResource(getApplicationInfo().logo);
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams logoSize = new LinearLayout.LayoutParams(dp(wide ? 40 : 64),dp(wide ? 40 : 64)); logoSize.setMargins(0,0,dp(12),0);
        identity.addView(logo,logoSize);
        LinearLayout wordmark = column(); if (wide) { TextView mark = text("Zakuroishi", 22, p.accent); mark.setSingleLine(true); wordmark.addView(mark); } else { wordmark.addView(text("Zakuroishi", 16, p.accent)); wordmark.addView(text("Your library", 30, p.ink)); } identity.addView(wordmark);
        search = new EditText(this); search.setId(0x1001); search.setSingleLine(true); search.setTextColor(p.ink); search.setHintTextColor(p.muted); search.setHint("Find a game");
        search.setTextSize(16); search.setMinHeight(dp(48)); search.setContentDescription("Find a game in your library");
        search.setBackground(p.row()); search.setPadding(dp(14), dp(8), dp(14), dp(8));
        // In touch mode only a text field can take focus, so search would win over Play; take it on a tap instead.
        search.setFocusableInTouchMode(false);
        search.setOnClickListener(v -> { if (v.isInTouchMode()) { v.setFocusableInTouchMode(true); v.requestFocus();
            getSystemService(android.view.inputmethod.InputMethodManager.class).showSoftInput(v, 0); } });
        // Wide: search takes the spare width, so the wordmark keeps its own and stays on one line.
        heading.addView(identity, compact ? new LinearLayout.LayoutParams(-1, -2) : wide ? new LinearLayout.LayoutParams(-2, -2) : new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout actions = new LinearLayout(this); actions.setGravity(Gravity.CENTER_VERTICAL);
        if (getResources().getConfiguration().fontScale > 1.3f) actions.setOrientation(LinearLayout.VERTICAL);
        add = button("Add game", true, "add"); add.setOnClickListener(v -> help(true));
        LinearLayout.LayoutParams addSize = new LinearLayout.LayoutParams(-2, -2); addSize.setMargins(0, dp(8), dp(8), dp(8)); actions.addView(add, addSize);
        Button menu = button("Menu", false, "menu"); menu.setContentDescription("Menu: setup help, frontends, storage and settings files"); menu.setOnClickListener(v -> tools()); actions.addView(menu);
        if (wide) { LinearLayout.LayoutParams find = new LinearLayout.LayoutParams(0, -2, 1); find.setMargins(dp(16), 0, dp(16), 0); heading.addView(search, find); }
        heading.addView(actions); page.addView(heading); if (!wide) page.addView(search, new LinearLayout.LayoutParams(-1, -2));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setIndeterminate(true);
        progress.setIndeterminateTintList(ColorStateList.valueOf(p.accent)); progress.setVisibility(View.GONE); page.addView(progress, new LinearLayout.LayoutParams(-1, dp(4)));
        status = text("", 14, p.muted); status.setPadding(0, dp(8), 0, dp(8)); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); page.addView(status);
        count = text("", 14, p.muted); count.setPadding(0, 0, dp(20), 0);
        legend = p.legend("A Play · Y Settings · X Saves · L1/R1 Page · Start Menu"); legend.addView(count, 0); legend.setPadding(0, dp(6), 0, dp(6)); page.addView(legend);
        details = text("", 16, p.ink); details.setSingleLine(true); details.setEllipsize(TextUtils.TruncateAt.END); details.setPadding(0, 0, 0, dp(10)); page.addView(details);
        list = column(); page.addView(list);
        library = new ScrollView(this); library.setFillViewport(true); library.setBackgroundColor(p.background); library.addView(page);
        setContentView(library); page.requestApplyInsets();
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) controller.setSystemBarsAppearance(dark ? 0 : WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        task = (ImportTask) getFragmentManager().findFragmentByTag("import");
        if (task == null) { task = new ImportTask(); getFragmentManager().beginTransaction().add(task, "import").commit(); }
        if (!task.busy) LosslessImport.clean(getFilesDir());
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int n, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int n) { render(); }
            public void afterTextChanged(Editable s) {}
        });
        showLibrary(); update();
        View first = ids.isEmpty() ? null : list.findViewWithTag(ids.get(0) + ".play"); if (first == null || !first.requestFocus()) add.requestFocus();
    }
    int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_A) {
            if (event.getAction() == KeyEvent.ACTION_UP && getCurrentFocus() != null) getCurrentFocus().performClick();
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B) {
            if (event.getAction() == KeyEvent.ACTION_UP && !hideKeyboard(getWindow())) {
                if (search.length() > 0) search.setText(""); // library root: B never exits
            }
            return true;
        }
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BUTTON_L1 || code == KeyEvent.KEYCODE_BUTTON_R1) {
            if (event.getAction() == KeyEvent.ACTION_UP) library.pageScroll(code == KeyEvent.KEYCODE_BUTTON_L1 ? View.FOCUS_UP : View.FOCUS_DOWN);
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_Y || code == KeyEvent.KEYCODE_BUTTON_X) {
            Object tag = getCurrentFocus() == null ? null : getCurrentFocus().getTag();
            if (event.getAction() == KeyEvent.ACTION_UP && tag instanceof String && ((String) tag).matches("[0-9A-F]{16}\\..*")) {
                String id = ((String) tag).substring(0, 16); if (code == KeyEvent.KEYCODE_BUTTON_Y) options(id); else saves(id);
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_START) {
            if (event.getAction() == KeyEvent.ACTION_UP) tools();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    private boolean imeShown() {
        WindowInsets insets = getWindow().getDecorView().getRootWindowInsets(); return insets != null && insets.isVisible(WindowInsets.Type.ime());
    }
    private boolean hideKeyboard(Window window) {
        WindowInsets insets = window.getDecorView().getRootWindowInsets();
        if (insets != null && insets.isVisible(WindowInsets.Type.ime()) && window.getInsetsController() != null) {
            window.getInsetsController().hide(WindowInsets.Type.ime()); return true;
        }
        return false;
    }
    private AlertDialog controllerDialog(AlertDialog dialog) { return controllerDialog(dialog, false); }
    AlertDialog controllerDialog(AlertDialog dialog, boolean cancelFocused) {
        dialog.setOnShowListener(window -> {
            p.dialog(dialog, false);
            // Light a button so A works at once: Cancel when asked, else the positive one (Cancel/Close when there is none).
            // Posted like focusFirst: requestFocusFromTouch can't leave touch mode, which the Thor stays in, before the window is attached.
            // A list row that focusFirst already lit is left alone, whichever of the two runs first.
            dialog.getWindow().getDecorView().post(() -> {
                View now = dialog.getCurrentFocus(); if (now instanceof Button && now.getTag() != null) return;
                Button first = dialog.getButton(cancelFocused ? DialogInterface.BUTTON_NEGATIVE : DialogInterface.BUTTON_POSITIVE), cancel = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
                if ((first == null || !first.requestFocusFromTouch()) && cancel != null) cancel.requestFocusFromTouch();
            });
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
    LinearLayout column() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    TextView text(String value, int size, int color) { return text(this, value, size, color); }
    static TextView text(Context context, String value, int size, int color) {
        TextView text = new TextView(context); text.setText(value); text.setTextSize(size); text.setTextColor(color);
        if (size >= 20) { text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); text.setAccessibilityHeading(true); } return text;
    }
    Button button(String label, boolean primary, String tag) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false); button.setTextSize(15); button.setTag(tag);
        button.setBackground(p.button(primary ? Palette.PRIMARY : Palette.SECONDARY)); button.setTextColor(p.label(p.ink));
        button.setMinHeight(dp(62)); button.setMinWidth(dp(98)); button.setPadding(dp(23), dp(15), dp(23), dp(15)); return button; // +5dp: the focus ring inset
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
            } catch(Exception e) { pendingIisu=null; task.errorTitle="Choosing the iiSU catalog failed"; task.error="Cannot retain access to the selected catalog: "+e.getMessage(); update(); }
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
                else controllerDialog(new AlertDialog.Builder(this, p.dialogTheme()).setTitle("Restore saves for " + name(id) + "?")
                        .setMessage("This replaces this game's in-game saves with the selected backup. Your current saves are kept as a local recovery copy. Close the game first. Quick save states are not included; start the game normally afterward.")
                        .setPositiveButton("Restore", (d, w) -> task.transferSave(getApplicationContext(), id, uri, true))
                        .setNegativeButton("Cancel", null).create(), true).show();
            }
        }
        if (request == IMPORT && result == RESULT_OK && intent != null && intent.getData() != null)
            task.start(getApplicationContext(), intent.getData(), pendingIncludeTextures);
        if (request == IMPORT_DRIVER) {
            String id = pendingDriverId; pendingDriverId = null;
            if (result == RESULT_OK && id != null && intent != null && intent.getData() != null)
                task.importDriver(getApplicationContext(), id, intent.getData());
        }
        if (request == IMPORT_LOSSLESS && result == RESULT_OK && intent != null && intent.getData() != null)
            task.importLossless(getApplicationContext(), intent.getData());
        if (result == RESULT_OK && intent != null && intent.getData() != null) {
            if (request == EXPORT_DIAGNOSTICS) task.exportDiagnostics(getApplicationContext(), intent.getData());
            if (request == EXPORT_SETTINGS) task.exportSettings(getApplicationContext(), intent.getData());
            if (request == IMPORT_SETTINGS) task.importSettings(getApplicationContext(), intent.getData());
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
        if (request == REPLACE_MODS) {
            String id = pendingModId; pendingModId = null;
            if (result == RESULT_OK && id != null && intent != null && intent.getData() != null)
                task.replaceMods(getApplicationContext(), id, intent.getData());
        }
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        if(pendingIisu!=null) state.putString("pendingIisu",pendingIisu.toString());
        if (pendingDriverId != null) state.putString("pendingDriverId", pendingDriverId);
        if (pendingSaveId != null) state.putString("pendingSaveId", pendingSaveId);
        if (pendingCodeId != null) state.putString("pendingCodeId", pendingCodeId);
        if (pendingTextureId != null) state.putString("pendingTextureId", pendingTextureId);
        if (pendingModId != null) state.putString("pendingModId", pendingModId);
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
    void importLossless() {
        if (task.busy) return;
        // ponytail: a DLL has no MIME type pickers agree on, so any file; the import checks it
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), IMPORT_LOSSLESS);
    }
    /** Focus the first list row (tagged button) of a shown dialog, or its Close button when there are none, leaving touch mode so the ring shows. */
    void focusFirst(AlertDialog dialog) {
        View decor = dialog.getWindow().getDecorView();
        // Right after show() the window isn't attached, and requestFocusFromTouch can't leave touch mode until it is.
        decor.post(() -> {
            ArrayList<View> found = new ArrayList<>();
            decor.addFocusables(found, View.FOCUS_DOWN, View.FOCUSABLES_ALL);
            for (View view : found) if (view instanceof Button && view.getTag() != null) { view.requestFocusFromTouch(); return; }
            Button close = dialog.getButton(DialogInterface.BUTTON_NEGATIVE); if (close != null) close.requestFocusFromTouch();
        });
    }
    boolean busy() { return task != null && task.busy; }
    /** The header's Menu (also Start). */
    private void tools() {
        chooser("Menu", new String[][]{
            {"Setup help", "What a game folder needs, and how to play."},
            {"Frontends…", "Export launch files so another game frontend can start your games."},
            {"Storage…", "See free space and delete old imports and backups you no longer need."},
            {"Export diagnostics…", "Save a text file with app, device and log details to attach to a bug report. It contains no games, saves or textures."},
            {"Export settings…", "Save every game's settings and controller mapping to a file."},
            {"Import settings…", "Load a file saved with Export settings. If anything in it is invalid, nothing is changed."}},
            new Runnable[]{() -> help(false), this::frontends, () -> StorageDialog.show(this), this::exportDiagnostics, this::exportSettings, this::importSettings});
    }
    /** The tile's touch-only ⋯: Settings, Saves, and Pin where the home screen supports it. */
    private void more(String id) {
        String[][] items = {{"Settings", "Resolution, textures, controls and native code."}, {"Saves", "Export or restore this game's in-game saves."},
            {"Pin to home screen", "Start this game from an icon on your home screen."}};
        ShortcutManager shortcuts = getSystemService(ShortcutManager.class);
        chooser(names.get(id), shortcuts != null && shortcuts.isRequestPinShortcutSupported() ? items : Arrays.copyOf(items, 2),
            new Runnable[]{() -> options(id), () -> saves(id), () -> pin(id)});
    }
    /** A list of labelled actions; items[i] = {label, one-line effect}. */
    private void chooser(String title, String[][] items, Runnable[] actions) {
        LinearLayout content = column(); content.setPadding(dp(24), dp(8), dp(24), dp(12));
        AlertDialog[] dialog = new AlertDialog[1];
        for (int i = 0; i < items.length; i++) {
            Runnable action = actions[i];
            Button b = button(items[i][0], false, "choice." + i); b.setOnClickListener(v -> { dialog[0].dismiss(); action.run(); });
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2); size.setMargins(0, dp(i == 0 ? 0 : 8), 0, 0); content.addView(b, size);
            content.addView(text(items[i][1], 14, p.muted));
        }
        ScrollView scroll = new ScrollView(this); scroll.addView(content);
        dialog[0] = controllerDialog(new AlertDialog.Builder(this, p.dialogTheme()).setTitle(title).setView(scroll).setNegativeButton("Close", null).create());
        dialog[0].show(); focusFirst(dialog[0]);
    }
    /** Pins a home screen icon that opens zakuro://game/ID (FrontendLaunch). */
    private void pin(String id) {
        android.graphics.Bitmap art = icons.get(id);
        ShortcutInfo info = new ShortcutInfo.Builder(this, id).setShortLabel(names.get(id)).setLongLabel(names.get(id))
                .setIcon(art != null ? Icon.createWithBitmap(art) : Icon.createWithResource(this, getApplicationInfo().icon))
                .setIntent(new Intent(Intent.ACTION_VIEW, Uri.parse("zakuro://game/" + id)).setPackage(getPackageName())).build();
        try { getSystemService(ShortcutManager.class).requestPinShortcut(info, null); }
        catch (IllegalStateException e) { Toast.makeText(this, "The home screen can't add a shortcut right now.", Toast.LENGTH_LONG).show(); }
    }
    private void pickDocument(boolean create, String type, String title, int request) {
        if (task.busy) return;
        Intent intent = new Intent(create ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(type);
        if (create) intent.putExtra(Intent.EXTRA_TITLE, title + "-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + (type.equals("text/plain") ? ".txt" : ".json"));
        try { startActivityForResult(intent, request); }
        catch (ActivityNotFoundException e) { Toast.makeText(this, "No document picker is available on this device.", Toast.LENGTH_LONG).show(); }
    }
    private void exportDiagnostics() { pickDocument(true, "text/plain", "Zakuroishi-diagnostics", EXPORT_DIAGNOSTICS); }
    private void exportSettings() { pickDocument(true, "application/json", "Zakuroishi-settings", EXPORT_SETTINGS); }
    private void importSettings() { pickDocument(false, "*/*", null, IMPORT_SETTINGS); }
    void saves(String id) {
        if (task.busy) return;
        controllerDialog(new AlertDialog.Builder(this, p.dialogTheme()).setTitle("Saves · " + name(id))
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
            controllerDialog(new AlertDialog.Builder(this, p.dialogTheme()).setTitle("Saves unavailable").setMessage(e.getMessage()).setPositiveButton("OK", null).create()).show();
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
    private void replaceMods(String id) {
        if (task.busy) return;
        pendingModId = id;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REPLACE_MODS);
    }
    // The game process edits these too; the shared accessor reloads cross-process changes.
    private android.content.SharedPreferences settings() { return GameSettings.preferences(this); }
    private String name(String id) {
        String saved = settings().getString(id + ".name", "").trim(); if (!saved.isEmpty()) return saved;
        try { return GameMetadata.name(GameImport.active(this, id), id); } catch (Exception e) { return "Game " + id.substring(8); }
    }
    private void help(boolean importing) {
        String folder = "Choose the folder with your decrypted game (.3ds, .cci or .cxi). A native code file (title.so) is optional. Without it the game runs more slowly; add it later from the game's settings.";
        String body = importing ? folder + "\n\nOnly add games you trust. Setup help in Menu has the details."
                : "1. " + folder + "\n\n"
                + "2. Texture packs are optional. Leave Include texture pack off to add just the game. You can attach a pack later from the game's Settings.\n\n"
                + "3. Add the folder, then select Play. Reimporting keeps this app's saves.\n\n"
                + "Native game code must be built for Zakuroishi Android ABI 5 (ABI 4 code still runs; rebuilding it runs faster). If an older build cannot start, open its Settings and choose Replace native game code. Select the rebuilt title.so; this keeps the ROM, textures and saves. See the project's Android build documentation for building compatible code.\n\n"
                + "Only add builds you trust: native game code can access this app's files and saves.\n\n"
                + "Use the D-pad to move and your controller's confirm button to select. Hold the handheld still briefly after a game resumes to calibrate motion.\n\n"
                + "Native library interface documentation:\nhttps://github.com/fearkov/3dsrecomp/blob/73b8f9caf3700991c290dd84bccffb92294d6b15/docs/interface.md";
        LinearLayout content = column(); content.setPadding(dp(24), dp(8), dp(24), dp(12));
        TextView instructions = text(body, 16, p.ink); instructions.setAutoLinkMask(android.text.util.Linkify.WEB_URLS);
        instructions.setMovementMethod(android.text.method.LinkMovementMethod.getInstance()); content.addView(instructions);
        CheckBox include = new CheckBox(this); include.setText("Include texture pack (optional)"); include.setTextColor(p.ink); include.setMinHeight(dp(56));
        include.setChecked(false); if (importing) content.addView(include);
        ScrollView scroll = new ScrollView(this); scroll.addView(content);
        AlertDialog.Builder dialog = new AlertDialog.Builder(this, p.dialogTheme()).setTitle(importing ? "Add a game" : "Getting started").setView(scroll);
        if (importing) dialog.setPositiveButton("Choose folder", (d, which) -> {
            pendingIncludeTextures = include.isChecked();
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE); intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION); startActivityForResult(intent, IMPORT);
        }).setNegativeButton("Cancel", null); else dialog.setPositiveButton("Got it", null); controllerDialog(dialog.create()).show();
    }
    private void showLibrary() { scan(); render(); }
    private void scan() {
        File[] files = new File(getFilesDir(), "imports").listFiles(); Set<String> found = new HashSet<>();
        if (files != null) for (File file : files) if (file.getName().matches("[0-9A-F]{16}\\.active(\\.bak)?")) found.add(file.getName().substring(0, 16));
        DisplayManager displays = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        boolean second = displays != null && displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).length > 0;
        ids = new ArrayList<>(found); names.clear(); summaries.clear(); lastPlayed.clear(); playMs.clear(); interpreted.clear();
        SharedPreferences played = getSharedPreferences("played", Context.MODE_MULTI_PROCESS); // written by the game process
        for (String id : ids) {
            names.put(id, name(id)); lastPlayed.put(id, played.getLong(id + ".last", 0)); playMs.put(id, played.getLong(id + ".ms", 0));
            summaries.put(id, summary(id, second));
            try { if (!new File(GameImport.active(this, id), "code/title.so").isFile()) interpreted.add(id); } catch (Exception ignored) { } // summary() already reports unreadable files
            if (!icons.containsKey(id)) try {
                int[] pixels = GameMetadata.romIcon(new File(GameImport.active(this, id), "game/game.3ds"));
                icons.put(id, pixels == null ? null : android.graphics.Bitmap.createBitmap(pixels, GameMetadata.ICON, GameMetadata.ICON, android.graphics.Bitmap.Config.ARGB_8888));
            } catch (Exception e) { icons.put(id, null); }
        }
        // Most recently played first, then by name.
        ids.sort((a, b) -> { int recent = Long.compare(lastPlayed.get(b), lastPlayed.get(a)); return recent != 0 ? recent : names.get(a).compareToIgnoreCase(names.get(b)); });
    }
    private String summary(String id, boolean second) {
        String summary = Math.max(1, Math.min(3, settings().getInt(id + ".resolution", 1))) + "× resolution";
        try { if (new File(GameImport.active(this, id), "data/mods/" + id + "/textures").isDirectory()) summary += settings().getBoolean(id + ".textures", true) ? "  ·  HD textures on" : "  ·  HD textures off";
            else summary += "  ·  Original textures";
        } catch (Exception e) { return "Files unavailable. Add this game again to repair it."; }
        return second ? summary + (GameSettings.load(this, id).flag("dual_display") ? "  ·  Second screen on" : "  ·  Second screen off") : summary;
    }
    private void render() {
        Object focus = getCurrentFocus() == null ? null : getCurrentFocus().getTag(); int scroll = library.getScrollY(); list.removeAllViews();
        legend.setVisibility(ids.isEmpty() ? View.GONE : View.VISIBLE);
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT); int shown = 0;
        // Plain focusable tiles in LinearLayout rows: a GridView's selection is invisible in touch mode, which the Thor stays in.
        int width = getResources().getConfiguration().screenWidthDp, columns = width >= 760 ? 5 : Math.max(2, Math.min(4, width / 170));
        String firstShown = null; boolean focusedShown = false; LinearLayout row = null;
        for (String id : ids) {
            String title = names.get(id); if (!title.toLowerCase(Locale.ROOT).contains(query)) continue; shown++;
            if (firstShown == null) firstShown = id; if (id.equals(focused)) focusedShown = true;
            if ((shown - 1) % columns == 0) { row = new LinearLayout(this); row.setWeightSum(columns); list.addView(row, new LinearLayout.LayoutParams(-1, -2)); }
            row.addView(tile(id, title, shown == 1), new LinearLayout.LayoutParams(0, -1, 1));
        }
        if (!focusedShown) focused = firstShown;
        count.setText(query.isEmpty() ? ids.size() + (ids.size() == 1 ? " game" : " games") : shown + " of " + ids.size() + " games");
        if (ids.isEmpty()) {
            LinearLayout empty = column(); empty.setPadding(dp(8), dp(28), dp(8), dp(20)); empty.addView(text("Add your first game", 24, p.ink));
            TextView body = text("Choose Add game to import a game folder. Setup help in Menu explains what you'll need.", 17, p.muted); body.setPadding(0, dp(12), 0, dp(12)); empty.addView(body);
            Button first = button("Add game", true, "empty.add"); first.setEnabled(add.isEnabled()); first.setFocusedByDefault(true); first.setOnClickListener(v -> help(true));
            empty.addView(first, new LinearLayout.LayoutParams(-2, -2)); list.addView(empty);
        } else if (shown == 0) list.addView(text("No matching games. Try a different name.", 17, p.muted));
        if (search.length() == 0 && !imeShown()) search.setFocusableInTouchMode(false);
        View now = getCurrentFocus(), restore = focus == null ? null : list.findViewWithTag(focus);
        if (restore != null) restore.requestFocus();
        else if ((now == null || now == search) && !(now == search && (search.length() > 0 || imeShown()))) { // first tile, unless the user is typing in search
            View play = firstShown == null ? null : list.findViewWithTag(firstShown + ".play"); if (play != null) play.requestFocus();
        }
        showDetails();
        library.post(() -> library.scrollTo(0, scroll));
    }
    /** One focus stop per game: A or a tap plays; Y/X read the "<id>.play" tag; the touch-only ⋯ opens Settings, Saves and Pin. */
    private View tile(String id, String title, boolean first) {
        FrameLayout tile = new FrameLayout(this); tile.setTag(id + ".play"); tile.setFocusable(true); tile.setFocusedByDefault(first);
        tile.setBackground(p.button(Palette.SECONDARY)); tile.setContentDescription("Play " + title); tile.setOnClickListener(v -> play(id));
        tile.setOnFocusChangeListener((v, has) -> { if (has && !id.equals(focused)) { focused = id; showDetails(); } });
        LinearLayout body = column(); body.setGravity(Gravity.CENTER_HORIZONTAL); body.setPadding(dp(8), dp(12), dp(8), dp(8)); // inside the background's own 5dp ring inset
        body.addView(icon(this, p, id, dp(72)), new LinearLayout.LayoutParams(dp(72), dp(72)));
        TextView name = text(title, 16, p.ink); name.setTextColor(p.label(p.ink)); name.setDuplicateParentStateEnabled(true); name.setGravity(Gravity.CENTER_HORIZONTAL);
        name.setMinLines(2); name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END); name.setPadding(0, dp(8), 0, dp(2)); body.addView(name, new LinearLayout.LayoutParams(-1, -2));
        TextView time = text(playTime(id), 14, p.muted); time.setTextColor(p.label(p.muted)); time.setDuplicateParentStateEnabled(true); time.setGravity(Gravity.CENTER_HORIZONTAL);
        time.setSingleLine(true); time.setEllipsize(TextUtils.TruncateAt.END); body.addView(time, new LinearLayout.LayoutParams(-1, -2));
        tile.addView(body, new FrameLayout.LayoutParams(-1, -2));
        TextView more = text("⋯", 18, p.muted); more.setTextColor(p.label(p.muted)); more.setDuplicateParentStateEnabled(true); more.setGravity(Gravity.CENTER);
        more.setContentDescription("Settings, saves and more for " + title); more.setOnClickListener(v -> more(id));
        more.setFocusable(false); // after the click listener, which makes it focusable: the controller has Y and X instead
        tile.addView(more, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.END));
        return tile;
    }
    /** The game's icon at a square size, or a plain placeholder shape when the ROM has none. */
    private static ImageView icon(Context c, Palette s, String id, int size) {
        ImageView icon = new ImageView(c); icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (icons.get(id) != null) icon.setImageBitmap(icons.get(id)); else icon.setBackground(s.shape(s.soft, s.border, 1, 12));
        int radius = size / 7; icon.setClipToOutline(true);
        icon.setOutlineProvider(new ViewOutlineProvider() { public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius); } });
        return icon;
    }
    private String playTime(String id) {
        long minutes = playMs.get(id) / 60000;
        return minutes == 0 ? (lastPlayed.get(id) == 0 ? "Not played yet" : "Under 1 min played") : (minutes >= 60 ? minutes / 60 + " h " : "") + minutes % 60 + " min played";
    }
    private String lastTime(String id) {
        long last = lastPlayed.get(id), now = System.currentTimeMillis();
        return last == 0 ? "" : now - last < 60000 ? "Played just now" : "Played " + DateUtils.getRelativeTimeSpanString(last, now, DateUtils.MINUTE_IN_MILLIS);
    }
    /** The focused game's full name, play time, last played and settings on one line, and large on the bottom screen. */
    private void showDetails() {
        String id = focused; details.setVisibility(id == null ? View.GONE : View.VISIBLE);
        if (id != null) details.setText(names.get(id) + "  ·  " + playTime(id) + (lastPlayed.get(id) == 0 ? "" : "  ·  " + lastTime(id))
                + (interpreted.contains(id) ? "  ·  No native code" : "") + "  ·  " + summaries.get(id));
        secondScreen();
    }
    /** Finds the bottom (presentation) display the way Game does, and shows or drops the details there. */
    private void showSecond() {
        DisplayManager displays = getSystemService(DisplayManager.class); if (displays == null) return;
        int own = getDisplay() == null ? Display.DEFAULT_DISPLAY : getDisplay().getDisplayId(); Display target = null;
        for (Display candidate : displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) if (candidate.getDisplayId() != own && candidate.isValid()) { target = candidate; break; }
        if (target == null) for (Display candidate : displays.getDisplays()) if (candidate.getDisplayId() != own && candidate.isValid()) { target = candidate; break; }
        if (second != null && target != null && second.getDisplay().getDisplayId() == target.getDisplayId()) { secondScreen(); return; }
        if (second != null) { second.dismiss(); second = null; }
        if (target == null) return;
        second = new Presentation(this, target, p.dark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        // Never focusable: the controller's focus stays on the top screen; these buttons are for touch.
        second.getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE); secondScreen();
        try { second.show(); } catch (WindowManager.InvalidDisplayException e) { second = null; }
    }
    // ponytail: rebuilt on every focus move (about fifteen views); keep the views and only set their text if moving through the grid ever stutters.
    private void secondScreen() {
        if (second == null) return;
        Context c = second.getContext(); Palette s = new Palette(c); String id = focused; // the bottom screen has its own density
        LinearLayout page = new LinearLayout(c); page.setOrientation(LinearLayout.VERTICAL); page.setBackgroundColor(s.background); page.setPadding(s.dp(32), s.dp(28), s.dp(32), s.dp(24));
        if (id == null || !names.containsKey(id)) {
            page.setGravity(Gravity.CENTER); page.addView(text(c, ids.isEmpty() ? "Choose Add game on the top screen to add your first game." : "No matching games.", 20, s.muted));
            second.setContentView(page); return;
        }
        LinearLayout top = new LinearLayout(c); top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams iconSize = new LinearLayout.LayoutParams(s.dp(112), s.dp(112)); iconSize.setMargins(0, 0, s.dp(20), 0); top.addView(icon(c, s, id, s.dp(112)), iconSize);
        LinearLayout info = new LinearLayout(c); info.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(c, names.get(id), 26, s.ink); name.setMaxLines(3); name.setEllipsize(TextUtils.TruncateAt.END); info.addView(name);
        info.addView(text(c, playTime(id), 18, s.muted)); if (lastPlayed.get(id) != 0) info.addView(text(c, lastTime(id), 18, s.muted));
        top.addView(info, new LinearLayout.LayoutParams(0, -2, 1)); page.addView(top);
        TextView summary = text(c, (interpreted.contains(id) ? "No native code: runs more slowly in the interpreter.\n" : "") + summaries.get(id), 16, s.muted); summary.setPadding(0, s.dp(16), 0, 0); page.addView(summary);
        page.addView(new View(c), new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout buttons = new LinearLayout(c); String[] labels = {"Play", "Settings", "Saves"};
        Runnable[] actions = {() -> play(id), () -> options(id), () -> saves(id)};
        for (int i = 0; i < labels.length; i++) {
            Button b = new Button(c); b.setText(labels[i]); b.setAllCaps(false); b.setTextSize(20); b.setTextColor(s.label(s.ink)); b.setContentDescription(labels[i] + " " + names.get(id));
            b.setBackground(s.button(i == 0 ? Palette.PRIMARY : Palette.SECONDARY)); b.setMinHeight(s.dp(96)); Runnable action = actions[i]; b.setOnClickListener(v -> action.run()); b.setFocusable(false);
            buttons.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
        }
        page.addView(buttons);
        View chips = s.legend("A Play · Y Settings · X Saves · Start Menu"); chips.setPadding(0, s.dp(16), 0, 0); page.addView(chips);
        second.setContentView(page);
    }
    @Override protected void onStart() {
        super.onStart(); getSystemService(DisplayManager.class).registerDisplayListener(displayWatch, null); showSecond();
    }
    @Override protected void onStop() { // the game process shows its own Presentation on the bottom screen
        getSystemService(DisplayManager.class).unregisterDisplayListener(displayWatch);
        if (second != null) { second.dismiss(); second = null; } super.onStop();
    }
    private void options(String id) {
        try {
            SettingsDialog.show(this, id, GameImport.active(this, id), name(id), false,
                    this::showLibrary, null, () -> replaceCode(id), () -> replaceTextures(id), () -> replaceMods(id));
        } catch (Exception e) {
            controllerDialog(new AlertDialog.Builder(this, p.dialogTheme()).setTitle("Settings unavailable")
                    .setMessage(e.getMessage()).setPositiveButton("OK", null).create()).show();
        }
    }
    private void frontends() {
        controllerDialog(new AlertDialog.Builder(this, p.dialogTheme()).setTitle("Frontend library")
            .setMessage("Export a small launch file for each imported game to a folder that Cocoon, iiSU or another frontend can scan. Games, textures and saves stay in Zakuroishi.\n\nChoose the exported Games folder in your frontend and configure Zakuroishi as its player. A Cocoon configuration and setup instructions are included. Some frontend versions need a custom player or the original ROM folder.\n\nExport again after adding games. Choose an empty folder the first time.")
            .setPositiveButton("Export library…",(d,w)->{
                if(task.busy) return;
                Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                startActivityForResult(pick,EXPORT_FRONTEND);
            }).setNeutralButton("Prepare iiSU config…",(d,w)->{
                if(task.busy) return;
                controllerDialog(new AlertDialog.Builder(this, p.dialogTheme()).setTitle("Choose iiSU's emuladores.json")
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
        } catch (Exception e) { controllerDialog(new AlertDialog.Builder(this, p.dialogTheme()).setTitle("Couldn't open this game").setMessage("Open Settings to replace native game code without copying the ROM.\n\n" + e.getMessage()).setPositiveButton("OK", null).create()).show(); }
    }
    private void update() {
        if (task == null || status == null) return;
        boolean arm64 = Arrays.asList(Build.SUPPORTED_64_BIT_ABIS).contains("arm64-v8a"); add.setEnabled(!task.busy && arm64); progress.setVisibility(task.busy ? View.VISIBLE : View.GONE);
        status.setAccessibilityLiveRegion(task.busy ? View.ACCESSIBILITY_LIVE_REGION_NONE : View.ACCESSIBILITY_LIVE_REGION_POLITE);
        progress.setContentDescription(task.message);
        status.setText(!arm64 ? "Playing games requires an ARM64 Android device." : task.message); status.setVisibility(status.getText().length() == 0 ? View.GONE : View.VISIBLE);
        if (task.error != null && !isFinishing()) { String error = task.error; task.error = null;
            String title = task.errorTitle == null ? "Couldn't complete the operation" : task.errorTitle; task.errorTitle = null;
            controllerDialog(new AlertDialog.Builder(this, p.dialogTheme()).setTitle(title).setMessage(error).setPositiveButton("OK", null).create()).show(); }
    }
    /** Returns the text for the success dialog: the driver library's SHA-256. */
    private static String installDriver(Context context, String id, Uri uri) throws Exception {
        String report = null;
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
            report = GameImport.identity("the driver library (" + library + ")", GameImport.sha256(new File(stage,library)));
            if (!stage.renameTo(target)) throw new java.io.IOException("Cannot install driver");
            installed = true;
            if (!GameSettings.preferences(context).edit().putString(id+".driver",key).putBoolean(id+".advanced.custom_driver",true).commit())
                throw new java.io.IOException("Driver imported but selection could not be saved. Choose it in Performance settings.");
        } finally {
            if (!installed) try (java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.walk(stage.toPath())) {
                for (java.nio.file.Path path : (Iterable<java.nio.file.Path>)paths.sorted(Comparator.reverseOrder())::iterator) java.nio.file.Files.deleteIfExists(path);
            }
        }
        return report;
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
        boolean busy; String message = "", error, errorTitle;
        private final Handler main = new Handler(Looper.getMainLooper());
        @Override public void onCreate(Bundle state) { super.onCreate(state); setRetainInstance(true); }
        void start(Context context, Uri uri) {
            start(context, uri, false);
        }
        void start(Context context, Uri uri, boolean includeTextures) { run(context, null, uri, 0, includeTextures); }
        void importDriver(Context context, String id, Uri uri) { run(context, id, uri, 3, false); }
        void replaceCode(Context context, String id, Uri uri) { run(context, id, uri, 1, false); }
        void replaceTextures(Context context, String id, Uri uri) { run(context, id, uri, 2, true); }
        void replaceMods(Context context, String id, Uri uri) { run(context, id, uri, 6, false); }
        void importLossless(Context context, Uri uri) { run(context, null, uri, 7, false); }
        void transferSave(Context context, String id, Uri uri, boolean restore) { run(context, id, uri, restore ? 5 : 4, false); }
        interface Job { String run() throws Exception; }
        /** Runs a short job off the main thread; the job returns the status line for success. */
        private void simple(String failTitle, String working, boolean libraryChanged, Job job) {
            if (busy) return; busy = true; error = null; errorTitle = failTitle; message = working; refresh(false);
            new Thread(() -> {
                String failure = null, done = "";
                try { done = job.run(); }
                catch (Exception e) { failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
                final String result = failure, status = done;
                main.post(() -> { busy = false; error = result; message = result == null ? status : failTitle + ". See the error for details."; refresh(libraryChanged); });
            }, "tools").start();
        }
        private static void write(Context context, Uri uri, String text) throws java.io.IOException {
            try (java.io.OutputStream output = context.getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null) throw new java.io.IOException("Cannot write the selected file.");
                output.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        void exportDiagnostics(Context context, Uri uri) {
            simple("Exporting diagnostics failed", "Collecting diagnostics…", false, () -> { write(context, uri, Diagnostics.build(context)); return "Diagnostics saved. Attach the file to your bug report."; });
        }
        void exportSettings(Context context, Uri uri) {
            simple("Exporting settings failed", "Exporting settings…", false, () -> { write(context, uri, SettingsTransfer.export(context)); return "Settings exported."; });
        }
        void importSettings(Context context, Uri uri) {
            simple("Importing settings failed", "Checking settings file…", true, () -> {
                String text;
                try (java.io.InputStream input = context.getContentResolver().openInputStream(uri)) {
                    if (input == null) throw new java.io.IOException("Cannot read the selected file.");
                    byte[] bytes = input.readNBytes(SettingsTransfer.MAX_BYTES + 1);
                    if (bytes.length > SettingsTransfer.MAX_BYTES) throw new java.io.IOException("This is not a Zakuroishi settings file: it is too large.");
                    text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                }
                int games = SettingsTransfer.importJson(context, text);
                return "Settings imported for " + games + (games == 1 ? " game" : " games") + ". They apply the next time each game starts.";
            });
        }
        void prepareIisu(Context context, Uri source, Uri destination) {
            if(busy) return; busy=true; error=null; errorTitle="Preparing iiSU catalog failed"; message="Preparing iiSU catalog copy…"; refresh(false);
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
            if(busy) return; busy=true; error=null; errorTitle="Exporting frontend library failed"; message="Exporting frontend library…"; refresh(false);
            new Thread(()->{
                String failure=null; int count=0;
                try { count=FrontendExport.export(context,tree); }
                catch(Exception e) { failure=e.getMessage()==null?"Frontend export failed":e.getMessage(); }
                final String result=failure; final int exported=count;
                main.post(()->{ busy=false; error=result; message=result==null?"Exported "+exported+" game references. Scan Zakuro frontend/Games; setup instructions are in README.txt.":"Frontend export failed; existing game files are unchanged."; refresh(false); });
            },"frontend-export").start();
        }
        /** The success text for a freshly imported title.so, or null if it cannot be read back (the import itself already succeeded). */
        private static String codeReport(Context context, String id) {
            try { File code = new File(GameImport.active(context, id), "code/title.so");
                return code.isFile() ? GameImport.codeReport(code) : "Imported without native code; it runs in the interpreter."; } catch (Exception e) { return null; }
        }
        private void run(Context context, String id, Uri uri, int operation, boolean includeTextures) {
            if (busy) return; busy = true; error = null;
            String action = operation == 0 ? "Adding game" : operation == 1 ? "Replacing code" : operation == 2 ? "Importing texture pack" : operation == 3 ? "Importing Vulkan driver" : operation == 4 ? "Exporting saves" : operation == 6 ? "Importing mod" : operation == 7 ? "Importing Lossless.dll" : "Restoring saves";
            errorTitle = action + " failed";
            message = operation == 0 ? "Checking game folder… No files copied yet." : "Checking selected files…"; refresh(true);
            new Thread(() -> {
                String failure = null, detail = null;
                try {
                    GameImport.Progress callback = (bytes, files, name) -> main.post(() -> {
                        message = String.format(Locale.getDefault(), action + " · %.1f MB copied · %d files", bytes / 1048576.0, files); refresh(false);
                    });
                    if (operation == 0) detail = codeReport(context, GameImport.install(context, uri, includeTextures, callback));
                    else if (operation == 1) detail = codeReport(context, GameImport.replaceCode(context, id, uri, callback));
                    else if (operation == 2) GameImport.replaceTextures(context, id, uri, callback);
                    else if (operation == 3) detail = installDriver(context, id, uri);
                    else if (operation == 6) GameImport.replaceRomfs(context, id, uri, callback);
                    else if (operation == 7) LosslessImport.install(context, uri);
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
                final String outcome = failure, report = detail;
                main.post(() -> { busy = false; error = outcome;
                    // the hash is what the user compares against, so it gets a dialog rather than only the status line
                    if (outcome == null && report != null) { errorTitle = operation == 0 ? "Game added" : operation == 1 ? "Native game code replaced" : "Vulkan driver imported"; error = report; }
                    // settings stay open over the status line, so the result needs its own dialog
                    if (outcome == null && operation == 7) { errorTitle = "Lossless.dll imported"; error = LosslessImport.DONE; }
                    message = outcome == null ? (operation == 0 ? "Game added. You're ready to play." : operation == 1 ? "Native game code replaced. Restart the game to use the new code." : operation == 2 ? "Texture pack imported. Enable it in Display settings and restart the game." : operation == 3 ? "Vulkan driver imported and selected. Restart the game to use it." : operation == 4 ? "Save ZIP exported to your chosen location." : operation == 6 ? "Romfs mod imported. It applies the next time the game starts." : operation == 7 ? LosslessImport.DONE : "Saves restored. Start the game normally; do not load an old quick save state.")
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
