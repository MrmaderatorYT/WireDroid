package com.ccs.wiredroid.wire;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import java.io.*;
import java.lang.reflect.Field;

/** Real Servo.h compilation -> pulse protocol -> wired powered actuator -> animation. */
public class WireMotionInstrumentation extends WireCircuitsInstrumentation {
    protected void checkMotion(){
        Bundle result=new Bundle();Activity activity=null;File root=new File(getTargetContext().getCacheDir(),"motion-check-"+System.nanoTime());
        com.ccs.wiredroid.util.AppPreferences prefs=new com.ccs.wiredroid.util.AppPreferences(getTargetContext());String previousRoot=prefs.getProjectRoot();
        android.content.SharedPreferences drawerPrefs=null;boolean previousPinned=true;
        try {
            WireExamples.createAt(getTargetContext(),root,"servo-pot");File source=WireProject.sketch(root,"uno");prefs.setProjectRoot(root.getPath());
            activity=startActivitySync(new Intent(getTargetContext(),com.ccs.wiredroid.ui.MainActivity.class).putExtra("wire_show",true).putExtra(com.ccs.wiredroid.ui.FileOpenActivity.EXTRA_OPEN_FILE,source.getPath()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            drawerPrefs=activity.getPreferences(Activity.MODE_PRIVATE);previousPinned=drawerPrefs.getBoolean("wire_pinned",true);
            Field field=activity.getClass().getDeclaredField("wireDrawer");field.setAccessible(true);WireDrawerController drawer=(WireDrawerController)field.get(activity);
            final Activity testActivity=activity;
            runOnMainSync(()->{testActivity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);drawer.show(false);if(drawer.canPin())drawer.setPinned(true);});web=drawer.getWebView();expect("WireApp.ready","true",15000);
            js("window.motionStops=[];const previousStop=stop;window.stop=function(...args){motionStops.push({reason:args[0]||'',hidden:document.hidden,stack:new Error().stack});return previousStop(...args);}");
            String originalScene=js("JSON.stringify(WireApp.getScene())");
            js("WireApp.select('servo');WireApp.previewServo('servo',45)");expect("Math.abs(WireApp.getMotionStates().servo.angle-45)<1","true",5000);
            turnServo(90);expect("Math.abs(WireApp.getMotionStates().servo.angle-135)<2","true",5000);
            expect("Number(document.querySelector('[data-servo-preview]').value)>133","true",5000);
            if(!originalScene.equals(js("JSON.stringify(WireApp.getScene())")))throw new AssertionError("Touching horn changed body/wiring");
            screenshot("wiredroid-servo-touch.png");
            js("WireApp.run()");expect("WireApp.getMotionStates().servo.active","true",45000);
            js("WireApp.setInput('pot',.9)");expect("Math.abs(WireApp.getMotionStates().servo.angle-162)<2","true",10000);
            expect("Math.abs(WireApp.getMotionStates().pot.angle-108)<1","true",5000);Thread.sleep(350);
            turnServo(-90);expect("document.getElementById('status').textContent.includes('Зупинити')","true",5000);
            expect("Math.abs(WireApp.getMotionStates().servo.target-162)<2","true",5000);
            screenshot("wiredroid-motion-high.png");
            js("WireApp.setInput('pot',.1)");Thread.sleep(200);
            String middle=js("WireApp.getMotionStates().servo.angle");double angle=Double.parseDouble(middle);if(angle<20||angle>160)throw new AssertionError("No intermediate animated position: "+middle);
            expect("Math.abs(WireApp.getMotionStates().servo.angle-18)<2","true",10000);Thread.sleep(350);screenshot("wiredroid-motion-low.png");
            String fixed=js("WireApp.getScene().nodes.find(n=>n.id==='servo').rotation||0");if(!"0".equals(fixed))throw new AssertionError("Animation rotated the body");
            js("WireApp.stop()");String frozen=js("WireApp.getMotionStates().servo.angle");Thread.sleep(350);if(!frozen.equals(js("WireApp.getMotionStates().servo.angle")))throw new AssertionError("Stop did not freeze motion");
            io.github.rosemoe.sora.widget.CodeEditor editor=activity.findViewById(com.ccs.wiredroid.R.id.editor);
            String calibration="#include <Arduino.h>\n#include <Servo.h>\nServo motor;\nvoid setup(){motor.attach(\"connector52\",1000,2000);motor.writeMicroseconds(1500);Serial.print(\"ANGLE=\");Serial.println(motor.read());Serial.print(\"US=\");Serial.println(motor.readMicroseconds());delay(1000);motor.write(0);delay(1000);motor.write(180);delay(1000);motor.detach();Serial.println(motor.attached()?\"FAILED\":\"DETACHED\");} void loop(){delay(50);}\n";
            runOnMainSync(()->editor.setText(calibration));js("WireApp.run()");
            expect("document.getElementById('serialOutput').textContent.includes('ANGLE=90')","true",45000);
            expect("document.getElementById('serialOutput').textContent.includes('US=1500')","true",10000);
            expect("WireApp.getMotionStates().servo.angle<5","true",10000);expect("WireApp.getMotionStates().servo.angle>175","true",10000);
            expect("document.getElementById('serialOutput').textContent.includes('DETACHED')","true",10000);expect("WireApp.getMotionStates().servo.active","false",5000);
            result.putString("stream","PASS: real touch gesture rotates servo horn without moving body/wires, inspector synchronization, running C++ retains control with stop hint, real Servo.h compile, powered wiring, live potentiometer-to-servo angle, animated intermediate positions, separate fixed body, stop freeze, string pin attach/custom limits, writeMicroseconds/read/readMicroseconds and detach/attached.\n");finish(Activity.RESULT_OK,result);
        }catch(Throwable error){String state="";try{state="\nstop traces="+js("window.motionStops")+"\nhidden="+js("document.hidden");}catch(Exception ignored){}result.putString("stream","FAIL: "+android.util.Log.getStackTraceString(error)+state);finish(Activity.RESULT_CANCELED,result);}
        finally {try{if(web!=null)js("WireApp.stop()");}catch(Exception ignored){}if(activity!=null){final Activity a=activity;runOnMainSync(a::finish);}if(drawerPrefs!=null)drawerPrefs.edit().putBoolean("wire_pinned",previousPinned).commit();prefs.setProjectRoot(previousRoot);prefs.removeRecentProject(root.getPath());WireProject.deleteNewProject(root);}
    }
    private void screenshot(String name)throws Exception{try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getCacheDir(),name))){android.graphics.Bitmap image=getUiAutomation().takeScreenshot();image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);image.recycle();}}
    private void turnServo(int degrees)throws Exception{
        org.json.JSONArray rect=new org.json.JSONArray(js("(()=>{const r=document.querySelector('[aria-label=\"Крутити важіль серво\"]').getBoundingClientRect();return [r.left+r.width/2,r.top+r.height/2,r.width*.3,innerWidth]})()"));
        int[] location=new int[2];runOnMainSync(()->web.getLocationOnScreen(location));float scale=web.getWidth()/(float)rect.getDouble(3);
        float x=location[0]+(float)rect.getDouble(0)*scale,y=location[1]+(float)rect.getDouble(1)*scale,r=(float)rect.getDouble(2)*scale;
        long down=android.os.SystemClock.uptimeMillis();touch(down,android.view.MotionEvent.ACTION_DOWN,x+r,y);
        for(int i=1;i<=12;i++){Thread.sleep(16);double angle=degrees*Math.PI/180*i/12;touch(down,android.view.MotionEvent.ACTION_MOVE,x+(float)Math.cos(angle)*r,y+(float)Math.sin(angle)*r);}
        touch(down,android.view.MotionEvent.ACTION_UP,x+(float)Math.cos(degrees*Math.PI/180)*r,y+(float)Math.sin(degrees*Math.PI/180)*r);
    }
    private void touch(long down,int action,float x,float y){android.view.MotionEvent event=android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,x,y,0);event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);getUiAutomation().injectInputEvent(event,true);event.recycle();}
}
