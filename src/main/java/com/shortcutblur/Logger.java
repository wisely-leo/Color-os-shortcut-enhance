package com.shortcutblur;

import android.util.Log;

/**
 * 统一日志门面。
 *
 * <p>同时输出到 logcat（TAG = {@link #TAG}）与 {@link ModuleLog} 文件日志。
 * 文件日志仅在 {@link ModuleLog#ENABLED} 为 true 时生效，release 下几乎零开销。
 */
public final class Logger {

    /** logcat tag。 */
    public static final String TAG = "ColorOSBlurEnhance";

    private Logger() {}

    /** 记录一条 INFO 日志。 */
    public static void log(String msg) {
        Log.i(TAG, msg);
        ModuleLog.i(msg);
    }
}
