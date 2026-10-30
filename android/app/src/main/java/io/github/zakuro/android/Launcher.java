package io.github.zakuro.android;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.view.View;
import android.widget.*;
import java.io.File;
import java.util.*;

/** Framework widgets keep the launcher small and usable with touch or a D-pad. */
public final class Launcher extends Activity {
    private static final int IMPORT = 1;
    private LinearLayout list;
    private TextView status;
    private Button add;
    private ImportTask task;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        page.setPadding(padding, padding, padding, padding);
        TextView heading = new TextView(this); heading.setText("Zakuro"); heading.setTextSize(28); page.addView(heading);
        TextView help = new TextView(this);
        help.setText("Import one Pocket Native built game folder with its original decrypted ROM, ARM64 title.so and optional textures folder. Use your own game dumps. Reimport keeps saves. Only import builds you trust: title.so executes native code with access to this app's game files and saves. Native gamepad controls are handled in game.");
        page.addView(help);
        add = new Button(this); add.setText("Import built game folder");
        add.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            startActivityForResult(intent, IMPORT);
        }); page.addView(add);
        status = new TextView(this); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); page.addView(status);
        ScrollView scroll = new ScrollView(this); list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list); page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(page);
        task = (ImportTask) getFragmentManager().findFragmentByTag("import");
        if (task == null) { task = new ImportTask(); getFragmentManager().beginTransaction().add(task, "import").commit(); }
        showLibrary(); update();
    }
    @Override protected void onResume() { super.onResume(); if (list != null) { showLibrary(); update(); } }
    @Override protected void onActivityResult(int request, int result, Intent intent) {
        super.onActivityResult(request, result, intent);
        if (request == IMPORT && result == RESULT_OK && intent != null && intent.getData() != null)
            task.start(getApplicationContext(), intent.getData());
    }
    private android.content.SharedPreferences settings() { return getSharedPreferences("games", MODE_PRIVATE); }
    private void showLibrary() {
        list.removeAllViews();
        File dir = new File(getFilesDir(), "imports"); File[] files = dir.listFiles();
        SortedSet<String> ids = new TreeSet<>();
        if (files != null) for (File f : files) {
            String name = f.getName();
            if (name.matches("[0-9A-F]{16}\\.active(\\.bak)?")) ids.add(name.substring(0, 16));
        }
        if (ids.isEmpty()) { TextView empty = new TextView(this); empty.setText("No games imported."); list.addView(empty); }
        for (String id : ids) {
            TextView title = new TextView(this); title.setText("Title " + id); title.setTextSize(20); list.addView(title);
            Switch textures = new Switch(this); textures.setText("HD textures");
            textures.setChecked(settings().getBoolean(id + ".textures", true));
            textures.setOnCheckedChangeListener((button, checked) -> settings().edit().putBoolean(id + ".textures", checked).apply());
            list.addView(textures);
            RadioGroup resolutions = new RadioGroup(this); resolutions.setOrientation(RadioGroup.VERTICAL);
            int selected = Math.max(1, Math.min(3, settings().getInt(id + ".resolution", 1)));
            for (int scale = 1; scale <= 3; scale++) {
                RadioButton option = new RadioButton(this); option.setId(View.generateViewId()); option.setTag(scale);
                option.setText(scale + "× resolution"); resolutions.addView(option);
                if (scale == selected) resolutions.check(option.getId());
            }
            resolutions.setOnCheckedChangeListener((group, checked) -> {
                View option = group.findViewById(checked);
                if (option != null) settings().edit().putInt(id + ".resolution", (Integer) option.getTag()).apply();
            }); list.addView(resolutions);
            Button play = new Button(this); play.setText("Play " + id);
            play.setEnabled(task == null || !task.busy);
            play.setOnClickListener(v -> {
                try {
                    File version = GameImport.active(this, id);
                    Intent intent = new Intent(this, Game.class);
                    intent.putExtra("game", new File(version, "game").getAbsolutePath());
                    intent.putExtra("code", new File(version, "code/title.so").getAbsolutePath());
                    intent.putExtra("data", new File(version, "data").getAbsolutePath());
                    intent.putExtra("texture_packs", settings().getBoolean(id + ".textures", true));
                    intent.putExtra("resolution", Math.max(1, Math.min(3, settings().getInt(id + ".resolution", 1))));
                    startActivity(intent);
                } catch (Exception e) { new AlertDialog.Builder(this).setTitle("Cannot launch").setMessage(e.getMessage()).setPositiveButton("OK", null).show(); }
            }); list.addView(play);
        }
    }
    private void update() {
        if (task == null || status == null) return;
        boolean arm64 = Arrays.asList(Build.SUPPORTED_64_BIT_ABIS).contains("arm64-v8a");
        add.setEnabled(!task.busy && arm64);
        status.setText(arm64 ? task.message : "This app requires an ARM64 Android device.");
    }
    /** Retained worker survives rotation without keeping an Activity or its views alive. */
    public static final class ImportTask extends Fragment {
        boolean busy;
        String message = "";
        private final Handler main = new Handler(Looper.getMainLooper());
        @Override public void onCreate(Bundle state) { super.onCreate(state); setRetainInstance(true); }
        void start(Context context, Uri uri) {
            if (busy) return;
            busy = true; message = "Importing… Keep the app open. Large texture packs can take several minutes."; refresh();
            new Thread(() -> {
                String result;
                try { result = "Imported " + GameImport.install(context, uri) + ". Ready to play."; }
                catch (Exception e) { result = "Import failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()); }
                final String outcome = result;
                main.post(() -> { busy = false; message = outcome; refresh(); });
            }, "game-import").start();
        }
        private void refresh() {
            Activity activity = getActivity();
            if (activity instanceof Launcher && !activity.isFinishing()) {
                Launcher launcher = (Launcher) activity; launcher.update(); launcher.showLibrary();
            }
        }
    }
}
