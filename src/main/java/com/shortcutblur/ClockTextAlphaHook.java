package com.shortcutblur;

import android.widget.RemoteViews;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedModule;

public final class ClockTextAlphaHook {

    private static final int TARGET_ALPHA = 0x4D;

    private static volatile boolean sInstalled = false;

    private ClockTextAlphaHook() {}

    private static boolean isClockTextId(int id) {
        for (int tid : ClockIds.TEXT_IDS) {
            if (tid == id) return true;
        }
        return false;
    }

    public static void install(XposedModule mod, ClassLoader cl) {
        if (sInstalled) return;
        synchronized (ClockTextAlphaHook.class) {
            if (sInstalled) return;
            sInstalled = true;
        }
        try {
            Class<?> rv = Class.forName("android.widget.RemoteViews", false, cl);
            Method m = rv.getDeclaredMethod("setTextColor", int.class, int.class);
            mod.hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(new HookColor());
            ModuleLog.d("CTCH", "hooked RemoteViews.setTextColor");
        } catch (Throwable t) {
            sInstalled = false;
            ModuleLog.e("CTCH", "hook fail", t);
        }
    }

    private static final class HookColor implements io.github.libxposed.api.XposedInterface.Hooker {
        @Override
        public Object intercept(Chain chain) throws Throwable {
            try {
                java.util.List<Object> args = chain.getArgs();
                if (args != null && args.size() >= 2) {
                    Object a0 = args.get(0);
                    Object a1 = args.get(1);
                    if (a0 instanceof Integer && a1 instanceof Integer) {
                        int id = (Integer) a0;
                        int color = (Integer) a1;
                        if (isClockTextId(id)) {
                            int newColor = (color & 0x00FFFFFF) | (TARGET_ALPHA << 24);
                            try { GlyphBlurRenderer.notifyContentMaybeChangedAll(); } catch (Throwable ignored) {}
                            ModuleLog.d("CTCH", "setTextColor id=0x" + Integer.toHexString(id)
                                + " color=0x" + Integer.toHexString(color)
                                + " -> 0x" + Integer.toHexString(newColor));

                            Object[] newArgs = new Object[args.size()];
                            for (int i = 0; i < args.size(); i++) newArgs[i] = args.get(i);
                            newArgs[1] = Integer.valueOf(newColor);
                            return chain.proceed(newArgs);
                        }
                    }
                }
            } catch (Throwable t) {
                ModuleLog.e("CTCH", "intercept fail", t);
            }
            return chain.proceed();
        }
    }
}
