package com.ccs.wiredroid.tools.bytecode;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Call Graph Model: analyzes .class files and builds a method call graph.
 * Each node is a method, each edge represents a call from one method to another.
 */
public final class CallGraphModel {

    public static final class MethodNode {
        public final String className;
        public final String methodName;
        public final String methodDesc;
        public final int access;

        public MethodNode(String className, String methodName, String methodDesc, int access) {
            this.className = className;
            this.methodName = methodName;
            this.methodDesc = methodDesc;
            this.access = access;
        }

        public String shortSignature() {
            return className.substring(className.lastIndexOf('.') + 1) + "." + methodName;
        }

        public String fullSignature() {
            return className + "." + methodName + methodDesc;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof MethodNode)) return false;
            MethodNode that = (MethodNode) o;
            return fullSignature().equals(that.fullSignature());
        }

        @Override
        public int hashCode() {
            return fullSignature().hashCode();
        }
    }

    public static final class CallEdge {
        public final MethodNode caller;
        public final MethodNode callee;
        public final String callType;

        public CallEdge(MethodNode caller, MethodNode callee, String callType) {
            this.caller = caller;
            this.callee = callee;
            this.callType = callType;
        }
    }

    private final Map<String, MethodNode> allMethods = new HashMap<>();
    private final List<CallEdge> edges = new ArrayList<>();
    private final Map<MethodNode, List<MethodNode>> callersOf = new HashMap<>();
    private final Map<MethodNode, List<MethodNode>> calleesOf = new HashMap<>();

    public Map<String, MethodNode> getAllMethods() { return allMethods; }
    public List<CallEdge> getEdges() { return edges; }

    /**
     * Get methods that this method calls (callees).
     */
    public List<MethodNode> getCallees(MethodNode method) {
        return calleesOf.getOrDefault(method, new ArrayList<>());
    }

    /**
     * Get methods that call this method (callers).
     */
    public List<MethodNode> getCallers(MethodNode method) {
        return callersOf.getOrDefault(method, new ArrayList<>());
    }

    /**
     * Analyze a single .class file.
     */
    public void analyzeClass(File classFile) throws IOException {
        try (InputStream is = new FileInputStream(classFile)) {
            analyzeClass(is);
        }
    }

    /**
     * Analyze a single .class file from an input stream.
     */
    public void analyzeClass(InputStream classStream) throws IOException {
        ClassReader cr = new ClassReader(classStream);
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            private String currentClass;

            @Override
            public void visit(int version, int access, String name, String signature,
                            String superName, String[] interfaces) {
                currentClass = name.replace('/', '.');
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                           String signature, String[] exceptions) {
                MethodNode caller = getOrCreateMethod(currentClass, name, desc, access);

                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                                              String desc, boolean isInterface) {
                        String calleeClass = owner.replace('/', '.');
                        MethodNode callee = getOrCreateMethod(calleeClass, name, desc, 0);

                        String callType;
                        switch (opcode) {
                            case Opcodes.INVOKEVIRTUAL: callType = "invokevirtual"; break;
                            case Opcodes.INVOKEINTERFACE: callType = "invokeinterface"; break;
                            case Opcodes.INVOKESTATIC: callType = "invokestatic"; break;
                            case Opcodes.INVOKESPECIAL: callType = "invokespecial"; break;
                            case Opcodes.INVOKEDYNAMIC: callType = "invokedynamic"; break;
                            default: callType = "unknown"; break;
                        }

                        CallEdge edge = new CallEdge(caller, callee, callType);
                        edges.add(edge);

                        calleesOf.computeIfAbsent(caller, k -> new ArrayList<>()).add(callee);
                        callersOf.computeIfAbsent(callee, k -> new ArrayList<>()).add(caller);
                    }
                };
            }
        }, 0);
    }

    /**
     * Analyze all .class files in a directory recursively.
     */
    public int analyzeDirectory(File dir) throws IOException {
        int count = 0;
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isDirectory()) {
                        count += analyzeDirectory(f);
                    } else if (f.getName().endsWith(".class")) {
                        try {
                            analyzeClass(f);
                            count++;
                        } catch (Exception e) {
                            // Skip malformed class files
                        }
                    }
                }
            }
        }
        return count;
    }

    /**
     * Find all methods in the project directory.
     */
    public List<MethodNode> findProjectMethods(File dir) {
        List<MethodNode> result = new ArrayList<>();
        for (MethodNode m : allMethods.values()) {
            if (!m.className.startsWith("java.") && !m.className.startsWith("javax.")
                    && !m.className.startsWith("android.") && !m.className.startsWith("androidx.")) {
                result.add(m);
            }
        }
        return result;
    }

    private MethodNode getOrCreateMethod(String className, String name, String desc, int access) {
        String key = className + "." + name + desc;
        return allMethods.computeIfAbsent(key, k -> new MethodNode(className, name, desc, access));
    }
}
