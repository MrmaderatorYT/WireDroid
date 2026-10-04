package java.lang.invoke;

/**
 * Compile-time stub for the bootstrap method behind every lambda.
 *
 * <p>{@code android.jar} ships the rest of {@code java.lang.invoke} — CallSite,
 * MethodType, MethodHandles.Lookup, even LambdaConversionException — but not
 * this class, because on Android lambdas never reach it: D8 desugars the
 * {@code invokedynamic} into ordinary classes while dexing.</p>
 *
 * <p>The compiler does not know that. ECJ emits an {@code invokedynamic} whose
 * bootstrap handle names this type, and then fails to resolve it:
 * <em>"The type java.lang.invoke.LambdaMetafactory cannot be resolved. It is
 * indirectly referenced from required .class files"</em> — reported against
 * line 1 of the file, which makes it look like a problem with the package
 * declaration. One lambda anywhere in a file failed the whole file, and every
 * class that referenced it failed after that.</p>
 *
 * <p>So this exists only to be resolved. The bodies are never called: by the
 * time the code runs, D8 has rewritten the call sites. It mirrors the stub
 * already kept here for {@link StringConcatFactory}, which fills the same kind
 * of hole for string concatenation.</p>
 */
public final class LambdaMetafactory {

    /** Flag for {@link #altMetafactory}: the lambda must be Serializable. */
    public static final int FLAG_SERIALIZABLE = 1 << 0;
    /** Flag for {@link #altMetafactory}: extra marker interfaces follow. */
    public static final int FLAG_MARKERS = 1 << 1;
    /** Flag for {@link #altMetafactory}: extra bridge signatures follow. */
    public static final int FLAG_BRIDGES = 1 << 2;

    private LambdaMetafactory() {}

    public static CallSite metafactory(MethodHandles.Lookup caller,
                                       String interfaceMethodName,
                                       MethodType factoryType,
                                       MethodType interfaceMethodType,
                                       MethodHandle implementation,
                                       MethodType dynamicMethodType)
            throws LambdaConversionException {
        return null;
    }

    public static CallSite altMetafactory(MethodHandles.Lookup caller,
                                          String interfaceMethodName,
                                          MethodType factoryType,
                                          Object... args)
            throws LambdaConversionException {
        return null;
    }
}
