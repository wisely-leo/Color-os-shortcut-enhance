package com.shortcutblur;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Reflect {

    private static final int MAX_DEPTH = 16;

    private static final int MAX_CACHE = 512;

    private static final Map<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Field> FIELD_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Constructor<?>> CTOR_CACHE = new ConcurrentHashMap<>();

    private Reflect() {}

    private static void capCache(Map<?, ?> cache) {
        if (cache.size() > MAX_CACHE) {
            try { cache.clear(); } catch (Throwable ignored) {}
        }
    }

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
                        capCache(METHOD_CACHE);
                        return m;
                    }
                }
            } catch (Throwable ignored) { }
            k = k.getSuperclass();
        }
        return null;
    }

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
            capCache(METHOD_CACHE);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

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
                capCache(FIELD_CACHE);
                return f;
            } catch (NoSuchFieldException nsf) {
                k = k.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    public static Object newInstance(Class<?> c) {
        if (c == null) return null;
        String key = c.getName();
        Constructor<?> ctor = CTOR_CACHE.get(key);
        try {
            if (ctor == null) {
                ctor = c.getDeclaredConstructor();
                ctor.setAccessible(true);
                CTOR_CACHE.put(key, ctor);
                capCache(CTOR_CACHE);
            }
            return ctor.newInstance();
        } catch (Throwable t) {
            return null;
        }
    }

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

    public static Object callStatic(Class<?> c, String name, Class<?>[] types, Object... args) {
        Method m = method(c, name, types);
        if (m == null) return null;
        try {
            return m.invoke(null, args);
        } catch (Throwable t) {
            return null;
        }
    }

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
