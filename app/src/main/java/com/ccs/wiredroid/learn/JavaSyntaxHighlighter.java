package com.ccs.wiredroid.learn;

import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import com.ccs.wiredroid.util.AppTheme;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Простий регекс-підсвічувач Java-коду у Spannable.
 * Легкий (без лексера), 0 нових залежностей; кольори з активної теми.
 */
final class JavaSyntaxHighlighter {

    private static final Set<String> KEYWORDS = new HashSet<>();
    private static final Set<String> TYPES = new HashSet<>();
    static {
        String k = "abstract,assert,break,case,catch,class,const,continue,default,do,else,enum,"
                + "extends,final,finally,for,goto,if,implements,import,instanceof,interface,"
                + "native,new,package,private,protected,public,return,static,strictfp,super,"
                + "switch,synchronized,this,throw,throws,transient,try,void,volatile,while,"
                + "true,false,null,var,record,yield,sealed,non-sealed,permits,when,exports,module,opens,provides,requires,to,uses,with,transitive";
        for (String s : k.split(",")) KEYWORDS.add(s.trim());
        String t = "boolean,byte,char,double,float,int,long,short,void,String";
        for (String s : t.split(",")) TYPES.add(s.trim());
    }

    private static final Pattern P_COMMENT_LINE = Pattern.compile("//[^\\n]*");
    private static final Pattern P_COMMENT_BLOCK = Pattern.compile("/\\*[\\s\\S]*?\\*/");
    private static final Pattern P_STRING = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"");
    private static final Pattern P_CHAR = Pattern.compile("'(?:\\\\.|[^'\\\\])'");
    private static final Pattern P_ANNOT = Pattern.compile("@[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern P_NUMBER = Pattern.compile("\\b\\d+(?:\\.\\d+)?[fFdDlL]?\\b");
    private static final Pattern P_WORD = Pattern.compile("\\b[A-Za-z_$][A-Za-z0-9_$-]*\\b");

    private JavaSyntaxHighlighter() {}

    static SpannableString highlight(String code, AppTheme theme) {
        SpannableString ss = new SpannableString(code == null ? "" : code);
        if (code == null || code.isEmpty() || theme == null) return ss;

        int clrComment = theme.editorComment;
        int clrKeyword = theme.editorKeyword;
        int clrString  = theme.editorString;
        int clrNumber  = theme.editorNumber;
        int clrType    = theme.editorString;
        int clrText    = theme.text;

        spanAll(ss, P_COMMENT_BLOCK, clrComment);
        spanAll(ss, P_COMMENT_LINE, clrComment);
        spanAll(ss, P_STRING, clrString);
        spanAll(ss, P_CHAR, clrString);
        spanAll(ss, P_ANNOT, clrKeyword);
        spanAll(ss, P_NUMBER, clrNumber);

        Matcher m = P_WORD.matcher(code);
        while (m.find()) {
            String w = m.group();
            if (KEYWORDS.contains(w)) {
                ss.setSpan(new ForegroundColorSpan(clrKeyword), m.start(), m.end(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (TYPES.contains(w)) {
                ss.setSpan(new ForegroundColorSpan(clrType), m.start(), m.end(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (Character.isUpperCase(w.charAt(0))) {
                ss.setSpan(new ForegroundColorSpan(clrType), m.start(), m.end(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return ss;
    }

    private static void spanAll(SpannableString ss, Pattern p, int color) {
        Matcher m = p.matcher(ss.toString());
        while (m.find()) {
            ss.setSpan(new ForegroundColorSpan(color), m.start(), m.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }
}
