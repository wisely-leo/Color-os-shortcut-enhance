package com.shortcutblur;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 反射工具：统一查找 / 调用入口，内置缓存。
 *
 * 背景：findMethod/findField/invoke 原本在 BlurEnhanceModule、GlyphBlurRenderer、
 * WidgetBlurAttacher 各自实现一份，且后两者无缓存，在 rebuild 热路径上每次都会
 * 全遍历 getDeclaredMethods()。此处统一为带缓存的单一实现。
 */
public final class Reflect {

    /** 沿继承链查找的上限，防空指针死循环。 */
    private static final int MAX_DEPTH = 16;

    private static final Map<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Field> FIELD_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Constructor<?>> CTOR_CACHE = new ConcurrentHashMap<>();

    private Reflect() {}

    // ---------------- 方法 ----------------

    /** 按参数个数查找（不限定可见性，沿继承链），带缓存。 */
    public static Method method(Class<?> c, String name, int paramCount) {
        if (c == null || name == null) return null;
        String key = c.getName() + "#n:" + name + "/" + paramCount;
        Method hit = METHOD_CACHE.get(key);
        if (hit != null) return hit;
        Class<?> k = c;
        int depth = 0;
        while (k != null && depth++ < MAX_DEPTH) {
            try {
                for (Method m : k.getDeclaredMethods()) {
                    if (m.getName().equals(name) && m.getParameterTypes().length == paramCount) {
                        m.setAccessible(true);
                        METHOD_CACHE.put(key, m);
                        return m;
                    }
                }
            } catch (Throwable ignored) { }
            k = k.getSuperclass();
        }
        return null;
    }

    /** 按参数类型查找（仅 public，沿继承链），带缓存。 */
    public static Method method(Class<?> c, String name, Class<?>... paramTypes) {
        if (c == null || name == null) return null;
        StringBuilder sb = new StringBuilder(c.getName()).append('#').append(name).append('(');
        if (paramTypes != null) {
            for (Class<?> p : paramTypes) sb.append(p == null ? "?" : p.getName()).append(',');
        }
        String key = sb.append(')').toString();
        Method hit = METHOD_CACHE.get(key);
        if (hit != null) return hit;
        try {
            Method m = c.getMethod(name, paramTypes);
            m.setAccessible(true);
            METHOD_CACHE.put(key, m);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------- 字段 ----------------

    /** 沿继承链查找字段（含私有），带缓存。 */
    public static Field field(Class<?> c, String name) {
        if (c == null || name == null) return null;
        String key = c.getName() + "#" + name;
        Field hit = FIELD_CACHE.get(key);
        if (hit != null) return hit;
        Class<?> k = c;
        while (k != null && k != Object.class) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                FIELD_CACHE.put(key, f);
                return f;
            } catch (NoSuchFieldException nsf) {
                k = k.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    // ---------------- 构造 ----------------

    /** 缓存无参构造器并创建实例。 */
    public static Object newInstance(Class<?> c) {
        if (c == null) return null;
        String key = c.getName();
        Constructor<?> ctor = CTOR_CACHE.get(key);
        try {
            if (ctor == null) {
                ctor = c.getDeclaredConstructor();
                ctor.setAccessible(true);
                CTOR_CACHE.put(key, ctor);
            }
            return ctor.newInstance();
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------- 调用 ----------------

    /** 调用无参方法（静默），失败返回 null。 */
    public static Object call(Object target, String name) {
        if (target == null) return null;
        Method m = method(target.getClass(), name, 0);
        if (m == null) return null;
        try {
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 按参数个数调用（静默），失败返回 null。 */
    public static Object call(Object target, String name, int paramCount, Object... args) {
        if (target == null) return null;
        Method m = method(target.getClass(), name, paramCount);
        if (m == null) return null;
        try {
            return m.invoke(target, args);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 按参数类型调用（静默），失败返回 null。 */
    public static Object callStatic(Class<?> c, String name, Class<?>[] types, Object... args) {
        Method m = method(c, name, types);
        if (m == null) return null;
        try {
            return m.invoke(null, args);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------- 字段读写 ----------------

    /** 读字段（先 static 再 instance，静默），失败返回 null。 */
    public static Object readField(Object target, String name) {
        if (target == null) return null;
        Field f = field(target.getClass(), name);
        if (f == null) return null;
        try {
            return f.get(target);
        } catch (Throwable t) {
            try {
                return f.get(null);
            } catch (Throwable t2) {
                return null;
            }
        }
    }
}
