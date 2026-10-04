package com.ccs.wiredroid.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CodeGeneratorTest {

    @Test
    public void noArgConstructorGeneratedCorrectly() {
        String result = RefactorController.buildConstructor("User", Collections.emptyList());
        assertTrue(result.contains("public User() {"));
        assertTrue(result.contains("}"));
        assertFalse(result.contains("this."));
    }

    @Test
    public void parameterizedConstructorGeneratedCorrectly() {
        List<RefactorController.FieldInfo> fields = new ArrayList<>();
        fields.add(new RefactorController.FieldInfo("String", "name"));
        fields.add(new RefactorController.FieldInfo("int", "age"));

        String result = RefactorController.buildConstructor("User", fields);
        assertTrue(result.contains("public User(String name, int age) {"));
        assertTrue(result.contains("this.name = name;"));
        assertTrue(result.contains("this.age = age;"));
    }

    @Test
    public void toStringEmptyFields() {
        String result = RefactorController.buildToString("EmptyClass", Collections.emptyList());
        assertTrue(result.contains("public String toString()"));
        assertTrue(result.contains("return \"EmptyClass{}\";"));
    }

    @Test
    public void toStringQuotesStringsOnlyAndFormatsArrays() {
        List<RefactorController.FieldInfo> fields = new ArrayList<>();
        fields.add(new RefactorController.FieldInfo("String", "name"));
        fields.add(new RefactorController.FieldInfo("int", "age"));
        fields.add(new RefactorController.FieldInfo("boolean", "active"));
        fields.add(new RefactorController.FieldInfo("int[]", "scores"));
        fields.add(new RefactorController.FieldInfo("String[][]", "matrix"));

        String result = RefactorController.buildToString("Player", fields);
        assertTrue("String should have single quotes", result.contains("name='\" + name + '\\''"));
        assertTrue("int should not have single quotes", result.contains("age=\" + age +"));
        assertTrue("boolean should not have single quotes", result.contains("active=\" + active +"));
        assertTrue("1D array should use Arrays.toString", result.contains("scores=\" + java.util.Arrays.toString(scores)"));
        assertTrue("2D array should use Arrays.deepToString", result.contains("matrix=\" + java.util.Arrays.deepToString(matrix)"));
    }

    @Test
    public void equalsAndHashCodeEmptyFields() {
        String result = RefactorController.buildEqualsAndHashCode("EmptyClass", Collections.emptyList());
        assertTrue(result.contains("public boolean equals(Object o)"));
        assertTrue(result.contains("return true;"));
        assertTrue(result.contains("public int hashCode()"));
        assertTrue(result.contains("return 0;"));
    }

    @Test
    public void equalsAndHashCodePrimitivesAndArrays() {
        List<RefactorController.FieldInfo> fields = new ArrayList<>();
        fields.add(new RefactorController.FieldInfo("int", "id"));
        fields.add(new RefactorController.FieldInfo("double", "score"));
        fields.add(new RefactorController.FieldInfo("String", "email"));
        fields.add(new RefactorController.FieldInfo("byte[]", "data"));
        fields.add(new RefactorController.FieldInfo("int[][]", "grid"));

        String result = RefactorController.buildEqualsAndHashCode("Account", fields);
        assertTrue("Primitive int should use !=", result.contains("if (id != that.id) return false;"));
        assertTrue("Double should use Double.compare", result.contains("Double.compare(score, that.score) != 0"));
        assertTrue("String should use Objects.equals", result.contains("java.util.Objects.equals(email, that.email)"));
        assertTrue("1D array should use Arrays.equals", result.contains("java.util.Arrays.equals(data, that.data)"));
        assertTrue("2D array should use Arrays.deepEquals", result.contains("java.util.Arrays.deepEquals(grid, that.grid)"));

        assertTrue("hashCode should use Arrays.hashCode for 1D array", result.contains("java.util.Arrays.hashCode(data)"));
        assertTrue("hashCode should use Arrays.deepHashCode for 2D array", result.contains("java.util.Arrays.deepHashCode(grid)"));
    }
}
