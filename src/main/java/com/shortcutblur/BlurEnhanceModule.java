package com.shortcutblur;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.DecelerateInterpolator;
import android.widget.RemoteViews;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public class BlurEnhanceModule extends XposedModule {

    private static final String CLS_POPUP_BLUR_VIEW = "com.android.launcher3.popup.PopupBlurView";
    private static final String CLS_OPLUS_POPUP = "com.android.launcher3.popup.OplusPopupContainerWithArrow";
    private static final String CLS_ARROW_POPUP = "com.android.launcher3.popup.ArrowPopup";
    private static final String CLS_LAUNCHER = "com.android.launcher.Launcher";

    private static final String CLS_OPLUS_EFFECT = "com.oplus.view.OplusViewBackgroundRenderEffect";
    private static final String CLS_DRAWABLE = "android.graphics.drawable.Drawable";
    private static final String CLS_OBJECT_ANIMATOR = "android.animation.ObjectAnimator";
    private static final String CLS_PROPERTY = "android.util.Property";
    private static final String CLS_ANIMATOR_SET = "android.animation.AnimatorSet";
    private static final String CLS_ANIMATOR = "android.animation.Animator";
    private static final String CLS_TIME_INTERPOLATOR = "android.animation.TimeInterpolator";
    private static final String CLS_DECELERATE = "android.view.animation.DecelerateInterpolator";
    private static final String M_GET_POP_BLUR_VIEW = "getPopBlurView";

    private static final String PKG_POSTEFFECT = "com.oplus.blur";

    private static final String PKG_CLOCK = "com.coloros.alarmclock";
    private static final String CLS_EA = "e.a";
    private static final float SAMPLE_SCALE = 0.5f;

    private static final float BLUR_RADIUS = 64.0f;
    private static final long BLUR_DURATION = 330L;

    private static final int F_STATIC = 1 << 0;
    private static final int F_ICON = 1 << 1;
    private static final int F_WALL = 1 << 2;
    private static final int F_ICON_ANIM = 1 << 3;

    private static final long ICON_BLUR_DELAY = 32L;
    private static final long DEPTH_FALLBACK_DELAY = 500L;

    private volatile ClassLoader cl;

    private volatile boolean installed = false;

    private volatile boolean postEffectInstalled = false;
    private final Set<Method> peHooked = new HashSet<>();

    private final Map<View, Boolean> armed = new WeakHashMap<>();
    private final Map<String, ValueAnimator> iconAnims = new ConcurrentHashMap<>();
    private final Map<String, String> animTargets = new ConcurrentHashMap<>();
    private final Set<String> dumpedCls = new HashSet<>();
    private final Map<View, Integer> flagsCache = new WeakHashMap<>();

    private volatile RenderEffect blurEffect;

    private static final Map<String, Class<?>> CLASS_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Field> FIELD_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Constructor<?>> CTOR_CACHE = new ConcurrentHashMap<>();

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        try {
            if (param == null) return;
            String pkg = param.getPackageName();
            ModuleLog.i("onPackageReady pkg=" + pkg);
            if (PKG_POSTEFFECT.equals(pkg)) {
                ClassLoader peLoader = param.getClassLoader();
                if (peLoader == null) return;
                if (postEffectInstalled) {
                    ModuleLog.d("READY", "posteffect already installed, skip");
                    return;
                }
                if (installPostEffectHooks(peLoader)) {
                    postEffectInstalled = true;
                }
                return;
            }

            if (PKG_CLOCK.equals(pkg)) {
                ClockTextAlphaHook.install(this, param.getClassLoader());
            }

            ClassLoader anyLoader = param.getClassLoader();
            if (anyLoader != null) {
                hookRemoteViewsApply(anyLoader);
                hookAppWidgetHostView(anyLoader);
            }

            if (!isTargetLauncher(pkg)) return;
            ClassLoader loader = param.getClassLoader();
            if (loader == null) return;
            this.cl = loader;

            if (installed) {
                ModuleLog.d("READY", "already installed, skip");
                return;
            }

            HookInstallResult r = installHooks(loader);
            if (r.critical > 0) {
                installed = true;
                ModuleLog.d("READY", "installed critical=" + r.critical + " total=" + r.total);
            } else {
                ModuleLog.d("READY", "no critical hook, will retry (total=" + r.total + ")");
            }
        } catch (Throwable t) {
            ModuleLog.e("READY", "onPackageReady failed", t);
        }
    }

    private static boolean isTargetLauncher(String p) {
        return "com.android.launcher".equals(p)
                || "com.oplus.launcher".equals(p)
                || "com.coloros.launcher".equals(p);
    }

    private static final class HookInstallResult {
        int critical;
        int total;
    }

    private HookInstallResult installHooks(ClassLoader loader) {
        HookInstallResult r = new HookInstallResult();

        Set<Method> hooked = new HashSet<>();
        try {
            Class<?> cls = loadClass(CLS_POPUP_BLUR_VIEW, loader);
            if (cls != null) {
                r.total += hookViewReturningMethod(cls, M_GET_POP_BLUR_VIEW, "pbv");
                r.total += hookPopupFinish(cls);
            }
            Class<?> comp = loadClass(CLS_POPUP_BLUR_VIEW + "$Companion", loader);
            if (comp != null) {
                r.total += hookViewReturningMethod(comp, M_GET_POP_BLUR_VIEW, "pbv_companion");
            }

            for (String cn : new String[]{CLS_OPLUS_POPUP, CLS_ARROW_POPUP, CLS_POPUP_BLUR_VIEW}) {
                Class<?> ac = loadClass(cn, loader);
                if (ac == null) continue;
                r.critical += hookPopupOpenCloseAnimation(ac, "onCreateOpenAnimation", true, hooked);
                r.critical += hookPopupOpenCloseAnimation(ac, "onCreateCloseAnimation", false, hooked);
            }
            r.total += r.critical;
            ModuleLog.d("INSTALL", "critical=" + r.critical + " total=" + r.total);
        } catch (Throwable t) {
            ModuleLog.e("INSTALL", "installHooks failed", t);
        }
        return r;
    }

    private int hookPopupFinish(Class<?> cls) {
        int n = 0;
        try {
            for (Method m : cls.getDeclaredMethods()) {
                if (!m.getName().equals("finish")) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length != 2) continue;
                if (!"android.view.ViewGroup".equals(pt[0].getName())) continue;
                if (pt[1] != boolean.class) continue;
                setAccessibleQuietly(m);
                hook((Executable) m)
                        .setId("iconblur.finish")
                        .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                        .intercept(new XposedInterface.Hooker() {
                            @Override
                            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                Object self = chain.getThisObject();
                                try {
                                    if (self instanceof View) {
                                        View v = (View) self;
                                        int flags = resolveBlurFlags(v);
                                        ModuleLog.d("FINISH", "flags=" + flags);
                                        clearBlurFlagsCache(v);
                                        if ((flags & F_WALL) != 0) {
                                            animateDepthBlur(v, 1.0f, 0.0f, BLUR_DURATION);
                                        }
                                        clearIconBlurByFlags(v, flags, "single-shot clear");
                                    }
                                } catch (Throwable t) {
                                    ModuleLog.e("FINISH", "hook body failed", t);
                                }
                                return chain.proceed();
                            }
                        });
                n++;
            }
        } catch (Throwable t) {
            ModuleLog.e("INSTALL", "hookPopupFinish failed", t);
        }
        return n;
    }

    private int hookPopupOpenCloseAnimation(Class<?> cls, final String methodName, final boolean opening,
                                  Set<Method> hooked) {
        int n = 0;
        try {
            for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals(methodName)) continue;
                    Class<?>[] pt = m.getParameterTypes();
                    if (pt.length != 1) continue;
                    if (!"android.animation.AnimatorSet".equals(pt[0].getName())) continue;

                    if (!hooked.add(m)) {
                        ModuleLog.d("INSTALL", "skip dup hook " + c.getName() + "." + methodName);
                        continue;
                    }
                    setAccessibleQuietly(m);
                    hook((Executable) m)
                            .setId("iconblur.opa." + methodName)
                            .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                            .intercept(new XposedInterface.Hooker() {
                                @Override
                                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                    Object self = chain.getThisObject();
                                    Object set = chain.getArg(0);
                                    Object result = chain.proceed();
                                    try {
                                        View anchor = null;
                                        if (self != null) {
                                            Object pbv = getFieldQuietlyAny(self, "mPopBlurView");
                                            if (pbv instanceof View) anchor = (View) pbv;
                                            if (anchor == null && self instanceof View) anchor = (View) self;
                                        }
                                        if (anchor != null && set != null) {
                                            int flags = resolveBlurFlags(anchor);
                                            ModuleLog.d("ANIM", methodName + " flags=" + flags);
                                            if ((flags & F_WALL) != 0) {
                                                float from = opening ? 0.0f : 1.0f;
                                                float to = opening ? 1.0f : 0.0f;
                                                Object anim = createDepthBlurAnimation(anchor, from, to, BLUR_DURATION);
                                                if (anim != null) {
                                                    playIntoAnimatorSet(set, anim);
                                                }
                                            }
                                            if (!opening) {
                                                clearBlurFlagsCache(anchor);
                                                clearIconBlurByFlags(anchor, flags, "single-shot clear(anim)");
                                            }
                                        }
                                    } catch (Throwable t) {
                                        ModuleLog.e("ANIM", "hook body failed (" + methodName + ")", t);
                                    }
                                    return result;
                                }
                            });
                    n++;
                }
            }
        } catch (Throwable t) {
            ModuleLog.e("INSTALL", "hookPopupOpenCloseAnimation(" + methodName + ") failed", t);
        }
        return n;
    }

    private int hookViewReturningMethod(Class<?> cls, String methodName, String id) {
        int n = 0;
        try {
            for (Method m : cls.getDeclaredMethods()) {
                if (!m.getName().equals(methodName)) continue;
                setAccessibleQuietly(m);
                final String mid = id + "#" + m.getParameterTypes().length;
                hook((Executable) m)
                        .setId("hook." + mid)
                        .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                        .intercept(new XposedInterface.Hooker() {
                            @Override
                            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                Object result = chain.proceed();
                                try {
                                    if (result instanceof View) {
                                        armBlurForView((View) result, mid);
                                    }
                                } catch (Throwable t) {
                                    ModuleLog.e("LIVE", "hook body failed", t);
                                }
                                return result;
                            }
                        });
                n++;
            }
        } catch (Throwable t) {
            ModuleLog.e("INSTALL", "hookViewReturningMethod(" + id + ") failed", t);
        }
        return n;
    }

    private int resolveBlurFlags(View view) {
        if (view == null) return 0;
        synchronized (flagsCache) {
            Integer c = flagsCache.get(view);
            if (c != null) return c;
        }
        int flags = isInsideOpenFolder(view)
                ? (F_STATIC | F_ICON | F_ICON_ANIM)
                : (F_STATIC | F_ICON | F_WALL);
        synchronized (flagsCache) {
            flagsCache.put(view, flags);
        }
        return flags;
    }

    private void clearBlurFlagsCache(View view) {
        if (view == null) return;
        synchronized (flagsCache) {
            flagsCache.remove(view);
        }
    }

    private void armBlurForView(View view, String mid) {
        if (view == null) return;
        try {
            ModuleLog.d("LIVE", "armBlurForView id=" + mid);
            final int flags = resolveBlurFlags(view);
            ModuleLog.d("LIVE", "flags=" + flags + " (static=" + ((flags & F_STATIC) != 0)
                    + " icon=" + ((flags & F_ICON) != 0) + " wall=" + ((flags & F_WALL) != 0) + ")");

            setIconBlurArmed(view, true);

            if ((flags & F_STATIC) != 0) clearStaticLayers(view);

            final View fv = view;

            if ((flags & F_ICON_ANIM) != 0) {
                applyIconBlurDelayed(fv, "single-shot apply");
            } else if ((flags & F_ICON) != 0) {
                applyIconBlurDelayed(fv, null);
            }

            if ((flags & F_WALL) == 0) return;
            fv.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        Object launcher = getLauncherQuietly(fv);
                        if (launcher == null) {
                            ModuleLog.d("DEPTH", "launcher null, skip retry");
                            return;
                        }
                        Object dc = invokeNoArgQuietly(launcher, "getDepthController");
                        if (dc == null) {
                            ModuleLog.d("DEPTH", "depthController null");
                            return;
                        }
                        Object g = invokeNoArgQuietly(dc, "getCurrentBlur");
                        float v = (g instanceof Float) ? (Float) g : -1f;
                        ModuleLog.d("DEPTH", "currentBlur=" + v);
                        if (v <= 0.05f) {
                            boolean ok = setDepthBlur(fv, 1.0f);
                            ModuleLog.d("DEPTH", "fallback setBlur=1 ok=" + ok);
                        }
                    } catch (Throwable t) {
                        ModuleLog.e("DEPTH", "retry failed", t);
                    }
                }
            }, DEPTH_FALLBACK_DELAY);
        } catch (Throwable t) {
            ModuleLog.e("LIVE", "armBlurForView failed", t);
        }
    }

    private void applyIconBlur(View view) {
        if (!isIconBlurArmed(view)) {
            ModuleLog.d("ICONBLUR", "skipped: disarmed");
            return;
        }
        applyIconBlurRadius(view, BLUR_RADIUS);
    }

    private void applyIconBlurRadius(View view, float radius) {
        applyIconBlurRadius(view, radius, true);
    }

    private void dumpOplusApiOnce(Class<?> cls) {
        String key = cls.getName();
        synchronized (dumpedCls) {
            if (!dumpedCls.add(key)) return;
        }
        try {
            StringBuilder sb = new StringBuilder();
            for (Method m : cls.getDeclaredMethods()) {
                sb.append(m.getName()).append('(');
                Class<?>[] ps = m.getParameterTypes();
                for (int i = 0; i < ps.length; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(ps[i].getSimpleName());
                }
                sb.append(")->").append(m.getReturnType().getSimpleName()).append(" | ");
            }
            ModuleLog.d("OPLUSDUMP", sb.toString());
            for (Field f : cls.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = java.lang.reflect.Modifier.isStatic(f.getModifiers()) ? f.get(null) : null;
                    ModuleLog.d("OPLUSDUMP", "FIELD " + f.getName() + " type=" + f.getType().getSimpleName() + " val=" + val);
                } catch (Throwable ig) {}
            }
            for (Class<?> c = cls.getSuperclass(); c != null && c != Object.class; c = c.getSuperclass()) {
                StringBuilder s2 = new StringBuilder("SUPER " + c.getName() + ": ");
                for (Method m : c.getDeclaredMethods()) {
                    s2.append(m.getName()).append('(');
                    Class<?>[] ps = m.getParameterTypes();
                    for (int i = 0; i < ps.length; i++) {
                        if (i > 0) s2.append(',');
                        s2.append(ps[i].getSimpleName());
                    }
                    s2.append(") | ");
                }
                ModuleLog.d("OPLUSDUMP", s2.toString());
            }
        } catch (Throwable t) {
            ModuleLog.e("OPLUSDUMP", "dump failed", t);
        }
    }

    private void applyIconBlurRadius(View view, float radius, boolean useOplus) {
        if (view == null) return;
        try {
            RenderEffect effect = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.MIRROR);

            if (!useOplus) {
                trySetViewRenderEffect(view, effect);
                return;
            }

            boolean oplusOk = false;
            try {
                Class<?> cls = loadClass(CLS_OPLUS_EFFECT, currentClassLoader());
                if (cls != null) {
                    dumpOplusApiOnce(cls);
                    Method m = findMethodCached(cls, "setBackgroundRenderEffect", RenderEffect.class, View.class);
                    if (m != null) {
                        m.invoke(null, effect, view);
                        oplusOk = true;
                    }
                }
            } catch (Throwable t) {
                ModuleLog.d("ICONBLUR", "oplus path failed: " + t);
            }

            if (!oplusOk) {
                String err = trySetViewRenderEffect(view, effect);
                ModuleLog.d("ICONBLUR", "fallback setRenderEffect err=" + err);
            }
        } catch (Throwable t) {
            ModuleLog.e("ICONBLUR", "applyIconBlurRadius failed", t);
        }
    }

    private void animateIconBlur(final View view, final float fromRadius, final float toRadius, long duration) {
        if (view == null) return;
        try {
            final String key = System.identityHashCode(view) + "";
            synchronized (iconAnims) {
                ValueAnimator old = iconAnims.remove(key);
                if (old != null) {
                    try { old.cancel(); } catch (Throwable ignore) {}
                }
            }
            final String targetKey = "t_" + System.identityHashCode(view);
            String nowTarget = toRadius <= 0.01f ? "0" : "64";
            String prevTarget = animTargets.get(targetKey);
            if (prevTarget != null && prevTarget.equals(nowTarget)) {
                ModuleLog.d("ICONANIM", "skip dup " + prevTarget);
                return;
            }
            animTargets.put(targetKey, nowTarget);
            final ValueAnimator va = ValueAnimator.ofFloat(fromRadius, toRadius);
            va.setDuration(duration);
            va.setInterpolator(new DecelerateInterpolator());
            va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                private float last = Float.NaN;

                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    try {
                        if (!isCurrentAnimation(key, va)) return;
                        float r = (Float) a.getAnimatedValue();
                        if (!Float.isNaN(last) && Math.abs(r - last) < 0.5f) return;
                        last = r;
                        if (!isIconBlurArmed(view)) {
                            try { va.cancel(); } catch (Throwable ignore) {}
                            return;
                        }
                        applyIconBlurRadius(view, r, true);
                    } catch (Throwable t) {
                        ModuleLog.e("ICONANIM", "update failed", t);
                    }
                }
            });
            synchronized (iconAnims) {
                iconAnims.put(key, va);
            }
            va.start();
            ModuleLog.d("ICONANIM", "anim " + fromRadius + "->" + toRadius + " dur=" + duration);
            final boolean[] finished = new boolean[]{false};
            view.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (finished[0]) return;
                    finished[0] = true;
                    try {
                        if (!isCurrentAnimation(key, va)) return;
                        removeAnimation(key);
                        if (!isIconBlurArmed(view)) {
                            clearIconBlur(view);
                            return;
                        }
                        if (toRadius <= 0.01f) {
                            applyIconBlurRadius(view, 0.0f, false);
                            clearIconBlur(view);
                        } else {
                            applyIconBlurRadius(view, toRadius, true);
                        }
                    } catch (Throwable t) {
                        ModuleLog.e("ICONANIM", "finalize failed", t);
                    }
                }
            }, duration + 16L);
        } catch (Throwable t) {
            ModuleLog.e("ICONANIM", "animateIconBlur failed", t);
            try {
                if (toRadius <= 0.01f) clearIconBlur(view);
                else applyIconBlurRadius(view, toRadius, true);
            } catch (Throwable ignore) {}
        }
    }

    private void setIconBlurArmed(View view, boolean value) {
        if (view == null) return;
        synchronized (armed) {
            if (value) {
                armed.put(view, Boolean.TRUE);
            } else {
                armed.remove(view);
            }
        }
    }

    private boolean isIconBlurArmed(View view) {
        if (view == null) return false;
        synchronized (armed) {
            return armed.containsKey(view);
        }
    }
private RenderEffect getBlurEffect() {
        RenderEffect e = blurEffect;
        if (e == null) {
            e = RenderEffect.createBlurEffect(BLUR_RADIUS, BLUR_RADIUS, Shader.TileMode.MIRROR);
            blurEffect = e;
        }
        return e;
    }

    private void clearIconBlurByFlags(View v, int flags, String tag) {
        if ((flags & F_ICON_ANIM) != 0) {
            ModuleLog.d("ICONANIM", "TEST " + tag);
            clearIconBlur(v);
        } else if ((flags & F_ICON) != 0) {
            clearIconBlur(v);
        }
    }

    private void applyIconBlurDelayed(final View v, final String tag) {
        v.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (tag != null) ModuleLog.d("ICONANIM", "TEST " + tag);
                    applyIconBlur(v);
                } catch (Throwable t) {
                    ModuleLog.e(tag != null ? "ICONANIM" : "ICONBLUR", "delayed apply failed", t);
                }
            }
        }, ICON_BLUR_DELAY);
    }

    private boolean isCurrentAnimation(String key, ValueAnimator va) {
        synchronized (iconAnims) {
            return iconAnims.get(key) == va;
        }
    }

    private void removeAnimation(String key) {
        synchronized (iconAnims) {
            iconAnims.remove(key);
        }
    }

    private static void setAccessibleQuietly(Executable e) {
        try {
            e.setAccessible(true);
        } catch (Throwable ignore) {
        }
    }

    private void clearIconBlur(View view) {

        setIconBlurArmed(view, false);
        try {
            Class<?> cls = loadClass(CLS_OPLUS_EFFECT, currentClassLoader());
            if (cls != null) {
                Method m = findMethodCached(cls, "setBackgroundRenderEffect", RenderEffect.class, View.class);
                if (m != null) {
                    m.invoke(null, null, view);
                    ModuleLog.d("CLEAR", "icon blur cleared via oplus");
                    return;
                }
            }
        } catch (Throwable t) {
            ModuleLog.e("CLEAR", "oplus clear failed", t);
        }
        try {
            Method m = findMethodCached(View.class, "setRenderEffect", RenderEffect.class);
            if (m != null) {
                m.invoke(view, (Object) null);
                ModuleLog.d("CLEAR", "icon blur cleared via View");
            }
        } catch (Throwable t) {
            ModuleLog.d("CLEAR", "icon blur clear failed: " + t);
        }
    }

    private void clearStaticLayers(View view) {
        try {
            Class<?> drawableCls = loadClass(CLS_DRAWABLE, currentClassLoader());
            boolean wall = false;
            boolean drag = false;

            if (drawableCls != null) {
                Method mw = findMethodCached(view.getClass(), "setWallpaperDrawable", drawableCls);
                if (mw != null) {
                    try { mw.invoke(view, (Object) null); wall = true; } catch (Throwable ignore) {}
                }
                Method md = findMethodCached(view.getClass(), "setDragLayerDrawable", drawableCls);
                if (md != null) {
                    try { md.invoke(view, (Object) null); drag = true; } catch (Throwable ignore) {}
                }
            }

            Field f = findField(view.getClass(), "mIsBlurUnavailable");
            if (f != null) {
                try { f.setBoolean(view, true); } catch (Throwable ignore) {}
            }
            invokeNoArgQuietly(view, "invalidate");
            ModuleLog.d("CLEAR", "staticLayers wall=" + wall + " drag=" + drag);
        } catch (Throwable t) {
            ModuleLog.e("CLEAR", "clearStaticLayers failed", t);
        }
    }

    private String trySetViewRenderEffect(View view, RenderEffect effect) {
        try {
            Method m = findMethodCached(View.class, "setRenderEffect", RenderEffect.class);
            if (m == null) return "setRenderEffect not found";
            m.invoke(view, effect);
            return null;
        } catch (Throwable t) {
            return String.valueOf(t);
        }
    }

    private Object createDepthBlurAnimation(View view, float from, float to, long duration) {
        try {
            Object launcher = getLauncherQuietly(view);
            if (launcher == null) return null;
            Object dc = invokeNoArgQuietly(launcher, "getDepthController");
            if (dc == null) return null;
            Object prop = getStaticFloatProperty(dc, "BLUR");
            if (prop == null) return null;

            float cur = from;
            try {
                Object g = invokeNoArgQuietly(dc, "getCurrentBlur");
                if (g instanceof Float) {
                    float v = (Float) g;
                    if (v >= 0.0f) cur = v;
                }
            } catch (Throwable ignore) {}

            Class<?> oaCls = loadClass(CLS_OBJECT_ANIMATOR, currentClassLoader());
            Class<?> propCls = loadClass(CLS_PROPERTY, currentClassLoader());
            if (oaCls == null || propCls == null) return null;

            Method ofFloat = findMethodCached(oaCls, "ofFloat", Object.class, propCls, float[].class);
            if (ofFloat == null) return null;
            Object anim = ofFloat.invoke(null, dc, prop, new float[]{cur, to});
            if (anim == null) return null;

            Class<?> animCls = loadClass(CLS_ANIMATOR, currentClassLoader());
            if (animCls == null) return null;

            Method setDur = findMethodCached(animCls, "setDuration", long.class);
            if (setDur != null) setDur.invoke(anim, duration);

            Class<?> ipCls = loadClass(CLS_TIME_INTERPOLATOR, currentClassLoader());
            Class<?> decCls = loadClass(CLS_DECELERATE, currentClassLoader());
            if (ipCls != null && decCls != null) {
                Object decObj = newInstanceCached(decCls);
                Method setI = findMethodCached(animCls, "setInterpolator", ipCls);
                if (setI != null && decObj != null) setI.invoke(anim, decObj);
            }
            return anim;
        } catch (Throwable t) {
            ModuleLog.e("ANIM", "createDepthBlurAnimation failed", t);
            return null;
        }
    }

    private boolean animateDepthBlur(View view, float from, float to, long duration) {
        try {
            Object anim = createDepthBlurAnimation(view, from, to, duration);
            if (anim != null) {
                Class<?> animCls = loadClass(CLS_ANIMATOR, currentClassLoader());
                if (animCls != null) {
                    Method start = findMethodCached(animCls, "start");
                    if (start != null) {
                        start.invoke(anim);
                        return true;
                    }
                }
            }
            return setDepthBlur(view, to);
        } catch (Throwable t) {
            return false;
        }
    }

    private void playIntoAnimatorSet(Object set, Object anim) {
        try {
            Class<?> setCls = loadClass(CLS_ANIMATOR_SET, currentClassLoader());
            Class<?> animCls = loadClass(CLS_ANIMATOR, currentClassLoader());
            if (setCls == null || animCls == null) return;
            Method play = findMethodCached(setCls, "play", animCls);
            if (play != null) play.invoke(set, anim);
        } catch (Throwable t) {
        }
    }

    private boolean setDepthBlur(View view, float value) {
        try {
            Object launcher = getLauncherQuietly(view);
            if (launcher == null) return false;
            Object dc = invokeNoArgQuietly(launcher, "getDepthController");
            if (dc == null) return false;
            Method m = findMethodCached(dc.getClass(), "setBlurWithoutAnim", float.class);
            if (m == null) return false;
            m.invoke(dc, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private Class<?> loadClass(String name, ClassLoader loader) {
        if (loader == null) return null;
        String key = name + "@" + System.identityHashCode(loader);
        Class<?> cached = CLASS_CACHE.get(key);
        if (cached != null) return cached;
        try {
            Class<?> c = Class.forName(name, false, loader);
            CLASS_CACHE.put(key, c);
            return c;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method findMethodCached(Class<?> cls, String name, Class<?>... paramTypes) {
        StringBuilder sb = new StringBuilder(cls.getName()).append('#').append(name).append('(');
        for (Class<?> p : paramTypes) sb.append(p.getName()).append(',');
        sb.append(')');
        String key = sb.toString();
        Method cached = METHOD_CACHE.get(key);
        if (cached != null) return cached;
        try {
            Method m = cls.getMethod(name, paramTypes);
            m.setAccessible(true);
            METHOD_CACHE.put(key, m);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field findField(Class<?> cls, String name) {
        String key = cls.getName() + "#" + name;
        Field cached = FIELD_CACHE.get(key);
        if (cached != null) return cached;
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                FIELD_CACHE.put(key, f);
                return f;
            } catch (NoSuchFieldException nsf) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static Object newInstanceCached(Class<?> cls) {
        String key = cls.getName();
        Constructor<?> cached = CTOR_CACHE.get(key);
        try {
            if (cached == null) {
                cached = cls.getDeclaredConstructor();
                cached.setAccessible(true);
                CTOR_CACHE.put(key, cached);
            }
            return cached.newInstance();
        } catch (Throwable t) {
            return null;
        }
    }

    private Object getStaticFloatProperty(Object dc, String name) {
        Field f = findField(dc.getClass(), name);
        if (f == null) return null;
        try {
            return f.get(null);
        } catch (Throwable ignore) {
        }
        try {
            return f.get(dc);
        } catch (Throwable t) {
            ModuleLog.d("DEPTH", "BLUR field not static nor instance-accessible: " + t);
            return null;
        }
    }

    private Object getLauncherQuietly(View view) {
        try {
            Context ctx = view.getContext();
            if (ctx == null) return null;
            Class<?> lc = loadClass(CLS_LAUNCHER, currentClassLoader());
            if (lc == null) return null;

            Method g = findMethodCached(lc, "getLauncher", Context.class);
            if (g != null) {
                try {
                    Object r = g.invoke(null, ctx);
                    if (r != null) return r;
                } catch (Throwable ignore) {}
            }
            Method g2 = findMethodCached(lc, "getLauncherOrNull", Context.class);
            if (g2 != null) {
                try {
                    Object r2 = g2.invoke(null, ctx);
                    if (r2 != null) return r2;
                } catch (Throwable ignore) {}
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private Object invokeNoArgQuietly(Object target, String name) {
        try {
            Method m = findMethodCached(target.getClass(), name);
            if (m == null) return null;
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private Object getFieldQuietlyAny(Object obj, String name) {
        Field f = findField(obj.getClass(), name);
        if (f == null) return null;
        try {
            return f.get(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean installPostEffectHooks(ClassLoader loader) {
        Class<?> cls;
        try {
            cls = Class.forName(CLS_EA, false, loader);
        } catch (Throwable t) {
            ModuleLog.e("INSTALL", "class not found: " + CLS_EA, t);
            return false;
        }
        ModuleLog.i("INSTALL class loaded: " + cls.getName());
        int critical = 0;
        critical += hookBySignature(cls, "c",
                new String[]{ "android.view.SurfaceControl", "java.lang.Float", "java.lang.Integer", "java.lang.Long" },
                "c");
        critical += hookBySignature(cls, "e",
                new String[]{ "android.view.SurfaceControl", "java.lang.Float", "java.lang.Integer", "java.lang.Long" },
                "e");
        critical += hookBySignature(cls, "d",
                new String[]{ "android.view.SurfaceControl", "float", "int", "long" },
                "d");
        critical += hookBySignature(cls, "f",
                new String[]{ "float", "int", "long" },
                "f");
        ModuleLog.i("READY posteffect installed critical=" + critical);
        return critical > 0;
    }

    private int hookBySignature(Class<?> cls, String methodName, String[] wantParams, String tag) {
        Method target = null;
        StringBuilder all = new StringBuilder();
        for (Method m : cls.getDeclaredMethods()) {
            all.append(m.getName()).append("(");
            Class<?>[] ps = m.getParameterTypes();
            for (int i = 0; i < ps.length; i++) {
                if (i > 0) all.append(",");
                all.append(ps[i].getName());
            }
            all.append(") ");
            if (!m.getName().equals(methodName)) continue;
            if (ps.length != wantParams.length) continue;
            boolean ok = true;
            for (int i = 0; i < ps.length; i++) {
                if (!ps[i].getName().equals(wantParams[i])) { ok = false; break; }
            }
            if (ok) { target = m; break; }
        }
        if (target == null) {
            ModuleLog.e("INSTALL", "method not found: " + methodName + " | declared: " + all, null);
            return 0;
        }
        final Method m = target;
        final Class<?>[] paramTypes = m.getParameterTypes();
        try {
            m.setAccessible(true);
            if (!peHooked.add(m)) {
                ModuleLog.d("INSTALL", "skip dup hook " + cls.getName() + "." + methodName);
                return 0;
            }
            this.hook(m)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .intercept(chain -> {
                        Object[] args = chain.getArgs().toArray();
                        int pos = findFloatParamIndex(paramTypes);
                        if (pos >= 0 && pos < args.length && args[pos] instanceof Number) {
                            float old = ((Number) args[pos]).floatValue();
                            if (old != SAMPLE_SCALE) {
                                args[pos] = SAMPLE_SCALE;
                                ModuleLog.d("SCALE", tag + " " + old + " -> " + SAMPLE_SCALE);
                            } else {
                                ModuleLog.d("SCALE", tag + " keep " + old);
                            }
                        } else {
                            ModuleLog.d("SCALE", tag + " no float arg (pos=" + pos + ")");
                        }
                        return chain.proceed(args);
                    });
            ModuleLog.i("INSTALL hooked " + cls.getName() + "." + methodName + " (" + tag + ")");
            return 1;
        } catch (Throwable t) {
            ModuleLog.e("INSTALL", "hook failed: " + cls.getName() + "." + methodName, t);
            return 0;
        }
    }

    private int findFloatParamIndex(Class<?>[] types) {
        int idx = -1;
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            if (t == float.class || t == Float.class) {
                idx = i;
            }
        }
        return idx;
    }

    private boolean isInsideOpenFolder(View view) {
        try {
            ViewGroup dragLayer = findDragLayer(view);
            if (dragLayer == null) {
                ModuleLog.d("FOLDER", "no dragLayer found, chain=" + dumpViewChainNames(view));
                return false;
            }
            for (int i = 0; i < dragLayer.getChildCount(); i++) {
                View c = dragLayer.getChildAt(i);
                String cn = c.getClass().getName();
                if (cn.contains("Workspace")) {
                    float a = c.getAlpha();
                    int vis = c.getVisibility();
                    boolean inFolder = (a < 0.9f) || (vis != View.VISIBLE);
                    ModuleLog.d("FOLDER", "workspace vis=" + vis + " alpha=" + a + " -> inFolder=" + inFolder);
                    return inFolder;
                }
            }
            ModuleLog.d("FOLDER", "no workspace child, dragLayer children=" + dumpChildViewNames(dragLayer));
            return false;
        } catch (Throwable t) {
            ModuleLog.e("FOLDER", "isInsideOpenFolder failed", t);
            return false;
        }
    }

    private ViewGroup findDragLayer(View view) {
        ViewParent p = view.getParent();
        int guard = 0;
        while (p != null && guard < 50) {
            if (p instanceof ViewGroup) {
                String cn = p.getClass().getName();
                if (cn.contains("DragLayer")) return (ViewGroup) p;
            }
            p = (p instanceof View) ? ((View) p).getParent() : null;
            guard++;
        }
        return null;
    }

    private String dumpViewChainNames(View view) {
        StringBuilder sb = new StringBuilder();
        try {
            ViewParent p = view.getParent();
            int g = 0;
            while (p != null && g < 20) {
                sb.append(p.getClass().getSimpleName()).append(" > ");
                p = (p instanceof View) ? ((View) p).getParent() : null;
                g++;
            }
        } catch (Throwable ignored) {}
        return sb.toString();
    }

    private String dumpChildViewNames(ViewGroup vg) {
        StringBuilder sb = new StringBuilder();
        try {
            for (int i = 0; i < vg.getChildCount(); i++) {
                sb.append(vg.getChildAt(i).getClass().getSimpleName()).append(" ");
            }
        } catch (Throwable ignored) {}
        return sb.toString();
    }

    private ClassLoader currentClassLoader() {
        return cl;
    }

    private void hookRemoteViewsApply(ClassLoader cl) {
        try {
            Method m = RemoteViews.class.getDeclaredMethod("apply", android.content.Context.class, ViewGroup.class);
            hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(new RemoteViewsApplyHook(cl));
            ModuleLog.i("[merge] hooked RemoteViews.apply");
        } catch (Throwable t) {
            ModuleLog.e("MERGE", "RemoteViews.apply hook fail", t);
        }
    }

    private void hookAppWidgetHostView(ClassLoader cl) {
        try {
            Class<?> ahv = Class.forName("android.appwidget.AppWidgetHostView", false, cl);
            for (Method m : ahv.getDeclaredMethods()) {
                if (m.getName().equals("updateAppWidget")) {
                    hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(new AppWidgetHostViewHook(cl));
                    ModuleLog.i("[merge] hooked AppWidgetHostView.updateAppWidget");
                }
            }
        } catch (Throwable t) {
            ModuleLog.e("MERGE", "AppWidgetHostView hook fail", t);
        }
    }

    private static final class RemoteViewsApplyHook implements XposedInterface.Hooker {
        private final ClassLoader cl;
        RemoteViewsApplyHook(ClassLoader c) { this.cl = c; }
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object result = chain.proceed();
            try {
                java.util.List<Object> args = chain.getArgs();
                Object vg = args.size() > 1 ? args.get(1) : null;
                if (vg instanceof ViewGroup) {
                    WidgetBlurAttacher.attach("[apply]", (View) vg, cl);
                }
            } catch (Throwable t) {
                ModuleLog.e("MERGE", "apply post fail", t);
            }
            return result;
        }
    }

    private static final class AppWidgetHostViewHook implements XposedInterface.Hooker {
        private final ClassLoader cl;
        AppWidgetHostViewHook(ClassLoader c) { this.cl = c; }
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object self = chain.getThisObject();
            Object result = chain.proceed();
            try {
                if (self instanceof View) {
                    View v = (View) self;
                    if (v instanceof ViewGroup) {
                        WidgetBlurAttacher.attach("[ahv]", v, cl);
                    }
                    GlyphBlurRenderer.onWidgetUpdated(v);
                }
            } catch (Throwable t) {
                ModuleLog.e("MERGE", "ahv post fail", t);
            }
            return result;
        }
    }

}
