package io.github.zakuro.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;

/** Public launch boundary. The NativeActivity itself remains private. */
public final class FrontendLaunch extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            Intent request=getIntent();
            if (!Intent.ACTION_VIEW.equals(request.getAction())) throw new IOException("Expected a game launch request");
            Uri uri=request.getData();
            if(uri==null) throw new IOException("No game reference supplied");
            String id;
            if("zakuro".equals(uri.getScheme())) {
                if(!"game".equals(uri.getHost()) || uri.getPathSegments().size()!=1 || uri.getQuery()!=null || uri.getFragment()!=null)
                    throw new IOException("Expected zakuro://game/TITLE_ID");
                id=FrontendReference.validId(uri.getLastPathSegment());
            } else if("content".equals(uri.getScheme())) {
                try(ParcelFileDescriptor descriptor=getContentResolver().openFileDescriptor(uri,"r")) {
                    if(descriptor==null) throw new IOException("Cannot open frontend reference");
                    try(FileInputStream in=new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) { id=FrontendReference.read(in.getChannel()); }
                }
            } else throw new IOException("Use a content URI or Zakuroishi game link");
            startActivity(GameLaunch.intent(this,id)); finish();
        } catch(Exception e) {
            new AlertDialog.Builder(this).setTitle("Couldn't launch this game")
                .setMessage("Import this game into Zakuroishi first. Frontend references use the installed game's settings and saves.\n\n"+e.getMessage())
                .setPositiveButton("Open library",(d,w)->{ startActivity(new Intent(this,Launcher.class)); finish(); })
                .setNegativeButton("Close",(d,w)->finish()).setOnCancelListener(d->finish()).show();
        }
    }
}
