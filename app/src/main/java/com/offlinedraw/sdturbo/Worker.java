
package com.offlinedraw.sdturbo;

import android.content.Context;
import android.graphics.Bitmap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Single background worker + shared UI state (survives rotation / activity restarts). */
public final class Worker {
    public static final Worker I = new Worker();

    public volatile boolean busy = false, cancel = false;
    public volatile String stage = "idle", error = null, note = null;
    public volatile int pct = -1;
    public volatile Bitmap result = null;

    private final StringBuilder sb = new StringBuilder();
    private final ExecutorService ex = Executors.newSingleThreadExecutor();

    public interface Job { void run() throws Exception; }

    public void log(String s) {
        synchronized (sb) {
            sb.append(s).append('\n');
            if (sb.length() > 16000) sb.delete(0, sb.length() - 16000);
        }
    }

    public String log() { synchronized (sb) { return sb.toString(); } }

    public void progress(String s, int percent) { stage = s; pct = percent; log(s); }

    public void start(Context c, Job j) {
        if (busy) return;
        busy = true; cancel = false; error = null;
        final Context app = c.getApplicationContext();
        KeepAliveService.start(app);
        ex.execute(() -> {
            try { j.run(); }
            catch (Throwable t) {
                error = t.getClass().getSimpleName() + ": " + t.getMessage();
                log("ERROR " + error);
            } finally {
                busy = false; stage = "idle"; pct = -1;
                KeepAliveService.stop(app);
            }
        });
    }
}
