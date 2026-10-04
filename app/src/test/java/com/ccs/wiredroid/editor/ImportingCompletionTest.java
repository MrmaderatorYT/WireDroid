package com.ccs.wiredroid.editor;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/** Which classes still need an import, and which already have one. */
public class ImportingCompletionTest {

    private static final String SOURCE =
            "package com.example.app;\n"
            + "\n"
            + "import java.util.List;\n"
            + "import java.util.concurrent.*;\n"
            + "import static java.util.Arrays.asList;\n"
            + "\n"
            + "public class Demo {\n"
            + "}\n";

    @Test
    public void readsPlainImports() {
        List<String> names = ImportingCompletion.importedNames(SOURCE);
        assertTrue(names.contains("List"));
    }

    @Test
    public void readsStaticAndWildcardImports() {
        List<String> names = ImportingCompletion.importedNames(SOURCE);
        assertTrue(names.contains("asList"));
        assertTrue(names.contains("*"));
    }

    @Test
    public void aFileWithNoImportsHasNone() {
        assertTrue(ImportingCompletion.importedNames("class A {}").isEmpty());
        assertTrue(ImportingCompletion.importedNames(null).isEmpty());
    }

    @Test
    public void aTypeDeclaredHereNeedsNoImport() {
        // Importing it would be a compile error, so it must not be offered.
        assertTrue(ImportingCompletion.declaredInFile(SOURCE, "Demo"));
        assertTrue(ImportingCompletion.declaredInFile("interface Store {}", "Store"));
        assertTrue(ImportingCompletion.declaredInFile("enum Mode { A }", "Mode"));
        assertTrue(ImportingCompletion.declaredInFile("record Point(int x) {}", "Point"));
    }

    @Test
    public void aMentionIsNotADeclaration() {
        assertFalse(ImportingCompletion.declaredInFile(SOURCE, "ArrayList"));
        assertFalse(ImportingCompletion.declaredInFile("new Demo();", "Demo"));
        assertFalse(ImportingCompletion.declaredInFile(null, "Demo"));
    }
}
