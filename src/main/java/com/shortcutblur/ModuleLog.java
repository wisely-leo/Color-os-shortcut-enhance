package com.shortcutblur;

import android.os.Process;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;

public final class ModuleLog {

    /**
     * 日志总开关（编译期常量，默认关闭）。
     *
     * false = 发布默认。编译器内联把 d()/e()/i() 调用点折叠为 return，
     *          dex 中不保留任何日志逻辑，零开销、零日志。
     * true  = 仅用于本地排障，需手动改这一行并重新编译，门槛故意提高，
     *         禁止合入发布构建。
     *
     * 注意：static final 基本类型常量无法在运行时修改，开关只在编译期生效。
     */
    public static final boolean ENABLED = false;

    public static final String TAG = "colorosblurenhance";

    private static final long T0 = SystemClock.uptimeMillis();

    private static final String[] DIRS = {
            "/storage/emulated/0/Download",
            "/sdcard/Download",
            "/storage/emulated/0/Android/media",
            "/storage/emulated/0"
    };

    private static final String FILE = "ColorOSBlurEnhance.log";

    private static final String FILE_BLUR = "PostEffectBlur.log";

    private static final int UID_BLUR = 10205;

    private static File logFile;

    private static volatile boolean broken;

    private ModuleLog() {}

    public static void d(String category, String detail) {
        if (!ENABLED) return;
        long t = SystemClock.uptimeMillis() - T0;
        write("D|" + category + "|t=" + t + "|pid=" + Process.myPid() + "|" + detail + "\n");
    }

    public static void e(String category, String detail, Throwable t) {
        if (!ENABLED) return;
        long ms = SystemClock.uptimeMillis() - T0;
        String extra = "";
        if (t != null) {
            extra = "|" + t.getClass().getSimpleName() + ":" + t.getMessage();
            try {
                StackTraceElement[] st = t.getStackTrace();
                if (st != null && st.length > 0) {
                    extra += "@" + st[0].getClassName() + "." + st[0].getMethodName()
                            + ":" + st[0].getLineNumber();
                }
            } catch (Throwable ignored) {}
        }
        write("E|" + category + "|t=" + ms + "|pid=" + Process.myPid() + "|" + detail + extra + "\n");
    }

    public static void i(String detail) {
        d("INFO", detail);
    }

    private static synchronized void write(String s) {
        if (!ENABLED) return;
        try {
            android.util.Log.i(TAG, s.trim());
        } catch (Throwable ignored) { }
        try {
            if (logFile == null) {
                logFile = open();
                if (logFile == null) { broken = true; return; }
                raw("=== log start pid=" + Process.myPid()
                        + " uid=" + Process.myUid()
                        + " file=" + logFile.getAbsolutePath() + " ===\n");
            }
            raw(s);
        } catch (Throwable t) {
            broken = true;
            logFile = null;
            android.util.Log.w(TAG, "write failed: " + t);
        }
    }

    private static void raw(String s) {
        if (!ENABLED) return;
        Writer w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(logFile, true), "UTF-8");
            w.write(s);
            w.flush();
        } catch (Throwable t) {
            broken = true;
        } finally {
            if (w != null) {
                try { w.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private static String pickFileName() {
        if (!ENABLED) return FILE;
        try {
            if (Process.myUid() == UID_BLUR) return FILE_BLUR;
        } catch (Throwable ignored) {}
        return FILE;
    }

    private static File open() {
        if (!ENABLED) return null;
        for (String d : DIRS) {
            try {
                File dir = new File(d);
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, pickFileName());
                FileOutputStream fos = new FileOutputStream(f, false);
                fos.write(("=== ColorOSBlurEnhance log start uid=" + Process.myUid() + " ===\n").getBytes("UTF-8"));
                fos.close();
                android.util.Log.i(TAG, "log file opened: " + f.getAbsolutePath());
                return f;
            } catch (Throwable t) {
                android.util.Log.w(TAG, "open failed at " + d + ": " + t);
            }
        }
        android.util.Log.e(TAG, "NO writable log dir found");
        return null;
    }
}
