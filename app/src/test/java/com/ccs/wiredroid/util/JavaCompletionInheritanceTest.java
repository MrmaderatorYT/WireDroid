package com.ccs.wiredroid.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Completion has to see what a class inherits, not only what it declares.
 *
 * <p>Reproduces the oop-zoo shape: {@code Lion extends Animal}, and
 * {@code getName()} declared only on {@code Animal}. Offering members of the
 * subclass alone is the failure that looks, from the editor, like the method
 * does not exist.</p>
 */
public class JavaCompletionInheritanceTest {

    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

    private static final String ANIMAL =
            "package com.example.training.oop.zoo.animals;\n"
            + "\n"
            + "public abstract class Animal {\n"
            + "    private final String name;\n"
            + "    public abstract void makeSound();\n"
            + "    public String getName() {\n"
            + "        return name;\n"
            + "    }\n"
            + "}\n";

    private static final String LION =
            "package com.example.training.oop.zoo.animals;\n"
            + "\n"
            + "public class Lion extends Animal {\n"
            + "    public Lion(String name) {\n"
            + "        super(name);\n"
            + "    }\n"
            + "    @Override\n"
            + "    public void makeSound() {\n"
            + "    }\n"
            + "}\n";

    /** The members completion would offer for {@code typeName}, by method name. */
    private Set<String> membersOf(String typeName, File projectRoot, String currentSource,
                                  List<String> imports, String currentPkg) throws Exception {
        Method resolve = JavaReflectionCompletion.class.getDeclaredMethod(
                "resolveMembersRecursive", String.class, List.class, String.class, String.class,
                File.class, ClassLoader.class, Set.class, Map.class, Map.class);
        resolve.setAccessible(true);

        Map<String, Object> methods = new LinkedHashMap<>();
        Map<String, Object> fields = new LinkedHashMap<>();
        resolve.invoke(null, typeName, imports, currentPkg, currentSource, projectRoot,
                null, new HashSet<String>(), methods, fields);

        Set<String> names = new HashSet<>();
        for (String sig : methods.keySet()) {
            names.add(sig.substring(0, sig.indexOf('/')));
        }
        return names;
    }

    private File zooProject() throws Exception {
        File root = temp.newFolder("oop-zoo");
        File pkg = new File(root, "src/main/java/com/example/training/oop/zoo/animals");
        assertTrue(pkg.mkdirs());
        Files.write(new File(pkg, "Animal.java").toPath(), ANIMAL.getBytes(StandardCharsets.UTF_8));
        Files.write(new File(pkg, "Lion.java").toPath(), LION.getBytes(StandardCharsets.UTF_8));
        return root;
    }

    @Test
    public void subclassOffersItsOwnMethods() throws Exception {
        Set<String> names = membersOf("Lion", zooProject(), null, new ArrayList<>(),
                "com.example.training.oop.zoo.animals");
        assertNotNull(names);
        assertTrue("makeSound is declared on Lion itself, got " + names,
                names.contains("makeSound"));
    }

    @Test
    public void subclassOffersInheritedMethods() throws Exception {
        Set<String> names = membersOf("Lion", zooProject(), null, new ArrayList<>(),
                "com.example.training.oop.zoo.animals");
        assertTrue("getName() is declared on Animal and Lion extends Animal, "
                        + "so completion must offer it; got " + names,
                names.contains("getName"));
    }

    @Test
    public void inheritedMethodsAlsoResolveFromAnotherPackage() throws Exception {
        // The call site usually lives outside the animals package and reaches the
        // type through an import, which is a different lookup path.
        List<String> imports = new ArrayList<>();
        imports.add("com.example.training.oop.zoo.animals.Lion");
        Set<String> names = membersOf("Lion", zooProject(), null, imports,
                "com.example.training.oop.zoo");
        assertTrue("getName() must survive the imported-type path; got " + names,
                names.contains("getName"));
    }

    @Test
    public void theEnclosingClassIsFoundAtACursorInsideIt() {
        // What "call it with no receiver" resolves against. The cursor sits in
        // Lion's body, so the answer has to be Lion — this is the lookup that
        // was missing entirely, not merely wrong.
        int cursor = LION.indexOf("super(name);");
        assertTrue(cursor > 0);
        assertEquals("Lion", JavaReflectionCompletion.enclosingTypeName(LION, cursor));
    }

    @Test
    public void aCursorBeforeAnyClassHasNoEnclosingType() {
        assertNull(JavaReflectionCompletion.enclosingTypeName(LION, 5));
    }

    @Test
    public void inheritedMethodIsReachableWithoutAReceiver() throws Exception {
        // The reported bug: inside a subclass, getName() is called with no
        // receiver, so nothing ever asked for the members of the enclosing type.
        int cursor = LION.indexOf("super(name);");
        String enclosing = JavaReflectionCompletion.enclosingTypeName(LION, cursor);
        Set<String> names = membersOf(enclosing, zooProject(), LION, new ArrayList<>(),
                "com.example.training.oop.zoo.animals");
        assertTrue("a bare getName() inside Lion must be offered; got " + names,
                names.contains("getName"));
    }

    @Test
    public void theSubclassBufferItselfStillSeesTheParent() throws Exception {
        // Editing Lion.java: the buffer is the current source, so the declaration
        // is parsed from it rather than from disk — the parent still has to be
        // followed to the project files.
        Set<String> names = membersOf("Lion", zooProject(), LION, new ArrayList<>(),
                "com.example.training.oop.zoo.animals");
        assertTrue("getName() must be found when Lion is the open buffer; got " + names,
                names.contains("getName"));
    }
}
