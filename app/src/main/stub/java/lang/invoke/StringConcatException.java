package java.lang.invoke;

public class StringConcatException extends Exception {
    public StringConcatException(String message) {
        super(message);
    }
    public StringConcatException(String message, Throwable cause) {
        super(message, cause);
    }
}
