package com.shortcutblur;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

/**
 * View / Context 遍历工具：统一“向上找祖先”与“向下递归查找”两类模式。
 *
 * 背景：findHost、findWidgetProviderRoot、findDragLayer、findLauncher、
 * findViewByIdRecursive 原本在多个类里各写一份，guard 上限也从 30 到 50 不统一。
 * 此处收敛为单一实现，统一 MAX_DEPTH。
 */
public final class ViewUtils {

    /** 沿链条向上/向下遍历的上限，防死循环。 */
    public static final int MAX_DEPTH = 50;

    private static final Rect VISIBLE_RECT = new Rect();

    private ViewUtils() {}

    // ---------------- 向上找祖先 ----------------

    /**
     * 从 v 自身开始向上找第一个类名包含 namePart 的 View（含 v）。
     *
     * 用于 findHost：v 本身可能就是 AppWidgetHostView。
     */
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

    /**
     * 向上找 namePart 的第一个匹配，但返回它的“前一个”节点（即匹配节点的子节点）。
     *
     * 用于 findWidgetProviderRoot：从 container 往上是 AppWidgetHostView，
     * 但需要的是它内部的那层 widget root。链走到头未命中则返回最后一个节点。
     */
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

    /**
     * 向上找第一个 ViewGroup 且类名包含 namePart（含 v 自身）。
     *
     * 用于 findDragLayer。
     */
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

    /**
     * 沿 Context 链向上找完整类名等于 className 的 Context。
     *
     * 用于 findLauncher。
     */
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

    // ---------------- 向下递归 ----------------

    /**
     * 深度优先查找 id 匹配的 View。
     *
     * 用于 findViewByIdRecursive（AppWidgetHostView 内部的 RemoteViews 树，
     * findViewById 不一定能直接命中，需自行递归）。
     */
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

    // ---------------- 可见性 ----------------

    /**
     * 是否“真的可见”：已 attach、isShown、尺寸非零、全局可见区域足够大。
     *
     * 用于 isReallyVisible。内部 Rect 复用，勿在多线程共享结果。
     */
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
