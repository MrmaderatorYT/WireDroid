package com.ccs.wiredroid.tools.bytecode;
import com.ccs.wiredroid.R;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Чисте (без Android-залежностей) форматування JVM-метаданих для байткод-в'ювера.
 * Перетворює ASM-дескриптори/флаги у людське подання (стиль IntelliJ / javap).
 */
public final class BytecodeFormatter {

    private BytecodeFormatter() {}

    // ── Типи ────────────────────────────────────────────────────────────────

    /** {@code Ljava/lang/String;} → {@code java.lang.String}; {@code [[I} → {@code int[][]}. */
    public static String typeDesc(String desc) {
        if (desc == null || desc.isEmpty()) return "";
        try {
            return Type.getType(desc).getClassName();
        } catch (Throwable t) {
            return desc;
        }
    }

    /** Псевдонім {@link #typeDesc} для більш читабельних викликів із field/type інструкцій. */
    public static String className(String internalName) {
        if (internalName == null) return "";
        // internal name може бути у формі "java/lang/String" або дескриптором "Ljava/lang/String;"
        if (internalName.startsWith("L") && internalName.endsWith(";") && internalName.length() > 2) {
            return typeDesc(internalName);
        }
        return internalName.replace('/', '.');
    }

    /** {@code (Ljava/lang/String;I)V} → {@code main(String, int) : void}. */
    public static String methodSignature(String name, String desc) {
        if (desc == null) return name;
        try {
            Type t = Type.getMethodType(desc);
            Type[] args = t.getArgumentTypes();
            StringBuilder sb = new StringBuilder(name).append('(');
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(args[i].getClassName());
            }
            sb.append(") : ").append(t.getReturnType().getClassName());
            return sb.toString();
        } catch (Throwable ex) {
            return name + desc;
        }
    }

    /** Короткий підпис для дерева методів: {@code name(arg, arg)} (без повернення). */
    public static String methodShort(String name, String desc) {
        if (desc == null) return name + "()";
        try {
            Type[] args = Type.getMethodType(desc).getArgumentTypes();
            StringBuilder sb = new StringBuilder(name).append('(');
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(args[i].getClassName());
            }
            return sb.append(')').toString();
        } catch (Throwable ex) {
            return name;
        }
    }

    // ── Access flags ────────────────────────────────────────────────────────

    private static final int CLASS_MASK =
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_ABSTRACT |
                    Opcodes.ACC_INTERFACE | Opcodes.ACC_ANNOTATION | Opcodes.ACC_ENUM |
                    Opcodes.ACC_MODULE | Opcodes.ACC_SYNTHETIC;

    /** Читабельні флаги класу: {@code "public final abstract"}. */
    public static String classAccess(int access) {
        return accessString(access & CLASS_MASK);
    }

    private static final int MEMBER_MASK =
            Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED |
                    Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_ABSTRACT |
                    Opcodes.ACC_SYNCHRONIZED | Opcodes.ACC_NATIVE | Opcodes.ACC_VARARGS |
                    Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_TRANSIENT |
                    Opcodes.ACC_VOLATILE | Opcodes.ACC_ENUM;

    /** Читабельні флаги методу/поля: {@code "public static final"}. */
    public static String memberAccess(int access) {
        return accessString(access & MEMBER_MASK);
    }

    private static String accessString(int access) {
        StringBuilder sb = new StringBuilder();
        if ((access & Opcodes.ACC_PUBLIC) != 0)        sb.append("public ");
        if ((access & Opcodes.ACC_PRIVATE) != 0)       sb.append("private ");
        if ((access & Opcodes.ACC_PROTECTED) != 0)     sb.append("protected ");
        if ((access & Opcodes.ACC_STATIC) != 0)        sb.append("static ");
        if ((access & Opcodes.ACC_FINAL) != 0)         sb.append("final ");
        if ((access & Opcodes.ACC_SYNCHRONIZED) != 0)  sb.append("synchronized ");
        if ((access & Opcodes.ACC_VOLATILE) != 0)      sb.append("volatile ");
        if ((access & Opcodes.ACC_TRANSIENT) != 0)     sb.append("transient ");
        if ((access & Opcodes.ACC_NATIVE) != 0)        sb.append("native ");
        if ((access & Opcodes.ACC_ABSTRACT) != 0)      sb.append("abstract ");
        if ((access & Opcodes.ACC_VARARGS) != 0)       sb.append("varargs ");
        if ((access & Opcodes.ACC_BRIDGE) != 0)        sb.append("bridge ");
        if ((access & Opcodes.ACC_SYNTHETIC) != 0)     sb.append("synthetic ");
        if ((access & Opcodes.ACC_INTERFACE) != 0)     sb.append("interface ");
        if ((access & Opcodes.ACC_ANNOTATION) != 0)    sb.append("@interface ");
        if ((access & Opcodes.ACC_ENUM) != 0)          sb.append("enum ");
        if ((access & Opcodes.ACC_MODULE) != 0)        sb.append("module ");
        return sb.toString().trim();
    }

    // ── Іконки для дерева ───────────────────────────────────────────────────

    /** SVG-іконка (R.drawable) для класу за access flags. */
    public static int classIconRes(int access) {
        if ((access & Opcodes.ACC_ANNOTATION) != 0) return R.drawable.ic_bc_annotation;
        if ((access & Opcodes.ACC_INTERFACE) != 0)  return R.drawable.ic_bc_interface;
        if ((access & Opcodes.ACC_ENUM) != 0)       return R.drawable.ic_bc_enum;
        return R.drawable.ic_bc_class;
    }

    /** SVG-іконка для методу за access flags. */
    public static int methodIconRes(int access) {
        if ((access & Opcodes.ACC_PRIVATE) != 0)   return R.drawable.ic_bc_private;
        if ((access & Opcodes.ACC_PROTECTED) != 0) return R.drawable.ic_bc_protected;
        if ((access & Opcodes.ACC_PUBLIC) != 0)    return R.drawable.ic_bc_public;
        return R.drawable.ic_bc_package;
    }

    /** SVG-іконка для поля за access flags. */
    public static int fieldIconRes(int access) {
        if ((access & Opcodes.ACC_PRIVATE) != 0)   return R.drawable.ic_bc_field_private;
        if ((access & Opcodes.ACC_PROTECTED) != 0) return R.drawable.ic_bc_field_protected;
        if ((access & Opcodes.ACC_PUBLIC) != 0)    return R.drawable.ic_bc_field_public;
        return R.drawable.ic_bc_field_package;
    }

    // ── Версія класу ─────────────────────────────────────────────────────────

    private static final Map<Integer, String> VERSIONS = new LinkedHashMap<>();
    static {
        VERSIONS.put(45, "1.1");
        VERSIONS.put(46, "1.2");
        VERSIONS.put(47, "1.3");
        VERSIONS.put(48, "1.4");
        VERSIONS.put(49, "5");
        VERSIONS.put(50, "6");
        VERSIONS.put(51, "7");
        VERSIONS.put(52, "8");
        VERSIONS.put(53, "9");
        VERSIONS.put(54, "10");
        VERSIONS.put(55, "11");
        VERSIONS.put(56, "12");
        VERSIONS.put(57, "13");
        VERSIONS.put(58, "14");
        VERSIONS.put(59, "15");
        VERSIONS.put(60, "16");
        VERSIONS.put(61, "17");
        VERSIONS.put(62, "18");
        VERSIONS.put(63, "19");
        VERSIONS.put(64, "20");
        VERSIONS.put(65, "21");
        VERSIONS.put(66, "22");
    }

    /** major version → рядок {@code "Java 17 (61)"}. */
    public static String versionLabel(int major) {
        String label = VERSIONS.get(major);
        if (label == null) return "major " + major;
        return "Java " + label + " (" + major + ")";
    }
}
