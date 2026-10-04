package com.ccs.wiredroid.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import com.ccs.wiredroid.tools.compilers.ProjectCompiler;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class JShellActivity extends AppCompatActivity {
    private TextView consoleOutput;
    private EditText inputField;
    private ScrollView scrollView;
    private List<String> history = new ArrayList<>();
    
    public static void launch(Context context) {
        context.startActivity(new Intent(context, JShellActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF2B2B2B); // Dark theme base

        Toolbar toolbar = new Toolbar(this);
        toolbar.setTitle("JShell (REPL)");
        toolbar.setBackgroundColor(0xFF222222);
        toolbar.setTitleTextColor(0xFFFFFFFF);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        consoleOutput = new TextView(this);
        consoleOutput.setTextColor(0xFFCCCCCC);
        consoleOutput.setTextSize(14f);
        consoleOutput.setPadding(32, 32, 32, 32);
        consoleOutput.setTypeface(android.graphics.Typeface.MONOSPACE);
        
        scrollView = new ScrollView(this);
        scrollView.addView(consoleOutput);
        
        inputField = new EditText(this);
        inputField.setHint("jshell> enter Java code here...");
        inputField.setHintTextColor(0xFF888888);
        inputField.setTextColor(0xFFFFFFFF);
        inputField.setTypeface(android.graphics.Typeface.MONOSPACE);
        inputField.setSingleLine(true);
        inputField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        inputField.setBackgroundColor(0xFF1E1E1E);
        inputField.setPadding(32, 32, 32, 32);

        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        root.addView(toolbar);
        root.addView(scrollView, scrollParams);
        root.addView(inputField, new LinearLayout.LayoutParams(-1, -2));
        
        setContentView(root);

        appendOutput("Welcome to JShell for Android.\nType Java statements (e.g. System.out.println(\"Hi\");)\n\n");

        inputField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE || 
                (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER && event.getAction() == android.view.KeyEvent.ACTION_DOWN)) {
                String code = inputField.getText().toString();
                if (!code.isEmpty()) {
                    inputField.setText("");
                    executeCode(code);
                }
                return true;
            }
            return false;
        });
    }

    private void appendOutput(String text) {
        runOnUiThread(() -> {
            consoleOutput.append(text);
            scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private void executeCode(String code) {
        appendOutput("jshell> " + code + "\n");
        history.add(code);
        
        new Thread(() -> {
            try {
                File cacheDir = new File(getCacheDir(), "jshell_cache");
                if (!cacheDir.exists()) cacheDir.mkdirs();
                
                StringBuilder src = new StringBuilder();
                src.append("public class JShellMain {\n");
                src.append("  public static void main(String[] args) throws Exception {\n");
                for (String line : history) {
                    if (!line.trim().endsWith(";") && !line.trim().endsWith("}") && !line.trim().endsWith("{")) {
                        src.append("    ").append(line).append(";\n");
                    } else {
                        src.append("    ").append(line).append("\n");
                    }
                }
                src.append("  }\n}\n");

                File srcFile = new File(cacheDir, "JShellMain.java");
                ProjectCompiler.writeUtf8Public(srcFile, src.toString());

                File androidJar = ProjectCompiler.ensureAndroidJarPublic(this, cacheDir);
                
                String ecjErr = ProjectCompiler.compileEcjPublic(androidJar, null, cacheDir, "1.8", srcFile);
                if (ecjErr != null) {
                    history.remove(history.size() - 1);
                    appendOutput("Error: " + ecjErr + "\n");
                    return;
                }

                File classFile = ProjectCompiler.findClassFilePublic(cacheDir, "JShellMain");
                if (classFile == null) {
                    history.remove(history.size() - 1);
                    appendOutput("Error: Could not find compiled class.\n");
                    return;
                }

                File dexDir = new File(cacheDir, "jshell_dex");
                if (!dexDir.exists()) dexDir.mkdirs();
                else {
                    File[] oldFiles = dexDir.listFiles();
                    if (oldFiles != null) for (File f : oldFiles) f.delete();
                }

                ProjectCompiler.runD8DexPublic(androidJar, dexDir, classFile);

                ProjectCompiler.debugRunDex(this, "JShellMain", dexDir, cacheDir, null, new ProjectCompiler.Callback() {
                    @Override public void onProgress(String msg) { appendOutput(msg + "\n"); }
                    @Override public void onResult(String res) { 
                        if (!res.trim().isEmpty() && !res.equals("Success")) {
                            // res contains the full output of the run
                            // Wait, debugRunDex gives us the result. We already append onProgress.
                        }
                        appendOutput("\n");
                    }
                    @Override public void onProblems(java.util.List<com.ccs.wiredroid.analysis.ProblemItem> problems) {}
                });
            } catch (Exception e) {
                history.remove(history.size() - 1);
                appendOutput("Exception: " + e.getMessage() + "\n");
            }
        }).start();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
