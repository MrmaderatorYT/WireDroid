package com.ccs.wiredroid.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Which icon a source file gets.
 *
 * <p>Nearly every file matches more than one rule — a test class is a class, an
 * enum is a class, an exception may be abstract — so the order the rules are
 * asked in <em>is</em> the behaviour. These pin that order.</p>
 */
public class ClassKindTest {

    @Test
    public void javaDeclarations() {
        assertEquals(ClassKind.INTERFACE, ClassKind.classify("Feeder.java",
                "public interface Feeder { void feed(); }"));
        assertEquals(ClassKind.ABSTRACT, ClassKind.classify("Animal.java",
                "public abstract class Animal { }"));
        assertEquals(ClassKind.FINAL, ClassKind.classify("Zoo.java",
                "public final class Zoo { }"));
        assertEquals(ClassKind.ENUM, ClassKind.classify("Diet.java",
                "public enum Diet { HERBIVORE, CARNIVORE }"));
        assertEquals(ClassKind.RECORD, ClassKind.classify("Point.java",
                "public record Point(int x, int y) { }"));
        assertEquals(ClassKind.ANNOTATION, ClassKind.classify("Marker.java",
                "public @interface Marker { }"));
    }

    @Test
    public void kotlinDeclarations() {
        assertEquals(ClassKind.INTERFACE, ClassKind.classify("Feeder.kt",
                "interface Feeder { fun feed() }"));
        assertEquals(ClassKind.INTERFACE, ClassKind.classify("Op.kt",
                "fun interface Op { fun run() }"));
        assertEquals(ClassKind.ABSTRACT, ClassKind.classify("Animal.kt",
                "abstract class Animal"));
        assertEquals(ClassKind.ENUM, ClassKind.classify("Diet.kt",
                "enum class Diet { HERBIVORE }"));
        assertEquals(ClassKind.ANNOTATION, ClassKind.classify("Marker.kt",
                "annotation class Marker"));
        // Kotlin has no record; a data class is the same idea and gets the icon.
        assertEquals(ClassKind.RECORD, ClassKind.classify("Point.kt",
                "data class Point(val x: Int, val y: Int)"));
        assertEquals(ClassKind.FINAL, ClassKind.classify("Result.kt",
                "sealed class Result"));
    }

    @Test
    public void plainClassesGetTheClassIconNotTheFinalOne() {
        assertEquals(ClassKind.CLASS, ClassKind.classify("Zoo.java", "public class Zoo { }"));
        // Every Kotlin class is final unless opened. Marking them all FINAL
        // would paint a whole tree one shade and tell the reader nothing, so
        // they read as plain classes.
        assertEquals(ClassKind.CLASS, ClassKind.classify("Zoo.kt", "class Zoo"));
        assertEquals(ClassKind.CLASS, ClassKind.classify("Zoo.kt", "open class Zoo"));
        assertEquals(ClassKind.CLASS, ClassKind.classify("Registry.kt", "object Registry"));
    }

    @Test
    public void testsWinOverBeingAPlainClass() {
        assertEquals(ClassKind.TEST, ClassKind.classify("ZooTest.java",
                "public class ZooTest { }"));
        assertEquals(ClassKind.TEST, ClassKind.classify("Checks.java",
                "import org.junit.Test;\npublic class Checks { @Test public void a() {} }"));
        assertEquals(ClassKind.TEST, ClassKind.classify("ZooSpec.kt", "class ZooSpec"));
    }

    @Test
    public void throwablesAreFoundByNameOrBySupertype() {
        assertEquals(ClassKind.EXCEPTION, ClassKind.classify("FeedException.java",
                "public class FeedException { }"));
        assertEquals(ClassKind.EXCEPTION, ClassKind.classify("Oops.java",
                "public class Oops extends IllegalStateException { }"));
        assertEquals(ClassKind.EXCEPTION, ClassKind.classify("Oops.kt",
                "class Oops : IllegalStateException(\"no\")"));
    }

    @Test
    public void moreSpecificDeclarationsWinOverClassLikeOnes() {
        // An annotation and an enum are both spelled with 'class' in Kotlin;
        // asking for 'class' first would swallow them.
        assertEquals(ClassKind.ANNOTATION, ClassKind.classify("Marker.kt",
                "annotation class Marker"));
        assertEquals(ClassKind.ENUM, ClassKind.classify("Diet.kt",
                "enum class Diet"));
        // An abstract exception is an exception: that is the fact worth showing.
        assertEquals(ClassKind.EXCEPTION, ClassKind.classify("BaseException.java",
                "public abstract class BaseException extends Exception { }"));
    }

    @Test
    public void filesDeclaringNoTypeGetNothing() {
        assertNull(ClassKind.classify("package-info.java", "package com.example;"));
        assertNull(ClassKind.classify("Main.kt", "fun main() { println(1) }"));
    }

    @Test
    public void wordsInsideNamesDoNotCount() {
        // 'interfaces' and 'recorded' contain the keywords but declare nothing,
        // so this stays an ordinary class rather than becoming a record.
        assertEquals(ClassKind.CLASS, ClassKind.classify("Util.java",
                "class Util { String recorded; Object interfaces; }"));
    }
}
