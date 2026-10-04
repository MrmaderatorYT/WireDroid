package com.ccs.wiredroid.learn;

import android.content.Context;

import com.ccs.wiredroid.analysis.ProblemItem;
import com.ccs.wiredroid.tools.compilers.ProjectCompiler;

import java.util.List;

/** Builds hidden Java 8 boilerplate and delegates ECJ → D8 → DEX execution. */
final class LessonSnippetRunner {

    interface Callback {
        void onProgress(String message);
        void onResult(String output);
    }

    private LessonSnippetRunner() {
    }

    static void run(Context context, LessonBlock block, Callback callback) {
        if (block == null || !block.isRunnable()) {
            callback.onResult("Error: this example is not executable.");
            return;
        }
        String source = block.runMode == LessonBlock.RUN_JAVA_SOURCE
                ? block.executionText
                : wrapStatements(block.executionText);
        ProjectCompiler.runJavaSnippet(context.getApplicationContext(), source,
                new ProjectCompiler.Callback() {
                    @Override
                    public void onProgress(String message) {
                        callback.onProgress(message);
                    }

                    @Override
                    public void onResult(String output) {
                        callback.onResult(output == null ? "" : output);
                    }

                    @Override
                    public void onProblems(List<ProblemItem> problems) {
                        // Inline console already receives the ECJ diagnostics as text.
                    }
                });
    }

    static String wrapStatements(String statements) {
        return "import java.io.*;\n"
                + "import java.math.*;\n"
                + "import java.nio.charset.*;\n"
                + "import java.nio.file.*;\n"
                + "import java.time.*;\n"
                + "import java.time.format.*;\n"
                + "import java.time.temporal.*;\n"
                + "import java.util.*;\n"
                + "import java.util.concurrent.*;\n"
                + "import java.util.concurrent.atomic.*;\n"
                + "import java.util.function.*;\n"
                + "import java.util.regex.*;\n"
                + "import java.util.stream.*;\n\n"
                + "public final class SnippetRunner {\n"
                + "    public static void main(String[] args) throws Exception {\n"
                + indent(statements, "        ") + "\n"
                + "    }\n"
                + "}\n";
    }

    private static String indent(String text, String prefix) {
        return prefix + text.replace("\r\n", "\n").replace("\r", "\n")
                .replace("\n", "\n" + prefix);
    }
}
