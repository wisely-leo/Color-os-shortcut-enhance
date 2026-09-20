package com.shortcutblur;

import android.os.Process;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;

public final class ModuleLog {

    public static final boolean ENABLED = false;

    public static final String TAG = "colorosblurenhance";

    private static final long T0 = SystemClock.uptimeMillis();

    private static final String DIR = "/storage/emulated/0/Download";

    private static final String FILE = "ColorOSBlurEnhance.log";

    private static final String FILE_BLUR = "PostEffectBlur.log";

    private static final int UID_BLUR = 10205;

    private static File logFile;

    private static boolean broken;

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
        if (broken) return;
        try {
            if (logFile == null) {
                logFile = open();
                if (logFile == null) {
                    broken = true;
                    return;
                }
                raw("=== ShortcutBlur log start pid=" + Process.myPid()
                        + " uid=" + Process.myUid()
                        + " file=" + logFile.getAbsolutePath() + " ===\n");
            }
            raw(s);
        } catch (Throwable t) {
            broken = true;
        }
    }

    private static void raw(String s) {
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
        try {
            if (Process.myUid() == UID_BLUR) return FILE_BLUR;
        } catch (Throwable ignored) {}
        return FILE;
    }

    private static File open() {
        try {
            File dir = new File(DIR);
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, pickFileName());
            FileOutputStream fos = new FileOutputStream(f, false);
            fos.write(new byte[0]);
            fos.close();
            return f;
        } catch (Throwable t) {
            return null;
        }
    }
}
