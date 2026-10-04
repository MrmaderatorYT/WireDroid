package com.ccs.wiredroid.wire;

import android.app.Instrumentation;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.WebView;
import org.json.*;
import java.io.*;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Device integration: real compiler -> native process -> WebView -> LED and input nets. */
public class EmbeddedSmokeInstrumentation extends Instrumentation {
    protected WebView web;
    private boolean legacyIno;
    @Override public void onCreate(Bundle arguments) {super.onCreate(arguments);legacyIno=arguments!=null&&"ino".equals(arguments.getString("format"));start();}
    protected String js(String expression) throws Exception {
        CountDownLatch done=new CountDownLatch(1);AtomicReference<String> value=new AtomicReference<>();
        runOnMainSync(()->web.evaluateJavascript(expression,result->{value.set(result);done.countDown();}));
        if(!done.await(5,TimeUnit.SECONDS))throw new Exception("WebView timeout");return value.get();
    }
    protected void expect(String expression,String expected,long timeout) throws Exception {
        long end=System.currentTimeMillis()+timeout;
        while(System.currentTimeMillis()<end){if(expected.equals(js(expression)))return;Thread.sleep(75);}
        throw new AssertionError(expression+" expected "+expected+"; logs="+js("document.getElementById('logs').textContent")+"; stopOrigins="+js("JSON.stringify(window.stopOrigins)"));
    }
    @Override public void onStart() {
        Bundle result=new Bundle();Activity activity=null;
        android.content.SharedPreferences drawerPreferences=null;boolean previousPinned=true;
        com.ccs.wiredroid.util.AppPreferences prefs = new com.ccs.wiredroid.util.AppPreferences(getTargetContext());
        String previousRoot = prefs.getProjectRoot();String extension=legacyIno?".ino":".cpp";
        File root = new File(getTargetContext().getCacheDir(),"embedded-smoke-"+System.nanoTime());
        try {
            root.mkdirs();
            try(InputStream in=getTargetContext().getAssets().open("wiredroid/example.json")){
                WireProject.write(new File(root,WireProject.SCENE),WireProject.read(in,2*1024*1024));
            }
            File source=new File(root,"sketches/uno"+extension);
            WireProject.write(source,"#include <Arduino.h>\nvoid setup(){pinMode(13,OUTPUT);pinMode(2,INPUT_PULLUP);}\nvoid loop(){digitalWrite(13,HIGH);Serial.println(digitalRead(2));delay(200);digitalWrite(13,LOW);delay(200);}\n");
            if(!source.getCanonicalFile().equals(WireProject.sketch(root,"uno")))throw new AssertionError("Board source resolution: "+extension);
            prefs.setProjectRoot(root.getPath());
            Intent intent=new Intent(getTargetContext(),com.ccs.wiredroid.ui.MainActivity.class).putExtra("wire_show",true)
                .putExtra(com.ccs.wiredroid.ui.FileOpenActivity.EXTRA_OPEN_FILE,source.getAbsolutePath()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity=startActivitySync(intent);
            drawerPreferences=activity.getPreferences(Activity.MODE_PRIVATE);previousPinned=drawerPreferences.getBoolean("wire_pinned",true);
            Field f=com.ccs.wiredroid.ui.MainActivity.class.getDeclaredField("wireDrawer");f.setAccessible(true);
            WireDrawerController drawer=(WireDrawerController)f.get(activity);
            if(drawer==null)throw new AssertionError("No right-hand board tool window");
            runOnMainSync(()->drawer.show(false));web=drawer.getWebView();
            final Activity ide=activity;
            io.github.rosemoe.sora.widget.CodeEditor editor=activity.findViewById(com.ccs.wiredroid.R.id.editor);
            if(editor==null||activity.findViewById(com.ccs.wiredroid.R.id.tabsRecycler)==null||activity.findViewById(com.ccs.wiredroid.R.id.toolbar)==null)
                throw new AssertionError("Full IDE editor, tabs and toolbar required");
            if(drawer.canPin())runOnMainSync(()->drawer.setPinned(true));
            expect("!!(window.WireApp&&document.querySelectorAll('.node .pin').length>40)","true",15000);
            js("WireApp.run()");
            expect("document.querySelector('[data-node=led]').classList.contains('led-on')","true",45000);
            expect("document.querySelector('[data-node=led]').classList.contains('led-on')","false",5000);
            expect("document.getElementById('logs').textContent.includes('setup() виконано')","true",5000);
            expect("/Arduino Uno: 1/.test(document.getElementById('logs').textContent)","true",5000);
            android.widget.TextView language=activity.findViewById(com.ccs.wiredroid.R.id.statusLanguage);
            AtomicReference<String> languageText=new AtomicReference<>();
            runOnMainSync(()->languageText.set(language.getText().toString()));
            if(!(legacyIno?"Arduino C++":"C++").equals(languageText.get()))throw new AssertionError("Embedded language status: "+languageText.get());
            expect("document.getElementById('logs').textContent.includes('uno"+extension+"')","true",5000);
            if(legacyIno&&new File(root,"sketches/uno.cpp").exists())throw new AssertionError("Legacy .ino was shadowed by a generated .cpp");
            expect("document.body.classList.contains('embedded')","true",5000);
            // Capture the working native IDE and board panel together before the error test.
            try(FileOutputStream image=new FileOutputStream(new File(getTargetContext().getCacheDir(),"wiredroid-ide-preview.png"))){
                android.graphics.Bitmap screenshot=getUiAutomation().takeScreenshot();
                if(screenshot==null)throw new AssertionError("No IDE screenshot");
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,image);screenshot.recycle();
            }
            js("WireApp.stop()");
            // The native process must return compiler diagnostics for invalid C++.
            // Edit the original IDE buffer; Run must save it before invoking Clang.
            runOnMainSync(()->editor.setText("#include <Arduino.h>\nvoid setup(){ this_is_invalid_cpp; }\nvoid loop(){}\n"));
            js("WireApp.run()");expect("document.getElementById('logs').textContent.includes('this_is_invalid_cpp')","true",45000);
            js("WireApp.stop()");
            runOnMainSync(()->drawer.setPinned(false));
            java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();
            runOnMainSync(()->closed.set(drawer.closeDrawer()));
            if(!closed.get())throw new AssertionError("Back/close must close the right drawer");
            runOnMainSync(()->drawer.show(false));
            expect("WireApp.ready","true",5000);
            js("WireHost.editSketch('uno')");Thread.sleep(300);
            if(activity!=ide)throw new AssertionError("Sketch opened a separate editor activity");
            result.putString("stream","PASS ("+extension+"): full IDE editor/tabs/toolbar, right drawer docking and close, inline sketch opening, source resolution, unsaved editor compilation, real Clang C++17 build, native GPIO, visible LED HIGH/LOW, digitalRead INPUT_PULLUP roundtrip, compiler diagnostics, stop.\n");
            finish(Activity.RESULT_OK,result);
        }catch(Throwable error){result.putString("stream","FAIL: "+error+"\n");finish(Activity.RESULT_CANCELED,result);}
        finally {
            if(activity!=null){final Activity finishActivity=activity;runOnMainSync(finishActivity::finish);}
            if(drawerPreferences!=null)drawerPreferences.edit().putBoolean("wire_pinned",previousPinned).commit();
            prefs.setProjectRoot(previousRoot);prefs.removeRecentProject(root.getPath());
        }
    }
}
