package java.lang.invoke;

public final class StringConcatFactory {
    private StringConcatFactory() {}

    public static CallSite makeConcat(MethodHandles.Lookup lookup, String name, MethodType concatType) throws StringConcatException {
        return null;
    }

    public static CallSite makeConcatWithConstants(MethodHandles.Lookup lookup, String name, MethodType concatType, String recipe, Object... constants) throws StringConcatException {
        return null;
    }
}
