package com.ccs.wiredroid.wire;

import android.app.*;
import android.content.Intent;
import com.ccs.wiredroid.ui.FileTab;
import com.ccs.wiredroid.util.AppPreferences;
import io.github.rosemoe.sora.widget.CodeEditor;
import java.io.*;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicReference;

/** Full IDE buffer recovery and navigation; no compiler or USB device required. */
public final class WireIdeReliabilityChecks {
    private WireIdeReliabilityChecks(){}
    public static String run(Instrumentation tests)throws Exception {
        AppPreferences prefs=new AppPreferences(tests.getTargetContext());String previousRoot=prefs.getProjectRoot(),encoding=prefs.getFileEncoding();boolean autoSave=prefs.isAutoSave();
        File root=new File(tests.getTargetContext().getCacheDir(),"ide-recovery-"+System.nanoTime());Activity activity=null;
        android.content.SharedPreferences drawerPrefs=null;boolean pinned=true;
        StringBuilder report=new StringBuilder();
        try {
            WireExamples.createAt(tests.getTargetContext(),root,"blink-uno");File source=WireProject.sketch(root,"uno");
            String original="#include <Arduino.h>\nvoid setup() {}\nvoid loop() {}\n",draft="#include <Arduino.h>\nvoid setup() { pinMode(13, OUTPUT); }\nvoid loop() { digitalWrite(13, HIGH); }\n";
            WireProject.write(source,original);RecoveryStore.draft(source,draft);prefs.setProjectRoot(root.getPath());prefs.setFileEncoding("UTF-8");prefs.setAutoSave(false);
            activity=tests.startActivitySync(new Intent(tests.getTargetContext(),com.ccs.wiredroid.ui.MainActivity.class).putExtra("wire_show",true).putExtra(com.ccs.wiredroid.ui.FileOpenActivity.EXTRA_OPEN_FILE,source.getPath()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            final Activity ide=activity;drawerPrefs=activity.getPreferences(Activity.MODE_PRIVATE);pinned=drawerPrefs.getBoolean("wire_pinned",true);
            CodeEditor editor=activity.findViewById(com.ccs.wiredroid.R.id.editor);require(editor!=null,"No full IDE editor");
            AtomicReference<String> buffer=new AtomicReference<>();tests.runOnMainSync(()->buffer.set(editor.getText().toString()));
            require(draft.equals(buffer.get()),"Draft not restored into full editor");require(original.equals(WireProject.readFile(source,4096)),"Restored draft overwrote saved program");
            Object workspace=field(activity,"ws");FileTab tab=(FileTab)field(workspace,"leftTab");require(tab.recoveredDraft&&tab.isModified,"Draft dirty state missing");
            int copies=0;for(Object candidate:(java.util.List<?>)invokeValue(workspace,"allTabs",new Class<?>[0]))if(((FileTab)candidate).file.getCanonicalFile().equals(source.getCanonicalFile()))copies++;
            require(copies==1,"Canonical source opened in "+copies+" independent tabs");
            report.append("PASS full IDE: restored buffer is dirty; saved C++ file preserved\n");
            tests.runOnMainSync(()->invoke(ide,"openWireDiagnostic",new Class<?>[]{File.class,int.class,int.class},source,999,999));Thread.sleep(300);
            tests.runOnMainSync(()->require(editor.getCursor().getLeftLine()==editor.getText().getLineCount()-1,"Diagnostic line not clamped"));
            report.append("PASS full IDE: diagnostic selects/clamps original source location\n");
            String edited=draft+"// changed after recovery\n";tests.runOnMainSync(()->editor.setText(edited));
            long end=System.currentTimeMillis()+5000;boolean checkpoint=false;
            while(System.currentTimeMillis()<end){if(edited.equals(RecoveryStore.readDraft(source,4096))){checkpoint=true;break;}Thread.sleep(75);}
            require(checkpoint,"Editor checkpoint missing with AutoSave disabled");require(original.equals(WireProject.readFile(source,4096)),"Checkpoint changed saved source");
            report.append("PASS full IDE: debounce checkpoint works with AutoSave disabled\n");
            tests.runOnMainSync(()->invoke(ide,"saveCurrentFile",new Class<?>[0]));Thread.sleep(600);
            require(edited.equals(WireProject.readFile(source,4096)),"Explicit Save did not commit latest editor buffer");require(RecoveryStore.readDraft(source,4096)==null,"Old checkpoint reappeared after Save");
            report.append("PASS full IDE: explicit Save commits latest buffer and clears checkpoint\n");
            WireDrawerController drawer=(WireDrawerController)field(activity,"wireDrawer");require(drawer!=null,"No board drawer");
            tests.runOnMainSync(()->drawer.show(false));WireWorkspace wire=(WireWorkspace)field(drawer,"workspace");
            tests.runOnMainSync(drawer::hide);require(Boolean.TRUE.equals(field(wire,"paused")),"Hidden drawer can restart native program");
            tests.runOnMainSync(()->drawer.show(false));require(Boolean.FALSE.equals(field(wire,"paused")),"Reopened drawer remains paused");
            report.append("PASS full IDE: hidden drawer blocks native restart; reopened drawer resumes\n");
            requireSaved(source,edited,"Drawer visibility changed committed source");
            require(source.getCanonicalFile().equals(tab.file.getCanonicalFile()),"Editor tab switched to another source: "+tab.file);
            String pending=edited+"// pending run save\n";
            File sourceAlias=new File(source.getParentFile(),"./"+source.getName());
            prefs.setReadOnly(sourceAlias.getPath(),true);
            try {
                AtomicReference<Boolean> locked=new AtomicReference<>();
                tests.runOnMainSync(()->locked.set((Boolean)invokeValue(ide,"isFileReadOnly",new Class<?>[]{File.class},tab.file)));
                require(Boolean.TRUE.equals(locked.get()),"Lock not visible in IDE: source="+source+" tab="+tab.file+" prefs="+prefs.getReadOnlyFiles());
                com.ccs.wiredroid.project.ProjectManager project=(com.ccs.wiredroid.project.ProjectManager)field(ide,"projectManager");
                try{project.writeFile(source,pending);throw new AssertionError("ProjectManager bypassed source lock");}catch(IOException blocked){}
                tests.runOnMainSync(()->editor.setText(pending));
                requireSaved(source,edited,"Content-change event changed locked source");
                tests.runOnMainSync(wire::run);
                requireSaved(source,edited,"Drawer Run changed locked source");
                tests.runOnMainSync(()->wire.new Bridge().saveEditor());
                requireSaved(source,edited,"Bridge Run changed locked source");
                tests.runOnMainSync(()->invoke(ide,"saveEditorToTab",new Class<?>[]{CodeEditor.class,FileTab.class},editor,tab));
                require(pending.equals(RecoveryStore.readDraft(source,4096)),"Implicit locked save lost latest checkpoint");
                require(tab.isModified,"Failed save cleared modified state");
                requireSaved(source,edited,"Implicit save changed locked source");
                require(Boolean.FALSE.equals(field(wire,"runRequested")),"Failed editor Save queued a run of old code");
                boolean priorAuto=prefs.isAutoSave(),priorPerf=prefs.isPerfAutoSave(),priorPs=prefs.isPsAutoSave();
                try {
                    prefs.setAutoSave(true);prefs.setPerfAutoSave(true);prefs.setPsAutoSave(true);
                    tests.runOnMainSync(()->invoke(ide,"scheduleAutoSave",new Class<?>[]{CodeEditor.class,FileTab.class,int.class},editor,tab,0));
                    Runnable autoSaveJob=(Runnable)field(ide,"pendingAutoSave");require(autoSaveJob!=null,"No AutoSave job");
                    android.os.Handler autoHandler=(android.os.Handler)field(ide,"autoSaveHandler");
                    tests.runOnMainSync(()->{autoHandler.removeCallbacks(autoSaveJob);autoSaveJob.run();});
                    require(tab.isModified,"AutoSave failure cleared modified status");requireSaved(source,edited,"AutoSave changed locked source");
                    tests.runOnMainSync(wire::run);requireSaved(source,edited,"Run after failed AutoSave changed source");
                    require(Boolean.FALSE.equals(field(wire,"runRequested")),"Failed AutoSave let old code Run");
                }finally{prefs.setAutoSave(priorAuto);prefs.setPerfAutoSave(priorPerf);prefs.setPsAutoSave(priorPs);}
                report.append("PASS full IDE: failed AutoSave keeps dirty buffer and prevents running old source\n");
                report.append("PASS full IDE: read-only Save failure blocks drawer and JS Run without losing buffer\n");
            }finally {prefs.setReadOnly(source.getPath(),false);prefs.setReadOnly(sourceAlias.getPath(),false);}
            AtomicReference<Boolean> savedForRun=new AtomicReference<>();
            tests.runOnMainSync(()->savedForRun.set((Boolean)invokeValue(ide,"saveWireCodeForRun",new Class<?>[0])));
            require(Boolean.TRUE.equals(savedForRun.get())&&!tab.isModified,"Run Save failed after unlocking file");
            require(pending.equals(WireProject.readFile(source,4096)),"Run Save committed stale code");
            report.append("PASS full IDE: retry after unlocking commits latest code before Run\n");
            return report.toString();
        }catch(Throwable failure){throw new AssertionError(report.toString()+"FAIL "+failure.getMessage(),failure);
        }finally {
            if(activity!=null){Activity done=activity;tests.runOnMainSync(done::finish);Thread.sleep(300);}
            if(drawerPrefs!=null)drawerPrefs.edit().putBoolean("wire_pinned",pinned).commit();
            prefs.setProjectRoot(previousRoot);prefs.setFileEncoding(encoding);prefs.setAutoSave(autoSave);prefs.removeRecentProject(root.getPath());prefs.removeRecentProject(root.getCanonicalPath());WireProject.deleteNewProject(root);
        }
    }
    private static Object field(Object target,String name)throws Exception {Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    private static void invoke(Object target,String name,Class<?>[] types,Object... arguments){try{Method method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);method.invoke(target,arguments);}catch(Exception e){throw new RuntimeException(e);}}
    private static Object invokeValue(Object target,String name,Class<?>[] types,Object... arguments){try{Method method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(target,arguments);}catch(Exception e){throw new RuntimeException(e);}}
    private static void requireSaved(File source,String expected,String message)throws Exception {
        String actual=WireProject.readFile(source,4096);
        if(!expected.equals(actual))throw new AssertionError(message+": path="+source+" expected="+org.json.JSONObject.quote(expected)+" actual="+org.json.JSONObject.quote(actual)+" raw="+org.json.JSONObject.quote(new String(java.nio.file.Files.readAllBytes(source.toPath()),java.nio.charset.StandardCharsets.UTF_8)));
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
