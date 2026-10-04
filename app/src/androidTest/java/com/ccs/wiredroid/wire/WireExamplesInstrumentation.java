package com.ccs.wiredroid.wire;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import java.io.*;
import java.lang.reflect.Field;
import org.json.*;

/** Run the actual bundled projects through the IDE, native compiler and circuit model. */
public final class WireExamplesInstrumentation extends WireMotionInstrumentation {
    private boolean examplesSuite, creationSuite, circuitsSuite, motionSuite,reliabilitySuite,ideReliabilitySuite,nativeSessionSuite;private String onlyExample;private boolean isolated;private android.hardware.display.VirtualDisplay testDisplay;private android.media.ImageReader testImages;private android.app.Presentation testPresentation;private WireWorkspace testWorkspace;
    private static final class WorkspaceActivity extends Activity {void attach(android.content.Context context){attachBaseContext(context);}@Override public Object getSystemService(String name){return getBaseContext().getSystemService(name);}}
    @Override public void onCreate(Bundle arguments) {nativeSessionSuite=arguments!=null&&"native-session".equals(arguments.getString("suite"));ideReliabilitySuite=arguments!=null&&"ide-reliability".equals(arguments.getString("suite"));reliabilitySuite=arguments!=null&&"reliability".equals(arguments.getString("suite"));motionSuite=arguments!=null&&"motion".equals(arguments.getString("suite"));circuitsSuite=arguments!=null&&"circuits".equals(arguments.getString("suite"));creationSuite=arguments!=null&&"creation".equals(arguments.getString("suite"));examplesSuite=arguments!=null&&"examples".equals(arguments.getString("suite"));onlyExample=arguments==null?null:arguments.getString("example");isolated=arguments!=null&&"true".equals(arguments.getString("isolated"));super.onCreate(arguments);}
    private Object field(Object target, String name) throws Exception {
        Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);
    }
    private void checkCreation() {
        Bundle result=new Bundle(); Activity activity=null; File root=null;
        com.ccs.wiredroid.util.AppPreferences prefs=new com.ccs.wiredroid.util.AppPreferences(getTargetContext());
        String previousRoot=prefs.getProjectRoot();
        try {
            activity=startActivitySync(new Intent(getTargetContext(),com.ccs.wiredroid.ui.NewProjectActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            final Activity wizard=activity;
            android.widget.TextView examples=(android.widget.TextView)field(wizard,"examplesModeChip");
            runOnMainSync(()->examples.performClick());
            android.widget.LinearLayout block=(android.widget.LinearLayout)field(wizard,"wireExamplesBlock");
            if(block.getVisibility()!=android.view.View.VISIBLE)throw new AssertionError("Examples picker hidden");
            android.widget.Spinner picker=(android.widget.Spinner)block.getChildAt(1);
            if(picker.getCount()!=WireExamples.list(getTargetContext()).length()||picker.getCount()<17)throw new AssertionError("Missing examples");
            runOnMainSync(()->picker.setSelection(2));Thread.sleep(400);
            if(!"traffic-uno".equals(field(wizard,"wireExampleId")))throw new AssertionError("Selection ignored");
            android.widget.EditText name=(android.widget.EditText)field(wizard,"nameEdit");
            String projectName="Wire_UI_Check_"+System.nanoTime();
            runOnMainSync(()->name.setText(projectName));Thread.sleep(800);
            root=com.ccs.wiredroid.maven.MavenPaths.projectDir(getTargetContext(),projectName);
            try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getCacheDir(),"wiredroid-creation-preview.png"))){android.graphics.Bitmap image=getUiAutomation().takeScreenshot();image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);image.recycle();}
            java.lang.reflect.Method create=wizard.getClass().getDeclaredMethod("startCreate");create.setAccessible(true);
            runOnMainSync(()->{try{create.invoke(wizard);}catch(Exception e){throw new RuntimeException(e);}});
            for(int i=0;i<100&&!new File(root,WireProject.SCENE).isFile();i++)Thread.sleep(100);
            JSONObject scene=new JSONObject(WireProject.readFile(new File(root,WireProject.SCENE),2*1024*1024));
            if(scene.getJSONArray("nodes").length()!=7)throw new AssertionError("Wrong selected example: "+scene.getJSONArray("nodes").length());
            activity=startActivitySync(new Intent(getTargetContext(),com.ccs.wiredroid.ui.MainActivity.class).putExtra("wire_show",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Thread.sleep(1500);
            android.widget.HorizontalScrollView scroll=activity.findViewById(com.ccs.wiredroid.R.id.toolbarScroller);
            if(scroll==null)throw new AssertionError("No toolbar scroller");
            android.view.View back=activity.findViewById(com.ccs.wiredroid.R.id.toolbarBack), overflow=activity.findViewById(com.ccs.wiredroid.R.id.toolbarOverflow);
            if(back.getRight()>overflow.getLeft())throw new AssertionError("Back and overflow overlap");
            final android.widget.HorizontalScrollView scroller=scroll;
            runOnMainSync(()->scroller.fullScroll(android.view.View.FOCUS_RIGHT));Thread.sleep(300);
            try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getCacheDir(),"wiredroid-toolbar-preview.png"))){android.graphics.Bitmap image=getUiAutomation().takeScreenshot();image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);image.recycle();}
            result.putString("stream","PASS project wizard: all bundled examples, selected traffic-light bundle created. PASS toolbar: horizontal scroller and separate back/overflow controls.\n");finish(Activity.RESULT_OK,result);
        } catch(Throwable e){result.putString("stream","FAIL "+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,result);}
        finally {
            if(activity!=null){final Activity a=activity;runOnMainSync(a::finish);}
            prefs.setProjectRoot(previousRoot);
            if(root!=null){prefs.removeRecentProject(root.getPath());try{prefs.removeRecentProject(root.getCanonicalPath());}catch(IOException ignored){}WireProject.deleteNewProject(root);}
        }
    }
    @Override public void onStart() {
        if(nativeSessionSuite){Bundle result=new Bundle();try{result.putString("stream",WireNativeSessionChecks.run(getTargetContext()));finish(Activity.RESULT_OK,result);}catch(Throwable e){result.putString("stream","FAIL "+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,result);}return;}
        if(ideReliabilitySuite){Bundle result=new Bundle();try{result.putString("stream",WireIdeReliabilityChecks.run(this));finish(Activity.RESULT_OK,result);}catch(Throwable e){result.putString("stream","FAIL "+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,result);}return;}
        if(reliabilitySuite){Bundle result=new Bundle();try{result.putString("stream",WireReliabilityChecks.run(getTargetContext()));finish(Activity.RESULT_OK,result);}catch(Throwable e){result.putString("stream","FAIL "+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,result);}return;}
        if(motionSuite){checkMotion();return;}
        if(circuitsSuite){checkCircuits();return;}
        if(creationSuite){checkCreation();return;}
        if(!examplesSuite){super.onStart();return;}
        Bundle result=new Bundle();Activity activity=null;StringBuilder report=new StringBuilder();
        com.ccs.wiredroid.util.AppPreferences prefs=new com.ccs.wiredroid.util.AppPreferences(getTargetContext());
        String previousRoot=prefs.getProjectRoot();
        android.content.SharedPreferences drawerPrefs=null;boolean previousPinned=true;
        File batch=new File(getTargetContext().getCacheDir(),"example-checks-"+System.nanoTime());batch.mkdirs();
        java.util.List<File> roots=new java.util.ArrayList<>();
        try {
            if(isolated){
                runOnMainSync(()->{
                    testImages=android.media.ImageReader.newInstance(1280,800,android.graphics.PixelFormat.RGBA_8888,3);
                    testImages.setOnImageAvailableListener(reader->{android.media.Image image=reader.acquireLatestImage();if(image!=null)image.close();},new android.os.Handler(android.os.Looper.getMainLooper()));
                    android.hardware.display.DisplayManager manager=(android.hardware.display.DisplayManager)getTargetContext().getSystemService(android.content.Context.DISPLAY_SERVICE);
                    testDisplay=manager.createVirtualDisplay("WireDroid integration",1280,800,160,testImages.getSurface(),android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY|android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION);
                });if(testDisplay==null)throw new AssertionError("No isolated test display");
            }
            JSONArray examples=WireExamples.list(getTargetContext());
            if(examples.length()<17)throw new AssertionError("Expected at least seventeen complete examples");
            for(int i=0;i<examples.length();i++) {
                JSONObject item=examples.getJSONObject(i);String id=item.getString("id"),board=item.getString("board");
                if(onlyExample!=null&&!onlyExample.equals(id))continue;
                File root=WireExamples.createAt(getTargetContext(),new File(batch,id),id);roots.add(root);
                File source=WireProject.sketch(root,board);
                if(!source.getName().endsWith(".cpp"))throw new AssertionError("Example source must be C++");
                prefs.setProjectRoot(root.getPath());
                Intent intent=new Intent(getTargetContext(),com.ccs.wiredroid.ui.MainActivity.class)
                    .putExtra("wire_show",true).putExtra(com.ccs.wiredroid.ui.FileOpenActivity.EXTRA_OPEN_FILE,source.getPath()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if(isolated){
                    final File currentRoot=root;
                    runOnMainSync(()->{
                        try{testPresentation=new android.app.Presentation(getTargetContext(),testDisplay.getDisplay());WorkspaceActivity owner=new WorkspaceActivity();owner.attach(testPresentation.getContext());testWorkspace=new WireWorkspace(owner,currentRoot,file->{},()->{});web=testWorkspace.getView();testPresentation.setContentView(web);testPresentation.show();}
                        catch(Exception e){throw new RuntimeException(e);}
                    });
                }else{
                    activity=startActivitySync(intent);final Activity current=activity;runOnMainSync(()->current.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
                    if(drawerPrefs==null){drawerPrefs=activity.getPreferences(Activity.MODE_PRIVATE);previousPinned=drawerPrefs.getBoolean("wire_pinned",true);}
                    Field f=com.ccs.wiredroid.ui.MainActivity.class.getDeclaredField("wireDrawer");f.setAccessible(true);WireDrawerController drawer=(WireDrawerController)f.get(activity);
                    runOnMainSync(()->{drawer.show(false);if(drawer.canPin())drawer.setPinned(true);});web=drawer.getWebView();
                }
                expect("WireApp.ready","true",15000);
                js("WireApp.select("+JSONObject.quote(board)+");WireApp.resizeSelected(0.5)");
                expect("WireApp.getScene().nodes.find(n=>n.id==="+JSONObject.quote(board)+").scale","0.5",5000);
                expect("document.querySelectorAll('.node .pin').length>5","true",5000);
                js("WireApp.save()");
                JSONObject persisted=new JSONObject(WireProject.readFile(new File(root,WireProject.SCENE),2*1024*1024));
                boolean saved=false;JSONArray nodes=persisted.getJSONArray("nodes");
                for(int n=0;n<nodes.length();n++)if(board.equals(nodes.getJSONObject(n).getString("id")))saved=nodes.getJSONObject(n).getDouble("scale")==.5;
                if(!saved)throw new AssertionError("Component size did not persist");
                js("window.stopOrigins=[];const oldStop=WireApp.stop;WireApp.stop=function(...args){stopOrigins.push({nativePause:true,hidden:document.hidden,stack:new Error().stack});return oldStop(...args);};document.addEventListener('visibilitychange',()=>stopOrigins.push({visibility:document.hidden}));WireApp.run()");
                if(id.equals("servo-sweep")) {
                    expect("WireApp.getMotionStates().servo.active","true",45000);
                    expect("WireApp.getMotionStates().servo.angle<5","true",10000);
                    expect("WireApp.getMotionStates().servo.angle>175","true",10000);
                } else if(id.equals("servo-pot")) {
                    expect("WireApp.getMotionStates().servo.active","true",45000);
                    js("WireApp.setInput('pot',.9)");expect("Math.abs(WireApp.getMotionStates().servo.angle-162)<2","true",10000);
                    js("WireApp.setInput('pot',.1)");expect("Math.abs(WireApp.getMotionStates().servo.angle-18)<2","true",10000);
                } else if(id.equals("electronics-rc")) {
                    expect("WireApp.getElectronics()?.volts['uno:connector0']>0.5","true",120000);
                    expect("WireApp.getElectronics().currents.r!==undefined","true",5000);
                } else if(id.equals("motor-driver")) {
                    expect("Math.abs(WireApp.getElectronics()?.states.motor.rpm||0)>20","true",120000);
                    expect("WireApp.getMotionStates().motor.active","true",5000);
                } else if(id.equals("stepper-driver")) {
                    expect("Math.abs(WireApp.getMotionStates().motor.angle)>3","true",120000);
                } else if(id.equals("a4988-microsteps")||id.equals("unipolar-stepper")) {
                    expect("Math.abs(WireApp.getMotionStates().motor?.angle||0)>3","true",120000);
                    expect("WireApp.getMotionStates().motor.active","true",5000);
                } else if(id.equals("esc-bldc")) {
                    expect("WireApp.getMotionStates().motor.active&&Math.abs(WireApp.getElectronics()?.states.motor?.rpm||0)>20","true",120000);
                } else if(id.equals("quadrature-encoder")) {
                    expect("Array.from(document.getElementById('serialOutput').textContent.matchAll(/Encoder: (-?\\d+)/g)).some(m=>Math.abs(Number(m[1]))>2)","true",120000);
                } else if(id.equals("avr-blink")) {
                    expect("(WireApp.getMCU().uno?.cycles||0)>0","true",15000);
                    expect("document.querySelector('[data-node=led]').classList.contains('led-on')","true",15000);
                    expect("document.querySelector('[data-node=led]').classList.contains('led-on')","false",15000);
                    js("WireApp.pauseMCU()");expect("WireApp.getMCU().uno?.paused","true",5000);
                    js("window.pausedCycles=WireApp.getMCU().uno.cycles");Thread.sleep(150);
                    expect("WireApp.getMCU().uno.cycles===window.pausedCycles","true",5000);
                    js("WireApp.stepMCU('uno')");expect("WireApp.getMCU().uno.cycles>window.pausedCycles","true",5000);
                    js("document.getElementById('debugBoard').value='uno';document.getElementById('debugReset').click()");
                    expect("WireApp.getMCU().uno?.cycles===0&&WireApp.getMCU().uno?.paused","true",5000);
                    js("WireApp.continueMCU()");expect("WireApp.getMCU().uno.cycles>0","true",5000);
                } else if(id.equals("lcd-project")) {
                    expect("WireApp.getDevices().lcd?.text[0].includes('WireDroid C++')","true",120000);
                    expect("WireApp.getDevices().lcd.text[1].includes('Count:')","true",15000);
                    expect("document.getElementById('serialOutput').textContent.includes('-0.500')&&document.getElementById('serialOutput').textContent.includes('C/STL 6')","true",5000);
                } else if(id.equals("i2c-eeprom")) {
                    expect("document.getElementById('serialOutput').textContent.includes('42')","true",120000);
                } else if(id.equals("button-uno")) {
                    expect("document.getElementById('logs').textContent.includes('Button released')","true",45000);
                    expect("document.querySelector('[data-node=led]').classList.contains('led-on')","false",5000);
                    js("WireApp.pressButton('button',true)");
                    expect("document.querySelector('[data-node=led]').classList.contains('led-on')","true",5000);
                    js("WireApp.pressButton('button',false)");
                    expect("document.querySelector('[data-node=led]').classList.contains('led-on')","false",5000);
                } else if(id.equals("traffic-uno")) {
                    for(String led:new String[]{"led_red","led_green","led_yellow"})expect("document.querySelector('[data-node="+led+"]').classList.contains('led-on')","true",45000);
                    if(!isolated)try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getCacheDir(),"wiredroid-examples-preview.png"))) {
                        android.graphics.Bitmap screenshot=getUiAutomation().takeScreenshot();screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);screenshot.recycle();
                    }
                } else {
                    String led=id.equals("alternating-nano")?"led_red":"led";
                    expect("document.querySelector('[data-node="+led+"]').classList.contains('led-on')","true",45000);
                    expect("document.querySelector('[data-node="+led+"]').classList.contains('led-on')","false",5000);
                    if(id.equals("alternating-nano"))expect("document.querySelector('[data-node=led_green]').classList.contains('led-on')","true",5000);
                }
                js("WireApp.stop()");if(id.equals("avr-blink"))expect("Object.keys(WireApp.getMCU()).length===0","true",5000);
                String passed="PASS: "+id+" · project copy, "+(id.equals("avr-blink")?"real AVR firmware execution, pause/step/reset/stop":"native C/C++ build and expected circuit behavior")+", per-board size 50% saved\n";report.append(passed);Bundle progress=new Bundle();progress.putString("stream",passed);sendStatus(1,progress);
                if(isolated)runOnMainSync(()->{testWorkspace.close();testWorkspace=null;testPresentation.dismiss();testPresentation=null;});else{final Activity done=activity;runOnMainSync(done::finish);}waitForIdleSync();activity=null;
            }
            // Reopening an example cannot overwrite an existing project.
            File first=roots.get(0);String original=WireProject.readFile(new File(first,WireProject.SCENE),2*1024*1024);
            try{WireExamples.createAt(getTargetContext(),first,"blink-uno");throw new AssertionError("Existing project overwritten");}
            catch(IOException expected){}
            if(!original.equals(WireProject.readFile(new File(first,WireProject.SCENE),2*1024*1024)))throw new AssertionError("Existing scene changed");
            report.append("PASS: existing project overwrite rejected\n");result.putString("stream",report.toString());finish(Activity.RESULT_OK,result);
        }catch(Throwable error){result.putString("stream",report+"FAIL: "+error+"\n");finish(Activity.RESULT_CANCELED,result);}
        finally {
            if(activity!=null){final Activity done=activity;runOnMainSync(done::finish);}
            if(drawerPrefs!=null)drawerPrefs.edit().putBoolean("wire_pinned",previousPinned).commit();
            prefs.setProjectRoot(previousRoot);for(File root:roots)prefs.removeRecentProject(root.getPath());
            if(testDisplay!=null)runOnMainSync(()->{if(testWorkspace!=null)testWorkspace.close();if(testPresentation!=null)testPresentation.dismiss();testDisplay.release();testImages.close();});
        }
    }
}
