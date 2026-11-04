package io.github.zakuro.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;

/**
 * Takes the user's own Lossless.dll into app-private storage, over adb only (DUMP permission):
 * {@code adb shell 'content write --uri content://io.github.zakuro.android.lossless/dll < /sdcard/Download/Lossless.dll'}.
 * The game translates it at its next launch and deletes it.
 * ponytail: adb import for the prototype, a SAF picker like the driver import when this ships.
 */
public final class LosslessImport extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!mode.startsWith("w")) throw new FileNotFoundException("write only");
        File folder = new File(getContext().getFilesDir(), "lsfg");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new FileNotFoundException("no folder");
        return ParcelFileDescriptor.open(new File(folder, "Lossless.dll"),
            ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
