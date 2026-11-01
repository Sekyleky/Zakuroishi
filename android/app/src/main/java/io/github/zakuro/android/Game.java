package io.github.zakuro.android;

import android.app.NativeActivity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.KeyEvent;
import android.content.DialogInterface;

/** Lifecycle and controller events remain owned by NativeActivity and the runtime. */
public final class Game extends NativeActivity {
    // Register bridge exports with ART as well as NativeActivity's native loader.
    static { System.loadLibrary("zakuro_android"); }
    // Shared with bridge.rs.
    private static final int PAUSE=0, SAVE=1, LOAD=2, RESTART=3, RECALIBRATE=4, SWAP=5, RESUME=6, EXIT=7;
    private native void nativeCommand(int command);
    private native void nativeButton(int keyCode, boolean pressed);
    private native void nativeStick(int stick, float x, float y);
    private native void nativeTouch(int mask, float x, float y);
    private native void nativeSecondSurface(android.view.Surface surface);
    private native void nativeSecondTouch(boolean pressed, float x, float y, int width, int height);
    private android.hardware.display.DisplayManager displays;
    private android.app.Presentation secondDisplay;
    private int failedSecondDisplay = -1;
    public void closeSecondDisplay() { runOnUiThread(() -> {
        if (secondDisplay != null) { failedSecondDisplay=secondDisplay.getDisplay().getDisplayId(); secondDisplay.dismiss(); secondDisplay=null; }
    }); }
    private final android.hardware.display.DisplayManager.DisplayListener displayListener = new android.hardware.display.DisplayManager.DisplayListener() {
        public void onDisplayAdded(int id) { failedSecondDisplay=-1; updateSecondDisplay(); }
        public void onDisplayRemoved(int id) { failedSecondDisplay=-1; updateSecondDisplay(); }
        public void onDisplayChanged(int id) { failedSecondDisplay=-1; updateSecondDisplay(); }
    };
    private void updateSecondDisplay() {
        if (displays == null || settings == null || !settings.flag("dual_display") || isFinishing()) return;
        android.view.Display[] available = displays.getDisplays(android.hardware.display.DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        int own=getDisplay()==null ? android.view.Display.DEFAULT_DISPLAY : getDisplay().getDisplayId();
        android.view.Display target=null;
        for(android.view.Display candidate:available) if(candidate.getDisplayId()!=own && candidate.isValid()) { target=candidate; break; }
        if(target==null) for(android.view.Display candidate:displays.getDisplays())
            if(candidate.getDisplayId()!=own && candidate.isValid()) { target=candidate; break; }
        if (secondDisplay != null && target != null && secondDisplay.getDisplay().getDisplayId() == target.getDisplayId() && target.isValid()) return;
        if (secondDisplay != null) { secondDisplay.dismiss(); secondDisplay = null; }
        if (target == null || !target.isValid() || target.getDisplayId()==failedSecondDisplay) return;
        android.app.Presentation presentation = new android.app.Presentation(this,target);
        android.view.SurfaceView surface = new android.view.SurfaceView(presentation.getContext());
        surface.getHolder().addCallback(new android.view.SurfaceHolder.Callback() {
            public void surfaceCreated(android.view.SurfaceHolder holder) { nativeSecondSurface(holder.getSurface()); }
            public void surfaceChanged(android.view.SurfaceHolder holder,int format,int width,int height) { nativeSecondSurface(holder.getSurface()); }
            public void surfaceDestroyed(android.view.SurfaceHolder holder) { nativeSecondSurface(null); }
        });
        surface.setOnTouchListener((v,event) -> {
            if (!hasWindowFocus()) return true;
            int action=event.getActionMasked();
            boolean pressed=action != android.view.MotionEvent.ACTION_UP && action != android.view.MotionEvent.ACTION_CANCEL;
            nativeSecondTouch(pressed,event.getX(),event.getY(),v.getWidth(),v.getHeight());
            return true;
        });
        presentation.setContentView(surface);
        presentation.getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try { presentation.show(); secondDisplay=presentation; }
        catch (WindowManager.InvalidDisplayException e) { presentation.dismiss(); showNativeMessage("Second display disconnected. Using the main display."); }
    }
    private android.content.Intent pendingRestart;
    private int touchMask;
    private android.hardware.input.InputManager inputManager;
    private android.widget.FrameLayout touchOverlay;
    private String batteryText = "";
    private boolean batteryRegistered;
    private final android.content.BroadcastReceiver batteryReceiver = new android.content.BroadcastReceiver() {
        public void onReceive(android.content.Context context, android.content.Intent battery) {
            int level=battery.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL,-1), scale=battery.getIntExtra(android.os.BatteryManager.EXTRA_SCALE,-1);
            int status=battery.getIntExtra(android.os.BatteryManager.EXTRA_STATUS,-1);
            batteryText=level>=0 && scale>0 ? Math.round(level*100f/scale)+"%" + ((status==android.os.BatteryManager.BATTERY_STATUS_CHARGING || status==android.os.BatteryManager.BATTERY_STATUS_FULL) ? " charging" : "") : "";
        }
    };
    private final android.hardware.input.InputManager.InputDeviceListener inputListener = new android.hardware.input.InputManager.InputDeviceListener() {
        public void onInputDeviceAdded(int id) { updateTouchVisibility(); }
        public void onInputDeviceRemoved(int id) { updateTouchVisibility(); }
        public void onInputDeviceChanged(int id) { updateTouchVisibility(); }
    };
    private void updateTouchVisibility() {
        if(touchOverlay==null || settings==null) return;
        boolean gamepad=false;
        if(inputManager!=null) for(int id:inputManager.getInputDeviceIds()) {
            android.view.InputDevice device=inputManager.getInputDevice(id);
            if(device!=null && !device.isVirtual() && (device.supportsSource(android.view.InputDevice.SOURCE_GAMEPAD) || device.supportsSource(android.view.InputDevice.SOURCE_JOYSTICK))) { gamepad=true; break; }
        }
        boolean visible=settings.flag("touch_controls") && !(settings.flag("touch_auto_hide") && gamepad);
        if(!visible && touchOverlay.getVisibility()==android.view.View.VISIBLE) {
            touchMask=0; nativeTouch(0,-1,-1); nativeButton(106,false); nativeButton(107,false);
            nativeStick(0,Float.NaN,Float.NaN); nativeStick(1,Float.NaN,Float.NaN);
        }
        touchOverlay.setVisibility(visible ? android.view.View.VISIBLE : android.view.View.GONE);
    }
    private AlertDialog pauseDialog;
    private boolean menuOpen;
    private android.widget.TextView stats;
    private final android.os.Handler hud = new android.os.Handler(android.os.Looper.getMainLooper());
    private String nativeStats = "";
    private GameSettings settings;
    private final Runnable clock = new Runnable() {
        public void run() {
            if (stats != null && settings != null) {
                String text = settings.flag("show_clock") ? android.text.format.DateFormat.getTimeFormat(Game.this).format(new java.util.Date()) : "";
                if (settings.flag("show_battery")) text += "  " + batteryText;
                if (settings.flag("show_fps")) text += "  " + nativeStats;
                stats.setText(text.trim());
            }
            hud.postDelayed(this, 1000);
        }
    };
    public void updateNativeStats(String text) { runOnUiThread(() -> nativeStats = text == null ? "" : text); }
    public void showNativeMessage(String text) { runOnUiThread(() -> android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()); }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private void overlays() {
        String id = getIntent().getStringExtra("game_id");
        if (id == null) return;
        settings = GameSettings.load(this, id);
        String orientation=(String)settings.values.get("orientation");
        setRequestedOrientation(orientation.equals("portrait") ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                : orientation.equals("auto") ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        if(settings.flag("show_battery")) {
            registerReceiver(batteryReceiver,new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)); batteryRegistered=true;
        }
        android.widget.FrameLayout overlay = new android.widget.FrameLayout(this);
        overlay.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        android.widget.Button menu = new android.widget.Button(this); menu.setText("Pause");
        menu.setContentDescription("Open game pause menu"); menu.setOnClickListener(v -> pauseMenu());
        overlay.addView(menu, new android.widget.FrameLayout.LayoutParams(-2, dp(52), android.view.Gravity.TOP | android.view.Gravity.END));
        stats = new android.widget.TextView(this); stats.setTextColor(0xffffffff); stats.setBackgroundColor(0x99000000); stats.setTextSize(15);
        overlay.addView(stats, new android.widget.FrameLayout.LayoutParams(-2,-2,android.view.Gravity.TOP | android.view.Gravity.START));
        if (settings.flag("touch_controls")) {
            touchOverlay = new android.widget.FrameLayout(this);
            overlay.addView(touchOverlay,new android.widget.FrameLayout.LayoutParams(-1,-1));
            android.widget.LinearLayout controls = new android.widget.LinearLayout(this);
            String[] labels={"L","↑","↓","←","→","Select","Start","Y","X","B","A","R","ZL","ZR","L3","R3"};
            int[] keys={512,64,128,32,16,4,8,2048,1024,2,1,256,16384,32768,-106,-107};
            android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(this);
            for(int i=0;i<labels.length;i++) {
                android.widget.Button key=new android.widget.Button(this); key.setText(labels[i]); key.setContentDescription("Game button " + labels[i]);
                final int code=keys[i]; key.setOnTouchListener((v,event) -> {
                    if(event.getActionMasked()==android.view.MotionEvent.ACTION_DOWN) { if (code < 0) nativeButton(-code,true); else { touchMask |= code; nativeTouch(touchMask,-1,-1); } return true; }
                    if(event.getActionMasked()==android.view.MotionEvent.ACTION_UP || event.getActionMasked()==android.view.MotionEvent.ACTION_CANCEL) { if (code < 0) nativeButton(-code,false); else { touchMask &= ~code; nativeTouch(touchMask,-1,-1); } if(event.getActionMasked()==android.view.MotionEvent.ACTION_UP) v.performClick(); return true; }
                    return true;
                });
                controls.addView(key,new android.widget.LinearLayout.LayoutParams(dp(56),dp(56)));
            }
            scroll.addView(controls); touchOverlay.addView(scroll,new android.widget.FrameLayout.LayoutParams(-1,-2,android.view.Gravity.BOTTOM));
            for(int i=0;i<2;i++) {
                final int index=i;
                android.widget.TextView stick=new android.widget.TextView(this); stick.setText(i==0 ? "Circle Pad" : "C-stick"); stick.setGravity(android.view.Gravity.CENTER); stick.setTextColor(0xffffffff); stick.setBackgroundColor(0x99555555);
                stick.setContentDescription(i==0 ? "Touch Circle Pad" : "Touch C-stick");
                stick.setOnTouchListener((v,e) -> {
                    int action=e.getActionMasked();
                    if(action==android.view.MotionEvent.ACTION_UP || action==android.view.MotionEvent.ACTION_CANCEL) nativeStick(index,Float.NaN,Float.NaN);
                    else nativeStick(index,Math.max(-1,Math.min(1,(e.getX()/v.getWidth()*2-1))),Math.max(-1,Math.min(1,1-e.getY()/v.getHeight()*2)));
                    return true;
                });
                android.widget.FrameLayout.LayoutParams position=new android.widget.FrameLayout.LayoutParams(dp(100),dp(100),android.view.Gravity.BOTTOM | (i==0 ? android.view.Gravity.START : android.view.Gravity.END)); position.bottomMargin=dp(64); touchOverlay.addView(stick,position);
            }
        }
        inputManager=(android.hardware.input.InputManager)getSystemService(INPUT_SERVICE);
        inputManager.registerInputDeviceListener(inputListener,hud); updateTouchVisibility();
        addContentView(overlay,new android.view.ViewGroup.LayoutParams(-1,-1)); hud.post(clock);
    }
    @Override public void onBackPressed() { pauseMenu(); }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if(event.getKeyCode()==KeyEvent.KEYCODE_BACK && !menuOpen && errorDialog==null) {
            if(event.getAction()==KeyEvent.ACTION_UP) pauseMenu(); return true;
        }
        return super.dispatchKeyEvent(event);
    }
    public void showPauseMenu() { runOnUiThread(this::pauseMenu); }
    private void pauseMenu() {
        if(menuOpen || errorDialog!=null || isFinishing()) return;
        menuOpen=true; nativeCommand(PAUSE);
        String[] entries={"Resume","Save state","Load state","Restart game","Swap screens","Recalibrate motion","Settings…","Exit to library"};
        pauseDialog=new AlertDialog.Builder(this).setTitle("Paused").setItems(entries,(d,which) -> {
            switch(which) {
                case 1: nativeCommand(SAVE); break;
                case 2: nativeCommand(LOAD); break;
                case 3: confirmRestart(); break;
                case 4: nativeCommand(SWAP); break;
                case 5: nativeCommand(RECALIBRATE); break;
                case 6: openSettings(); break;
                case 7: nativeCommand(EXIT); finish(); break;
            }
        }).setNegativeButton("Resume",null).create();
        pauseDialog.setOnDismissListener(d -> { pauseDialog=null; if(!menuOpen) return; menuOpen=false; nativeCommand(RESUME); });
        pauseDialog.setOnKeyListener((d,code,event) -> {
            if(code==KeyEvent.KEYCODE_BUTTON_B) { if(event.getAction()==KeyEvent.ACTION_UP) d.cancel(); return true; }
            if(code==KeyEvent.KEYCODE_BUTTON_A) {
                if(event.getAction()==KeyEvent.ACTION_UP) {
                    android.widget.ListView items=pauseDialog.getListView(); int selected=items.getSelectedItemPosition();
                    if(selected>=0) items.performItemClick(items.getSelectedView(),selected,items.getItemIdAtPosition(selected));
                    else if(pauseDialog.getCurrentFocus()!=null) pauseDialog.getCurrentFocus().performClick();
                }
                return true;
            }
            return false;
        });
        pauseDialog.show();
    }
    private void confirmRestart() {
        menuOpen=false;
        new AlertDialog.Builder(this).setTitle("Restart game?").setMessage("Unsaved progress will be lost. Saved states and in-game saves are kept.")
                // Relaunch from saved settings: a core-only restart would keep this launch's options (e.g. HD textures).
                .setPositiveButton("Restart",(d,w) -> {
                    String id=getIntent().getStringExtra("game_id");
                    android.content.Intent next=new android.content.Intent(getIntent());
                    try { GameSettings.load(this,id).apply(next,GameImport.active(this,id)); pendingRestart=next; finish(); }
                    catch(Exception e) { nativeCommand(RESTART); }
                }).setNegativeButton("Cancel",null)
                .setOnDismissListener(d -> nativeCommand(RESUME)).show();
    }
    @Override protected void onNewIntent(android.content.Intent next) {
        super.onNewIntent(next);
        if (java.util.Objects.equals(getIntent().getStringExtra("game_id"),next.getStringExtra("game_id"))) return;
        nativeCommand(PAUSE);
        new AlertDialog.Builder(this).setTitle("Switch games?")
            .setMessage("Unsaved progress in the current game will be lost. In-game saves are kept.")
            .setPositiveButton("Switch",(d,w)->{ pendingRestart=next; finish(); })
            .setNegativeButton("Cancel",null).setOnDismissListener(d->nativeCommand(RESUME)).show();
    }
    private void openSettings() {
        menuOpen=false;
        String id=getIntent().getStringExtra("game_id");
        try {
            SettingsDialog.show(this,id,GameImport.active(this,id),getIntent().getStringExtra("game_name"),true,
                () -> {
                    android.content.Intent next=new android.content.Intent(getIntent());
                    try { GameSettings.load(this,id).apply(next,GameImport.active(this,id)); }
                    catch(Exception e) { showNativeMessage(e.getMessage()); nativeCommand(RESUME); return; }
                    pendingRestart = next; finish();
                }, () -> nativeCommand(RESUME),null,null);
        } catch(Exception e) { showNativeMessage(e.getMessage()); nativeCommand(RESUME); }
    }
    private String runtimeError;
    private volatile SaveArchive.Guard saveGuard;
    /** Called before the native runtime opens any save files. */
    public String prepareSaves() {
        try {
            String id = getIntent().getStringExtra("game_id");
            saveGuard = SaveArchive.lock(getFilesDir(), id);
            SaveArchive.recover(getFilesDir(), id);
            return null;
        } catch (Exception e) {
            return "Cannot open saves. " + e.getMessage();
        }
    }
    private AlertDialog errorDialog;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        fullscreen();
        overlays();
        displays = (android.hardware.display.DisplayManager)getSystemService(DISPLAY_SERVICE);
        displays.registerDisplayListener(displayListener,hud); updateSecondDisplay();
        if (state != null && state.getString("runtimeError") != null)
            showRuntimeError(state.getString("runtimeError"));
    }
    /** JNI signature: (Ljava/lang/String;)V. Finish only after acknowledgment. */
    public void showRuntimeError(String message) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed() || errorDialog != null) return;
            runtimeError = message == null || message.trim().isEmpty()
                    ? "The game could not start. Please check the imported build." : message;
            errorDialog = new AlertDialog.Builder(this)
                    .setTitle("Game stopped")
                    .setMessage(runtimeError)
                    .setCancelable(false)
                    .setPositiveButton("Return to library", (dialog, which) -> finish())
                    .create();
            errorDialog.setCanceledOnTouchOutside(false);
            errorDialog.setOnKeyListener((dialog, code, event) -> {
                if (code != KeyEvent.KEYCODE_BUTTON_A && code != KeyEvent.KEYCODE_BUTTON_B) return false;
                if (event.getAction() == KeyEvent.ACTION_UP) finish();
                return true;
            });
            errorDialog.show();
            errorDialog.getButton(DialogInterface.BUTTON_POSITIVE).requestFocus();
        });
    }
    /** Compatibility with the current native report_error call site. */
    public void showNativeError(String message) { showRuntimeError(message); }
    @Override protected void onSaveInstanceState(Bundle state) {
        if (runtimeError != null) state.putString("runtimeError", runtimeError);
        super.onSaveInstanceState(state);
    }
    private android.os.PerformanceHintManager.Session hintSession;
    /** The target follows the selected emulation speed; unlimited aims for 4x. */
    public void startPerformanceHints(int[] tids, long targetNanos) {
        if (android.os.Build.VERSION.SDK_INT < 31) return;
        try {
            if(hintSession != null) { hintSession.updateTargetWorkDuration(Math.max(1,targetNanos)); return; }
            android.os.PerformanceHintManager manager = getSystemService(android.os.PerformanceHintManager.class);
            if (manager != null) hintSession = manager.createHintSession(tids, Math.max(1, targetNanos));
        } catch (Throwable e) { android.util.Log.w("Zakuro", "PerformanceHintManager", e); }
    }
    public void reportFrame(long actualNanos) {
        android.os.PerformanceHintManager.Session session = hintSession;
        if (session == null) return;
        try { session.reportActualWorkDuration(actualNanos); }
        catch (Throwable e) { android.util.Log.w("Zakuro", "reportActualWorkDuration", e); hintSession = null; }
    }
    @Override protected void onDestroy() {
        if (hintSession != null) {
            try { hintSession.close(); } catch (Throwable e) { android.util.Log.w("Zakuro", "Closing hint session", e); }
            hintSession = null;
        }
        if (inputManager != null) inputManager.unregisterInputDeviceListener(inputListener);
        if (batteryRegistered) { unregisterReceiver(batteryReceiver); batteryRegistered=false; }
        if (displays != null) displays.unregisterDisplayListener(displayListener);
        if (secondDisplay != null) { secondDisplay.dismiss(); secondDisplay=null; }
        nativeSecondSurface(null);
        hud.removeCallbacksAndMessages(null);
        if (pauseDialog != null) pauseDialog.dismiss();
        if (errorDialog != null) { errorDialog.dismiss(); errorDialog = null; }
        // NativeActivity unload joins android_main; only then may a new native session start.
        super.onDestroy();
        if (saveGuard != null) {
            try { saveGuard.close(); } catch (java.io.IOException e) { android.util.Log.w("Zakuro", "Closing save lock", e); }
            saveGuard = null;
        }
        if (pendingRestart != null) {
            android.content.Intent next = pendingRestart;
            pendingRestart = null;
            next.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            android.content.Context application = getApplicationContext();
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> application.startActivity(next));
        }
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) fullscreen();
        else touchMask=0;
    }
    @Override protected void onResume() {
        super.onResume();
        updateSecondDisplay();
        fullscreen();
    }
    private void fullscreen() {
        getWindow().setDecorFitsSystemWindows(false);
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            controller.hide(WindowInsets.Type.systemBars());
            controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }
}
