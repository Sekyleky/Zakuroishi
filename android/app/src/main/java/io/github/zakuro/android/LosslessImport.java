package io.github.zakuro.android;

import android.content.Context;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Takes the user's own Lossless.dll through the file picker, translates its shaders into app-private
 * storage and deletes the app's copy. Nothing from the DLL is bundled or downloaded.
 */
final class LosslessImport {
    static final String DONE = "Shaders translated and the app's copy of the file deleted. Turn on Frame generation in a game's Performance settings; it needs a Turnip driver.";
    private static final int SHADERS = 26;
    private static final long MAX_BYTES = 64L << 20;
    private static final String NOT_IT = "Choose Lossless.dll from your own Lossless Scaling folder. Shaders already imported are unchanged.";
    private LosslessImport() {}

    static boolean ready(File files) {
        String[] shaders = new File(files, "lsfg/spv").list((folder, name) -> name.endsWith(".spv"));
        return shaders != null && shaders.length == SHADERS;
    }
    /** A crash during translation can leave the copy behind; the next launch removes it. */
    static void clean(File files) { new File(files, "lsfg/Lossless.dll").delete(); }

    /** DOS header and PE signature; the bridge then looks for the shader resources. */
    static boolean looksLikePe(byte[] head, int length) {
        if (length < 64 || head[0] != 'M' || head[1] != 'Z') return false;
        int at = (head[60] & 255) | (head[61] & 255) << 8 | (head[62] & 255) << 16 | head[63] << 24;
        return at >= 64 && at <= length - 4 && head[at] == 'P' && head[at + 1] == 'E' && head[at + 2] == 0 && head[at + 3] == 0;
    }

    static void install(Context context, Uri uri) throws IOException {
        File folder = new File(context.getFilesDir(), "lsfg"), dll = new File(folder, "Lossless.dll");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Cannot create the app's folder for shaders.");
        try {
            try (InputStream input = context.getContentResolver().openInputStream(uri); FileOutputStream output = new FileOutputStream(dll)) {
                if (input == null) throw new IOException("Cannot read the selected file.");
                byte[] buffer = new byte[65536], head = new byte[4096];
                int read, kept = 0; long total = 0;
                while ((read = input.read(buffer)) > 0) {
                    if ((total += read) > MAX_BYTES) throw new IOException("This file is too large to be Lossless.dll. " + NOT_IT);
                    int take = Math.min(read, head.length - kept); System.arraycopy(buffer, 0, head, kept, take); kept += take;
                    output.write(buffer, 0, read);
                }
                if (!looksLikePe(head, kept)) throw new IOException("This file is not a Windows DLL. " + NOT_IT);
            }
            String failure;
            try {
                System.loadLibrary("lsfg_bridge");
                failure = translate(dll.getPath(), folder.getPath());
            } catch (UnsatisfiedLinkError e) { throw new IOException("This build of the app has no frame generation.", e); }
            if (failure != null) throw new IOException("This file has no usable Lossless Scaling shaders (" + failure + "). " + NOT_IT);
        } finally { dll.delete(); }
    }
    /** Returns null, or why the shaders could not be translated. See android/third_party/lsfg/bridge.cpp. */
    private static native String translate(String dll, String folder);
}
