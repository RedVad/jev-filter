package com.jevfilter;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

/**
 * Guard against working without a network: we do not send requests when the phone is offline,
 * and after a few failures in a row we stop trying for a while instead of hammering the endpoint.
 */
final class Net {

    private static final int FAILS_BEFORE_PAUSE = 3;
    private static final long OFFLINE_PAUSE_MS = 30_000L;
    private static final long ERROR_PAUSE_MS = 120_000L;

    private static int fails = 0;
    private static long openUntil = 0L;

    private Net() {
    }

    static boolean online(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            NetworkInfo ni = cm.getActiveNetworkInfo();
            if (ni == null) return false;
            return ni.isConnected();
        } catch (Throwable t) {
            return true;
        }
    }

    /** false means: do not even try to reach the endpoint right now. */
    static synchronized boolean allowed(Context c) {
        long now = System.currentTimeMillis();
        if (now < openUntil) return false;
        if (!online(c)) {
            openUntil = now + OFFLINE_PAUSE_MS;
            return false;
        }
        return true;
    }

    /** true means the breaker just tripped and requests are paused for a while. */
    static synchronized boolean fail() {
        fails++;
        if (fails >= FAILS_BEFORE_PAUSE) {
            fails = 0;
            openUntil = System.currentTimeMillis() + ERROR_PAUSE_MS;
            return true;
        }
        return false;
    }

    static synchronized void ok() {
        fails = 0;
        openUntil = 0L;
    }

    /** Same pause state, but without changing it - for rate limited logging. */
    static synchronized boolean paused() {
        return System.currentTimeMillis() < openUntil;
    }
}
