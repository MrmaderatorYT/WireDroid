package com.ccs.wiredroid.tools.bytecode;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * JVM bytecode text (ASM {@link Textifier} / javap-style), similar to Recaf’s bytecode view.
 */
public final class BytecodeDisassembler {

    private BytecodeDisassembler() {}

    public static String disassemble(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        Textifier textifier = new Textifier();
        TraceClassVisitor trace = new TraceClassVisitor(null, textifier, pw);
        cr.accept(trace, ClassReader.SKIP_FRAMES);
        pw.flush();
        return sw.toString();
    }
}
