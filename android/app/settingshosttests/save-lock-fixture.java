package io.github.zakuro.android;
public final class SaveLockFixture extends android.app.Service {
    private SaveArchive.Guard guard;
    public android.os.IBinder onBind(android.content.Intent intent) { return null; }
    public int onStartCommand(android.content.Intent intent, int flags, int startId) {
        try {
            if (guard == null) guard = SaveArchive.lock(getFilesDir(), "0004000000000001");
            android.util.Log.i("SaveLockFixture", "LOCK_HELD");
        } catch (Exception e) { throw new IllegalStateException(e); }
        return START_NOT_STICKY;
    }
    public void onDestroy() {
        try { if (guard != null) guard.close(); } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        super.onDestroy();
    }
}
