package com.ccs.wiredroid.tools.bytecode;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Decompiles a .class file into readable Java-like source with JavaDoc comments,
 * similar to IntelliJ IDEA's "Decompile" view.
 */
public final class ClassDecompiler {

    private ClassDecompiler() {}

    public static String decompile(byte[] classBytes) {
        try {
            ClassReader cr = new ClassReader(classBytes);
            ClassNode cn = new ClassNode();
            cr.accept(cn, ClassReader.EXPAND_FRAMES);

            StringBuilder sb = new StringBuilder();
            decompile(cn, sb);
            return sb.toString();
        } catch (Exception e) {
            return "// Error decompiling class: " + e.getMessage();
        }
    }

    private static void decompile(ClassNode cn, StringBuilder sb) {
        String pkg = getPackage(cn.name);
        String simpleName = getSimpleName(cn.name);

        // Package
        if (!pkg.isEmpty()) {
            sb.append("package ").append(pkg).append(";\n\n");
        }

        // Imports (from method signatures)
        List<String> imports = collectImports(cn);
        for (String imp : imports) {
            sb.append("import ").append(imp).append(";\n");
        }
        if (!imports.isEmpty()) sb.append("\n");

        // Class JavaDoc
        sb.append("/**\n");
        sb.append(" * ").append(simpleName);
        if (cn.sourceFile != null) {
            sb.append(" — from `").append(cn.sourceFile).append("`");
        }
        sb.append("\n");
        sb.append(" * Compiled class file (Java ").append(versionLabel(cn.version)).append(")\n");
        sb.append(" *\n");

        // Superclass
        if (cn.superName != null && !"java/lang/Object".equals(cn.superName)) {
            sb.append(" * Extends: {@link ").append(dotted(cn.superName)).append("}\n");
        }

        // Interfaces
        if (cn.interfaces != null && !cn.interfaces.isEmpty()) {
            for (Object iface : cn.interfaces) {
                sb.append(" * Implements: {@link ").append(dotted((String) iface)).append("}\n");
            }
        }

        // Fields summary
        if (cn.fields != null && !cn.fields.isEmpty()) {
            sb.append(" *\n");
            sb.append(" * Fields:\n");
            for (FieldNode fn : cn.fields) {
                sb.append(" *   ").append(accessStr(fn.access))
                  .append(" {@link ").append(dotted(Type.getType(fn.desc).getClassName())).append("} ")
                  .append(fn.name);
                if (fn.value != null) {
                    sb.append(" = ").append(fn.value);
                }
                sb.append("\n");
            }
        }

        // Methods summary
        if (cn.methods != null && !cn.methods.isEmpty()) {
            sb.append(" *\n");
            sb.append(" * Methods:\n");
            for (MethodNode mn : cn.methods) {
                sb.append(" *   ").append(accessStr(mn.access))
                  .append(" {@link ").append(dotted(Type.getReturnType(mn.desc).getClassName())).append("} ")
                  .append(mn.name).append(methodParamList(mn.desc));
                if (mn.exceptions != null && !mn.exceptions.isEmpty()) {
                    sb.append(" throws ");
                    for (int i = 0; i < mn.exceptions.size(); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append("{@link ").append(dotted((String) mn.exceptions.get(i))).append("}");
                    }
                }
                sb.append("\n");
            }
        }

        sb.append(" */\n");

        // Class declaration
        sb.append(accessStrNoStatic(cn.access));
        if ((cn.access & Opcodes.ACC_INTERFACE) != 0) {
            sb.append("interface ");
        } else if ((cn.access & Opcodes.ACC_ENUM) != 0) {
            sb.append("enum ");
        } else if ((cn.access & Opcodes.ACC_ANNOTATION) != 0) {
            sb.append("@interface ");
        } else {
            sb.append("class ");
        }
        sb.append(simpleName);

        if (cn.superName != null && !"java/lang/Object".equals(cn.superName)) {
            sb.append(" extends ").append(dotted(cn.superName));
        }

        if (cn.interfaces != null && !cn.interfaces.isEmpty()) {
            sb.append(" implements ");
            for (int i = 0; i < cn.interfaces.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(dotted((String) cn.interfaces.get(i)));
            }
        }

        sb.append(" {\n\n");

        // Fields
        if (cn.fields != null) {
            for (FieldNode fn : cn.fields) {
                fieldJavaDoc(fn, sb);
                sb.append("    ").append(accessStr(fn.access))
                  .append(Type.getType(fn.desc).getClassName()).append(" ").append(fn.name);
                if (fn.value != null) {
                    sb.append(" = ").append(fn.value);
                }
                sb.append(";\n\n");
            }
        }

        // Methods
        if (cn.methods != null) {
            for (MethodNode mn : cn.methods) {
                methodJavaDoc(cn, mn, sb);
                sb.append("    ").append(accessStr(mn.access))
                  .append(Type.getReturnType(mn.desc).getClassName()).append(" ")
                  .append(mn.name).append("(");
                Type[] argTypes = Type.getArgumentTypes(mn.desc);
                String[] argNames = generateArgNames(mn, argTypes);
                for (int i = 0; i < argTypes.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(argTypes[i].getClassName()).append(" ").append(argNames[i]);
                }
                sb.append(")");
                if (mn.exceptions != null && !mn.exceptions.isEmpty()) {
                    sb.append(" throws ");
                    for (int i = 0; i < mn.exceptions.size(); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(dotted((String) mn.exceptions.get(i)));
                    }
                }
                sb.append(" {\n");

                // Method body — show bytecode hints for non-abstract, non-native
                if ((mn.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0) {
                    methodBody(mn, sb);
                } else {
                    sb.append("        // native or abstract\n");
                }

                sb.append("    }\n\n");
            }
        }

        sb.append("}\n");
    }

    private static void fieldJavaDoc(FieldNode fn, StringBuilder sb) {
        sb.append("    /**\n");
        sb.append("     * {@link ").append(dotted(Type.getType(fn.desc).getClassName())).append("} ");
        sb.append(fn.name).append("\n");
        sb.append("     *\n");
        sb.append("     * Access: ").append(accessStr(fn.access)).append("\n");
        if (fn.signature != null) {
            sb.append("     * Signature: ").append(fn.signature).append("\n");
        }
        if (fn.value != null) {
            sb.append("     * Constant value: ").append(fn.value).append("\n");
        }
        annotationsJavaDoc(fn.visibleAnnotations, "     * ", sb);
        sb.append("     */\n");
    }

    private static void methodJavaDoc(ClassNode cn, MethodNode mn, StringBuilder sb) {
        sb.append("    /**\n");

        // Simple description from bytecode patterns
        String desc = describeMethod(cn, mn);
        if (desc != null) {
            sb.append("     * ").append(desc).append("\n");
            sb.append("     *\n");
        }

        sb.append("     * Access: ").append(accessStr(mn.access)).append("\n");

        // Parameters
        Type[] argTypes = Type.getArgumentTypes(mn.desc);
        if (argTypes.length > 0) {
            String[] argNames = generateArgNames(mn, argTypes);
            for (int i = 0; i < argTypes.length; i++) {
                sb.append("     * @param ").append(argNames[i])
                  .append(" {@link ").append(dotted(argTypes[i].getClassName())).append("}\n");
            }
        }

        // Return
        Type retType = Type.getReturnType(mn.desc);
        if (retType.getSort() != Type.VOID) {
            sb.append("     * @return {@link ").append(dotted(retType.getClassName())).append("}\n");
        }

        // Exceptions
        if (mn.exceptions != null && !mn.exceptions.isEmpty()) {
            for (Object exc : mn.exceptions) {
                sb.append("     * @throws ").append(dotted((String) exc)).append("\n");
            }
        }

        if (mn.signature != null) {
            sb.append("     * Signature: ").append(mn.signature).append("\n");
        }

        annotationsJavaDoc(mn.visibleAnnotations, "     * ", sb);
        sb.append("     */\n");
    }

    private static String describeMethod(ClassNode cn, MethodNode mn) {
        String name = mn.name;
        Type retType = Type.getReturnType(mn.desc);
        Type[] argTypes = Type.getArgumentTypes(mn.desc);

        if (name.equals("<init>")) {
            return "Constructor for {@link " + dotted(cn.name) + "}";
        }
        if (name.equals("<clinit>")) {
            return "Static initializer";
        }

        // Common method patterns
        if (name.startsWith("get") && argTypes.length == 0 && retType.getSort() != Type.VOID) {
            String field = Character.toLowerCase(name.charAt(3)) + name.substring(4);
            return "Getter for {@link #" + field + "}";
        }
        if (name.startsWith("set") && argTypes.length == 1 && retType.getSort() == Type.VOID) {
            String field = Character.toLowerCase(name.charAt(3)) + name.substring(4);
            return "Setter for {@link #" + field + "}";
        }
        if (name.startsWith("is") && argTypes.length == 0 && retType.getClassName().equals("boolean")) {
            String field = Character.toLowerCase(name.charAt(2)) + name.substring(3);
            return "Predicate for {@link #" + field + "}";
        }
        if (name.equals("toString")) return "Returns string representation";
        if (name.equals("hashCode")) return "Returns hash code";
        if (name.equals("equals") && argTypes.length == 1) return "Compares with another object";
        if (name.equals("clone")) return "Returns a clone of this object";

        return null;
    }

    private static void methodBody(MethodNode mn, StringBuilder sb) {
        // Show key instructions as comments
        int maxLines = 15;
        int count = 0;
        boolean inCode = false;
        String lastInvoke = null;

        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null && count < maxLines; insn = insn.getNext()) {
            if (insn.getType() == AbstractInsnNode.METHOD_INSN) {
                MethodInsnNode min = (MethodInsnNode) insn;
                String owner = getSimpleName(min.owner);
                lastInvoke = owner + "." + min.name + "()";
                count++;
            } else if (insn.getType() == AbstractInsnNode.LINE) {
                // skip line number nodes
            } else if (insn.getOpcode() >= 0) {
                count++;
            }
        }

        if (mn.instructions.size() == 0) {
            sb.append("        // empty method body\n");
        } else if (mn.instructions.size() <= 3) {
            sb.append("        // ").append(mn.instructions.size()).append(" instruction(s)\n");
        } else {
            sb.append("        // ").append(mn.instructions.size()).append(" bytecode instructions\n");
            if (lastInvoke != null) {
                sb.append("        // last call: ").append(lastInvoke).append("\n");
            }
        }
    }

    private static void annotationsJavaDoc(List<AnnotationNode> annotations, String prefix, StringBuilder sb) {
        if (annotations == null) return;
        for (AnnotationNode an : annotations) {
            sb.append(prefix).append("@").append(dotted(an.desc)).append("\n");
        }
    }

    private static List<String> collectImports(ClassNode cn) {
        List<String> imports = new ArrayList<>();
        // From fields
        if (cn.fields != null) {
            for (FieldNode fn : cn.fields) {
                addTypeImports(Type.getType(fn.desc), imports);
            }
        }
        // From methods
        if (cn.methods != null) {
            for (MethodNode mn : cn.methods) {
                addTypeImports(Type.getReturnType(mn.desc), imports);
                for (Type t : Type.getArgumentTypes(mn.desc)) {
                    addTypeImports(t, imports);
                }
            }
        }
        // From superclass
        if (cn.superName != null) {
            String cls = dotted(cn.superName);
            if (!cls.startsWith("java.lang.") && !cls.equals("java.lang.Object")) {
                imports.add(cls);
            }
        }
        // From interfaces
        if (cn.interfaces != null) {
            for (Object iface : cn.interfaces) {
                imports.add(dotted((String) iface));
            }
        }
        java.util.Collections.sort(imports);
        // Deduplicate
        List<String> unique = new ArrayList<>();
        String prev = null;
        for (String s : imports) {
            if (!s.equals(prev)) { unique.add(s); prev = s; }
        }
        return unique;
    }

    private static void addTypeImports(Type t, List<String> out) {
        if (t == null) return;
        if (t.getSort() == Type.ARRAY) t = t.getElementType();
        if (t.getSort() == Type.OBJECT) {
            String cls = t.getClassName();
            if (!cls.startsWith("java.lang.") && !cls.equals("java.lang.Object") && !cls.contains("$")) {
                out.add(cls);
            }
        }
    }

    private static String[] generateArgNames(MethodNode mn, Type[] argTypes) {
        String[] names = new String[argTypes.length];
        // Try to get from local variable table
        if (mn.localVariables != null && mn.localVariables.size() >= argTypes.length) {
            int idx = (mn.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
            for (int i = 0; i < argTypes.length && i < mn.localVariables.size(); i++) {
                org.objectweb.asm.tree.LocalVariableNode lv = mn.localVariables.get(i);
                if (lv != null && lv.name != null) {
                    names[i] = lv.name;
                    idx += argTypes[i].getSize();
                    continue;
                }
                names[i] = "arg" + i;
                idx += argTypes[i].getSize();
            }
        } else {
            for (int i = 0; i < argTypes.length; i++) {
                names[i] = "arg" + i;
            }
        }
        // Fallback
        for (int i = 0; i < names.length; i++) {
            if (names[i] == null) names[i] = "arg" + i;
        }
        return names;
    }

    private static String accessStr(int access) {
        StringBuilder sb = new StringBuilder();
        if ((access & Opcodes.ACC_PUBLIC) != 0) sb.append("public ");
        if ((access & Opcodes.ACC_PROTECTED) != 0) sb.append("protected ");
        if ((access & Opcodes.ACC_PRIVATE) != 0) sb.append("private ");
        if ((access & Opcodes.ACC_STATIC) != 0) sb.append("static ");
        if ((access & Opcodes.ACC_FINAL) != 0) sb.append("final ");
        if ((access & Opcodes.ACC_ABSTRACT) != 0) sb.append("abstract ");
        if ((access & Opcodes.ACC_NATIVE) != 0) sb.append("native ");
        if ((access & Opcodes.ACC_SYNCHRONIZED) != 0) sb.append("synchronized ");
        if ((access & Opcodes.ACC_VOLATILE) != 0) sb.append("volatile ");
        if ((access & Opcodes.ACC_TRANSIENT) != 0) sb.append("transient ");
        return sb.toString();
    }

    private static String accessStrNoStatic(int access) {
        StringBuilder sb = new StringBuilder();
        if ((access & Opcodes.ACC_PUBLIC) != 0) sb.append("public ");
        if ((access & Opcodes.ACC_PROTECTED) != 0) sb.append("protected ");
        if ((access & Opcodes.ACC_PRIVATE) != 0) sb.append("private ");
        if ((access & Opcodes.ACC_FINAL) != 0) sb.append("final ");
        if ((access & Opcodes.ACC_ABSTRACT) != 0) sb.append("abstract ");
        return sb.toString();
    }

    private static String dotted(String internal) {
        if (internal == null) return "";
        return internal.replace('/', '.');
    }

    private static String getPackage(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash > 0 ? internalName.substring(0, slash).replace('/', '.') : "";
    }

    private static String getSimpleName(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash >= 0 ? internalName.substring(slash + 1) : internalName;
    }

    private static String methodParamList(String desc) {
        Type[] args = Type.getArgumentTypes(desc);
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(args[i].getClassName());
        }
        sb.append(")");
        return sb.toString();
    }

    private static String versionLabel(int version) {
        switch (version) {
            case 45: return "1.1";
            case 46: return "1.2";
            case 47: return "1.3";
            case 48: return "1.4";
            case 49: return "5";
            case 50: return "6";
            case 51: return "7";
            case 52: return "8";
            case 53: return "9";
            case 54: return "10";
            case 55: return "11";
            case 56: return "12";
            case 57: return "13";
            case 58: return "14";
            case 59: return "15";
            case 60: return "16";
            case 61: return "17";
            case 62: return "18";
            case 63: return "19";
            case 64: return "20";
            case 65: return "21";
            default: return "class " + version;
        }
    }
}
