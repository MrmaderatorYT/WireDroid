package com.ccs.wiredroid.uml;

import android.graphics.Typeface;
import android.text.Editable;
import android.text.Spannable;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.widget.EditText;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real-time syntax highlighter for PlantUML code.
 */
public final class PlantUmlHighlighter {

    private PlantUmlHighlighter() {}

    private static final int C_KEYWORD_DARK    = 0xFFCC7832; // Orange / Amber
    private static final int C_STRING_DARK     = 0xFF6A8759; // Forest Green
    private static final int C_COMMENT_DARK    = 0xFF808080; // Gray
    private static final int C_TYPE_DARK       = 0xFFFFC66D; // Warm Yellow
    private static final int C_RELATION_DARK   = 0xFF9876AA; // Purple
    private static final int C_VISIBILITY_DARK = 0xFF4A86C8; // Blue

    private static final int C_KEYWORD_LIGHT    = 0xFF0033B3; // IntelliJ Blue
    private static final int C_STRING_LIGHT     = 0xFF067D17; // Green
    private static final int C_COMMENT_LIGHT    = 0xFF8C8C8C; // Gray
    private static final int C_TYPE_LIGHT       = 0xFF871094; // Purple
    private static final int C_RELATION_LIGHT   = 0xFF2A3F8F; // Dark Blue
    private static final int C_VISIBILITY_LIGHT = 0xFF005FB8; // Bright Blue

    private static final Pattern PATTERN_COMMENT = Pattern.compile("(?m)^[ \\t]*'[^\\n]*|/'[\\s\\S]*?'/");
    private static final Pattern PATTERN_STRING = Pattern.compile("\"[^\"]*\"");
    private static final Pattern PATTERN_KEYWORDS = Pattern.compile(
            "(?i)\\b(@startuml|@enduml|class|interface|enum|record|abstract|extends|implements|package|namespace|skinparam|hide|show|note|as|participant|actor|boundary|control|entity|database|collections|queue)\\b");
    private static final Pattern PATTERN_TYPES = Pattern.compile(
            "\\b(int|long|double|float|boolean|char|byte|short|void|String|List|Set|Map|Collection|Object|Integer|Long|Double|Float|Boolean|Character|Byte|Short)\\b");
    private static final Pattern PATTERN_RELATIONS = Pattern.compile(
            "(<\\|--|--\\|>|<\\|\\.\\.|\\.\\.\\|>|-->|<--|\\*--|--\\*|o--|--o|\\.\\.|--|==)");
    private static final Pattern PATTERN_VISIBILITY = Pattern.compile("(?m)^[ \\t]*([+\\-#~])(?=[ \\tA-Za-z0-9_])");

    public static void highlight(Spannable text, boolean dark) {
        if (text == null || text.length() == 0) return;

        // Clear existing spans
        ForegroundColorSpan[] oldColorSpans = text.getSpans(0, text.length(), ForegroundColorSpan.class);
        for (ForegroundColorSpan s : oldColorSpans) text.removeSpan(s);
        StyleSpan[] oldStyleSpans = text.getSpans(0, text.length(), StyleSpan.class);
        for (StyleSpan s : oldStyleSpans) text.removeSpan(s);

        int kwColor = dark ? C_KEYWORD_DARK : C_KEYWORD_LIGHT;
        int strColor = dark ? C_STRING_DARK : C_STRING_LIGHT;
        int cmtColor = dark ? C_COMMENT_DARK : C_COMMENT_LIGHT;
        int typeColor = dark ? C_TYPE_DARK : C_TYPE_LIGHT;
        int relColor = dark ? C_RELATION_DARK : C_RELATION_LIGHT;
        int visColor = dark ? C_VISIBILITY_DARK : C_VISIBILITY_LIGHT;

        // Keywords
        Matcher km = PATTERN_KEYWORDS.matcher(text);
        while (km.find()) {
            text.setSpan(new ForegroundColorSpan(kwColor), km.start(), km.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new StyleSpan(Typeface.BOLD), km.start(), km.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        // Types
        Matcher tm = PATTERN_TYPES.matcher(text);
        while (tm.find()) {
            text.setSpan(new ForegroundColorSpan(typeColor), tm.start(), tm.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        // Relations / Arrows
        Matcher rm = PATTERN_RELATIONS.matcher(text);
        while (rm.find()) {
            text.setSpan(new ForegroundColorSpan(relColor), rm.start(), rm.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new StyleSpan(Typeface.BOLD), rm.start(), rm.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        // Visibility symbols
        Matcher vm = PATTERN_VISIBILITY.matcher(text);
        while (vm.find()) {
            text.setSpan(new ForegroundColorSpan(visColor), vm.start(1), vm.end(1), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new StyleSpan(Typeface.BOLD), vm.start(1), vm.end(1), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        // Strings
        Matcher sm = PATTERN_STRING.matcher(text);
        while (sm.find()) {
            text.setSpan(new ForegroundColorSpan(strColor), sm.start(), sm.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        // Comments
        Matcher cm = PATTERN_COMMENT.matcher(text);
        while (cm.find()) {
            text.setSpan(new ForegroundColorSpan(cmtColor), cm.start(), cm.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new StyleSpan(Typeface.ITALIC), cm.start(), cm.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    public static void attach(EditText editText, boolean dark) {
        if (editText == null) return;
        editText.addTextChangedListener(new TextWatcher() {
            private boolean isFormatting = false;

            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (isFormatting) return;
                isFormatting = true;
                try {
                    highlight(s, dark);
                } finally {
                    isFormatting = false;
                }
            }
        });
    }
}
