package com.ccs.wiredroid.tools.bytecode;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Редагування байткоду: зміна інструкцій, полів, методів та запис назад у .class.
 */
public final class BytecodeEditor {

    private final ClassNode classNode;
    private final byte[] originalBytes;

    private BytecodeEditor(ClassNode classNode, byte[] originalBytes) {
        this.classNode = classNode;
        this.originalBytes = originalBytes;
    }

    public static BytecodeEditor parse(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);
        return new BytecodeEditor(cn, classBytes);
    }

    public ClassNode getClassNode() {
        return classNode;
    }

    public byte[] toBytes() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        classNode.accept(cw);
        return cw.toByteArray();
    }

    public byte[] toBytesNoFrames() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        classNode.accept(cw);
        return cw.toByteArray();
    }

    /** Замінює opcode інструкції за індексом у списку інструкцій методу. */
    public boolean replaceInsnOpcode(MethodNode mn, int insnIndex, int newOpcode) {
        AbstractInsnNode insn = getInsnAt(mn, insnIndex);
        if (insn == null) return false;
        int currentOpcode = insn.getOpcode();
        if (currentOpcode == newOpcode) return true;

        if (insn instanceof VarInsnNode) {
            ((VarInsnNode) insn).setOpcode(newOpcode);
        } else if (insn instanceof IntInsnNode) {
            ((IntInsnNode) insn).setOpcode(newOpcode);
        } else if (insn instanceof TypeInsnNode) {
            ((TypeInsnNode) insn).setOpcode(newOpcode);
        } else if (insn instanceof FieldInsnNode) {
            ((FieldInsnNode) insn).setOpcode(newOpcode);
        } else if (insn instanceof MethodInsnNode) {
            ((MethodInsnNode) insn).setOpcode(newOpcode);
        } else if (insn instanceof JumpInsnNode) {
            ((JumpInsnNode) insn).setOpcode(newOpcode);
        } else if (insn instanceof LdcInsnNode) {
            // LDC не має opcode змінного
            return false;
        } else if (insn instanceof InsnNode) {
            return false;
        } else if (insn instanceof IincInsnNode) {
            // IINC має фіксований opcode
            return false;
        } else if (insn instanceof MultiANewArrayInsnNode) {
            return false;
        } else if (insn instanceof TableSwitchInsnNode) {
            return false;
        }
        return true;
    }

    /** Замінює operand VarInsn (номер локальної змінної). */
    public boolean replaceVarInsn(MethodNode mn, int insnIndex, int newVar) {
        AbstractInsnNode insn = getInsnAt(mn, insnIndex);
        if (insn instanceof VarInsnNode) {
            ((VarInsnNode) insn).var = newVar;
            return true;
        }
        return false;
    }

    /** Замінює значення IINC. */
    public boolean replaceIinc(MethodNode mn, int insnIndex, int newVar, int newIncr) {
        AbstractInsnNode insn = getInsnAt(mn, insnIndex);
        if (insn instanceof IincInsnNode) {
            ((IincInsnNode) insn).var = newVar;
            ((IincInsnNode) insn).incr = newIncr;
            return true;
        }
        return false;
    }

    /** Замінює LDC-константу. */
    public boolean replaceLdc(MethodNode mn, int insnIndex, Object newCst) {
        AbstractInsnNode insn = getInsnAt(mn, insnIndex);
        if (insn instanceof LdcInsnNode) {
            ((LdcInsnNode) insn).cst = newCst;
            return true;
        }
        return false;
    }

    /** Замінює LDC-рядок. */
    public boolean replaceLdcString(MethodNode mn, int insnIndex, String newStr) {
        return replaceLdc(mn, insnIndex, newStr);
    }

    /** Замінює LDC числове значення (int). */
    public boolean replaceLdcInt(MethodNode mn, int insnIndex, int value) {
        return replaceLdc(mn, insnIndex, value);
    }

    /** Замінює LDC числове значення (float). */
    public boolean replaceLdcFloat(MethodNode mn, int insnIndex, float value) {
        return replaceLdc(mn, insnIndex, value);
    }

    /** Замінює LDC числове значення (long). */
    public boolean replaceLdcLong(MethodNode mn, int insnIndex, long value) {
        return replaceLdc(mn, insnIndex, value);
    }

    /** Замінює LDC числове значення (double). */
    public boolean replaceLdcDouble(MethodNode mn, int insnIndex, double value) {
        return replaceLdc(mn, insnIndex, value);
    }

    /** Видаляє інструкцію за індексом. */
    public boolean removeInsn(MethodNode mn, int insnIndex) {
        AbstractInsnNode insn = getInsnAt(mn, insnIndex);
        if (insn == null) return false;
        mn.instructions.remove(insn);
        return true;
    }

    /** Вставляє нову інструкцію перед заданою. */
    public void insertInsnBefore(MethodNode mn, int insnIndex, AbstractInsnNode newInsn) {
        AbstractInsnNode anchor = getInsnAt(mn, insnIndex);
        if (anchor != null) {
            mn.instructions.insertBefore(anchor, newInsn);
        }
    }

    /** Вставляє нову інструкцію після заданої. */
    public void insertInsnAfter(MethodNode mn, int insnIndex, AbstractInsnNode newInsn) {
        AbstractInsnNode anchor = getInsnAt(mn, insnIndex);
        if (anchor != null) {
            mn.instructions.insert(anchor, newInsn);
        }
    }

    /** Замінює всю інструкцію на нову. */
    public boolean replaceInsn(MethodNode mn, int insnIndex, AbstractInsnNode newInsn) {
        AbstractInsnNode old = getInsnAt(mn, insnIndex);
        if (old == null) return false;
        mn.instructions.set(old, newInsn);
        return true;
    }

    /** Змінює access-flags методу. */
    public void setMethodAccess(MethodNode mn, int newAccess) {
        mn.access = newAccess;
    }

    /** Змінює access-flags поля. */
    public void setFieldAccess(FieldNode fn, int newAccess) {
        fn.access = newAccess;
    }

    /** Змінює maxStack / maxLocals. */
    public void setMaxStackLocals(MethodNode mn, int maxStack, int maxLocals) {
        mn.maxStack = maxStack;
        mn.maxLocals = maxLocals;
    }

    /** Повертає список інструкцій методу. */
    public List<AbstractInsnNode> getMethodInsns(MethodNode mn) {
        List<AbstractInsnNode> list = new ArrayList<>();
        for (AbstractInsnNode n = mn.instructions.getFirst(); n != null; n = n.getNext()) {
            list.add(n);
        }
        return list;
    }

    /** Знаходить метод за іменем та дескриптором. */
    public MethodNode findMethod(String name, String desc) {
        for (MethodNode mn : classNode.methods) {
            if (mn.name.equals(name) && mn.desc.equals(desc)) return mn;
        }
        return null;
    }

    /** Знаходить поле за іменем. */
    public FieldNode findField(String name) {
        for (FieldNode fn : classNode.fields) {
            if (fn.name.equals(name)) return fn;
        }
        return null;
    }

    private static AbstractInsnNode getInsnAt(MethodNode mn, int index) {
        int i = 0;
        for (AbstractInsnNode n = mn.instructions.getFirst(); n != null; n = n.getNext()) {
            if (i == index) return n;
            i++;
        }
        return null;
    }

    /** Фабричні методи для створення нових інструкцій. */
    public static AbstractInsnNode newInsn(int opcode) {
        return new InsnNode(opcode);
    }

    public static AbstractInsnNode newVarInsn(int opcode, int var) {
        return new VarInsnNode(opcode, var);
    }

    public static AbstractInsnNode newIntInsn(int opcode, int operand) {
        return new IntInsnNode(opcode, operand);
    }

    public static AbstractInsnNode newLdcInsn(Object value) {
        return new LdcInsnNode(value);
    }

    public static AbstractInsnNode newFieldInsn(int opcode, String owner, String name, String desc) {
        return new FieldInsnNode(opcode, owner, name, desc);
    }

    public static AbstractInsnNode newMethodInsn(int opcode, String owner, String name, String desc) {
        return new MethodInsnNode(opcode, owner, name, desc);
    }

    public static AbstractInsnNode newJumpInsn(int opcode, LabelNode label) {
        return new JumpInsnNode(opcode, label);
    }

    public static AbstractInsnNode newTypeInsn(int opcode, String desc) {
        return new TypeInsnNode(opcode, desc);
    }

    public static LabelNode newLabel() {
        return new LabelNode();
    }
}
