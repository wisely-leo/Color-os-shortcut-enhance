package com.shortcutblur;

import android.content.Context;
import android.content.ContextWrapper;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;

/**
 * v30: 模糊层范围从【时间行容器】扩到【provider 根】。
 *   原因：日期/天气在时间行容器【上方】，模糊层 bounds 锚在时间行容器会导致
 *   它们的字形被裁掉（只露出下半部分，即观察到的“矩形”= 模糊层 bounds）。
 *   改为根后，bounds 覆盖整个组件，日期/时间/天气全部在范围内。
 */
public class WidgetBlurAttacher {

    private static final int TYPE_WIDGET = 7;
    private static final int TARGET_ROOT = 0x7f0a017b;
    private static final int CONTAINER_ID = 0x7f0a02ab;
    // v21: 同一个 container 只处理一次，避免反复 createBlurForView重建矩形背景
    private static final java.util.WeakHashMap<View, Boolean> sDone = new java.util.WeakHashMap<View, Boolean>();

    public static void attach(String tag, View root, ClassLoader cl) {
        if (root == null) return;
        try {
            View host = findHost(root);
            if (host == null) { Logger.log("BW " + tag + " host not found"); return; }
            Object launcher = findLauncher(host.getContext());
            if (launcher == null) { Logger.log("BW no launcher"); return; }
            Logger.log("BW " + tag + " launcher=" + launcher);
            // v30: 模糊 target = provider 根（覆盖日期/时间/天气整个区域）
            View container = findViewByIdRecursive(root, TARGET_ROOT);
            if (container == null) { container = findViewByIdRecursive(root, CONTAINER_ID); }
            if (container == null) { Logger.log("BW target not found"); return; }
            synchronized (sDone) {
                if (Boolean.TRUE.equals(sDone.get(container))) { Logger.log("BW skip(done)"); return; }
                sDone.put(container, Boolean.TRUE);
            }
            Logger.log("BW container=" + container.getClass().getName() + " " + container.getWidth() + "x" + container.getHeight());
            try { container.setTag("OplusBlurBg"); } catch (Throwable t) { Logger.log("BW setTag FAIL: " + t); }
            boolean ok = tryCardBlurManager(launcher, container);
            if (!ok) ok = tryCreateViaHost(host, container);
            Logger.log("BW final ok=" + ok);
            GlyphBlurRenderer.attachGlyphBlur(container, container.getContext().getClassLoader());
        } catch (Throwable t) {
            Logger.log("BW attach FAIL: " + t);
        }
    }

    private static boolean tryCardBlurManager(Object launcher, View target) {
        try {
            Object mgr = invoke(launcher, "getCardBlurManager");
            if (mgr == null) { Logger.log("BW cardBlurManager null"); return false; }
            Method m = findMethod(mgr.getClass(), "createBlurForView", 3);
            if (m == null) { Logger.log("BW createBlurForView m null"); return false; }
            Object r = m.invoke(mgr, target, null, TYPE_WIDGET);
            Logger.log("BW createBlurForView(target) => " + r);
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            Logger.log("BW CardBlur FAIL: " + t);
            return false;
        }
    }

    private static boolean tryCreateViaHost(View host, View target) {
        try {
            Method m = findMethod(host.getClass(), "createBlurForView", 1);
            if (m == null) return false;
            m.invoke(host, target);
            Logger.log("BW host.createBlurForView(target) OK");
            return true;
        } catch (Throwable t) {
            Logger.log("BW hostCreate FAIL: " + t);
            return false;
        }
    }

    private static Object invoke(Object o, String name) {
        try { return o.getClass().getMethod(name).invoke(o); }
        catch (Throwable t) { Logger.log("BW invoke " + name + " fail: " + t); return null; }
    }

    private static View findHost(View v) {
        View cur = v;
        int g = 0;
        while (cur != null && g++ < 50) {
            if (cur.getClass().getName().contains("AppWidgetHostView")) return cur;
            if (cur.getParent() instanceof View) cur = (View) cur.getParent();
            else break;
        }
        return null;
    }

    private static Object findLauncher(Context ctx) {
        Context c = ctx;
        int g = 0;
        while (c != null && g++ < 30) {
            if (c.getClass().getName().equals("com.android.launcher.Launcher")) return c;
            if (c instanceof ContextWrapper) c = ((ContextWrapper) c).getBaseContext();
            else break;
        }
        return null;
    }

    private static Method findMethod(Class<?> c, String name, int params) {
        Class<?> k = c;
        int g = 0;
        while (k != null && g++ < 10) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterTypes().length == params) {
                    m.setAccessible(true);
                    return m;
                }
            }
            k = k.getSuperclass();
        }
        return null;
    }

    private static View findViewByIdRecursive(View v, int id) {
        if (v.getId() == id) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findViewByIdRecursive(g.getChildAt(i), id);
                if (r != null) return r;
            }
        }
        return null;
    }
}
