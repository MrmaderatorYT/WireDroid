package com.ccs.wiredroid.tools.bytecode;

import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Darcula-like highlighting for ASM Textifier output (opcodes, types, numbers, comments).
 */
public final class BytecodeHighlighter {

    private static final int CLR_COMMENT   = 0xFF808080;
    private static final int CLR_OPCODE    = 0xFFCC7832;
    private static final int CLR_NUMBER      = 0xFF6897BB;
    private static final int CLR_TYPE      = 0xFF6A8759;
    private static final int CLR_DIRECTIVE = 0xFF9876AA;

    private static final Set<String> OPCODES = new HashSet<>();

    static {
        String ops = "aaload,aastore,aconst_null,aload,aload_0,aload_1,aload_2,aload_3,anewarray,areturn,"
                + "arraylength,astore,astore_0,astore_1,astore_2,astore_3,athrow,baload,bastore,bipush,breakpoint,"
                + "caload,castore,checkcast,d2f,d2i,d2l,dadd,daload,dastore,dcmpg,dcmpl,dconst_0,dconst_1,ddiv,"
                + "dload,dload_0,dload_1,dload_2,dload_3,dmul,dneg,drem,dreturn,dstore,dstore_0,dstore_1,dstore_2,"
                + "dstore_3,dsub,dup,dup_x1,dup_x2,dup2,dup2_x1,dup2_x2,f2d,f2i,f2l,fadd,faload,fastore,fcmpg,fcmpl,"
                + "fconst_0,fconst_1,fdiv,fload,fload_0,fload_1,fload_2,fload_3,fmul,fneg,frem,freturn,fstore,"
                + "fstore_0,fstore_1,fstore_2,fstore_3,fsub,getfield,getstatic,goto,goto_w,i2b,i2c,i2d,i2f,i2l,i2s,"
                + "iadd,iaload,iand,iastore,iconst_m1,iconst_0,iconst_1,iconst_2,iconst_3,iconst_4,iconst_5,idiv,"
                + "if_acmpeq,if_acmpne,if_icmpeq,if_icmpne,if_icmplt,if_icmpge,if_icmpgt,if_icmple,ifeq,ifne,iflt,"
                + "ifge,ifgt,ifle,ifnonnull,ifnull,iinc,iload,iload_0,iload_1,iload_2,iload_3,imul,ineg,instanceof,"
                + "invokedynamic,invokeinterface,invokespecial,invokestatic,invokevirtual,ior,irem,ireturn,ishl,"
                + "ishr,istore,istore_0,istore_1,istore_2,istore_3,isub,iushr,ixor,jsr,jsr_w,l2d,l2f,l2i,ladd,laload,"
                + "land,lastore,lcmp,lconst_0,lconst_1,ldc,ldc_w,ldc2_w,ldiv,lload,lload_0,lload_1,lload_2,lload_3,"
                + "lmul,lneg,lookupswitch,lor,lrem,lreturn,lshl,lshr,lstore,lstore_0,lstore_1,lstore_2,lstore_3,lsub,"
                + "lushr,lxor,monitorenter,monitorexit,multianewarray,new,newarray,nop,pop,pop2,putfield,putstatic,ret,"
                + "return,saload,sastore,sipush,swap,tableswitch,wide";
        for (String s : ops.split(",")) {
            OPCODES.add(s.trim().toLowerCase(Locale.US));
        }
    }

    private static final Pattern PAT_COMMENT = Pattern.compile("//[^\n\r]*");
    private static final Pattern PAT_TYPE_DESC = Pattern.compile("L[\\w/$]+;");
    private static final Pattern PAT_NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?(?:[fdlL])?\\b");
    private static final Pattern PAT_WORD = Pattern.compile("\\b[A-Za-z_$][\\w$]*\\b");
    private static final Pattern PAT_DIRECTIVE = Pattern.compile(
            "\\b(version|class|interface|extends|implements|Code|SourceFile|Signature|Deprecated|RuntimeVisibleAnnotations|"
                    + "InnerClasses|EnclosingMethod|NestHost|NestMembers|PermittedSubclasses|Module|Record)\\b");

    private BytecodeHighlighter() {}

    /** Whether a word is one of the JVM bytecode mnemonics highlighted here. */
    public static boolean isOpcode(String word) {
        return word != null && OPCODES.contains(word.toLowerCase(Locale.US));
    }

    public static SpannableString highlight(String text) {
        if (text == null) text = "";
        SpannableString ss = new SpannableString(text);
        if (text.isEmpty()) return ss;

        spanAll(ss, PAT_COMMENT, CLR_COMMENT);
        spanAll(ss, PAT_TYPE_DESC, CLR_TYPE);
        spanAll(ss, PAT_NUMBER, CLR_NUMBER);
        spanAll(ss, PAT_DIRECTIVE, CLR_DIRECTIVE);

        Matcher wm = PAT_WORD.matcher(text);
        while (wm.find()) {
            String w = wm.group();
            if (OPCODES.contains(w.toLowerCase(Locale.US))) {
                ss.setSpan(new ForegroundColorSpan(CLR_OPCODE), wm.start(), wm.end(), 0);
            }
        }
        return ss;
    }

    private static void spanAll(SpannableString ss, Pattern p, int color) {
        String text = ss.toString();
        Matcher m = p.matcher(text);
        while (m.find()) {
            ss.setSpan(new ForegroundColorSpan(color), m.start(), m.end(), 0);
        }
    }
}
