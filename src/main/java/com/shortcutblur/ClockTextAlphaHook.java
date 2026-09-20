package com.shortcutblur;

import android.widget.RemoteViews;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedModule;

/**
 * P1: 把时钟字体变半透明，验证能否透出下层。
 * 策略：Hook RemoteViews.setTextColor(int,int)（本应用内），
 *       对时钟 3 个 id（小时/冒号/分钟）强制 alpha。
 */
public final class ClockTextAlphaHook {

    // 与 View dump / aapt2 资源表 100%% 吻合
    private static final int ID_HOUR    = 0x7f0a02cf; // local_hour_txt
    private static final int ID_COLON   = 0x7f0a02c8; // local_colon_txt
    private static final int ID_MINUTES = 0x7f0a02d3; // local_minutes_txt
    private static final int ID_DATE    = 0x7f0a02ca; // local_date_info_txt
    private static final int ID_WEATHER = 0x7f0a02da; // local_weather_info_txt

    // 目标 alpha（0x00=全透，0x80=半透，0xFF=不透）
    private static final int TARGET_ALPHA = 0x4D; // v28: 50% -> 70% 透明

    private ClockTextAlphaHook() {}

    public static void install(XposedModule mod, ClassLoader cl) {
        try {
            Class<?> rv = Class.forName("android.widget.RemoteViews", false, cl);
            Method m = rv.getDeclaredMethod("setTextColor", int.class, int.class);
            mod.hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(new HookColor());
            Logger.log("CTCH hooked RemoteViews.setTextColor");
        } catch (Throwable t) {
            Logger.log("CTCH hook fail: " + t);
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
                        if (id == ID_HOUR || id == ID_COLON || id == ID_MINUTES || id == ID_DATE || id == ID_WEATHER) {
                            int newColor = (color & 0x00FFFFFF) | (TARGET_ALPHA << 24);
                            Logger.log("CTCH setTextColor id=0x" + Integer.toHexString(id)
                                + " color=0x" + Integer.toHexString(color)
                                + " -> 0x" + Integer.toHexString(newColor));
                            // 用新参数数组继续（getArgs() 不可变，必须走 proceed(Object[])）
                            Object[] newArgs = new Object[args.size()];
                            for (int i = 0; i < args.size(); i++) newArgs[i] = args.get(i);
                            newArgs[1] = Integer.valueOf(newColor);
                            return chain.proceed(newArgs);
                        }
                    }
                }
            } catch (Throwable t) {
                Logger.log("CTCH intercept fail: " + t);
            }
            return chain.proceed();
        }
    }
}
