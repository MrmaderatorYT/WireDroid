package com.ccs.wiredroid.tools.bytecode;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

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

public final class DependencyModel {

    public static final class ClassNode {
        public final String name;
        public final String simpleName;
        public final String packageName;
        public String superClass;
        public final List<String> interfaces = new ArrayList<>();
        public final List<String> usedBy = new ArrayList<>();
        public final List<String> dependsOn = new ArrayList<>();
        public int methodCount;
        public int fieldCount;
        public int linesOfCode;

        public ClassNode(String name) {
            this.name = name;
            int lastDot = name.lastIndexOf('.');
            this.simpleName = lastDot >= 0 ? name.substring(lastDot + 1) : name;
            this.packageName = lastDot >= 0 ? name.substring(0, lastDot) : "";
        }
    }

    public static final class DependencyEdge {
        public final String from;
        public final String to;
        public final DependencyType type;

        public DependencyEdge(String from, String to, DependencyType type) {
            this.from = from;
            this.to = to;
            this.type = type;
        }
    }

    public enum DependencyType {
        EXTENDS("extends"),
        IMPLEMENTS("implements"),
        USES("uses"),
        FIELD("field"),
        PARAMETER("parameter"),
        RETURNS("returns");

        public final String label;
        DependencyType(String label) { this.label = label; }
    }

    private final Map<String, ClassNode> classes = new HashMap<>();
    private final List<DependencyEdge> edges = new ArrayList<>();
    private final Set<String> edgeKeys = new HashSet<>();
    private final Set<String> analyzedClasses = new HashSet<>();
    private final Map<String, Set<String>> incomingUses = new HashMap<>();

    public Map<String, ClassNode> getClasses() { return classes; }
    public List<DependencyEdge> getEdges() {
        List<DependencyEdge> projectEdges = new ArrayList<>();
        for (DependencyEdge edge : edges) {
            if (classes.containsKey(edge.from) && classes.containsKey(edge.to)) {
                projectEdges.add(edge);
            }
        }
        return projectEdges;
    }

    public static String normalizeClassName(String raw) {
        if (raw == null) return null;
        String clean = raw.replace('/', '.');
        while (clean.startsWith("[")) clean = clean.substring(1);
        if (clean.startsWith("L") && clean.endsWith(";")) {
            clean = clean.substring(1, clean.length() - 1);
        }
        int dollar = clean.indexOf('$');
        if (dollar > 0) {
            String suffix = clean.substring(dollar + 1);
            if (!suffix.isEmpty() && (Character.isDigit(suffix.charAt(0))
                    || suffix.startsWith("Lambda") || suffix.startsWith("ExternalSynthetic")
                    || suffix.startsWith("$"))) {
                clean = clean.substring(0, dollar);
            }
        }
        return clean;
    }

    public static boolean isIgnoredClass(String name) {
        if (name == null) return true;
        String s = name.replace('/', '.');
        if (s.startsWith("java.") || s.startsWith("javax.") || s.startsWith("android.") || s.startsWith("androidx.")
                || s.startsWith("kotlin.") || s.startsWith("dalvik.") || s.startsWith("org.intellij.")
                || s.startsWith("org.jetbrains.") || s.startsWith("com.google.android.material.")) {
            return true;
        }
        int lastDot = s.lastIndexOf('.');
        String simpleName = lastDot >= 0 ? s.substring(lastDot + 1) : s;
        return simpleName.equals("R") || simpleName.startsWith("R$") || simpleName.equals("BuildConfig") || simpleName.equals("BR");
    }

    public int analyzeDirectory(File dir) throws IOException {
        int count = 0;
        if (dir == null || !dir.isDirectory()) return 0;
        String dirName = dir.getName();
        if (dirName.equals(".gradle") || dirName.equals(".git") || dirName.equals("desugar_graph")
                || dirName.equals("transforms") || dirName.equals("incremental") || dirName.equals("res")) {
            return 0;
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (Thread.currentThread().isInterrupted()) return count;
                if (f.isDirectory()) {
                    count += analyzeDirectory(f);
                } else if (f.getName().endsWith(".class")) {
                    String fname = f.getName();
                    if (fname.equals("R.class") || fname.startsWith("R$") || fname.equals("BuildConfig.class")) {
                        continue;
                    }
                    try {
                        analyzeClass(f);
                        count++;
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return count;
    }

    public void analyzeClass(File classFile) throws IOException {
        try (InputStream is = new FileInputStream(classFile)) {
            analyzeClass(is);
        }
    }

    public void analyzeClass(InputStream classStream) throws IOException {
        ClassReader cr = new ClassReader(classStream);
        String bytecodeClass = normalizeClassName(cr.getClassName());
        String bytecodeKey = cr.getClassName().replace('/', '.');
        if (bytecodeClass == null || isIgnoredClass(bytecodeClass)
                || !analyzedClasses.add(bytecodeKey)) {
            return;
        }
        final String[] currentClass = {null};
        final String[] currentSuper = {null};
        final List<String> currentInterfaces = new ArrayList<>();
        final Set<String> usedClasses = new HashSet<>();

        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                currentClass[0] = normalizeClassName(name);
                currentSuper[0] = superName != null ? normalizeClassName(superName) : null;
                if (interfaces != null) {
                    for (String iface : interfaces) {
                        String n = normalizeClassName(iface);
                        if (n != null && !isIgnoredClass(n)) {
                            currentInterfaces.add(n);
                        }
                    }
                }
            }

            @Override
            public FieldVisitor visitField(int access, String name, String desc,
                                           String signature, Object value) {
                addTypeRefs(desc, usedClasses);
                if (signature != null) addTypeRefs(signature, usedClasses);
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                addTypeRefs(desc, usedClasses);
                if (signature != null) addTypeRefs(signature, usedClasses);
                if (exceptions != null) {
                    for (String ex : exceptions) {
                        String n = normalizeClassName(ex);
                        if (n != null && !isIgnoredClass(n)) usedClasses.add(n);
                    }
                }

                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                                                String desc, boolean isInterface) {
                        String n = normalizeClassName(owner);
                        if (n != null && !isIgnoredClass(n)) usedClasses.add(n);
                        addTypeRefs(desc, usedClasses);
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name,
                                               String desc) {
                        String n = normalizeClassName(owner);
                        if (n != null && !isIgnoredClass(n)) usedClasses.add(n);
                    }

                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        String n = normalizeClassName(type);
                        if (n != null && !isIgnoredClass(n)) usedClasses.add(n);
                    }

                    @Override
                    public void visitLdcInsn(Object cst) {
                        if (cst instanceof Type) {
                            Type t = (Type) cst;
                            String n = normalizeClassName(t.getClassName());
                            if (n != null && !isIgnoredClass(n)) usedClasses.add(n);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        String className = currentClass[0];
        if (className == null || isIgnoredClass(className)) return;

        ClassNode cn = getOrCreateClass(className);
        Set<String> incoming = incomingUses.get(className);
        if (incoming != null) {
            for (String source : incoming) {
                if (!cn.usedBy.contains(source)) cn.usedBy.add(source);
            }
        }

        if (currentSuper[0] != null && !"java.lang.Object".equals(currentSuper[0]) && !isIgnoredClass(currentSuper[0])) {
            cn.superClass = currentSuper[0];
            addEdge(className, currentSuper[0], DependencyType.EXTENDS);
        }

        for (String iface : currentInterfaces) {
            if (!cn.interfaces.contains(iface)) cn.interfaces.add(iface);
            addEdge(className, iface, DependencyType.IMPLEMENTS);
        }

        for (String used : usedClasses) {
            if (used.equals(className)) continue;
            if (isIgnoredClass(used)) continue;

            if (!cn.dependsOn.contains(used)) cn.dependsOn.add(used);

            incomingUses.computeIfAbsent(used, key -> new HashSet<>()).add(className);
            ClassNode usedNode = classes.get(used);
            if (usedNode != null && !usedNode.usedBy.contains(className)) {
                usedNode.usedBy.add(className);
            }

            boolean isFieldOrHierarchical = used.equals(currentSuper[0])
                    || currentInterfaces.contains(used);
            if (!isFieldOrHierarchical) {
                addEdge(className, used, DependencyType.USES);
            }
        }
    }

    private void addTypeRefs(String descriptor, Set<String> usedClasses) {
        if (descriptor == null) return;
        try {
            if (descriptor.contains(")")) {
                Type methodType = Type.getMethodType(descriptor);
                String ret = normalizeClassName(methodType.getReturnType().getClassName());
                if (ret != null && !isIgnoredClass(ret)) usedClasses.add(ret);
                for (Type t : methodType.getArgumentTypes()) {
                    String arg = normalizeClassName(t.getClassName());
                    if (arg != null && !isIgnoredClass(arg)) usedClasses.add(arg);
                }
            } else {
                Type t = Type.getType(descriptor);
                String n = normalizeClassName(t.getClassName());
                if (n != null && !isIgnoredClass(n)) usedClasses.add(n);
            }
        } catch (Exception ignored) {
        }
    }

    private void addEdge(String from, String to, DependencyType type) {
        String key = from + '\u0000' + to + '\u0000' + type.name();
        if (edgeKeys.add(key)) {
            edges.add(new DependencyEdge(from, to, type));
        }
    }

    private ClassNode getOrCreateClass(String name) {
        return classes.computeIfAbsent(name, ClassNode::new);
    }

    public List<ClassNode> getProjectClasses() {
        return new ArrayList<>(classes.values());
    }

    public Set<String> getProjectPackages() {
        Set<String> packages = new HashSet<>();
        for (ClassNode cn : getProjectClasses()) {
            if (!cn.packageName.isEmpty()) {
                packages.add(cn.packageName);
            }
        }
        return packages;
    }

    public int getInDegree(String className) {
        ClassNode cn = classes.get(className);
        if (cn == null) return 0;
        int count = 0;
        for (String source : cn.usedBy) {
            if (classes.containsKey(source)) count++;
        }
        return count;
    }

    public int getOutDegree(String className) {
        ClassNode cn = classes.get(className);
        if (cn == null) return 0;
        int count = 0;
        for (String target : cn.dependsOn) {
            if (classes.containsKey(target)) count++;
        }
        return count;
    }

    public List<String> findCircularDependencies() {
        Set<String> visited = new HashSet<>();
        Set<String> inStack = new HashSet<>();
        List<String> cycles = new ArrayList<>();

        for (ClassNode cn : getProjectClasses()) {
            if (!visited.contains(cn.name)) {
                detectCycle(cn.name, visited, inStack, cycles, new ArrayList<>());
            }
        }
        return cycles;
    }

    private void detectCycle(String current, Set<String> visited, Set<String> inStack,
                             List<String> cycles, List<String> path) {
        visited.add(current);
        inStack.add(current);
        path.add(current);

        ClassNode cn = classes.get(current);
        if (cn != null) {
            for (String dep : cn.dependsOn) {
                if (!visited.contains(dep)) {
                    detectCycle(dep, visited, inStack, cycles, path);
                } else if (inStack.contains(dep)) {
                    StringBuilder cycle = new StringBuilder();
                    int idx = path.indexOf(dep);
                    for (int i = idx; i < path.size(); i++) {
                        if (i > idx) cycle.append(" → ");
                        cycle.append(path.get(i));
                    }
                    cycle.append(" → ").append(dep);
                    cycles.add(cycle.toString());
                }
            }
        }

        inStack.remove(current);
        path.remove(path.size() - 1);
    }
}
