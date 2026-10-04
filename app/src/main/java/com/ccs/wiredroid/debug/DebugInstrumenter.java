package com.ccs.wiredroid.debug;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DebugInstrumenter {

    private static final String BRIDGE = "com/ccs/wiredroid/debug/DebugBridge";

    public static Set<File> instrumentDirectory(File classDir, Set<Integer> breakpointLines) throws IOException {
        Set<File> instrumented = new HashSet<>();
        if (!classDir.exists()) return instrumented;
        File[] files = classDir.listFiles((dir, name) -> name.endsWith(".class"));
        if (files == null) return instrumented;
        for (File f : files) {
            if (instrumentFile(f, breakpointLines)) {
                instrumented.add(f);
            }
        }
        return instrumented;
    }

    public static boolean instrumentFile(File classFile, Set<Integer> breakpointLines) throws IOException {
        FileInputStream fis = new FileInputStream(classFile);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = fis.read(buf)) != -1) bos.write(buf, 0, n);
        fis.close();
        byte[] original = bos.toByteArray();
        byte[] result = instrument(original, breakpointLines);
        if (result != null) {
            FileOutputStream fos = new FileOutputStream(classFile);
            fos.write(result);
            fos.close();
            return true;
        }
        return false;
    }

    public static byte[] instrument(byte[] classBytes, Set<Integer> breakpointLines) {
        // Instrument ALL lines so that breakpoints can be set/changed dynamically
        // at runtime. DebugBridge.hitBreakpoint() checks the live breakpoint set.
        try {
            ClassReader cr = new ClassReader(classBytes);
            ClassNode cn = new ClassNode();
            cr.accept(cn, 0);

            boolean modified = false;
            for (MethodNode mn : cn.methods) {
                if (mn.instructions == null || mn.instructions.size() == 0) continue;
                if ((mn.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
                if (instrumentMethod(cn, mn, breakpointLines)) {
                    modified = true;
                }
            }

            if (!modified) return null;

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override
                protected String getCommonSuperClass(String type1, String type2) {
                    return "java/lang/Object";
                }
            };
            cn.accept(cw);
            return cw.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean instrumentMethod(ClassNode cn, MethodNode mn, Set<Integer> breakpointLines) {
        // Build map: line number → LabelNode (original, from instruction list)
        Map<Integer, LabelNode> lineToLabelNode = new HashMap<>();
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LineNumberNode) {
                LineNumberNode lnn = (LineNumberNode) insn;
                if (lnn.start instanceof LabelNode) {
                    lineToLabelNode.put(lnn.line, (LabelNode) lnn.start);
                }
            }
        }

        if (lineToLabelNode.isEmpty()) {
            return false;
        }

        // Обчислити глобальний порядковий індекс кожної LabelNode для порівняння scope.
        Map<LabelNode, Integer> labelOrder = new HashMap<>();
        int order = 0;
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode) labelOrder.put((LabelNode) insn, order++);
        }

        // Instrument ALL lines — dynamic breakpoint check happens in DebugBridge.hitBreakpoint()
        Set<Integer> linesToInstrument = new HashSet<>(lineToLabelNode.keySet());

        // Get local variable info
        String[] localNames = new String[mn.maxLocals];
        String[] localTypes = new String[mn.maxLocals];
        if (mn.localVariables != null) {
            for (LocalVariableNode lvn : mn.localVariables) {
                int idx = lvn.index;
                if (idx < localNames.length) {
                    localNames[idx] = lvn.name;
                    localTypes[idx] = lvn.desc;
                }
            }
        }

        Type[] argTypes = Type.getArgumentTypes(mn.desc);
        boolean isStatic = (mn.access & Opcodes.ACC_STATIC) != 0;
        int argIdx = isStatic ? 0 : 1;
        for (int i = 0; i < argTypes.length; i++) {
            if (argIdx < localNames.length && (localNames[argIdx] == null || localNames[argIdx].isEmpty())) {
                localNames[argIdx] = "arg" + i;
                localTypes[argIdx] = argTypes[i].getDescriptor();
            }
            argIdx += argTypes[i].getSize();
        }

        for (int i = 0; i < localNames.length; i++) {
            if (localNames[i] == null) localNames[i] = "local" + i;
            if (localTypes[i] == null) localTypes[i] = "Ljava/lang/Object;";
        }

        // For each line, insert a hook AFTER the label so DebugBridge can check dynamically
        int maxLocals = Math.max(mn.maxLocals, 4);
        List<AbstractInsnNode> toInsertAfter = new ArrayList<>();
        Set<Integer> inserted = new HashSet<>();

        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode) {
                LabelNode ln = (LabelNode) insn;
                for (Map.Entry<Integer, LabelNode> entry : lineToLabelNode.entrySet()) {
                    if (entry.getValue() == ln && !inserted.contains(entry.getKey())) {
                        toInsertAfter.add(insn);
                        inserted.add(entry.getKey());
                        break;
                    }
                }
            }
        }

        if (toInsertAfter.isEmpty()) return false;

        // Build hook instructions and insert them
        for (AbstractInsnNode target : toInsertAfter) {
            // Find which line this corresponds to
            int targetLine = -1;
            for (Map.Entry<Integer, LabelNode> entry : lineToLabelNode.entrySet()) {
                if (entry.getValue() == target) {
                    targetLine = entry.getKey();
                    break;
                }
            }
            if (targetLine < 0) continue;

            // Для кожного слоту локальної перевірити, чи target-Label входить у scope
            // змінної (між lvn.start та lvn.end). Якщо поза scope або target передує start,
            // слот може бути неініціалізованим (тип top) → emit null, бо *LOAD дасть VerifyError.
            boolean[] inScope = new boolean[maxLocals];
            if (mn.localVariables != null) {
                int targetOrder = labelOrder.getOrDefault((LabelNode) target, -1);
                for (LocalVariableNode lvn : mn.localVariables) {
                    int idx = lvn.index;
                    if (idx >= maxLocals) continue;
                    Integer startOrd = labelOrder.get(lvn.start);
                    Integer endOrd = labelOrder.get(lvn.end);
                    if (startOrd == null) continue;
                    int end = (endOrd == null) ? Integer.MAX_VALUE : endOrd;
                    if (targetOrder >= startOrd && targetOrder < end) {
                        inScope[idx] = true;
                    }
                }
                // у нестатичних методах/конструкторах slot 0 = this — доступний,
                // але в <init> до super() це uninitializedThis. buildHook emit null для slot 0
                // у конструкторах окремо, тож тут не відмічаємо.
            }

            InsnList hook = buildHook(mn, cn.name, targetLine,
                    localNames, localTypes, maxLocals, inScope);
            mn.instructions.insert(target, hook);
        }

        return true;
    }

    private static InsnList buildHook(MethodNode mn, String className, int line,
                                       String[] localNames, String[] localTypes,
                                       int maxLocals, boolean[] inScope) {
        InsnList hook = new InsnList();

        // Push arguments for DebugBridge.hitBreakpoint(String, String, String, String, int, String[], String[], Object[])
        hook.add(new org.objectweb.asm.tree.LdcInsnNode(className));
        hook.add(new org.objectweb.asm.tree.LdcInsnNode(className + ".java"));
        hook.add(new org.objectweb.asm.tree.LdcInsnNode(mn.name));
        hook.add(new org.objectweb.asm.tree.LdcInsnNode(mn.desc));
        pushInt(hook, line);

        // localNames array
        pushInt(hook, localNames.length);
        hook.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/String"));
        for (int i = 0; i < localNames.length; i++) {
            hook.add(new InsnNode(Opcodes.DUP));
            pushInt(hook, i);
            hook.add(new org.objectweb.asm.tree.LdcInsnNode(localNames[i]));
            hook.add(new InsnNode(Opcodes.AASTORE));
        }

        // localTypes array
        pushInt(hook, localTypes.length);
        hook.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/String"));
        for (int i = 0; i < localTypes.length; i++) {
            hook.add(new InsnNode(Opcodes.DUP));
            pushInt(hook, i);
            hook.add(new org.objectweb.asm.tree.LdcInsnNode(localTypes[i]));
            hook.add(new InsnNode(Opcodes.AASTORE));
        }

        // localValues array — реальні значення змінних з боксуванням примітивів.
        // Доступність слоту визначає inScope[] (обчислено в instrumentMethod за LocalVariableTable):
        // слот поза scope або this-до-super() → emit null (тип top → інакше VerifyError).
        // long/double займають 2 слоти.
        boolean isConstructor = "<init>".equals(mn.name);
        pushInt(hook, maxLocals);
        hook.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        for (int i = 0; i < maxLocals; i++) {
            String desc = (i < localTypes.length) ? localTypes[i] : null;
            boolean twoSlot = "J".equals(desc) || "D".equals(desc);

            // DUP масиву + індекс для AASTORE
            hook.add(new InsnNode(Opcodes.DUP));
            pushInt(hook, i);

            // Emit null якщо слот недоступний:
            //  - конструктор slot 0 (this до super() = uninitializedThis)
            //  - слот поза scope змінної (неініціалізований → тип top → VerifyError)
            boolean accessible = !(isConstructor && i == 0);
            if (accessible && inScope != null && i < inScope.length && !inScope[i]) {
                accessible = false;
            }
            if (!accessible) {
                hook.add(new InsnNode(Opcodes.ACONST_NULL));
                hook.add(new InsnNode(Opcodes.AASTORE));
                continue;
            }

            // emit *LOAD + боксинг примітивів → Object для AASTORE
            emitLocalLoad(hook, i, desc);
            hook.add(new InsnNode(Opcodes.AASTORE));

            // для long/double пропускаємо наступний слот (вони займають 2)
            if (twoSlot && i + 1 < maxLocals) i++;
        }

        // Call DebugBridge.hitBreakpoint
        hook.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE,
                "hitBreakpoint",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/Object;)V",
                false));

        return hook;
    }

    /**
     * Emit інструкції завантаження локальної змінної slot {@code index} з дескриптором {@code desc}
     * + боксування примітивів у відповідні wrapper-об'єкти (Object[] не приймає примітиви).
     * <ul>
     *   <li>I/Z/B/C/S → ILOAD → Integer/Boolean/Byte/Character/Short.valueOf</li>
     *   <li>J → LLOAD → Long.valueOf</li>
     *   <li>F → FLOAD → Float.valueOf</li>
     *   <li>D → DLOAD → Double.valueOf</li>
     *   <li>L...;/[... → ALOAD (вже Object)</li>
     * </ul>
     * Якщо {@code desc} null/невідомий → ALOAD (вважаємо посиланням).
     */
    private static void emitLocalLoad(InsnList hook, int index, String desc) {
        if (desc == null || desc.isEmpty() || desc.startsWith("L") || desc.startsWith("[")) {
            hook.add(new VarInsnNode(Opcodes.ALOAD, index));
            return;
        }
        switch (desc) {
            case "I":
                hook.add(new VarInsnNode(Opcodes.ILOAD, index));
                hook.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC,
                        "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
                break;
            case "Z":
                hook.add(new VarInsnNode(Opcodes.ILOAD, index));
                hook.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC,
                        "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false));
                break;
            case "B":
                hook.add(new VarInsnNode(Opcodes.ILOAD, index));
                hook.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC,
                        "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false));
                break;
            case "C":
                hook.add(new VarInsnNode(Opcodes.ILOAD, index));
                hook.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC,
                        "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false));
                break;
            case "S":
                hook.add(new VarInsnNode(Opcodes.ILOAD, index));
                hook.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC,
                        "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false));
                break;
            case "J":
                hook.add(new VarInsnNode(Opcodes.LLOAD, index));
                hook.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC,
                        "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false));
                break;
            case "F":
                hook.add(new VarInsnNode(Opcodes.FLOAD, index));
                hook.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC,
                        "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false));
                break;
            case "D":
                hook.add(new VarInsnNode(Opcodes.DLOAD, index));
                hook.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC,
                        "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false));
                break;
            default:
                // невідомий дескриптор — безпечне посилання
                hook.add(new VarInsnNode(Opcodes.ALOAD, index));
                break;
        }
    }

    private static void pushInt(InsnList list, int value) {
        if (value >= -1 && value <= 5) {
            list.add(new InsnNode(Opcodes.ICONST_0 + value));
        } else if (value >= -128 && value <= 127) {
            list.add(new org.objectweb.asm.tree.IntInsnNode(Opcodes.BIPUSH, value));
        } else if (value >= -32768 && value <= 32767) {
            list.add(new org.objectweb.asm.tree.IntInsnNode(Opcodes.SIPUSH, value));
        } else {
            list.add(new org.objectweb.asm.tree.LdcInsnNode(value));
        }
    }
}
