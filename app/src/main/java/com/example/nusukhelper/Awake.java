package com.example.nusukhelper;

import android.content.Context;
import android.os.PowerManager;

/** Keeps the screen on while an auto mode runs (a sleeping screen stops the work). */
public final class Awake {
    private Awake() { }
    private static PowerManager.WakeLock lock;

    @SuppressWarnings("deprecation")
    public static synchronized void hold(Context c) {
        try {
            if (lock == null) {
                PowerManager pm = (PowerManager) c.getApplicationContext().getSystemService(Context.POWER_SERVICE);
                lock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "nusukhelper:auto");
                lock.setReferenceCounted(false);
            }
            if (!lock.isHeld()) lock.acquire(6 * 60 * 60 * 1000L);
        } catch (Exception ignored) { }
    }

    public static synchronized void release() {
        try { if (lock != null && lock.isHeld()) lock.release(); } catch (Exception ignored) { }
    }
}
