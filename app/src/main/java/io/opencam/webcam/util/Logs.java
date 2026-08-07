package io.opencam.webcam.util;

import android.util.Log;

/** Minimal logging wrapper so the rest of the app stays dependency-free. */
public final class Logs {
    public static final String TAG = "OpenCam";

    private Logs() {
    }

    public static void i(String msg) {
        Log.i(TAG, msg);
    }

    public static void e(String msg) {
        Log.e(TAG, msg);
    }

    public static void e(String msg, Throwable t) {
        Log.e(TAG, msg, t);
    }
}
