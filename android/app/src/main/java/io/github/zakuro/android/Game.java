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
    private String batteryText = "";
    private float batteryVolts;
    /** the power the battery gives or takes now, or "" where the device doesn't say. */
    private String batteryPower() {
        android.os.BatteryManager manager=(android.os.BatteryManager)getSystemService(BATTERY_SERVICE);
        int micro=manager==null ? Integer.MIN_VALUE : manager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        if(micro==Integer.MIN_VALUE || micro==0 || batteryVolts<=0) return "";
        // some devices report mA instead of µA
        float amps=Math.abs(micro)>=20000 ? Math.abs(micro)/1e6f : Math.abs(micro)/1e3f;
        return String.format(java.util.Locale.ROOT," %.1f W",amps*batteryVolts);
    }
    /** HUD windows of their own: NativeActivity takes this window's surface, so views added to it never draw. */
    private final java.util.List<android.view.View> panels = new java.util.ArrayList<>();
    private void panel(android.view.View view, int gravity, boolean touchable) { panel(view, gravity, touchable, -2, -2, dp(8), dp(8)); }
    private void panel(android.view.View view, int gravity, boolean touchable, int width, int height, int x, int y) {
        getWindow().getDecorView().post(() -> {
            if (isFinishing() || isDestroyed()) return;
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | (touchable ? WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL : WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
                android.graphics.PixelFormat.TRANSLUCENT);
            params.gravity = gravity; params.x = x; params.y = y;
            params.token = getWindow().getDecorView().getWindowToken();
            try { getWindowManager().addView(view, params); panels.add(view); }
            catch (RuntimeException e) { android.util.Log.w("Zakuro", "Could not show a HUD window", e); }
            if (touchViews.contains(view)) updateTouchVisibility();
        });
    }
    /** the touch controls' windows; hidden ones also stop taking touches, which then reach the game. */
    private final java.util.List<android.view.View> touchViews = new java.util.ArrayList<>();
    private boolean touchShown = true;
    private boolean batteryRegistered;
    private final android.content.BroadcastReceiver batteryReceiver = new android.content.BroadcastReceiver() {
        public void onReceive(android.content.Context context, android.content.Intent battery) {
            int level=battery.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL,-1), scale=battery.getIntExtra(android.os.BatteryManager.EXTRA_SCALE,-1);
            int status=battery.getIntExtra(android.os.BatteryManager.EXTRA_STATUS,-1), tenths=battery.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE,Integer.MIN_VALUE);
            batteryVolts=battery.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE,0)/1000f;
            batteryText=level>=0 && scale>0 ? Math.round(level*100f/scale)+"%" + ((status==android.os.BatteryManager.BATTERY_STATUS_CHARGING || status==android.os.BatteryManager.BATTERY_STATUS_FULL) ? " charging" : "")
                + (tenths!=Integer.MIN_VALUE ? String.format(java.util.Locale.ROOT," %.1f°C",tenths/10f) : "") : "";
        }
    };
    private final android.hardware.input.InputManager.InputDeviceListener inputListener = new android.hardware.input.InputManager.InputDeviceListener() {
        public void onInputDeviceAdded(int id) { updateTouchVisibility(); }
        public void onInputDeviceRemoved(int id) { updateTouchVisibility(); }
        public void onInputDeviceChanged(int id) { updateTouchVisibility(); }
    };
    private void updateTouchVisibility() {
        if(touchViews.isEmpty() || settings==null) return;
        boolean gamepad=false;
        if(inputManager!=null) for(int id:inputManager.getInputDeviceIds()) {
            android.view.InputDevice device=inputManager.getInputDevice(id);
            if(device!=null && !device.isVirtual() && (device.supportsSource(android.view.InputDevice.SOURCE_GAMEPAD) || device.supportsSource(android.view.InputDevice.SOURCE_JOYSTICK))) { gamepad=true; break; }
        }
        boolean visible=settings.flag("touch_controls") && !(settings.flag("touch_auto_hide") && gamepad);
        if(!visible && touchShown) {
            touchMask=0; nativeTouch(0,-1,-1); nativeButton(106,false); nativeButton(107,false);
            nativeStick(0,Float.NaN,Float.NaN); nativeStick(1,Float.NaN,Float.NaN);
        }
        touchShown=visible;
        for(android.view.View view:touchViews) {
            view.setVisibility(visible ? android.view.View.VISIBLE : android.view.View.GONE);
            if(!(view.getLayoutParams() instanceof WindowManager.LayoutParams) || !view.isAttachedToWindow()) continue;
            WindowManager.LayoutParams params=(WindowManager.LayoutParams)view.getLayoutParams();
            int before=params.flags;
            params.flags=visible ? params.flags & ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : params.flags | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            if(params.flags!=before) getWindowManager().updateViewLayout(view,params);
        }
    }
    private AlertDialog pauseDialog;
    private Palette palette;
    private boolean menuOpen;
    private android.widget.TextView stats;
    private final android.os.Handler hud = new android.os.Handler(android.os.Looper.getMainLooper());
    private String nativeStats = "";
    private GameSettings settings;
    private final Runnable clock = new Runnable() {
        public void run() {
            if (stats != null && settings != null) {
                String text = settings.flag("show_clock") ? android.text.format.DateFormat.getTimeFormat(Game.this).format(new java.util.Date()) : "";
                if (settings.flag("show_battery")) text += "  " + batteryText + batteryPower();
                if ((settings.flag("show_fps") || settings.flag("warm_before_start")) && !nativeStats.isEmpty()) text += "\n" + nativeStats;
                stats.setText(text.trim());
                stats.setVisibility(text.trim().isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
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
        android.widget.Button menu = new android.widget.Button(this); menu.setText("Pause");
        menu.setContentDescription("Open game pause menu"); menu.setOnClickListener(v -> pauseMenu());
        if (settings.flag("show_pause_button")) panel(menu, android.view.Gravity.TOP | android.view.Gravity.END, true);
        stats = new android.widget.TextView(this); stats.setTextColor(0xffffffff); stats.setBackgroundColor(0x99000000); stats.setTextSize(13); stats.setTypeface(android.graphics.Typeface.MONOSPACE);
        stats.setPadding(dp(6), dp(3), dp(6), dp(3)); stats.setVisibility(android.view.View.GONE);
        panel(stats, android.view.Gravity.TOP | android.view.Gravity.START, false);
        if (settings.flag("touch_controls")) {
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
            scroll.addView(controls); touchViews.add(scroll); panel(scroll, android.view.Gravity.BOTTOM, true, -1, -2, 0, 0);
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
                touchViews.add(stick); panel(stick, android.view.Gravity.BOTTOM | (i==0 ? android.view.Gravity.START : android.view.Gravity.END), true, dp(100), dp(100), 0, dp(64));
            }
        }
        inputManager=(android.hardware.input.InputManager)getSystemService(INPUT_SERVICE);
        inputManager.registerInputDeviceListener(inputListener,hud); updateTouchVisibility();
        hud.post(clock);
    }
    @Override public void onBackPressed() { pauseMenu(); }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if((event.getKeyCode()==KeyEvent.KEYCODE_BACK || event.getKeyCode()==KeyEvent.KEYCODE_BUTTON_MODE) && !menuOpen && errorDialog==null) {
            if(event.getAction()==KeyEvent.ACTION_UP) pauseMenu(); return true;
        }
        return super.dispatchKeyEvent(event);
    }
    public void showPauseMenu() { runOnUiThread(this::pauseMenu); }
    private void pauseMenu() {
        if(menuOpen || errorDialog!=null || isFinishing()) return;
        menuOpen=true; nativeCommand(PAUSE);
        // Grid order (index = switch case below): Resume|Save, Load|Swap, Recalibrate|Settings, Restart|Exit.
        String[] entries={"Resume","Save state","Load state","Restart game","Swap screens","Recalibrate motion","Settings…","Exit to library"};
        int[] grid={0,1,2,4,5,6,3,7};
        Palette p=palette;
        android.widget.LinearLayout card=new android.widget.LinearLayout(this);
        card.setOrientation(android.widget.LinearLayout.VERTICAL); card.setPadding(p.dp(24),p.dp(22),p.dp(24),p.dp(22));
        android.widget.LinearLayout head=new android.widget.LinearLayout(this); head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.TextView title=new android.widget.TextView(this); title.setText("Paused"); title.setTextSize(24); title.setTextColor(p.ink);
        android.widget.TextView name=new android.widget.TextView(this); name.setText(getIntent().getStringExtra("game_name")); name.setTextSize(14); name.setTextColor(p.muted);
        name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END); name.setGravity(android.view.Gravity.END);
        head.addView(title); head.addView(name,new android.widget.LinearLayout.LayoutParams(0,android.view.ViewGroup.LayoutParams.WRAP_CONTENT,1));
        card.addView(head);
        android.widget.Button first=null;
        android.widget.LinearLayout line=null;
        pauseDialog=new AlertDialog.Builder(this,p.dialogTheme()).setView(card).create();
        for(int i=0;i<grid.length;i++) {
            final int which=grid[i];
            if(i%2==0) { line=new android.widget.LinearLayout(this); card.addView(line,new android.widget.LinearLayout.LayoutParams(-1,-2)); }
            android.widget.Button tile=new android.widget.Button(this);
            tile.setText(entries[which]); tile.setAllCaps(false); tile.setTextSize(17); tile.setStateListAnimator(null);
            tile.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
            tile.setMinHeight(p.dp(66)); tile.setBackground(p.button(Palette.SECONDARY)); tile.setPadding(p.dp(23),0,p.dp(18),0); // after the background: its inset would replace the padding
            tile.setTextColor(p.label(which==3 || which==7 ? p.danger : p.ink));
            tile.setOnClickListener(v -> { pauseAction(which); pauseDialog.dismiss(); });
            line.addView(tile,new android.widget.LinearLayout.LayoutParams(0,-2,1));
            if(first==null) first=tile;
        }
        card.addView(p.legend("A Select · B Resume · ✚ Move"));
        pauseDialog.setOnDismissListener(d -> { pauseDialog=null; if(!menuOpen) return; menuOpen=false; nativeCommand(RESUME); });
        pauseDialog.setOnKeyListener((d,code,event) -> {
            if(code==KeyEvent.KEYCODE_BUTTON_B) { if(event.getAction()==KeyEvent.ACTION_UP) d.cancel(); return true; }
            if(code==KeyEvent.KEYCODE_BUTTON_A) {
                if(event.getAction()==KeyEvent.ACTION_UP) {
                    if(pauseDialog.getCurrentFocus()!=null) pauseDialog.getCurrentFocus().performClick();
                }
                return true;
            }
            return false;
        });
        pauseDialog.show();
        p.dialog(pauseDialog,false); pauseDialog.getWindow().setLayout(p.dp(560),-2); android.widget.Button focus=first; focus.setFocusableInTouchMode(true); focus.post(focus::requestFocus); // lit on open even in touch mode, once the window is shown
    }
    private void pauseAction(int which) {
        switch(which) {
            case 1: nativeCommand(SAVE); break;
            case 2: confirmLoad(); break;
            case 3: confirmRestart(); break;
            case 4: nativeCommand(SWAP); break;
            case 5: nativeCommand(RECALIBRATE); break;
            case 6: openSettings(); break;
            case 7: confirmExit(); break;
        }
    }
    private void confirmRestart() {
        menuOpen=false;
        confirm(new AlertDialog.Builder(this,palette.dialogTheme()).setTitle("Restart game?").setMessage("Unsaved progress will be lost. Saved states and in-game saves are kept.")
                // Relaunch from saved settings: a core-only restart would keep this launch's options (e.g. HD textures).
                .setPositiveButton("Restart",(d,w) -> {
                    String id=getIntent().getStringExtra("game_id");
                    android.content.Intent next=new android.content.Intent(getIntent());
                    try { GameSettings.load(this,id).apply(next,GameImport.active(this,id)); pendingRestart=next; finish(); }
                    catch(Exception e) { nativeCommand(RESTART); }
                }).setNegativeButton("Cancel",null)
                .setOnDismissListener(d -> nativeCommand(RESUME)));
    }
    private void confirmLoad() {
        menuOpen=false;
        confirm(new AlertDialog.Builder(this,palette.dialogTheme()).setTitle("Load quick state?").setMessage("Progress since it was saved is lost.")
                .setPositiveButton("Load",(d,w) -> nativeCommand(LOAD)).setNegativeButton("Cancel",null)
                .setOnDismissListener(d -> nativeCommand(RESUME)));
    }
    private void confirmExit() {
        menuOpen=false;
        confirm(new AlertDialog.Builder(this,palette.dialogTheme()).setTitle("Exit to library?").setMessage("Unsaved progress is lost. In-game saves are kept.")
                .setPositiveButton("Exit",(d,w) -> { nativeCommand(EXIT); finish(); }).setNegativeButton("Cancel",null)
                .setOnDismissListener(d -> nativeCommand(RESUME)));
    }
    /** Shows a confirmation with Cancel focused; A clicks the focused button, B cancels. */
    private void confirm(AlertDialog.Builder b) {
        AlertDialog d=b.create();
        d.setOnKeyListener((dd,code,event) -> {
            if(code!=KeyEvent.KEYCODE_BUTTON_A && code!=KeyEvent.KEYCODE_BUTTON_B) return false;
            if(event.getAction()==KeyEvent.ACTION_UP) {
                if(code==KeyEvent.KEYCODE_BUTTON_B) dd.cancel();
                else if(d.getCurrentFocus()!=null) d.getCurrentFocus().performClick();
            }
            return true;
        });
        d.show(); palette.dialog(d,true); android.widget.Button cancel=d.getButton(AlertDialog.BUTTON_NEGATIVE); cancel.setFocusableInTouchMode(true); cancel.post(cancel::requestFocus);
    }
    @Override protected void onNewIntent(android.content.Intent next) {
        super.onNewIntent(next);
        if (java.util.Objects.equals(getIntent().getStringExtra("game_id"),next.getStringExtra("game_id"))) return;
        nativeCommand(PAUSE);
        confirm(new AlertDialog.Builder(this,palette.dialogTheme()).setTitle("Switch games?")
            .setMessage("Unsaved progress in the current game will be lost. In-game saves are kept.")
            .setPositiveButton("Switch",(d,w)->{ pendingRestart=next; finish(); })
            .setNegativeButton("Cancel",null).setOnDismissListener(d->nativeCommand(RESUME)));
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
                }, () -> nativeCommand(RESUME),null,null,null);
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
        palette=new Palette(this);
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
            errorDialog = new AlertDialog.Builder(this, palette.dialogTheme())
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
            errorDialog.show(); palette.dialog(errorDialog, false);
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
        for (android.view.View view : panels) {
            try { getWindowManager().removeViewImmediate(view); } catch (RuntimeException e) { android.util.Log.w("Zakuro", "Removing a HUD window", e); }
        }
        panels.clear();
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
