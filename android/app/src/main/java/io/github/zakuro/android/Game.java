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
    private String runtimeError;
    private AlertDialog errorDialog;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        fullscreen();
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
    @Override protected void onDestroy() {
        if (errorDialog != null) { errorDialog.dismiss(); errorDialog = null; }
        super.onDestroy();
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) fullscreen();
    }
    @Override protected void onResume() {
        super.onResume();
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
