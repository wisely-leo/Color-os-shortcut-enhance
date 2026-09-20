package com.shortcutblur;

import android.util.Log;

public final class Logger {

    public static final String TAG = "ColorOSBlurEnhance";

    private Logger() {}

    public static void log(String msg) {
        Log.i(TAG, msg);
        ModuleLog.i(msg);
    }
}
