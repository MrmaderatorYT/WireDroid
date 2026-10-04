package com.ccs.wiredroid.tools.compilers;

import com.ccs.wiredroid.analysis.ProblemItem;
import android.content.Context;

import java.util.List;

public class CompilerTask {

    private final Context context;
    private final String sourceCode;
    private final Callback callback;

    public interface Callback {
        void onProgress(String message);
        void onResult(String output);
    }

    public CompilerTask(Context context, String sourceCode, Callback callback) {
        this.context = context;
        this.sourceCode = sourceCode;
        this.callback = callback;
    }

    public void execute() {
        ProjectCompiler.runSingleSource(context, sourceCode, null, null, new ProjectCompiler.Callback() {
            @Override
            public void onProgress(String message) {
                callback.onProgress(message);
            }

            @Override
            public void onResult(String output) {
                callback.onResult(output);
            }

            @Override
            public void onProblems(List<ProblemItem> problems) {
                /* одиночний файл — ігноруємо список */
            }
        });
    }
}
