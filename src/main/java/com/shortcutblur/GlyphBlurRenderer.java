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

    private static final int ID_HOUR    = 0x7f0a02cf;
    private static final int ID_COLON   = 0x7f0a02c8;
    private static final int ID_MINUTES = 0x7f0a02d3;
    private static final int ID_DATE    = 0x7f0a02ca;
    private static final int ID_WEATHER = 0x7f0a02da;

    private static final int ID_WEATHER_IMG1 = 0x7f0a02d9;
    private static final int ID_WEATHER_IMG2 = 0x7f0a0276;

    private static final float ICON_ALPHA = 0.30f;

    private static final class GlyphSnapshot {
        final Path localPath;
        final float offX, offY;
        GlyphSnapshot(Path localPath, float offX, float offY) {
            this.localPath = localPath; this.offX = offX; this.offY = offY;
        }
    }

    private static final java.util.WeakHashMap<View, GlyphSnapshot[]> sSnapsMap = new java.util.WeakHashMap<View, GlyphSnapshot[]>();

    public static void attachGlyphBlur(final View container, final ClassLoader cl) {
        if (container == null) return;
        try { tryAttachOnce(container, cl, 0); }
        catch (Throwable t) { Logger.log("GB apply FAIL: " + t); }
    }

    private static void tryAttachOnce(final View container, final ClassLoader cl, final int attempt) {
        try {
            android.graphics.drawable.Drawable bg = container.getBackground();
            if (bg == null) { retry(container, cl, attempt, "bg null"); return; }
            String bgName = bg.getClass().getName();
            Object blurDrawable = invokeNoArg(bg, "getBlurDrawable");
            if (blurDrawable == null) { retry(container, cl, attempt, "bg=" + bgName + " no getBlurDrawable"); return; }
            Logger.log("GB bg=" + bgName + " blur=" + blurDrawable.getClass().getName());

            rebuildSnapshots(container);
            GlyphSnapshot[] snaps = sSnapsMap.get(container);
            if (snaps == null || snaps.length == 0) { Logger.log("GB snapshot empty"); return; }
            Logger.log("GB snapshot n=" + snaps.length);

            Class<?> iface = Class.forName("com.oplus.posteffect.path.BlurDrawablePathProvider", false, cl);
            Object provider = Proxy.newProxyInstance(cl, new Class<?>[]{ iface }, new InvocationHandler() {
                @Override public Object invoke(Object proxy, Method method, Object[] args) {
                    try {
                        if ("getPath".equals(method.getName()) && args != null && args.length >= 2) {
                            Path out = (Path) args[1];
                            if (out != null) out.set(snapshotsToPath(sSnapsMap.get(container)));
                        }
                    } catch (Throwable t) { Logger.log("GB proxy getPath FAIL: " + t); }
                    return null;
                }
            });

            Method setPP = findMethod(blurDrawable.getClass(), "setPathProvider", 1);
            if (setPP == null) { Logger.log("GB setPathProvider not found"); return; }
            setPP.setAccessible(true);
            setPP.invoke(blurDrawable, provider);
            Logger.log("GB setPathProvider OK");

            installRefreshPoller(container, blurDrawable);

            Method inv = findMethod(blurDrawable.getClass(), "invalidatePath", 0);
            if (inv != null) { inv.setAccessible(true); inv.invoke(blurDrawable); Logger.log("GB invalidatePath OK"); }
            container.invalidate();
            Logger.log("GB DONE container");
        } catch (Throwable t) {
            Logger.log("GB tryAttachOnce FAIL(attempt=" + attempt + "): " + t);
        }
    }

    private static void retry(final View container, final ClassLoader cl, final int attempt, final String why) {
        if (attempt >= MAX_RETRY) { Logger.log("GB give up: " + why); return; }
        if (attempt == 0 || attempt == MAX_RETRY - 1) Logger.log("GB retry(" + attempt + "): " + why);
        container.postDelayed(new Runnable() {
            @Override public void run() { tryAttachOnce(container, cl, attempt + 1); }
        }, RETRY_DELAY_MS);
    }

    static void onWidgetUpdated(final View container) {
        if (container == null) return;

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
            if (bg != null) blur = invokeNoArg(bg, "getBlurDrawable");
            rebuildSnapshots(container);
            if (blur != null) {
                Method inv = findMethod(blur.getClass(), "invalidatePath", 0);
                if (inv != null) { inv.setAccessible(true); inv.invoke(blur); }
            }
            container.invalidate();
        } catch (Throwable t) { Logger.log("GB rebuildSnapshotsNow FAIL: " + t); }
    }

    private static void appendWeatherIconSnapshots(View container, View base, java.util.ArrayList<GlyphSnapshot> list) {
        int[] iconIds = { ID_WEATHER_IMG1, ID_WEATHER_IMG2 };
        for (int id : iconIds) {
            try {
                View v = container.findViewById(id);
                if (!(v instanceof android.widget.ImageView)) {
                    View root = findWidgetProviderRoot(container);
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

                int bw = Math.min(bmp.getWidth(), 96);
                int bh = Math.max(1, bmp.getHeight() * bw / bmp.getWidth());
                android.graphics.Bitmap small = android.graphics.Bitmap.createScaledBitmap(bmp, bw, bh, true);
                int[] px = new int[bw * bh];
                small.getPixels(px, 0, bw, 0, 0, bw, bh);
                android.graphics.Region region = new android.graphics.Region();
                int alphaThreshold = 40;
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
                int[] locV = new int[2];
                int[] locB = new int[2];
                v.getLocationInWindow(locV);
                base.getLocationInWindow(locB);
                float dx = locV[0] - locB[0];
                float dy = locV[1] - locB[1];
                list.add(new GlyphSnapshot(iconPath, dx, dy));
            } catch (Throwable t) { Logger.log("GB iconPath FAIL id=0x" + Integer.toHexString(id) + ": " + t); }
        }
    }

    static void applyIconAlpha(View container) {
        try {
            int[] iconIds = { ID_WEATHER_IMG1, ID_WEATHER_IMG2 };
            for (int id : iconIds) {
                View v = container.findViewById(id);
                if (v == null) {
                    View root = findWidgetProviderRoot(container);
                    if (root != null) v = root.findViewById(id);
                }
                if (v != null && Math.abs(v.getAlpha() - ICON_ALPHA) > 0.01f) {
                    v.setAlpha(ICON_ALPHA);
                    Logger.log("GB iconAlpha id=0x" + Integer.toHexString(id)
                        + " cls=" + v.getClass().getSimpleName() + " alpha=" + ICON_ALPHA);
                }
            }
        } catch (Throwable t) { Logger.log("GB applyIconAlpha FAIL: " + t); }
    }

    static void rebuildSnapshots(View container) {
        try {

            final View base = container;
            int[] ids = { ID_HOUR, ID_COLON, ID_MINUTES, ID_DATE, ID_WEATHER };
            java.util.ArrayList<GlyphSnapshot> list = new java.util.ArrayList<GlyphSnapshot>();
            for (int id : ids) {

                View v = container.findViewById(id);
                if (!(v instanceof TextView)) {
                    View root = findWidgetProviderRoot(container);
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

                int[] locV = new int[2];
                int[] locB = new int[2];
                v.getLocationInWindow(locV);
                base.getLocationInWindow(locB);
                float dx = locV[0] - locB[0];
                float dy = locV[1] - locB[1];
                Path localPath = new Path();
                tp.getTextPath(text, 0, text.length(), startX, baseline, localPath);
                list.add(new GlyphSnapshot(localPath, dx, dy));
            }

            appendWeatherIconSnapshots(container, base, list);
            sSnapsMap.put(container, list.isEmpty() ? null : list.toArray(new GlyphSnapshot[0]));
            applyIconAlpha(container);
        } catch (Throwable t) {
            Logger.log("GB rebuildSnapshots FAIL: " + t);
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
        synchronized (sPolling) {
            if (Boolean.TRUE.equals(sPolling.get(container))) { Logger.log("GB poller already"); return; }
            sPolling.put(container, Boolean.TRUE);
        }
        final Runnable r = new Runnable() {
            @Override public void run() {
                try {
                    StringBuilder sb = new StringBuilder();
                    int[] ids = { ID_HOUR, ID_COLON, ID_MINUTES, ID_DATE, ID_WEATHER };
                    for (int id : ids) {
                        View v = container.findViewById(id);
                        if (!(v instanceof TextView)) {
                            View root = findWidgetProviderRoot(container);
                            if (root != null) v = root.findViewById(id);
                        }
                        if (!(v instanceof TextView)) { sb.append("-"); sb.append("|"); continue; }
                        CharSequence cs = ((TextView) v).getText();
                        sb.append(cs == null ? "" : cs.toString());

                        int[] loc = new int[2];
                        v.getLocationInWindow(loc);
                        sb.append("@").append(loc[0]).append(",").append(loc[1]);
                        sb.append("|");
                    }
                    String cur = sb.toString();
                    String prev = sPollState.get(container);
                    if (prev == null || !prev.equals(cur)) {
                        sPollState.put(container, cur);
                        rebuildSnapshots(container);
                        Method inv = findMethod(blurDrawable.getClass(), "invalidatePath", 0);
                        if (inv != null) { inv.setAccessible(true); inv.invoke(blurDrawable); }
                        container.invalidate();
                    }
                } catch (Throwable t) { Logger.log("GB poll fail: " + t); }
                sHandler.postDelayed(this, 500L);
            }
        };
        sHandler.postDelayed(r, 500L);
        Logger.log("GB poller installed");
    }

    private static View findWidgetProviderRoot(View container) {
        View cur = container;
        View prev = container;
        int g = 0;
        while (cur != null && g++ < 50) {
            if (cur.getClass().getName().contains("AppWidgetHostView")) return prev;
            prev = cur;
            cur = (cur.getParent() instanceof View) ? (View) cur.getParent() : null;
        }
        return prev;
    }

    private static View findHost(View v) {
        View cur = v;
        int g = 0;
        while (cur != null && g++ < 50) {
            if (cur.getClass().getName().contains("AppWidgetHostView")) return cur;
            cur = (cur.getParent() instanceof View) ? (View) cur.getParent() : null;
        }
        return null;
    }

    private static Object invokeNoArg(Object o, String name) {
        if (o == null) return null;
        try {
            Method m = findMethod(o.getClass(), name, 0);
            if (m == null) return null;
            m.setAccessible(true);
            return m.invoke(o);
        } catch (Throwable t) { return null; }
    }

    private static Method findMethod(Class<?> c, String name, int params) {
        Class<?> k = c;
        int g = 0;
        while (k != null && g++ < 12) {
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
}
