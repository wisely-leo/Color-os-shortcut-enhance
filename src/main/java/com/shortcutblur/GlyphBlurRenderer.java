package com.shortcutblur;

import android.graphics.Path;
import android.text.TextPaint;
import android.view.View;
import android.widget.TextView;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public class GlyphBlurRenderer {

    private static final int MAX_RETRY = 12;

    private static final float GLYPH_DY = 0.0f;
    private static final long RETRY_DELAY_MS = 120L;

    private static final long POLL_INTERVAL_ON_MS = 500L;

    private static final long POLL_INTERVAL_OFF_MS = 3000L;

    private static final long POLL_INTERVAL_HIDDEN_MS = 1500L;

    private static final long POLL_INTERVAL_MAX_MS = 2000L;

    private static final long POLL_INTERVAL_TICK_MS = 500L;

    private static final int  POLL_STABLE_THRESHOLD = 6;

    private static final float ICON_ALPHA = 0.30f;

    private static final int ICON_ALPHA_THRESHOLD = 40;

    private static final int ICON_SAMPLE_MAX_W = 96;

    private static final float BRIGHTEN_GAIN = 1.25f;

    private static final class GlyphSnapshot {
        final Path localPath;
        final float offX, offY;
        GlyphSnapshot(Path localPath, float offX, float offY) {
            this.localPath = localPath; this.offX = offX; this.offY = offY;
        }
    }

    private static final java.util.WeakHashMap<View, GlyphSnapshot[]> sSnapsMap = new java.util.WeakHashMap<View, GlyphSnapshot[]>();
    private static volatile boolean sScreenOn = true;
    private static final java.util.WeakHashMap<View, PollRunner> sRunners = new java.util.WeakHashMap<View, PollRunner>();

    private static final java.util.HashMap<Integer, Integer> sIconFp = new java.util.HashMap<Integer, Integer>();
    private static final java.util.HashMap<Integer, Path> sIconFpPath = new java.util.HashMap<Integer, Path>();

    private static int iconFingerprint(android.graphics.Bitmap b) {
        if (b == null || b.isRecycled()) return 0;
        int w = b.getWidth(), h = b.getHeight();
        if (w <= 0 || h <= 0) return 0;
        int hash = 17;
        hash = hash * 31 + w;
        hash = hash * 31 + h;
        for (int iy = 0; iy < 4; iy++) {
            int y = h * iy / 4;
            for (int ix = 0; ix < 4; ix++) {
                hash = hash * 31 + b.getPixel(w * ix / 4, y);
            }
        }
        return hash;
    }
    public static void setScreenOn(boolean on) {
        boolean changed = (sScreenOn != on);
        sScreenOn = on;
        if (!changed) return;
        try {
            synchronized (sRunners) {
                for (PollRunner pr : new java.util.ArrayList<PollRunner>(sRunners.values())) pr.onScreenStateChanged();
            }
        } catch (Throwable t) { ModuleLog.e("GB", "screenStateChanged fail", t); }
    }

    public static void notifyContentMaybeChanged(View container) {
        if (container == null) return;
        try {
            PollRunner pr;
            synchronized (sRunners) { pr = sRunners.get(container); }
            if (pr != null) pr.kick();
        } catch (Throwable t) { ModuleLog.e("GB", "kick fail", t); }
    }

    public static void notifyContentMaybeChangedAll() {
        try {
            java.util.List<PollRunner> list;
            synchronized (sRunners) { list = new java.util.ArrayList<PollRunner>(sRunners.values()); }
            for (PollRunner pr : list) pr.kick();
        } catch (Throwable t) { ModuleLog.e("GB", "kickAll fail", t); }
    }

    public static void attachGlyphBlur(final View container, final ClassLoader cl) {
        if (container == null) return;
        try { tryAttachOnce(container, cl, 0); }
        catch (Throwable t) { ModuleLog.e("GB", "apply fail", t); }
    }

    private static void tryAttachOnce(final View container, final ClassLoader cl, final int attempt) {
        try {
            android.graphics.drawable.Drawable bg = container.getBackground();
            if (bg == null) { retry(container, cl, attempt, "bg null"); return; }
            String bgName = bg.getClass().getName();
            Object blurDrawable = Reflect.call(bg, "getBlurDrawable");
            if (blurDrawable == null) { retry(container, cl, attempt, "bg=" + bgName + " no getBlurDrawable"); return; }
            ModuleLog.d("GB", "bg=" + bgName + " blur=" + blurDrawable.getClass().getName());

            rebuildSnapshots(container);
            GlyphSnapshot[] snaps = sSnapsMap.get(container);
            if (snaps == null || snaps.length == 0) { return; }
            ModuleLog.d("GB", "snapshot n=" + snaps.length);

            Class<?> iface = Class.forName("com.oplus.posteffect.path.BlurDrawablePathProvider", false, cl);
            Object provider = Proxy.newProxyInstance(cl, new Class<?>[]{ iface }, new InvocationHandler() {
                @Override public Object invoke(Object proxy, Method method, Object[] args) {
                    try {
                        if ("getPath".equals(method.getName()) && args != null && args.length >= 2) {
                            Path out = (Path) args[1];
                            if (out != null) out.set(snapshotsToPath(sSnapsMap.get(container)));
                        }
                    } catch (Throwable t) { ModuleLog.e("GB", "proxy getPath fail", t); }
                    return null;
                }
            });

            Method setPP = Reflect.method(blurDrawable.getClass(), "setPathProvider", 1);
            if (setPP == null) { ModuleLog.e("GB", "setPathProvider not found", null); return; }
            setPP.invoke(blurDrawable, provider);
            ModuleLog.d("GB", "setPathProvider ok");

            installRefreshPoller(container, blurDrawable);
            applyClockBrighten(container);

            Method inv = Reflect.method(blurDrawable.getClass(), "invalidatePath", 0);
            if (inv != null) { inv.invoke(blurDrawable); ModuleLog.d("GB", "invalidatePath ok"); }
            container.invalidate();
            ModuleLog.d("GB", "done container");
        } catch (Throwable t) {
            ModuleLog.e("GB", "tryAttachOnce fail attempt=" + attempt, t);
        }
    }

    private static volatile Method sSetRenderEffectMethod;
    private static volatile android.graphics.RenderEffect sBrightenEffect;
    private static volatile boolean sBrightenBuildFailed;
    private static volatile boolean sBrightenLogged;

    private static android.graphics.RenderEffect buildBrightenEffect() {
        if (sBrightenEffect != null) return sBrightenEffect;
        if (sBrightenBuildFailed) return null;

        final float gain = BRIGHTEN_GAIN;
        float[] m = new float[] {
                gain, 0f,   0f,   0f, 0f,
                0f,   gain, 0f,   0f, 0f,
                0f,   0f,   gain, 0f, 0f,
                0f,   0f,   0f,   1f, 0f
        };
        try {
            android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix(m);
            android.graphics.ColorFilter cf = new android.graphics.ColorMatrixColorFilter(cm);
            Method cmf = android.graphics.RenderEffect.class.getMethod(
                    "createColorFilterEffect", android.graphics.ColorFilter.class);
            sBrightenEffect = (android.graphics.RenderEffect) cmf.invoke(null, cf);
            return sBrightenEffect;
        } catch (Throwable t) {
            sBrightenBuildFailed = true;
            ModuleLog.e("BRIGHT", "buildBrightenEffect fail", t);
            return null;
        }
    }

    private static Method findSetRenderEffect(View container) {
        Method cached = sSetRenderEffectMethod;
        if (cached != null) return cached;
        Class<?> target = android.view.View.class;
        try {
            cached = target.getMethod("setRenderEffect", android.graphics.RenderEffect.class);
            cached.setAccessible(true);
            sSetRenderEffectMethod = cached;
            return cached;
        } catch (Throwable t) {
            ModuleLog.e("BRIGHT", "View.setRenderEffect NOT FOUND", t);
            return null;
        }
    }

    static void applyClockBrighten(View container) {
        if (container == null) return;
        try {
            android.graphics.RenderEffect effect = buildBrightenEffect();
            if (effect == null) return;
            Method setRE = findSetRenderEffect(container);
            if (setRE == null) return;

            setRE.invoke(container, effect);
            if (!sBrightenLogged) { sBrightenLogged = true; ModuleLog.d("BRIGHT", "applied gain=" + BRIGHTEN_GAIN); }
        } catch (Throwable t) {
            ModuleLog.e("BRIGHT", "applyClockBrighten fail", t);
        }
    }

    private static void retry(final View container, final ClassLoader cl, final int attempt, final String why) {
        if (attempt >= MAX_RETRY) { ModuleLog.e("GB", "give up: " + why, null); return; }
        if (attempt == 0 || attempt == MAX_RETRY - 1) ModuleLog.d("GB", "retry(" + attempt + "): " + why);
        container.postDelayed(new Runnable() {
            @Override public void run() { tryAttachOnce(container, cl, attempt + 1); }
        }, RETRY_DELAY_MS);
    }

    static void onWidgetUpdated(final View container) {
        if (container == null) return;
        ModuleLog.d("GB", "poll -> WIDGET_UPDATED (evt: updateAppWidget)");
        notifyContentMaybeChangedAll();

        rebuildSnapshotsNow(container);

        container.postOnAnimation(new Runnable() {
            @Override public void run() { rebuildSnapshotsNow(container); }
        });

        container.postDelayed(new Runnable() {
            @Override public void run() { rebuildSnapshotsNow(container); }
        }, 50L);
    }

    private static void rebuildSnapshotsNow(View container) {
        try {
            Object blur = null;
            android.graphics.drawable.Drawable bg = container.getBackground();
            if (bg != null) blur = Reflect.call(bg, "getBlurDrawable");
            rebuildSnapshots(container);
            if (blur != null) {
                Method inv = Reflect.method(blur.getClass(), "invalidatePath", 0);
                if (inv != null) { inv.setAccessible(true); inv.invoke(blur); }
            }
            container.invalidate();
        } catch (Throwable t) { ModuleLog.e("GB", "rebuildSnapshotsNow fail", t); }
    }

    private static void appendWeatherIconSnapshots(View container, View base, java.util.ArrayList<GlyphSnapshot> list) {
        int[] iconIds = ClockIds.ICON_IDS;
        for (int id : iconIds) {
            try {
                View v = container.findViewById(id);
                if (!(v instanceof android.widget.ImageView)) {
                    View root = ViewUtils.descendantJustBelow(container, "AppWidgetHostView");
                    if (root != null) v = root.findViewById(id);
                }
                if (!(v instanceof android.widget.ImageView)) continue;
                android.widget.ImageView iv = (android.widget.ImageView) v;
                android.graphics.drawable.Drawable d = iv.getDrawable();
                if (d == null) continue;
                android.graphics.Bitmap bmp = null;
                if (d instanceof android.graphics.drawable.BitmapDrawable) {
                    bmp = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
                }
                if (bmp == null || bmp.isRecycled()) continue;
                if (bmp.getWidth() <= 0 || bmp.getHeight() <= 0) continue;

                int fp = iconFingerprint(bmp);
                Integer cFp = sIconFp.get(id);
                if (cFp != null && cFp.intValue() == fp) {
                    Path cached = sIconFpPath.get(id);
                    if (cached != null) {
                        list.add(new GlyphSnapshot(cached, localOffsetX(v, base), localOffsetY(v, base)));
                        continue;
                    }
                }

                int bw = Math.min(bmp.getWidth(), ICON_SAMPLE_MAX_W);
                int bh = Math.max(1, bmp.getHeight() * bw / bmp.getWidth());
                android.graphics.Bitmap small = android.graphics.Bitmap.createScaledBitmap(bmp, bw, bh, true);
                int[] px = new int[bw * bh];
                small.getPixels(px, 0, bw, 0, 0, bw, bh);
                android.graphics.Region region = new android.graphics.Region();
                int alphaThreshold = ICON_ALPHA_THRESHOLD;
                for (int y = 0; y < bh; y++) {
                    int runStart = -1;
                    for (int x = 0; x < bw; x++) {
                        int a = (px[y * bw + x] >>> 24);
                        if (a > alphaThreshold) {
                            if (runStart < 0) runStart = x;
                        } else {
                            if (runStart >= 0) { region.op(new android.graphics.Rect(runStart, y, x, y + 1), android.graphics.Region.Op.UNION); runStart = -1; }
                        }
                    }
                    if (runStart >= 0) region.op(new android.graphics.Rect(runStart, y, bw, y + 1), android.graphics.Region.Op.UNION);
                }
                if (region.isEmpty()) continue;
                Path iconPath = new Path();
                android.graphics.RegionIterator it = new android.graphics.RegionIterator(region);
                android.graphics.Rect r = new android.graphics.Rect();
                while (it.next(r)) iconPath.addRect(r.left, r.top, r.right, r.bottom, Path.Direction.CW);

                float sx = (float) iv.getWidth() / bw;
                float sy = (float) iv.getHeight() / bh;
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.setScale(sx, sy);
                iconPath.transform(m);
                float dx = localOffsetX(v, base);
                float dy = localOffsetY(v, base);
                list.add(new GlyphSnapshot(iconPath, dx, dy));
                sIconFp.put(Integer.valueOf(id), Integer.valueOf(fp));
                sIconFpPath.put(Integer.valueOf(id), iconPath);
            } catch (Throwable t) { ModuleLog.e("GB", "iconPath fail id=0x" + Integer.toHexString(id), t); }
        }
    }

    static float localOffsetX(View v, View container) {
        return localOffset(v, container, true);
    }
    static float localOffsetY(View v, View container) {
        return localOffset(v, container, false);
    }
    private static float localOffset(View v, View container, boolean horizontal) {
        if (v == null) return 0f;
        if (v == container) return 0f;
        float acc = 0f;
        View cur = v;
        int guard = 0;
        while (cur != null && cur != container && guard++ < 64) {
            acc += horizontal ? cur.getLeft() : cur.getTop();
            android.view.ViewParent p = cur.getParent();
            if (!(p instanceof View)) break;
            View pv = (View) p;
            acc -= horizontal ? pv.getScrollX() : pv.getScrollY();
            cur = pv;
        }
        return acc;
    }
    static void applyIconAlpha(View container) {
        try {
            int[] iconIds = ClockIds.ICON_IDS;
            for (int id : iconIds) {
                View v = container.findViewById(id);
                if (v == null) {
                    View root = ViewUtils.descendantJustBelow(container, "AppWidgetHostView");
                    if (root != null) v = root.findViewById(id);
                }
                if (v != null && Math.abs(v.getAlpha() - ICON_ALPHA) > 0.01f) {
                    v.setAlpha(ICON_ALPHA);
                    ModuleLog.d("GB", "iconAlpha id=0x" + Integer.toHexString(id)
                        + " cls=" + v.getClass().getSimpleName() + " alpha=" + ICON_ALPHA);
                }
            }
        } catch (Throwable t) { ModuleLog.e("GB", "applyIconAlpha fail", t); }
    }

    static void rebuildSnapshots(View container) {
        try {

            final View base = container;
            int[] ids = ClockIds.TEXT_IDS;
            java.util.ArrayList<GlyphSnapshot> list = new java.util.ArrayList<GlyphSnapshot>();
            for (int id : ids) {

                View v = container.findViewById(id);
                if (!(v instanceof TextView)) {
                    View root = ViewUtils.descendantJustBelow(container, "AppWidgetHostView");
                    if (root != null) v = root.findViewById(id);
                }
                if (!(v instanceof TextView)) continue;
                TextView tv = (TextView) v;
                CharSequence cs = tv.getText();
                if (cs == null || cs.length() == 0) continue;
                String text = cs.toString();
                TextPaint tp = tv.getPaint();
                if (tp == null) continue;

                float baseline;
                float startX;
                android.text.Layout lay = tv.getLayout();
                if (lay != null && lay.getLineCount() > 0) {
                    int line = 0;
                    baseline = tv.getTotalPaddingTop() + lay.getLineBaseline(line) + GLYPH_DY;

                    startX = tv.getTotalPaddingLeft() + lay.getLineLeft(line);
                } else {
                    android.graphics.Paint.FontMetrics fm = tp.getFontMetrics();
                    baseline = tv.getHeight() * 0.5f - (fm.ascent + fm.descent) * 0.5f + GLYPH_DY;
                    float w = tp.measureText(text);
                    startX = (tv.getWidth() - w) * 0.5f;
                }

                float dx = localOffsetX(v, base);
                float dy = localOffsetY(v, base);
                Path localPath = new Path();
                tp.getTextPath(text, 0, text.length(), startX, baseline, localPath);
                list.add(new GlyphSnapshot(localPath, dx, dy));
            }

            appendWeatherIconSnapshots(container, base, list);
            sSnapsMap.put(container, list.isEmpty() ? null : list.toArray(new GlyphSnapshot[0]));
            applyIconAlpha(container);
        } catch (Throwable t) {
            ModuleLog.e("GB", "rebuildSnapshots fail", t);
        }
    }

    static Path snapshotsToPath(GlyphSnapshot[] snaps) {
        Path out = new Path();
        if (snaps == null) return out;
        for (GlyphSnapshot s : snaps) {
            if (s.localPath == null) continue;
            Path gp = new Path(s.localPath);
            android.graphics.Matrix mtx = new android.graphics.Matrix();
            mtx.setTranslate(s.offX, s.offY);
            gp.transform(mtx);
            out.addPath(gp);
        }
        return out;
    }

    private static final java.util.WeakHashMap<View, Boolean> sPolling = new java.util.WeakHashMap<View, Boolean>();
    private static final java.util.WeakHashMap<View, String> sPollState = new java.util.WeakHashMap<View, String>();
    private static final android.os.Handler sHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private static void installRefreshPoller(final View container, final Object blurDrawable) {
        PollRunner pr;
        synchronized (sRunners) {
            if (sRunners.containsKey(container)) { return; }
            pr = new PollRunner(container, blurDrawable);
            sRunners.put(container, pr);
            sPolling.put(container, Boolean.TRUE);
        }
        pr.schedule(0L);
        ModuleLog.d("GB", "poller installed");
    }

    private static final class PollRunner implements Runnable {
        private final View container;
        private final Object blurDrawable;
        private long intervalMs = POLL_INTERVAL_TICK_MS;
        private int stableCount = 0;
        private volatile boolean stopped = false;
        private volatile boolean visible = true;
        private String lastLogState = null;
        private boolean ranOnce = false;
        private boolean changedLogged = false;

        PollRunner(View container, Object blurDrawable) {
            this.container = container;
            this.blurDrawable = blurDrawable;
        }

        void schedule(long delayMs) {
            if (stopped) return;
            sHandler.postDelayed(this, Math.max(0L, delayMs));
        }

        void onScreenStateChanged() {
            if (sScreenOn) {
                stopped = false;
                stableCount = 0;
                intervalMs = POLL_INTERVAL_TICK_MS;
                logOnce("SCREEN_ON");
                sHandler.removeCallbacks(this);
                schedule(0L);
            } else {
                logOnce("SCREEN_OFF");
                stopped = true;
                sHandler.removeCallbacks(this);
            }
        }

        void kick() {
            stopped = false;
            stableCount = 0;
            intervalMs = POLL_INTERVAL_TICK_MS;
            sHandler.removeCallbacks(this);
            ModuleLog.d("GB", "poll -> KICK (evt-driven)");
            schedule(0L);
        }

        private void logOnce(String st) {
            if ("STABLE".equals(st)) {
                ModuleLog.d("GB", "poll -> STABLE (interval=" + intervalMs + ")");
                return;
            }
            if (st.equals(lastLogState)) return;
            lastLogState = st;
            ModuleLog.d("GB", "poll -> " + st + " (interval=" + intervalMs + ")");
        }

        private boolean isClockContainer() {
            try {
                View v = container.findViewById(ClockIds.HOUR);
                if (v instanceof TextView) return true;
                View root = ViewUtils.descendantJustBelow(container, "AppWidgetHostView");
                if (root != null) {
                    View v2 = root.findViewById(ClockIds.HOUR);
                    if (v2 instanceof TextView) return true;
                }
            } catch (Throwable ignored) {}
            return false;
        }
        @Override public void run() {
            if (stopped) return;
            try {
                if (!ranOnce) {
                    ranOnce = true;
                    ModuleLog.d("GB", "poll -> START (interval=" + intervalMs + ")");
                }
                if (!sScreenOn) {
                    logOnce("SCREEN_OFF");
                    stopped = true;
                    return;
                }
                if (!ViewUtils.isReallyVisible(container)) {
                    visible = false;
                    stableCount++;
                    if (stableCount >= POLL_STABLE_THRESHOLD) {
                        if (intervalMs != POLL_INTERVAL_HIDDEN_MS) {
                            intervalMs = POLL_INTERVAL_HIDDEN_MS;
                            logOnce("HIDDEN");
                        }
                    } else {
                        intervalMs = POLL_INTERVAL_TICK_MS;
                    }
                    sHandler.postDelayed(this, intervalMs);
                    return;
                }
                if (!visible) {
                    visible = true;
                    stableCount = 0;
                    intervalMs = POLL_INTERVAL_TICK_MS;
                    logOnce("VISIBLE");
                }
                boolean changed = pollOnce();
                if (changed) {
                    if (!changedLogged) {
                        changedLogged = true;
                        ModuleLog.d("GB", "poll -> CHANGED (first change detected)");
                    }
                    stableCount = 0;
                    intervalMs = POLL_INTERVAL_TICK_MS;

                    applyClockBrighten(container);
                } else {
                    stableCount++;
                    if (stableCount >= POLL_STABLE_THRESHOLD && intervalMs < POLL_INTERVAL_MAX_MS) {
                        intervalMs = Math.min(POLL_INTERVAL_MAX_MS, intervalMs + POLL_INTERVAL_TICK_MS);
                        stableCount = 0;
                        logOnce("STABLE");
                    }
                }
            } catch (Throwable t) {
                ModuleLog.e("GB", "poll fail", t);
            }
            if (!stopped) sHandler.postDelayed(this, intervalMs);
        }

        private boolean pollOnce() {
            StringBuilder sb = new StringBuilder();
            int[] ids = ClockIds.TEXT_IDS;
            for (int id : ids) {
                View v = container.findViewById(id);
                if (!(v instanceof TextView)) {
                    View root = ViewUtils.descendantJustBelow(container, "AppWidgetHostView");
                    if (root != null) v = root.findViewById(id);
                }
                if (!(v instanceof TextView)) { sb.append("-"); sb.append("|"); continue; }
                CharSequence cs = ((TextView) v).getText();
                sb.append(cs == null ? "" : cs.toString());
                sb.append("@").append((int) localOffsetX(v, container)).append(",").append((int) localOffsetY(v, container));
                sb.append("|");
            }
            String cur = sb.toString();
            String prev = sPollState.get(container);
            if (prev == null || !prev.equals(cur)) {
                sPollState.put(container, cur);
                rebuildSnapshots(container);
                Method inv = Reflect.method(blurDrawable.getClass(), "invalidatePath", 0);
                if (inv != null) { try { inv.setAccessible(true); inv.invoke(blurDrawable); } catch (Throwable ignored) {} }
                container.invalidate();
                return true;
            }
            return false;
        }
    }

}
