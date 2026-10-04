package com.ccs.wiredroid.tools.bytecode;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Структурна модель .class для потужного байткод-в'ювера.
 * Будується через ASM tree API (патерн як у {@code debug/DebugInstrumenter}).
 *
 * <p>Усі дані чисті (POJO) — без Android-залежностей — щоб рендерери могли
 * працювати з ними незалежно від UI.</p>
 */
public final class BytecodeModel {

    public final ClassInfo classInfo;
    public final List<FieldInfo> fields;
    public final List<MethodInfo> methods;
    public final byte[] rawBytes;

    private BytecodeModel(ClassInfo classInfo, List<FieldInfo> fields,
                          List<MethodInfo> methods, byte[] rawBytes) {
        this.classInfo = classInfo;
        this.fields = fields;
        this.methods = methods;
        this.rawBytes = rawBytes;
    }

    /** Розбирає байтки класу у модель. */
    public static BytecodeModel parse(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        ClassNode cn = new ClassNode();
        cr.accept(cn, ClassReader.SKIP_FRAMES);

        ClassInfo info = new ClassInfo(
                BytecodeFormatter.classAccess(cn.access),
                cn.access,
                BytecodeFormatter.className(cn.name.replace('/', '.')),
                cn.name,
                BytecodeFormatter.className(cn.superName == null ? "java.lang.Object" : cn.superName),
                cn.superName,
                toStrings(cn.interfaces),
                cn.signature,
                BytecodeFormatter.versionLabel(cn.version & 0xFFFF),
                cn.version & 0xFFFF);

        List<FieldInfo> fields = new ArrayList<>();
        if (cn.fields != null) {
            for (FieldNode fn : cn.fields) {
                fields.add(new FieldInfo(
                        BytecodeFormatter.memberAccess(fn.access),
                        fn.access,
                        fn.name,
                        fn.desc,
                        BytecodeFormatter.typeDesc(fn.desc),
                        fn.signature,
                        fn.value));
            }
        }

        List<MethodInfo> methods = new ArrayList<>();
        if (cn.methods != null) {
            for (MethodNode mn : cn.methods) {
                methods.add(buildMethod(mn));
            }
        }
        return new BytecodeModel(info, fields, methods, classBytes);
    }

    private static List<String> toStrings(List<?> list) {
        List<String> out = new ArrayList<>();
        if (list != null) {
            for (Object o : list) out.add(o == null ? "" : BytecodeFormatter.className(o.toString()));
        }
        return out;
    }

    private static MethodInfo buildMethod(MethodNode mn) {
        String access = BytecodeFormatter.memberAccess(mn.access);
        String signature = BytecodeFormatter.methodSignature(mn.name, mn.desc);
        String shortSig = BytecodeFormatter.methodShort(mn.name, mn.desc);

        List<LocalVar> locals = new ArrayList<>();
        if (mn.localVariables != null) {
            for (LocalVariableNode lvn : mn.localVariables) {
                locals.add(new LocalVar(lvn.index, lvn.name,
                        BytecodeFormatter.typeDesc(lvn.desc)));
            }
        }

        List<ExceptionHandler> handlers = new ArrayList<>();
        if (mn.tryCatchBlocks != null) {
            for (TryCatchBlockNode tcb : mn.tryCatchBlocks) {
                handlers.add(new ExceptionHandler(
                        labelIndex(mn, tcb.start),
                        labelIndex(mn, tcb.end),
                        labelIndex(mn, tcb.handler),
                        tcb.type == null ? "any" : BytecodeFormatter.className(tcb.type)));
            }
        }

        List<Instruction> insns = disassemble(mn);
        return new MethodInfo(access, mn.access, mn.name, mn.desc, signature, shortSig,
                mn.signature, insns, locals, handlers, mn.maxStack, mn.maxLocals);
    }

    // ── Головний цикл: обхід instructions з розрахунком byte-offset ──────────

    private static List<Instruction> disassemble(MethodNode mn) {
        List<Instruction> out = new ArrayList<>();
        if (mn.instructions == null) return out;

        // Спершу привласнюємо кожному LabelNode індекс, щоб на нього посилатися.
        Map<LabelNode, Integer> labelIds = new HashMap<>();
        int labelSeq = 0;
        for (AbstractInsnNode n = mn.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n instanceof LabelNode) {
                labelIds.put((LabelNode) n, labelSeq++);
            }
        }

        int offset = 0;
        boolean methodHasLabels = !labelIds.isEmpty();
        for (AbstractInsnNode n = mn.instructions.getFirst(); n != null; n = n.getNext()) {
            Instruction insn = renderInsn(n, labelIds, offset);
            int size = insnSize(n, offset);
            offset += size;

            // Додаємо мітку перед інструкцією, якщо вона тут стоїть.
            if (n instanceof LabelNode) {
                Integer id = labelIds.get(n);
                if (id != null) insn.label = "L" + id;
            }
            out.add(insn);
            // suppress warning for unused boolean
            if (!methodHasLabels && insn.offset < 0) { /* noop */ }
        }
        return out;
    }

    private static int labelIndex(MethodNode mn, LabelNode node) {
        if (node == null || mn.instructions == null) return -1;
        int idx = 0;
        for (AbstractInsnNode n = mn.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n == node) return idx;
            if (n instanceof LabelNode) idx++;
        }
        return -1;
    }

    // ── Рендер окремої інструкції ────────────────────────────────────────────

    private static Instruction renderInsn(AbstractInsnNode n,
                                          Map<LabelNode, Integer> labelIds, int offset) {
        int type = n.getType();
        int opcode = n.getOpcode();
        String opName = opcode == 0 ? typeName(type) : opcodeName(opcode);
        String operand = "";
        String comment = "";
        int tokenType = Token.OPCODE;

        if (n instanceof InsnNode) {
            // bare opcode (iconst_0, areturn, athrow …)
        } else if (n instanceof IntInsnNode) {
            IntInsnNode i = (IntInsnNode) n;
            operand = String.valueOf(i.operand);
            if (opcode == Opcodes.NEWARRAY) {
                comment = newArrayType(i.operand);
            } else {
                tokenType = Token.NUMBER;
            }
        } else if (n instanceof VarInsnNode) {
            VarInsnNode v = (VarInsnNode) n;
            operand = String.valueOf(v.var);
            comment = "var" + v.var;
        } else if (n instanceof TypeInsnNode) {
            TypeInsnNode t = (TypeInsnNode) n;
            operand = '"' + BytecodeFormatter.className(t.desc) + '"';
            tokenType = Token.TYPE;
        } else if (n instanceof FieldInsnNode) {
            FieldInsnNode f = (FieldInsnNode) n;
            operand = BytecodeFormatter.className(f.owner) + "." + f.name;
            comment = "// field : " + BytecodeFormatter.typeDesc(f.desc);
            tokenType = Token.FIELD;
        } else if (n instanceof MethodInsnNode) {
            MethodInsnNode m = (MethodInsnNode) n;
            operand = BytecodeFormatter.className(m.owner) + "." + m.name;
            comment = "// " + BytecodeFormatter.methodSignature(m.name, m.desc)
                    + (m.itf ? "  [interface]" : "");
            tokenType = Token.METHOD;
        } else if (n instanceof InvokeDynamicInsnNode) {
            InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) n;
            operand = indy.name;
            comment = "// invokedynamic  bsm=" + BytecodeFormatter.className(indy.bsm.getOwner())
                    + "." + indy.bsm.getName();
            tokenType = Token.METHOD;
        } else if (n instanceof JumpInsnNode) {
            JumpInsnNode j = (JumpInsnNode) n;
            Integer id = labelIds.get(j.label);
            operand = id != null ? "L" + id : "?";
            tokenType = Token.LABEL;
        } else if (n instanceof LdcInsnNode) {
            LdcInsnNode ldc = (LdcInsnNode) n;
            Object cst = ldc.cst;
            if (cst instanceof String) {
                operand = stringLiteral((String) cst, 60);
                comment = "// String";
                tokenType = Token.STRING;
            } else if (cst instanceof Type) {
                operand = ((Type) cst).getClassName() + ".class";
                tokenType = Token.TYPE;
            } else if (cst instanceof Float || cst instanceof Double) {
                operand = String.valueOf(cst);
                comment = "// " + (cst instanceof Float ? "float" : "double");
                tokenType = Token.NUMBER;
            } else if (cst instanceof Long) {
                operand = cst + "L";
                tokenType = Token.NUMBER;
            } else if (cst instanceof Integer || cst instanceof Short || cst instanceof Byte) {
                operand = String.valueOf(cst);
                tokenType = Token.NUMBER;
            } else {
                operand = String.valueOf(cst);
            }
        } else if (n instanceof IincInsnNode) {
            IincInsnNode inc = (IincInsnNode) n;
            operand = inc.var + ", " + (inc.incr >= 0 ? "+" : "") + inc.incr;
            comment = "var" + inc.var + " += " + inc.incr;
            tokenType = Token.NUMBER;
        } else if (n instanceof TableSwitchInsnNode) {
            TableSwitchInsnNode sw = (TableSwitchInsnNode) n;
            int lo = sw.min, hi = sw.max;
            operand = "[" + lo + " .. " + hi + "]";
            StringBuilder cb = new StringBuilder("// default L").append(labelIds.get(sw.dflt));
            int cases = hi - lo + 1;
            for (int i = 0; i < cases && i < sw.labels.size(); i++) {
                Integer lid = labelIds.get(sw.labels.get(i));
                cb.append("  ").append(lo + i).append("→L").append(lid);
                if (cb.length() > 200) { cb.append(" …"); break; }
            }
            comment = cb.toString();
            tokenType = Token.LABEL;
        } else if (n instanceof LookupSwitchInsnNode) {
            LookupSwitchInsnNode sw = (LookupSwitchInsnNode) n;
            StringBuilder ob = new StringBuilder();
            StringBuilder cb = new StringBuilder("// default L").append(labelIds.get(sw.dflt));
            int n2 = Math.min(sw.keys.size(), sw.labels.size());
            for (int i = 0; i < n2; i++) {
                if (i > 0) ob.append(", ");
                ob.append(sw.keys.get(i));
                Integer lid = labelIds.get(sw.labels.get(i));
                cb.append("  ").append(sw.keys.get(i)).append("→L").append(lid);
                if (cb.length() > 200) { cb.append(" …"); break; }
            }
            operand = ob.toString();
            comment = cb.toString();
            tokenType = Token.LABEL;
        } else if (n instanceof MultiANewArrayInsnNode) {
            MultiANewArrayInsnNode ma = (MultiANewArrayInsnNode) n;
            operand = '"' + BytecodeFormatter.className(ma.desc) + "\" dims=" + ma.dims;
            tokenType = Token.TYPE;
        } else if (n instanceof LineNumberNode) {
            LineNumberNode ln = (LineNumberNode) n;
            return new Instruction(offset, "", "", "",
                    "// line " + ln.line, Token.META, -1, ln.line, null);
        } else if (n instanceof LabelNode) {
            Integer id = labelIds.get(n);
            String lbl = id != null ? "L" + id : "L?";
            return new Instruction(offset, lbl + ":", "", "",
                    "", Token.LABEL_DECL, -1, -1, null);
        } else if (n instanceof FrameNode) {
            FrameNode fr = (FrameNode) n;
            return new Instruction(offset, "", "", "",
                    "// frame type=" + fr.type, Token.META, -1, -1, null);
        }

        int sourceLine = -1;
        return new Instruction(offset, opName, operand, comment,
                comment, tokenType, -1, sourceLine, null);
    }

    private static String stringLiteral(String s, int max) {
        if (s.length() > max) {
            s = s.substring(0, max) + "…";
        }
        // екранування
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static String newArrayType(int operand) {
        switch (operand) {
            case Opcodes.T_BOOLEAN: return "boolean[]";
            case Opcodes.T_CHAR:    return "char[]";
            case Opcodes.T_FLOAT:   return "float[]";
            case Opcodes.T_DOUBLE:  return "double[]";
            case Opcodes.T_BYTE:    return "byte[]";
            case Opcodes.T_SHORT:   return "short[]";
            case Opcodes.T_INT:     return "int[]";
            case Opcodes.T_LONG:    return "long[]";
            default: return "?[" + operand + "]";
        }
    }

    private static String typeName(int type) {
        switch (type) {
            case AbstractInsnNode.LABEL:       return "label";
            case AbstractInsnNode.LINE:        return "line";
            case AbstractInsnNode.FRAME:       return "frame";
            default: return "?";
        }
    }

    private static String opcodeName(int opcode) {
        // Opcodes не надає масиву імен; будуємо через ASM-внутрішнє відображення.
        return OPCODE_NAMES[opcode & 0xFF];
    }

    // ── Розмір інструкції у байтах (для offset-калькулятора) ─────────────────
    // За специфікацією JVM (без wide-форм). TABLESWITCH/LOOKUPSWITCH мають змінну
    // довжину з padding — повертаємо 1 як наближення (offset колонка є косметикою).
    // Увага: у ASM public API немає констант для _0/_1/_2/_3 варіантів (ALOAD_0 тощо),
    // тому покладаємося на default=1 байт для них.

    private static int insnSize(AbstractInsnNode n, int offset) {
        int opcode = n.getOpcode();
        switch (opcode) {
            // ── 2 байти: опкод + 1-байтний операнд ──
            case Opcodes.BIPUSH: case Opcodes.LDC:
            case Opcodes.ILOAD: case Opcodes.LLOAD: case Opcodes.FLOAD:
            case Opcodes.DLOAD: case Opcodes.ALOAD:
            case Opcodes.ISTORE: case Opcodes.LSTORE: case Opcodes.FSTORE:
            case Opcodes.DSTORE: case Opcodes.ASTORE:
            case Opcodes.RET: case Opcodes.NEWARRAY:
                return 2;
            // ── 3 байти: опкод + 2-байтний операнд ──
            case Opcodes.SIPUSH: case Opcodes.IINC:
            case Opcodes.GETSTATIC: case Opcodes.GETFIELD:
            case Opcodes.PUTFIELD: case Opcodes.PUTSTATIC:
            case Opcodes.INVOKEVIRTUAL: case Opcodes.INVOKESPECIAL:
            case Opcodes.INVOKESTATIC: case Opcodes.NEW:
            case Opcodes.ANEWARRAY: case Opcodes.CHECKCAST: case Opcodes.INSTANCEOF:
            case Opcodes.IFEQ: case Opcodes.IFNE: case Opcodes.IFLT: case Opcodes.IFGE:
            case Opcodes.IFGT: case Opcodes.IFLE:
            case Opcodes.IF_ICMPEQ: case Opcodes.IF_ICMPNE:
            case Opcodes.IF_ICMPLT: case Opcodes.IF_ICMPGE:
            case Opcodes.IF_ICMPGT: case Opcodes.IF_ICMPLE:
            case Opcodes.IF_ACMPEQ: case Opcodes.IF_ACMPNE:
            case Opcodes.IFNULL: case Opcodes.IFNONNULL:
            case Opcodes.GOTO: case Opcodes.JSR:
                return 3;
            // ── 4 байти ──
            case Opcodes.MULTIANEWARRAY:
                return 4;
            // ── 5 байтів: interface/dynamic + wide branches (GOTO_W=0xC8, JSR_W=0xC9) ──
            case Opcodes.INVOKEINTERFACE: case Opcodes.INVOKEDYNAMIC:
            case 0xC8: case 0xC9:
                return 5;
            default:
                // псевдоінструкції (LABEL/LINE/FRAME) не займають байтів;
                // усі інші — 1 байт (включно з *_0/_1/_2/_3 варіантами).
                int t = n.getType();
                if (t == AbstractInsnNode.LABEL || t == AbstractInsnNode.LINE
                        || t == AbstractInsnNode.FRAME) {
                    return 0;
                }
                return 1;
        }
    }

    // ── Імена опкодів ────────────────────────────────────────────────────────

    /** Кеш імен опкодів за числовим кодом (0..255). */
    private static final String[] OPCODE_NAMES = buildOpcodeNames();

    private static String[] buildOpcodeNames() {
        String[] n = new String[256];
        for (int i = 0; i < 256; i++) n[i] = "op_" + i;
        // Дескриптори _0/_1/_2/_3 варіантів відсутні в ASM public API (Opcodes),
        // тому використовуємо числові коди з JVM-специфікації (JVMS Table 6.5).
        n[Opcodes.NOP]             = "nop";
        n[Opcodes.ACONST_NULL]     = "aconst_null";
        n[Opcodes.ICONST_M1]       = "iconst_m1";
        n[Opcodes.ICONST_0]        = "iconst_0";
        n[Opcodes.ICONST_1]        = "iconst_1";
        n[Opcodes.ICONST_2]        = "iconst_2";
        n[Opcodes.ICONST_3]        = "iconst_3";
        n[Opcodes.ICONST_4]        = "iconst_4";
        n[Opcodes.ICONST_5]        = "iconst_5";
        n[Opcodes.LCONST_0]        = "lconst_0";
        n[Opcodes.LCONST_1]        = "lconst_1";
        n[Opcodes.FCONST_0]        = "fconst_0";
        n[Opcodes.FCONST_1]        = "fconst_1";
        n[Opcodes.FCONST_2]        = "fconst_2";
        n[Opcodes.DCONST_0]        = "dconst_0";
        n[Opcodes.DCONST_1]        = "dconst_1";
        n[Opcodes.BIPUSH]          = "bipush";
        n[Opcodes.SIPUSH]          = "sipush";
        n[Opcodes.LDC]             = "ldc";
        n[Opcodes.ILOAD]           = "iload";
        n[Opcodes.LLOAD]           = "lload";
        n[Opcodes.FLOAD]           = "fload";
        n[Opcodes.DLOAD]           = "dload";
        n[Opcodes.ALOAD]           = "aload";
        // 0x1A..0x2D — load/store *_0..*_3 (числові коди JVMS)
        n[0x1A]= "iload_0";  n[0x1B]= "iload_1";  n[0x1C]= "iload_2";  n[0x1D]= "iload_3";
        n[0x1E]= "lload_0";  n[0x1F]= "lload_1";  n[0x20]= "lload_2";  n[0x21]= "lload_3";
        n[0x22]= "fload_0";  n[0x23]= "fload_1";  n[0x24]= "fload_2";  n[0x25]= "fload_3";
        n[0x26]= "dload_0";  n[0x27]= "dload_1";  n[0x28]= "dload_2";  n[0x29]= "dload_3";
        n[0x2A]= "aload_0";  n[0x2B]= "aload_1";  n[0x2C]= "aload_2";  n[0x2D]= "aload_3";
        n[Opcodes.IALOAD]   = "iaload";   n[Opcodes.LALOAD]  = "laload";
        n[Opcodes.FALOAD]   = "faload";   n[Opcodes.DALOAD]  = "daload";
        n[Opcodes.AALOAD]   = "aaload";   n[Opcodes.BALOAD]  = "baload";
        n[Opcodes.CALOAD]   = "caload";   n[Opcodes.SALOAD]  = "saload";
        n[Opcodes.ISTORE]   = "istore";   n[Opcodes.LSTORE]  = "lstore";
        n[Opcodes.FSTORE]   = "fstore";   n[Opcodes.DSTORE]  = "dstore";
        n[Opcodes.ASTORE]   = "astore";
        n[0x3B]= "istore_0"; n[0x3C]= "istore_1"; n[0x3D]= "istore_2"; n[0x3E]= "istore_3";
        n[0x3F]= "lstore_0"; n[0x40]= "lstore_1"; n[0x41]= "lstore_2"; n[0x42]= "lstore_3";
        n[0x43]= "fstore_0"; n[0x44]= "fstore_1"; n[0x45]= "fstore_2"; n[0x46]= "fstore_3";
        n[0x47]= "dstore_0"; n[0x48]= "dstore_1"; n[0x49]= "dstore_2"; n[0x4A]= "dstore_3";
        n[0x4B]= "astore_0"; n[0x4C]= "astore_1"; n[0x4D]= "astore_2"; n[0x4E]= "astore_3";
        n[Opcodes.IASTORE]  = "iastore";  n[Opcodes.LASTORE] = "lastore";
        n[Opcodes.FASTORE]  = "fastore";  n[Opcodes.DASTORE] = "dastore";
        n[Opcodes.AASTORE]  = "aastore";  n[Opcodes.BASTORE] = "bastore";
        n[Opcodes.CASTORE]  = "castore";  n[Opcodes.SASTORE] = "sastore";
        n[Opcodes.POP]      = "pop";      n[Opcodes.POP2]    = "pop2";
        n[Opcodes.DUP]      = "dup";      n[Opcodes.DUP_X1]  = "dup_x1";
        n[Opcodes.DUP_X2]   = "dup_x2";   n[Opcodes.DUP2]    = "dup2";
        n[Opcodes.DUP2_X1]  = "dup2_x1";  n[Opcodes.DUP2_X2] = "dup2_x2";
        n[Opcodes.SWAP]     = "swap";
        n[Opcodes.IADD] = "iadd";  n[Opcodes.LADD] = "ladd";  n[Opcodes.FADD] = "fadd";  n[Opcodes.DADD] = "dadd";
        n[Opcodes.ISUB] = "isub";  n[Opcodes.LSUB] = "lsub";  n[Opcodes.FSUB] = "fsub";  n[Opcodes.DSUB] = "dsub";
        n[Opcodes.IMUL] = "imul";  n[Opcodes.LMUL] = "lmul";  n[Opcodes.FMUL] = "fmul";  n[Opcodes.DMUL] = "dmul";
        n[Opcodes.IDIV] = "idiv";  n[Opcodes.LDIV] = "ldiv";  n[Opcodes.FDIV] = "fdiv";  n[Opcodes.DDIV] = "ddiv";
        n[Opcodes.IREM] = "irem";  n[Opcodes.LREM] = "lrem";  n[Opcodes.FREM] = "frem";  n[Opcodes.DREM] = "drem";
        n[Opcodes.INEG] = "ineg";  n[Opcodes.LNEG] = "lneg";  n[Opcodes.FNEG] = "fneg";  n[Opcodes.DNEG] = "dneg";
        n[Opcodes.ISHL] = "ishl";  n[Opcodes.LSHL] = "lshl";  n[Opcodes.ISHR] = "ishr";  n[Opcodes.LSHR] = "lshr";
        n[Opcodes.IUSHR]= "iushr"; n[Opcodes.LUSHR]= "lushr"; n[Opcodes.IAND] = "iand";  n[Opcodes.LAND] = "land";
        n[Opcodes.IOR]  = "ior";   n[Opcodes.LOR]  = "lor";   n[Opcodes.IXOR] = "ixor";  n[Opcodes.LXOR] = "lxor";
        n[Opcodes.IINC] = "iinc";  // 132 (0x84)
        n[Opcodes.NEWARRAY] = "newarray";  // 188 (0xBC)
        n[Opcodes.I2L] = "i2l";  n[Opcodes.I2F] = "i2f";  n[Opcodes.I2D] = "i2d";
        n[Opcodes.L2I] = "l2i";  n[Opcodes.L2F] = "l2f";  n[Opcodes.L2D] = "l2d";
        n[Opcodes.F2I] = "f2i";  n[Opcodes.F2L] = "f2l";  n[Opcodes.F2D] = "f2d";
        n[Opcodes.D2I] = "d2i";  n[Opcodes.D2L] = "d2l";  n[Opcodes.D2F] = "d2f";
        n[Opcodes.I2B] = "i2b";  n[Opcodes.I2C] = "i2c";  n[Opcodes.I2S] = "i2s";
        n[Opcodes.LCMP]  = "lcmp";
        n[Opcodes.FCMPL] = "fcmpl";  n[Opcodes.FCMPG] = "fcmpg";
        n[Opcodes.DCMPL] = "dcmpl";  n[Opcodes.DCMPG] = "dcmpg";
        n[Opcodes.IFEQ]  = "ifeq";   n[Opcodes.IFNE]  = "ifne";
        n[Opcodes.IFLT]  = "iflt";   n[Opcodes.IFGE]  = "ifge";
        n[Opcodes.IFGT]  = "ifgt";   n[Opcodes.IFLE]  = "ifle";
        n[Opcodes.IF_ICMPEQ] = "if_icmpeq";  n[Opcodes.IF_ICMPNE] = "if_icmpne";
        n[Opcodes.IF_ICMPLT] = "if_icmplt";  n[Opcodes.IF_ICMPGE] = "if_icmpge";
        n[Opcodes.IF_ICMPGT] = "if_icmpgt";  n[Opcodes.IF_ICMPLE] = "if_icmple";
        n[Opcodes.IF_ACMPEQ] = "if_acmpeq";  n[Opcodes.IF_ACMPNE] = "if_acmpne";
        n[Opcodes.GOTO]  = "goto";   n[Opcodes.JSR]  = "jsr";   n[Opcodes.RET] = "ret";
        n[Opcodes.TABLESWITCH]  = "tableswitch";
        n[Opcodes.LOOKUPSWITCH] = "lookupswitch";
        n[Opcodes.IRETURN] = "ireturn";  n[Opcodes.LRETURN] = "lreturn";
        n[Opcodes.FRETURN] = "freturn";  n[Opcodes.DRETURN] = "dreturn";
        n[Opcodes.ARETURN] = "areturn";  n[Opcodes.RETURN]  = "return";
        n[Opcodes.GETSTATIC]     = "getstatic";  n[Opcodes.PUTSTATIC] = "putstatic";
        n[Opcodes.GETFIELD]      = "getfield";   n[Opcodes.PUTFIELD]  = "putfield";
        n[Opcodes.INVOKEVIRTUAL] = "invokevirtual";
        n[Opcodes.INVOKESPECIAL] = "invokespecial";
        n[Opcodes.INVOKESTATIC]  = "invokestatic";
        n[Opcodes.INVOKEINTERFACE] = "invokeinterface";
        n[Opcodes.INVOKEDYNAMIC]   = "invokedynamic";
        n[Opcodes.NEW]         = "new";
        n[Opcodes.NEWARRAY]    = "newarray";
        n[Opcodes.ANEWARRAY]   = "anewarray";
        n[Opcodes.ARRAYLENGTH] = "arraylength";
        n[Opcodes.ATHROW]      = "athrow";
        n[Opcodes.CHECKCAST]   = "checkcast";
        n[Opcodes.INSTANCEOF]  = "instanceof";
        n[Opcodes.MONITORENTER]= "monitorenter";
        n[Opcodes.MONITOREXIT] = "monitorexit";
        n[Opcodes.MULTIANEWARRAY] = "multianewarray";
        n[Opcodes.IFNULL]      = "ifnull";     n[Opcodes.IFNONNULL] = "ifnonnull";
        n[0xC8] = "goto_w";   n[0xC9] = "jsr_w";
        return n;
    }

    // ── POJO-модель ──────────────────────────────────────────────────────────

    public static final class ClassInfo {
        public final String accessText;
        public final int access;
        public final String name;        // java.lang.String форма
        public final String internalName;
        public final String superName;
        public final String superInternal;
        public final List<String> interfaces;
        public final String signature;
        public final String versionLabel;
        public final int version;

        ClassInfo(String accessText, int access, String name, String internalName,
                  String superName, String superInternal, List<String> interfaces,
                  String signature, String versionLabel, int version) {
            this.accessText = accessText;
            this.access = access;
            this.name = name;
            this.internalName = internalName;
            this.superName = superName;
            this.superInternal = superInternal;
            this.interfaces = interfaces;
            this.signature = signature;
            this.versionLabel = versionLabel;
            this.version = version;
        }
    }

    public static final class FieldInfo {
        public final String accessText;
        public final int access;
        public final String name;
        public final String desc;
        public final String typeText;
        public final String signature;
        public final Object value;

        FieldInfo(String accessText, int access, String name, String desc,
                  String typeText, String signature, Object value) {
            this.accessText = accessText;
            this.access = access;
            this.name = name;
            this.desc = desc;
            this.typeText = typeText;
            this.signature = signature;
            this.value = value;
        }

        @Override public String toString() {
            return accessText + " " + typeText + " " + name;
        }
    }

    public static final class MethodInfo {
        public final String accessText;
        public final int access;
        public final String name;
        public final String desc;
        public final String signatureText;   // main(String[]) : void
        public final String shortText;       // main(String[])
        public final String genericSignature;
        public final List<Instruction> instructions;
        public final List<LocalVar> locals;
        public final List<ExceptionHandler> handlers;
        public final int maxStack;
        public final int maxLocals;

        MethodInfo(String accessText, int access, String name, String desc,
                   String signatureText, String shortText, String genericSignature,
                   List<Instruction> instructions, List<LocalVar> locals,
                   List<ExceptionHandler> handlers, int maxStack, int maxLocals) {
            this.accessText = accessText;
            this.access = access;
            this.name = name;
            this.desc = desc;
            this.signatureText = signatureText;
            this.shortText = shortText;
            this.genericSignature = genericSignature;
            this.instructions = instructions;
            this.locals = locals;
            this.handlers = handlers;
            this.maxStack = maxStack;
            this.maxLocals = maxLocals;
        }

        @Override public String toString() { return signatureText; }
    }

    public static final class Instruction {
        public int offset;          // byte offset у коді методу
        public String opcode;       // "iconst_1" / "L0:" / "// line 5"
        public String operand;      // "L3" / "\"Hello\"" / "java/lang/System.out"
        public String comment;      // розшифровка для користувача
        public int tokenType;       // Token.*
        public int argIndex;        // для VarInsn (номер локальної)
        public int sourceLine;      // з LineNumberNode, -1 якщо невідомо
        public String label;        // "L3", якщо перед інструкцією стоїть мітка

        Instruction(int offset, String opcode, String operand, String comment,
                    String fullComment, int tokenType, int argIndex, int sourceLine,
                    String label) {
            this.offset = offset;
            this.opcode = opcode;
            this.operand = operand;
            this.comment = fullComment;
            this.tokenType = tokenType;
            this.argIndex = argIndex;
            this.sourceLine = sourceLine;
            this.label = label;
        }
    }

    public static final class LocalVar {
        public final int index;
        public final String name;
        public final String type;
        LocalVar(int index, String name, String type) {
            this.index = index; this.name = name; this.type = type;
        }
    }

    public static final class ExceptionHandler {
        public final int startLabel;
        public final int endLabel;
        public final int handlerLabel;
        public final String type;
        ExceptionHandler(int startLabel, int endLabel, int handlerLabel, String type) {
            this.startLabel = startLabel;
            this.endLabel = endLabel;
            this.handlerLabel = handlerLabel;
            this.type = type;
        }
    }

    /** Типи токенів для підсвічування (мапляться на кольори теми в адаптері). */
    public static final class Token {
        public static final int META        = 0;  // line/Frame (сірий)
        public static final int OPCODE      = 1;  // опкод
        public static final int NUMBER      = 2;  // числовий операнд
        public static final int TYPE        = 3;  // тип
        public static final int STRING      = 4;  // рядок
        public static final int FIELD       = 5;  // поле
        public static final int METHOD      = 6;  // метод
        public static final int LABEL       = 7;  // jump-мітка
        public static final int LABEL_DECL  = 8;  // оголошення мітки
    }

    /**
     * Apply ProGuard/R8 deobfuscation to this model.
     * Returns a new model with deobfuscated names (or this if no deobfuscator).
     */
    public BytecodeModel deobfuscate(Deobfuscator deobfuscator) {
        if (deobfuscator == null || !deobfuscator.hasMapping()) return this;

        // Deobfuscate class info
        ClassInfo newClassInfo = new ClassInfo(
                classInfo.accessText,
                classInfo.access,
                deobfuscator.className(classInfo.name),
                classInfo.internalName,
                deobfuscator.className(classInfo.superName),
                classInfo.superInternal,
                classInfo.interfaces,
                classInfo.signature,
                classInfo.versionLabel,
                classInfo.version);

        // Deobfuscate fields
        List<FieldInfo> newFields = new ArrayList<>();
        for (FieldInfo f : fields) {
            String deobfName = deobfuscator.fieldName(classInfo.internalName, f.name);
            String deobfOwner = deobfuscator.getOriginalClassName(classInfo.internalName);
            if (deobfOwner == null) deobfOwner = classInfo.internalName;
            newFields.add(new FieldInfo(
                    f.accessText,
                    f.access,
                    deobfName,
                    f.desc,
                    f.typeText,
                    f.signature,
                    f.value));
        }

        // Deobfuscate methods and their instructions
        List<MethodInfo> newMethods = new ArrayList<>();
        for (MethodInfo m : methods) {
            String deobfName = deobfuscator.methodName(
                    classInfo.internalName, m.name, m.desc);
            String deobfOwner = deobfuscator.getOriginalClassName(classInfo.internalName);
            if (deobfOwner == null) deobfOwner = classInfo.internalName;

            List<Instruction> newInsns = new ArrayList<>();
            for (Instruction insn : m.instructions) {
                String newOperand = insn.operand;
                String newComment = insn.comment;

                if (insn.tokenType == Token.METHOD && insn.operand != null) {
                    // Try to deobfuscate method call target
                    int dotIdx = insn.operand.indexOf('.');
                    if (dotIdx > 0) {
                        String owner = insn.operand.substring(0, dotIdx);
                        String methName = insn.operand.substring(dotIdx + 1);
                        String deobfMethodOwner = deobfuscator.className(owner);
                        String deobfMethodName = deobfuscator.methodName(owner, methName, "");
                        if (deobfMethodOwner != null || deobfMethodName != null) {
                            newOperand = (deobfMethodOwner != null ? deobfMethodOwner : owner)
                                    + "." + (deobfMethodName != null ? deobfMethodName : methName);
                        }
                    }
                } else if (insn.tokenType == Token.FIELD && insn.operand != null) {
                    int dotIdx = insn.operand.indexOf('.');
                    if (dotIdx > 0) {
                        String owner = insn.operand.substring(0, dotIdx);
                        String fieldName = insn.operand.substring(dotIdx + 1);
                        String deobfFieldOwner = deobfuscator.className(owner);
                        String deobfFieldName = deobfuscator.fieldName(owner, fieldName);
                        if (deobfFieldOwner != null || deobfFieldName != null) {
                            newOperand = (deobfFieldOwner != null ? deobfFieldOwner : owner)
                                    + "." + (deobfFieldName != null ? deobfFieldName : fieldName);
                        }
                    }
                }

                newInsns.add(new Instruction(insn.offset, insn.opcode, newOperand,
                        insn.comment, insn.comment, insn.tokenType, insn.argIndex,
                        insn.sourceLine, insn.label));
            }

            newMethods.add(new MethodInfo(
                    m.accessText,
                    m.access,
                    deobfName,
                    m.desc,
                    m.signatureText,
                    m.shortText,
                    m.genericSignature,
                    newInsns,
                    m.locals,
                    m.handlers,
                    m.maxStack,
                    m.maxLocals));
        }

        return new BytecodeModel(newClassInfo, newFields, newMethods, rawBytes);
    }
}
