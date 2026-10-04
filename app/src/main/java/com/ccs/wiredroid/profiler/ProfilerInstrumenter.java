package com.ccs.wiredroid.profiler;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Instruments .class files with method entry/exit hooks for CPU profiling.
 * Inserts INVOKESTATIC ProfilerBridge.methodEnter() at method start
 * and ProfilerBridge.methodExit() at every RETURN instruction.
 */
public class ProfilerInstrumenter {

    private static final String BRIDGE = "com/ccs/wiredroid/profiler/ProfilerBridge";

    /**
     * Instruments every class under {@code classDir}, at any depth.
     *
     * <p>The depth matters: a compiler writes {@code com/example/App.class}, not
     * {@code App.class}, so a flat listing finds nothing for any class that
     * declares a package — which is to say, for almost every real class.</p>
     */
    public static Set<File> instrumentDirectory(File classDir) throws IOException {
        Set<File> instrumented = new HashSet<>();
        if (!classDir.exists()) return instrumented;
        for (File f : listClassFiles(classDir)) {
            if (instrumentFile(f)) {
                instrumented.add(f);
            }
        }
        return instrumented;
    }

    /** Every {@code .class} beneath a directory, package folders included. */
    public static java.util.List<File> listClassFiles(File dir) {
        java.util.List<File> out = new java.util.ArrayList<>();
        collectClassFiles(dir, out, 0);
        return out;
    }

    private static void collectClassFiles(File dir, java.util.List<File> out, int depth) {
        if (depth > 12) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (f.isDirectory()) collectClassFiles(f, out, depth + 1);
            else if (f.getName().endsWith(".class")) out.add(f);
        }
    }

    public static boolean instrumentFile(File classFile) throws IOException {
        FileInputStream fis = new FileInputStream(classFile);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = fis.read(buf)) != -1) bos.write(buf, 0, n);
        fis.close();
        byte[] original = bos.toByteArray();
        byte[] result = instrument(original);
        if (result != null) {
            FileOutputStream fos = new FileOutputStream(classFile);
            fos.write(result);
            fos.close();
            return true;
        }
        return false;
    }

    public static byte[] instrument(byte[] classBytes) {
        try {
            ClassReader cr = new ClassReader(classBytes);
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override
                protected String getCommonSuperClass(String type1, String type2) {
                    return "java/lang/Object";
                }
            };

            ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
                private String className;

                @Override
                public void visit(int version, int access, String name, String signature,
                                  String superName, String[] interfaces) {
                    this.className = name;
                    super.visit(version, access, name, signature, superName, interfaces);
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String desc,
                                                 String signature, String[] exceptions) {
                    MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);

                    if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                        return mv;
                    }
                    if ("<clinit>".equals(name)) {
                        return mv;
                    }

                    return new ProfilingMethodVisitor(mv, className, name, desc);
                }
            };

            cr.accept(cv, 0);
            return cw.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static class ProfilingMethodVisitor extends MethodVisitor implements Opcodes {

        private final MethodVisitor target;
        private final String className;
        private final String methodName;
        private final String methodDesc;
        private boolean insertedEntry = false;

        ProfilingMethodVisitor(MethodVisitor target, String className, String methodName, String methodDesc) {
            super(Opcodes.ASM9, target);
            this.target = target;
            this.className = className;
            this.methodName = methodName;
            this.methodDesc = methodDesc;
        }

        @Override
        public void visitCode() {
            super.visitCode();

            if (!insertedEntry) {
                insertMethodEnter();
                insertedEntry = true;
            }
        }

        private void insertMethodEnter() {
            target.visitLdcInsn(className);
            target.visitLdcInsn(methodName);
            target.visitLdcInsn(methodDesc);
            target.visitMethodInsn(INVOKESTATIC, BRIDGE,
                    "methodEnter",
                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
                    false);
        }

        private void insertMethodExit() {
            target.visitLdcInsn(className);
            target.visitLdcInsn(methodName);
            target.visitLdcInsn(methodDesc);
            target.visitMethodInsn(INVOKESTATIC, BRIDGE,
                    "methodExit",
                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
                    false);
        }

        @Override
        public void visitInsn(int opcode) {
            if (opcode >= IRETURN && opcode <= RETURN) {
                insertMethodExit();
            }
            super.visitInsn(opcode);
        }
    }
}
