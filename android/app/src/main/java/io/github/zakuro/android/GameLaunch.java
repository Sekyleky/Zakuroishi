package io.github.zakuro.android;

import android.content.Context;
import android.content.Intent;
import java.io.File;
import java.io.FileInputStream;

/** Both launchers resolve installed files here; external Intents cannot select native code. */
final class GameLaunch {
    static Intent intent(Context context, String id) throws Exception {
        if (id == null || !id.matches("[0-9A-F]{16}")) throw new java.io.IOException("Invalid game ID");
        File version = GameImport.active(context,id);
        GameSettings settings = GameSettings.load(context,id);
        // No library: the game runs in the interpreter, so there is nothing to check.
        if (settings.flag("aot") && new File(version,"code/title.so").isFile()) try (java.nio.channels.FileChannel code = new FileInputStream(new File(version,"code/title.so")).getChannel()) {
            NativeLibrary.inspect(code).requireCompatible();
        }
        Intent intent = new Intent(context,Game.class);
        intent.putExtra("game",new File(version,"game").getAbsolutePath());
        intent.putExtra("code",new File(version,"code/title.so").getAbsolutePath());
        intent.putExtra("data",new File(version,"data").getAbsolutePath());
        intent.putExtra("console_id",consoleId(context)); // the game uses it only in a local wireless room
        intent.putExtra("addons",GameImport.addonsDir(context,id).getAbsolutePath()); // updates and DLC, kept across re-imports
        // stable has no network permission: no rooms. An extra, not the settings, so a restart from the game (a copy of this intent) keeps it
        intent.putExtra("rooms",GameImport.nightly(context));
        settings.apply(intent,version);
        return intent;
    }
    /**
     * This install's console number, made once and kept, as the desktop does: in a room, consoles tell each other apart by it.
     * Outside a room the game keeps the core's shared default, so saves never see a new console.
     */
    static String consoleId(Context context) {
        android.content.SharedPreferences console = context.getSharedPreferences("console", Context.MODE_PRIVATE);
        long id = console.getLong("id", 0);
        if (id == 0) { id = new java.security.SecureRandom().nextLong() | 1; console.edit().putLong("id", id).commit(); }
        return Long.toHexString(id);
    }
}
