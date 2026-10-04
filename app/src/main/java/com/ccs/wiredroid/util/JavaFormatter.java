package com.ccs.wiredroid.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;

/**
 * A lightweight, dependency-free utility class for formatting Java source code.
 * Focuses on correct line indentation by scanning lines and matching braces
 * while respecting string literals, character literals, and comments.
 */
public class JavaFormatter {

    public static String format(String code, int tabSize) {
        if (code == null || code.isEmpty()) {
            return code;
        }

        StringBuilder result = new StringBuilder();
        StringBuilder indentBuilder = new StringBuilder();
        for (int i = 0; i < tabSize; i++) {
            indentBuilder.append(" ");
        }
        String indentUnit = indentBuilder.toString();

        BufferedReader reader = new BufferedReader(new StringReader(code));
        String line;
        int currentIndent = 0;
        boolean inBlockComment = false;

        try {
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    result.append("\n");
                    continue;
                }

                // Check for block comment status
                if (inBlockComment) {
                    if (trimmed.contains("*/")) {
                        inBlockComment = false;
                    }
                    result.append(getIndent(currentIndent, indentUnit))
                          .append(trimmed)
                          .append("\n");
                    continue;
                }

                if (trimmed.startsWith("/*") && !trimmed.contains("*/")) {
                    inBlockComment = true;
                    result.append(getIndent(currentIndent, indentUnit))
                          .append(trimmed)
                          .append("\n");
                    continue;
                }

                int braceBalance = 0;
                int startBraceDecrease = 0;
                
                boolean inString = false;
                boolean inChar = false;
                boolean inLineComment = false;
                boolean firstNonWhitespaceCharChecked = false;
                
                for (int i = 0; i < trimmed.length(); i++) {
                    char c = trimmed.charAt(i);
                    
                    if (inLineComment) {
                        break;
                    }
                    
                    if (c == '\\') {
                        i++; // skip next char
                        continue;
                    }
                    
                    if (inString) {
                        if (c == '"') {
                            inString = false;
                        }
                        continue;
                    }
                    if (inChar) {
                        if (c == '\'') {
                            inChar = false;
                        }
                        continue;
                    }
                    
                    if (c == '/' && i + 1 < trimmed.length()) {
                        if (trimmed.charAt(i + 1) == '/') {
                            inLineComment = true;
                            i++;
                            continue;
                        }
                    }
                    
                    if (c == '"') {
                        inString = true;
                        continue;
                    }
                    if (c == '\'') {
                        inChar = true;
                        continue;
                    }
                    
                    if (!firstNonWhitespaceCharChecked) {
                        firstNonWhitespaceCharChecked = true;
                        if (c == '}') {
                            startBraceDecrease++;
                        }
                    } else {
                        if (c == '}' && braceBalance == -startBraceDecrease) {
                            startBraceDecrease++;
                        }
                    }
                    
                    if (c == '{') {
                        braceBalance++;
                    } else if (c == '}') {
                        braceBalance--;
                    }
                }
                
                int renderIndent = currentIndent - startBraceDecrease;
                if (renderIndent < 0) {
                    renderIndent = 0;
                }
                
                result.append(getIndent(renderIndent, indentUnit))
                      .append(trimmed)
                      .append("\n");
                
                currentIndent += braceBalance;
                if (currentIndent < 0) {
                    currentIndent = 0;
                }
            }
        } catch (IOException e) {
            return code;
        }

        String formatted = result.toString();
        if (!code.endsWith("\n") && formatted.endsWith("\n")) {
            formatted = formatted.substring(0, formatted.length() - 1);
        }
        return formatted;
    }

    private static String getIndent(int level, String unit) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < level; i++) {
            sb.append(unit);
        }
        return sb.toString();
    }
}
