package org.jetbrains.kotlin.com.intellij.util.containers;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Android-safe replacement for the IntelliJ {@code Unsafe} shim bundled inside
 * {@code kotlin-compiler-embeddable}.
 *
 * <p><b>This deliberately shadows a vendored class.</b> The original is excluded
 * from the jar by the {@code patchKotlinJar} task in app/build.gradle, so exactly
 * one definition reaches the dex. Keep the two in sync: the public signatures
 * below must stay identical to upstream, because IntelliJ's
 * {@code ConcurrentLongObjectHashMap} and friends call them directly.</p>
 *
 * <h3>Why it exists</h3>
 * <p>The upstream shim resolves ten {@code MethodHandle}s against
 * {@code sun.misc.Unsafe} in its static initialiser and throws {@link Error} if
 * any one is missing. On Android that is fatal:
 * {@code sun.misc.Unsafe#getAndAddInt} sits on the hidden-API blocklist with
 * {@code max-target-r}, so it is unreachable by any app targeting above API 30.
 * This app targets 36, the lookup is denied, the initialiser throws, and every
 * attempt to build a {@code KotlinCoreEnvironment} dies with:</p>
 *
 * <pre>
 *   java.lang.Error: java.lang.NoSuchMethodException: getAndAddInt
 *     at …containers.Unsafe.&lt;clinit&gt;(Unsafe.java:40)
 *     at …util.ConcurrentLongObjectHashMap.&lt;clinit&gt;
 *     at …openapi.progress.impl.CoreProgressManager.&lt;clinit&gt;
 * </pre>
 *
 * <h3>What is different here</h3>
 * <ul>
 *   <li>{@link #getAndAddInt} is derived from a compare-and-swap loop instead of
 *       delegated. That is how the JDK implements it internally, and
 *       {@code compareAndSwapInt} is <em>not</em> blocked on Android.</li>
 *   <li>A handle that cannot be resolved is left {@code null} rather than thrown
 *       from the initialiser. One blocked primitive must not be able to take the
 *       whole compiler down again — it fails when called, if it ever is.</li>
 *   <li>Everything reaches {@code sun.misc.Unsafe} reflectively. The class exists
 *       at runtime but not in {@code android.jar}, so it cannot be named at
 *       compile time.</li>
 * </ul>
 */
public final class Unsafe {

    /** The platform singleton, or {@code null} if it could not be obtained. */
    private static final Object THE_UNSAFE;

    // Bound to THE_UNSAFE once, so the hot paths inside IntelliJ's concurrent
    // maps pay a MethodHandle invocation rather than a reflective one.
    private static final MethodHandle COMPARE_AND_SWAP_INT;
    private static final MethodHandle COMPARE_AND_SWAP_LONG;
    private static final MethodHandle COMPARE_AND_SWAP_OBJECT;
    private static final MethodHandle GET_INT_VOLATILE;
    private static final MethodHandle GET_OBJECT_VOLATILE;
    private static final MethodHandle PUT_OBJECT_VOLATILE;
    private static final MethodHandle OBJECT_FIELD_OFFSET;
    private static final MethodHandle ARRAY_INDEX_SCALE;
    private static final MethodHandle ARRAY_BASE_OFFSET;
    private static final MethodHandle COPY_MEMORY;

    static {
        Object unsafe = null;
        Class<?> type = null;
        try {
            type = Class.forName("sun.misc.Unsafe");
            // getUnsafe() checks the caller's class loader and would refuse an app
            // class, so the singleton is read straight out of the field. The class
            // itself is reachable on Android — only some of its methods are not.
            Field field = type.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            unsafe = field.get(null);
        } catch (Throwable ignored) {
            // Leaves every handle null; each accessor then reports it plainly.
        }
        THE_UNSAFE = unsafe;

        COMPARE_AND_SWAP_INT = bind(type, unsafe, "compareAndSwapInt",
                Object.class, long.class, int.class, int.class);
        COMPARE_AND_SWAP_LONG = bind(type, unsafe, "compareAndSwapLong",
                Object.class, long.class, long.class, long.class);
        COMPARE_AND_SWAP_OBJECT = bind(type, unsafe, "compareAndSwapObject",
                Object.class, long.class, Object.class, Object.class);
        GET_INT_VOLATILE = bind(type, unsafe, "getIntVolatile", Object.class, long.class);
        GET_OBJECT_VOLATILE = bind(type, unsafe, "getObjectVolatile", Object.class, long.class);
        PUT_OBJECT_VOLATILE = bind(type, unsafe, "putObjectVolatile",
                Object.class, long.class, Object.class);
        OBJECT_FIELD_OFFSET = bind(type, unsafe, "objectFieldOffset", Field.class);
        ARRAY_INDEX_SCALE = bind(type, unsafe, "arrayIndexScale", Class.class);
        ARRAY_BASE_OFFSET = bind(type, unsafe, "arrayBaseOffset", Class.class);
        COPY_MEMORY = bind(type, unsafe, "copyMemory",
                Object.class, long.class, Object.class, long.class, long.class);
    }

    private Unsafe() {
    }

    /**
     * Resolves one method and binds it to the singleton.
     *
     * @return the bound handle, or {@code null} when the method is absent or the
     *         platform refuses it — never throws, which is the entire point
     */
    private static MethodHandle bind(Class<?> type, Object receiver,
                                     String name, Class<?>... params) {
        if (type == null || receiver == null) return null;
        try {
            Method m = type.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return MethodHandles.lookup().unreflect(m).bindTo(receiver);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static MethodHandle require(MethodHandle handle, String name) {
        if (handle == null) {
            throw new UnsupportedOperationException(
                    "sun.misc.Unsafe." + name + " is unavailable on this Android runtime"
                            + (THE_UNSAFE == null ? " (Unsafe itself could not be obtained)" : ""));
        }
        return handle;
    }

    /** Rethrows whatever a handle threw without wrapping checked types. */
    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException) return (RuntimeException) t;
        if (t instanceof Error) throw (Error) t;
        return new RuntimeException(t);
    }

    // ── Delegated: none of these are blocked on Android ──────────────────────

    public static boolean compareAndSwapInt(Object o, long offset, int expected, int value) {
        try {
            return (boolean) require(COMPARE_AND_SWAP_INT, "compareAndSwapInt")
                    .invoke(o, offset, expected, value);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static boolean compareAndSwapLong(Object o, long offset, long expected, long value) {
        try {
            return (boolean) require(COMPARE_AND_SWAP_LONG, "compareAndSwapLong")
                    .invoke(o, offset, expected, value);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static boolean compareAndSwapObject(Object o, long offset, Object expected, Object value) {
        try {
            return (boolean) require(COMPARE_AND_SWAP_OBJECT, "compareAndSwapObject")
                    .invoke(o, offset, expected, value);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static Object getObjectVolatile(Object o, long offset) {
        try {
            return require(GET_OBJECT_VOLATILE, "getObjectVolatile").invoke(o, offset);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void putObjectVolatile(Object o, long offset, Object value) {
        try {
            require(PUT_OBJECT_VOLATILE, "putObjectVolatile").invoke(o, offset, value);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static long objectFieldOffset(Field field) {
        try {
            return (long) require(OBJECT_FIELD_OFFSET, "objectFieldOffset").invoke(field);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int arrayIndexScale(Class<?> arrayClass) {
        try {
            return (int) require(ARRAY_INDEX_SCALE, "arrayIndexScale").invoke(arrayClass);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int arrayBaseOffset(Class<?> arrayClass) {
        try {
            return (int) require(ARRAY_BASE_OFFSET, "arrayBaseOffset").invoke(arrayClass);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void copyMemory(Object srcBase, long srcOffset,
                                  Object destBase, long destOffset, long bytes) {
        try {
            require(COPY_MEMORY, "copyMemory")
                    .invoke(srcBase, srcOffset, destBase, destOffset, bytes);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ── Derived, because the direct call is blocked ──────────────────────────

    /**
     * Atomically adds {@code delta} and returns the <em>previous</em> value.
     *
     * <p>A CAS retry loop rather than a call to
     * {@code sun.misc.Unsafe#getAndAddInt}, which Android denies to any app
     * targeting above API 30. This is the same loop the JDK uses internally, so
     * the memory semantics are unchanged.</p>
     */
    public static int getAndAddInt(Object o, long offset, int delta) {
        MethodHandle get = require(GET_INT_VOLATILE, "getIntVolatile");
        MethodHandle cas = require(COMPARE_AND_SWAP_INT, "compareAndSwapInt");
        try {
            int current;
            do {
                current = (int) get.invoke(o, offset);
            } while (!(boolean) cas.invoke(o, offset, current, current + delta));
            return current;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }
}
