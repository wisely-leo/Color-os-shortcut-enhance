package com.shortcutblur;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

public final class ViewUtils {

    public static final int MAX_DEPTH = 50;

    private static final Rect VISIBLE_RECT = new Rect();

    private ViewUtils() {}

    public static View ancestorOfType(View v, String namePart) {
        View cur = v;
        int depth = 0;
        while (cur != null && depth++ < MAX_DEPTH) {
            try {
                if (cur.getClass().getName().contains(namePart)) return cur;
            } catch (Throwable ignored) { }
            ViewParent p = cur.getParent();
            cur = (p instanceof View) ? (View) p : null;
        }
        return null;
    }

    public static View descendantJustBelow(View v, String namePart) {
        View cur = v;
        View prev = v;
        int depth = 0;
        while (cur != null && depth++ < MAX_DEPTH) {
            try {
                if (cur.getClass().getName().contains(namePart)) return prev;
            } catch (Throwable ignored) { }
            prev = cur;
            ViewParent p = cur.getParent();
            cur = (p instanceof View) ? (View) p : null;
        }
        return prev;
    }

    public static ViewGroup ancestorGroupOfType(View v, String namePart) {
        View cur = v;
        int depth = 0;
        while (cur != null && depth++ < MAX_DEPTH) {
            try {
                if (cur instanceof ViewGroup && cur.getClass().getName().contains(namePart)) {
                    return (ViewGroup) cur;
                }
            } catch (Throwable ignored) { }
            ViewParent p = cur.getParent();
            cur = (p instanceof View) ? (View) p : null;
        }
        return null;
    }

    public static Context contextOfType(Context ctx, String className) {
        Context c = ctx;
        int depth = 0;
        while (c != null && depth++ < MAX_DEPTH) {
            try {
                if (c.getClass().getName().equals(className)) return c;
            } catch (Throwable ignored) { }
            if (c instanceof ContextWrapper) {
                c = ((ContextWrapper) c).getBaseContext();
            } else {
                break;
            }
        }
        return null;
    }

    public static View findByViewId(View root, int id) {
        if (root == null) return null;
        if (root.getId() == id) return root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            int n = g.getChildCount();
            for (int i = 0; i < n; i++) {
                View r = findByViewId(g.getChildAt(i), id);
                if (r != null) return r;
            }
        }
        return null;
    }

    public static boolean isReallyVisible(View v) {
        if (v == null) return false;
        try {
            if (!v.isAttachedToWindow()) return false;
            if (!v.isShown()) return false;
            if (v.getWidth() <= 0 || v.getHeight() <= 0) return false;
            Rect r = VISIBLE_RECT;
            r.setEmpty();
            if (!v.getGlobalVisibleRect(r)) return false;
            if (r.width() < 4 || r.height() < 4) return false;
        } catch (Throwable t) {
            return true;
        }
        return true;
    }
}
