package com.ccs.wiredroid.tools.bytecode;

import com.ccs.wiredroid.maven.MavenPaths;
import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * Creates a pure bytecode project — no Maven, no pom.xml.
 * Users write .class files (via bytecode disassembly) or .asm files,
 * compile them with ASM, and run the result.
 *
 * Structure:
 * <pre>
 *   ProjectName/
 *     src/          ← user puts .class / .asm files here
 *     out/          ← compiled output (auto-created)
 * </pre>
 */
public final class BytecodeProjectFactory {

    private BytecodeProjectFactory() {}

    public static File create(Context context, String projectName) throws IOException {
        if (projectName == null || projectName.trim().isEmpty()) {
            throw new IllegalArgumentException("empty name");
        }
        String safe = projectName.trim().replaceAll("[^a-zA-Z0-9_-]", "_");
        File root = MavenPaths.projectDir(context, safe);
        if (root.exists()) {
            throw new IOException("Project already exists: " + safe);
        }

        root.mkdirs();
        new File(root, "src").mkdirs();
        new File(root, "out").mkdirs();

        // Example .asm file
        writeUtf8(new File(root, "src/HelloWorld.asm"),
                "# Pure bytecode project — edit this file and compile\n"
                + "# Use javap-style instructions or ASM Textifier format\n"
                + "\n"
                + ".class public HelloWorld\n"
                + ".super java/lang/Object\n"
                + "\n"
                + ".method public static main([Ljava/lang/String;)V\n"
                + "    .limit stack 2\n"
                + "    .limit locals 1\n"
                + "\n"
                + "    getstatic java/lang/System/out Ljava/io/PrintStream;\n"
                + "    ldc \"Hello from bytecode!\"\n"
                + "    invokevirtual java/io/PrintStream/println(Ljava/lang/String;)V\n"
                + "    return\n"
                + ".end method\n");

        return root;
    }

    private static void writeUtf8(File file, String content) throws IOException {
        File p = file.getParentFile();
        if (p != null) p.mkdirs();
        try (OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8)) {
            w.write(content);
        }
    }
}
