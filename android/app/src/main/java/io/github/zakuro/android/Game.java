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
    private native void nativeKeyboard(String text, int button);
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
        if (displays == null || settings == null || isFinishing()) return;
        // Settings are read at launch and every change from the pause menu restarts the game, so this only matters
        // if a setting ever turns live; the presentation must still go with the switch.
        if (!settings.flag("dual_display")) { if (secondDisplay != null) { secondDisplay.dismiss(); secondDisplay = null; } return; }
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
    private boolean currentInMicroamps, hudWarned;
    /** the power the battery gives or takes now, or "" where the device doesn't say. */
    private String batteryPower() {
        android.os.BatteryManager manager=(android.os.BatteryManager)getSystemService(BATTERY_SERVICE);
        int micro=manager==null ? Integer.MIN_VALUE : manager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        if(micro==Integer.MIN_VALUE || micro==0 || batteryVolts<=0) return "";
        // ponytail: the unit is learned from the first reading of 20000 or more (that is µA; no phone draws 20 A),
        // and below that a reading could be µA or mA, so it shows no watts. A device that reports mA and never
        // reaches 20000 never shows watts; ask the device for its unit if one turns up that matters.
        if(Math.abs(micro)>=20000) currentInMicroamps=true;
        if(!currentInMicroamps) return "";
        return String.format(java.util.Locale.ROOT," %.1f W",Math.abs(micro)/1e6f*batteryVolts);
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
            catch (RuntimeException e) {
                android.util.Log.w("Zakuro", "Could not show a HUD window", e);
                if (!hudWarned) { hudWarned = true; showNativeMessage("This device refused the on-screen controls and info. They will not be shown."); }
            }
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
    /** The game's software keyboard: buttons one per line, left to right, the last confirms. */
    public void showKeyboard(String hint, String text, int maxLength, String buttons, boolean refused) { runOnUiThread(() -> {
        android.widget.EditText edit = new android.widget.EditText(this);
        edit.setSingleLine(); edit.setText(text); edit.setSelection(edit.length());
        if (maxLength > 0) edit.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(maxLength)});
        String[] labels = buttons.isEmpty() ? new String[]{"OK"} : buttons.split("\n", -1);
        int last = labels.length - 1;
        AlertDialog.Builder builder = new AlertDialog.Builder(this, palette.dialogTheme()).setCancelable(false).setView(edit)
                .setTitle(hint.isEmpty() ? "Enter text" : hint).setMessage(refused ? "That text isn't accepted. Try again." : null)
                .setPositiveButton(labels[last].isEmpty() ? "OK" : labels[last], (d, w) -> nativeKeyboard(edit.getText().toString(), last));
        // ponytail: a middle button (three-button keyboards) shows as neutral, Android has no fourth slot
        if (last >= 1) builder.setNegativeButton(labels[0], (d, w) -> nativeKeyboard(edit.getText().toString(), 0));
        if (last >= 2) builder.setNeutralButton(labels[1], (d, w) -> nativeKeyboard(edit.getText().toString(), 1));
        AlertDialog dialog = builder.create();
        dialog.setOnKeyListener((d, code, event) -> { // Start confirms, as in settings
            if (code != KeyEvent.KEYCODE_BUTTON_START) return false;
            if (event.getAction() == KeyEvent.ACTION_UP) dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
            return true;
        });
        if (dialog.getWindow() != null) dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        dialog.show();
        edit.requestFocus();
    }); }
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
        // Two per row in reading order; the index is the case in pauseAction.
        String[] entries={"Resume","Settings…","Save state…","Load state…","Swap screens","Recalibrate motion",hudLabel(),"Screenshot","Restart game","Exit to library"};
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
        for(int i=0;i<entries.length;i++) {
            final int which=i;
            if(i%2==0) { line=new android.widget.LinearLayout(this); card.addView(line,new android.widget.LinearLayout.LayoutParams(-1,-2)); }
            android.widget.Button tile=new android.widget.Button(this);
            tile.setText(entries[which]); tile.setAllCaps(false); tile.setTextSize(17); tile.setStateListAnimator(null);
            tile.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
            tile.setMinHeight(p.dp(66)); tile.setBackground(p.button(Palette.SECONDARY)); tile.setPadding(p.dp(23),0,p.dp(18),0); // after the background: its inset would replace the padding
            tile.setTextColor(p.label(which==8 || which==9 ? p.danger : p.ink));
            tile.setOnClickListener(v -> { if(pauseAction(which,tile) && pauseDialog!=null) pauseDialog.dismiss(); });
            line.addView(tile,new android.widget.LinearLayout.LayoutParams(0,-2,1));
            if(first==null) first=tile;
        }
        card.addView(p.legend("A Select · B Resume · X Quick save · Y Quick load"));
        pauseDialog.setOnDismissListener(d -> { pauseDialog=null; if(!menuOpen) return; menuOpen=false; nativeCommand(RESUME); });
        pauseDialog.setOnKeyListener((d,code,event) -> {
            if(code==KeyEvent.KEYCODE_BUTTON_B) { if(event.getAction()==KeyEvent.ACTION_UP) d.cancel(); return true; }
            if(code==KeyEvent.KEYCODE_BUTTON_X) { if(event.getAction()==KeyEvent.ACTION_UP) { saveState(0); d.dismiss(); } return true; }
            if(code==KeyEvent.KEYCODE_BUTTON_Y) { if(event.getAction()==KeyEvent.ACTION_UP) confirmLoad(false); return true; }
            if(code==KeyEvent.KEYCODE_BUTTON_A) {
                if(event.getAction()==KeyEvent.ACTION_UP) {
                    if(pauseDialog.getCurrentFocus()!=null) pauseDialog.getCurrentFocus().performClick();
                }
                return true;
            }
            return false;
        });
        pauseDialog.show();
        p.dialog(pauseDialog,false); pauseDialog.getWindow().setLayout(Math.min(p.dp(560),getResources().getDisplayMetrics().widthPixels-p.dp(32)),-2);
        android.widget.Button focus=first; focus.post(focus::requestFocusFromTouch); // lit on open even in touch mode, once the window is shown
    }
    /** Runs a tile; true when the menu should close and the game resume. */
    private boolean pauseAction(int which, android.widget.Button tile) {
        switch(which) {
            case 1: openSettings(); break;
            case 2: slotMenu(true); break;
            case 3: slotMenu(false); break;
            case 4: nativeCommand(SWAP); break;
            case 5: nativeCommand(RECALIBRATE); break;
            case 6: toggleHud(); tile.setText(hudLabel()); return false;
            case 7: hud.postDelayed(this::screenshot,250); break; // once the menu is gone
            case 8: confirmRestart(); break;
            case 9: confirmExit(); break;
        }
        return true;
    }
    private void confirmRestart() {
        menuOpen=false;
        // Relaunch from saved settings: a core-only restart would keep this launch's options (e.g. HD textures).
        confirm("Restart game?","Unsaved progress will be lost. Saved states and in-game saves are kept.","Restart",() -> {
            String id=getIntent().getStringExtra("game_id");
            android.content.Intent next=new android.content.Intent(getIntent());
            try { GameSettings.load(this,id).apply(next,GameImport.active(this,id)); pendingRestart=next; finish(); }
            catch(Exception e) { nativeCommand(RESTART); }
        },this::pauseMenu);
    }
    /** Y in the pause menu, or the Quick load hotkey in game: the quick slot, after asking. Cancel goes back where it came from. */
    private void confirmLoad(boolean inGame) {
        if(!stateFile(0).isFile()) { showNativeMessage(inGame ? "No quick state yet" : "No quick state yet. Press X in this menu to save one."); return; }
        menuOpen=false; if(inGame) nativeCommand(PAUSE); else if(pauseDialog!=null) pauseDialog.dismiss();
        confirm("Load quick state?","Progress since it was saved is lost.","Load",() -> nativeCommand(LOAD),inGame ? () -> nativeCommand(RESUME) : this::pauseMenu);
    }
    /** A bound controller hotkey went down. runtime.rs consumes those keys, so they never reach dispatchKeyEvent or the game. */
    public void runHotkey(String name) { runOnUiThread(() -> {
        if(menuOpen || errorDialog!=null || isFinishing()) return;
        switch(name) {
            case "quick_save": thumbnail(shot -> saveThumbnail(0,shot)); nativeCommand(SAVE); break; // native shows "Saved slot 0"
            case "quick_load": confirmLoad(true); break;
            case "swap_screens": nativeCommand(SWAP); break;
            case "screenshot": screenshot(); break;
            case "pause_menu": pauseMenu(); break;
        }
    }); }
    /** The game as the pause menu opened, taken before any dialog dims it, for the thumbnail of a state saved from the menu. */
    // The picture is taken at the save, not when the menu opens: on Android 13 and older the copy blocks the UI thread, and Back must stay cheap.
    private void saveState(int slot) { nativeCommand(SAVE | slot<<4); thumbnail(shot -> saveThumbnail(slot,shot)); }
    private java.io.File thumbnailFile(int slot) { return new java.io.File(stateFile(slot).getParentFile(),slot+".png"); }
    /** A copy of the game surface about 160 px wide, or null. Dialogs are separate windows, so they are never in it. */
    private void thumbnail(java.util.function.Consumer<android.graphics.Bitmap> done) {
        android.view.SurfaceHolder holder=gameSurface;
        if(holder==null || surfaceWidth<=0 || surfaceHeight<=0) { done.accept(null); return; }
        android.graphics.Bitmap shot=android.graphics.Bitmap.createBitmap(160,Math.max(1,160*surfaceHeight/surfaceWidth),android.graphics.Bitmap.Config.ARGB_8888);
        try { android.view.PixelCopy.request(holder.getSurface(),shot,result -> done.accept(result==android.view.PixelCopy.SUCCESS ? shot : null),hud); } // scales to the bitmap's size
        catch(RuntimeException e) { done.accept(null); }
    }
    /**
     * Writes <slot>.png beside the state file; a missing or unreadable one shows a blank tile.
     * ponytail: written when the save is asked for, not when it succeeds, so a failed save leaves the new picture beside the old state. Report the native result to Java if that shows up.
     */
    private void saveThumbnail(int slot, android.graphics.Bitmap shot) {
        java.io.File file=thumbnailFile(slot);
        if(shot==null) { file.delete(); return; } // no picture beats the previous state's picture
        new Thread(() -> {
            file.getParentFile().mkdirs();
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(file)) { if(!shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out)) throw new java.io.IOException(); }
            catch(java.io.IOException e) { file.delete(); }
        },"state-thumbnail").start();
    }
    /** Slot files sit beside the quick state (slot 0), named <n>.state; the native side builds the same path. */
    private java.io.File stateFile(int slot) {
        return new java.io.File(getIntent().getStringExtra("data"),"states/"+getIntent().getStringExtra("game_id")+"/"+slot+".state");
    }
    /** Quick slot (0) then slots 1-9 with their save time (load: only filled ones). A picks, B goes back to the pause menu. Overwrite and load ask first. */
    private void slotMenu(boolean save) {
        menuOpen=false;
        Palette p=palette;
        java.text.DateFormat when=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM,java.text.DateFormat.SHORT);
        java.util.List<Integer> slots=new java.util.ArrayList<>(); java.util.List<String> dates=new java.util.ArrayList<>();
        java.util.List<android.graphics.Bitmap> thumbs=new java.util.ArrayList<>(); // at most ten 160 px PNGs: cheap enough to decode here
        for(int n=0;n<=9;n++) {
            java.io.File f=stateFile(n); boolean used=f.isFile();
            if(!save && !used) continue;
            slots.add(n); dates.add(used ? when.format(new java.util.Date(f.lastModified())) : null);
            thumbs.add(used ? android.graphics.BitmapFactory.decodeFile(thumbnailFile(n).getPath()) : null); // null when missing or unreadable, as for states from older builds
        }
        if(slots.isEmpty()) { showNativeMessage("No saved states yet"); pauseMenu(); return; }
        android.widget.ArrayAdapter<Integer> rows=new android.widget.ArrayAdapter<Integer>(this,0,slots) {
            @Override public android.view.View getView(int i,android.view.View old,android.view.ViewGroup parent) {
                android.widget.LinearLayout row=new android.widget.LinearLayout(Game.this); row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setPadding(p.dp(24),p.dp(12),p.dp(24),p.dp(12)); row.setMinimumHeight(p.dp(56));
                android.widget.ImageView art=new android.widget.ImageView(Game.this); art.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                art.setBackground(p.shape(p.soft,p.border,1,8)); art.setClipToOutline(true); art.setImageBitmap(thumbs.get(i)); // the rounded background also clips the picture
                android.widget.LinearLayout.LayoutParams at=new android.widget.LinearLayout.LayoutParams(p.dp(96),p.dp(58)); at.setMarginEnd(p.dp(16)); row.addView(art,at);
                android.widget.TextView name=new android.widget.TextView(Game.this); name.setText(slotName(slots.get(i),true)); name.setTextSize(17); name.setTextColor(p.ink);
                android.widget.TextView date=new android.widget.TextView(Game.this); date.setText(dates.get(i)==null ? "Empty" : dates.get(i)); date.setTextSize(15); date.setTextColor(p.muted);
                row.addView(name,new android.widget.LinearLayout.LayoutParams(0,-2,1)); row.addView(date);
                return row;
            }
        };
        boolean[] chained={false}, ran={false}; // chained: a confirm took over and decides what follows
        AlertDialog d=new AlertDialog.Builder(this,p.dialogTheme()).setTitle(save ? "Save state" : "Load state")
            .setAdapter(rows,(dd,i) -> {
                int n=slots.get(i); String date=dates.get(i);
                Runnable command=save ? () -> saveState(n) : () -> nativeCommand(LOAD | n<<4);
                if(save && date==null) { ran[0]=true; command.run(); return; }
                chained[0]=true;
                confirm((save ? "Overwrite " : "Load ")+slotName(n,false)+"?",save ? "It holds the state saved "+date+"." : "Progress since it was saved ("+date+") is lost.",
                    save ? "Overwrite" : "Load",command,() -> slotMenu(save));
            }).setNegativeButton("Back",null).create();
        android.view.View legend=p.legend((save ? "A Save" : "A Load")+" · B Back · ✚ Move"); legend.setPadding(p.dp(24),p.dp(8),p.dp(24),p.dp(4));
        d.getListView().addFooterView(legend,null,false);
        d.setOnDismissListener(dd -> { if(chained[0]) return; if(ran[0]) nativeCommand(RESUME); else pauseMenu(); });
        d.setOnKeyListener((dd,code,event) -> {
            if(code!=KeyEvent.KEYCODE_BUTTON_A && code!=KeyEvent.KEYCODE_BUTTON_B) return false;
            if(event.getAction()==KeyEvent.ACTION_UP) {
                android.widget.ListView list=d.getListView(); int at=list.getSelectedItemPosition();
                if(code==KeyEvent.KEYCODE_BUTTON_B) d.cancel();
                else if(at>=0 && at<slots.size()) list.performItemClick(list.getSelectedView(),at,list.getItemIdAtPosition(at));
            }
            return true;
        });
        d.show(); p.dialog(d,false);
        android.widget.ListView list=d.getListView(); list.setSelector(p.row()); list.setItemsCanFocus(false); list.setFocusableInTouchMode(true); list.post(() -> { list.requestFocusFromTouch(); list.setSelection(0); }); // FromTouch: in touch mode a plain requestFocus leaves no row selected, so A did nothing until the D-pad moved
    }
    private static String slotName(int n, boolean title) { return n==0 ? (title ? "Quick slot" : "the quick slot") : (title ? "Slot " : "slot ")+n; }
    private void confirmExit() {
        menuOpen=false;
        confirm("Exit to library?","Unsaved progress is lost. In-game saves are kept.","Exit",() -> { nativeCommand(EXIT); finish(); },this::pauseMenu);
    }
    /** A confirmation with Cancel focused; A clicks the focused button, B cancels. Yes runs the action and resumes the game, cancel runs back. */
    private void confirm(String title, String message, String yes, Runnable action, Runnable back) {
        boolean[] ran={false};
        AlertDialog d=new AlertDialog.Builder(this,palette.dialogTheme()).setTitle(title).setMessage(message)
            .setPositiveButton(yes,(dd,w) -> { ran[0]=true; action.run(); }).setNegativeButton("Cancel",null).create();
        d.setOnDismissListener(dd -> { if(ran[0]) nativeCommand(RESUME); else back.run(); });
        d.setOnKeyListener((dd,code,event) -> {
            if(code!=KeyEvent.KEYCODE_BUTTON_A && code!=KeyEvent.KEYCODE_BUTTON_B) return false;
            if(event.getAction()==KeyEvent.ACTION_UP) {
                if(code==KeyEvent.KEYCODE_BUTTON_B) dd.cancel();
                else if(d.getCurrentFocus()!=null) d.getCurrentFocus().performClick();
            }
            return true;
        });
        d.show(); palette.dialog(d,true); android.widget.Button cancel=d.getButton(AlertDialog.BUTTON_NEGATIVE); cancel.post(cancel::requestFocusFromTouch);
    }
    private String hudLabel() { return "HUD · "+(settings!=null && (settings.flag("show_clock") || settings.flag("show_battery")) ? "On" : "Off"); }
    /** Clock and battery are drawn here, not by the native side, so they switch at once; the choice is stored for this game. */
    private void toggleHud() {
        if(settings==null) return;
        boolean on=!(settings.flag("show_clock") || settings.flag("show_battery"));
        try {
            GameSettings stored=GameSettings.load(this,settings.id);
            stored.values.put("show_clock",on); stored.values.put("show_battery",on); stored.save(this);
        } catch(RuntimeException e) { showNativeMessage("The HUD setting could not be saved."); return; }
        settings.values.put("show_clock",on); settings.values.put("show_battery",on); liveHud();
    }
    private void liveHud() {
        if(settings.flag("show_battery") && !batteryRegistered) {
            registerReceiver(batteryReceiver,new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)); batteryRegistered=true;
        }
        hud.removeCallbacks(clock); hud.post(clock);
    }
    /** True when the stored settings differ from the running ones only in what the Java HUD draws. */
    private static boolean onlyHudChanged(GameSettings running, GameSettings stored) {
        GameSettings same=running.copy();
        same.values.put("show_clock",stored.values.get("show_clock")); same.values.put("show_battery",stored.values.get("show_battery"));
        return same.json().equals(stored.json()) && same.resolution==stored.resolution && same.textures==stored.textures && same.driver.equals(stored.driver);
    }
    private android.view.SurfaceHolder gameSurface;
    private int surfaceWidth, surfaceHeight;
    @Override public void surfaceChanged(android.view.SurfaceHolder holder,int format,int width,int height) {
        super.surfaceChanged(holder,format,width,height); gameSurface=holder; surfaceWidth=width; surfaceHeight=height;
    }
    @Override public void surfaceDestroyed(android.view.SurfaceHolder holder) { gameSurface=null; super.surfaceDestroyed(holder); }
    /**
     * Copies the game picture on this display to Pictures/Zakuroishi.
     * ponytail: this display's surface only (the top screen, or both screens when the layout shows both here); the second
     * display is a separate surface and is not in the picture. Capture both if anyone asks for a bottom-screen shot.
     */
    private void screenshot() {
        android.view.SurfaceHolder holder=gameSurface;
        if(holder==null || surfaceWidth<=0 || surfaceHeight<=0 || isFinishing()) { showNativeMessage("Screenshot failed: the game picture is not ready."); return; }
        android.graphics.Bitmap shot=android.graphics.Bitmap.createBitmap(surfaceWidth,surfaceHeight,android.graphics.Bitmap.Config.ARGB_8888);
        try {
            android.view.PixelCopy.request(holder.getSurface(),shot,result -> {
                if(result!=android.view.PixelCopy.SUCCESS) { shot.recycle(); showNativeMessage("Screenshot failed: the picture could not be copied (error "+result+")."); return; }
                new Thread(() -> showNativeMessage(storeScreenshot(shot)),"screenshot").start();
            },hud);
        } catch(RuntimeException e) { shot.recycle(); showNativeMessage("Screenshot failed: "+e.getMessage()); }
    }
    /** MediaStore needs no permission on API 30+ for the app's own pictures. Returns the message to show. */
    private String storeScreenshot(android.graphics.Bitmap shot) {
        android.content.ContentResolver resolver=getContentResolver();
        android.content.ContentValues values=new android.content.ContentValues();
        values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,"Zakuroishi_"+new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",java.util.Locale.ROOT).format(new java.util.Date())+".png");
        values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"image/png");
        values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,android.os.Environment.DIRECTORY_PICTURES+"/Zakuroishi");
        values.put(android.provider.MediaStore.MediaColumns.IS_PENDING,1);
        android.net.Uri uri=null;
        try {
            uri=resolver.insert(android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY),values);
            if(uri==null) throw new java.io.IOException("no room in the picture library");
            try(java.io.OutputStream out=resolver.openOutputStream(uri)) {
                if(out==null || !shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out)) throw new java.io.IOException("the picture could not be written");
            }
            values.clear(); values.put(android.provider.MediaStore.MediaColumns.IS_PENDING,0); resolver.update(uri,values,null,null);
            return "Screenshot saved to Pictures/Zakuroishi";
        } catch(Exception e) {
            if(uri!=null) try { resolver.delete(uri,null,null); } catch(RuntimeException ignored) {}
            return "Screenshot failed: "+e.getMessage();
        } finally { shot.recycle(); }
    }
    @Override protected void onNewIntent(android.content.Intent next) {
        super.onNewIntent(next);
        if (java.util.Objects.equals(getIntent().getStringExtra("game_id"),next.getStringExtra("game_id"))) return;
        nativeCommand(PAUSE);
        confirm("Switch games?","Unsaved progress in the current game will be lost. In-game saves are kept.","Switch",
            () -> { pendingRestart=next; finish(); },() -> nativeCommand(RESUME));
    }
    private void openSettings() {
        menuOpen=false;
        String id=getIntent().getStringExtra("game_id");
        try {
            SettingsDialog.show(this,id,GameImport.active(this,id),getIntent().getStringExtra("game_name"),true,
                () -> {
                    android.content.Intent next=new android.content.Intent(getIntent());
                    try {
                        GameSettings stored=GameSettings.load(this,id);
                        if(settings!=null && onlyHudChanged(settings,stored)) { // clock and battery switch live, no restart
                            if(!stored.name.isEmpty()) getIntent().putExtra("game_name",stored.name);
                            settings=stored; liveHud(); nativeCommand(RESUME); return;
                        }
                        stored.apply(next,GameImport.active(this,id));
                    }
                    catch(Exception e) { showNativeMessage(e.getMessage()); nativeCommand(RESUME); return; }
                    pendingRestart = next; finish();
                }, this::pauseMenu,null,null,null);
        } catch(Exception e) { showNativeMessage(e.getMessage()); pauseMenu(); }
    }
    private String runtimeError;
    private volatile SaveArchive.Guard saveGuard;
    /** Called before the native runtime opens any save files. */
    public String prepareSaves() {
        // A pending update confirmed after the game starts would kill it mid-save: drop it. Never fails the launch.
        try { android.content.pm.PackageInstaller installer = getPackageManager().getPackageInstaller();
            for (android.content.pm.PackageInstaller.SessionInfo s : installer.getMySessions()) installer.abandonSession(s.getSessionId()); } catch (Exception ignored) { }
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
        playStart=android.os.SystemClock.elapsedRealtime();
        updateSecondDisplay();
        fullscreen();
    }
    private long playStart;
    /** Play time and last-played for the library, in a file only this process writes. */
    @Override protected void onPause() {
        String id=getIntent().getStringExtra("game_id");
        if(id!=null) {
            android.content.SharedPreferences played=getSharedPreferences("played",MODE_MULTI_PROCESS);
            played.edit().putLong(id+".ms",played.getLong(id+".ms",0)+android.os.SystemClock.elapsedRealtime()-playStart)
                    .putLong(id+".last",System.currentTimeMillis()).commit(); // not apply(): the launcher reads this file in its onResume
        }
        super.onPause(); // last: a native fault in here must not lose the record
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
