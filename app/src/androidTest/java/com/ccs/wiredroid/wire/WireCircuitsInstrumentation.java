package com.ccs.wiredroid.wire;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import org.json.*;
import java.io.*;
import java.lang.reflect.Field;

/** Actual Android compiler/ADC/Serial integration, not a JavaScript runtime mock. */
public class WireCircuitsInstrumentation extends EmbeddedSmokeInstrumentation {
    private JSONObject part(JSONArray catalog,String id)throws Exception{for(int i=0;i<catalog.length();i++)if(id.equals(catalog.getJSONObject(i).getString("id")))return catalog.getJSONObject(i);throw new AssertionError(id);}
    private String pin(JSONObject part,String name)throws Exception{JSONArray pins=part.getJSONArray("pins");for(int i=0;i<pins.length();i++)if(name.equalsIgnoreCase(pins.getJSONObject(i).getString("name").split("[ /]")[0]))return pins.getJSONObject(i).getString("id");throw new AssertionError(name);}
    private void wire(JSONObject scene,String id,String node,String pin,String boardPin)throws Exception{scene.getJSONArray("wires").put(new JSONObject().put("id",id).put("a",new JSONObject().put("node",node).put("pin",pin)).put("b",new JSONObject().put("node","uno").put("pin",boardPin)).put("color","#4ade80"));}
    protected void checkCircuits(){
        Bundle result=new Bundle();Activity activity=null;File root=new File(getTargetContext().getCacheDir(),"circuits-check-"+System.nanoTime());
        com.ccs.wiredroid.util.AppPreferences prefs=new com.ccs.wiredroid.util.AppPreferences(getTargetContext());String previousRoot=prefs.getProjectRoot();
        android.content.SharedPreferences drawerPrefs=null;boolean previousPinned=true;
        try {
            WireExamples.createAt(getTargetContext(),root,"blink-uno");
            JSONArray catalog;try(InputStream in=getTargetContext().getAssets().open("wiredroid/catalog.json")){catalog=new JSONArray(WireProject.read(in,10*1024*1024));}
            JSONObject uno=part(catalog,"arduino_Uno_Rev3(fix)"),scene=new JSONObject(WireProject.readFile(new File(root,WireProject.SCENE),2*1024*1024));
            scene.getJSONArray("nodes").put(new JSONObject().put("id","pot").put("part","potentiometer_rotary_9mm_6").put("label","Потенціометр").put("x",500).put("y",320).put("inputValue",.25));
            scene.getJSONArray("nodes").put(new JSONObject().put("id","sensor").put("part","tmp36").put("label","Керований ADC датчика").put("x",750).put("y",320).put("inputValue",.75));
            scene.getJSONArray("nodes").put(new JSONObject().put("id","manual").put("part","Pushbutton").put("label","Кнопка").put("x",600).put("y",500));
            scene.getJSONArray("nodes").put(new JSONObject().put("id","unknown").put("part","contrib/DHT22_Humidity_and_Temperature_Sensor__5a66c01a9adead9b6c488a2653070fb3_C9").put("label","Ручний сигнал датчика").put("x",850).put("y",500).put("inputValue",.5).put("simulation",new JSONObject().put("pin","connector2")));
            wire(scene,"unknown-input","unknown","connector2",pin(uno,"A2"));
            wire(scene,"pot-gnd","pot","connector0",pin(uno,"GND"));wire(scene,"pot-power","pot","connector2",pin(uno,"5V"));wire(scene,"pot-input","pot","connector1",pin(uno,"A0"));
            wire(scene,"sensor-gnd","sensor","connector2",pin(uno,"GND"));wire(scene,"sensor-power","sensor","connector0",pin(uno,"5V"));wire(scene,"sensor-input","sensor","connector1",pin(uno,"A1"));
            wire(scene,"button-gnd","manual","connector1",pin(uno,"GND"));wire(scene,"button-input","manual","connector0",pin(uno,"D2"));
            WireProject.write(new File(root,WireProject.SCENE),scene.toString());
            String code="#include <Arduino.h>\nvoid setup(){pinMode(13,OUTPUT);pinMode(A0,INPUT);pinMode(A1,INPUT);pinMode(A2,INPUT);pinMode(2,INPUT_PULLUP);Serial.begin(9600);}\nvoid loop(){int a=analogRead(A0);analogWrite(13,a*255/1023);Serial.print(\"POT=\");Serial.println(a);Serial.print(\"SENSOR=\");Serial.println(analogRead(A1));Serial.print(\"RAW=\");Serial.println(analogRead(A2));Serial.print(\"BUTTON=\");Serial.println(digitalRead(2));if(Serial.available()>0){Serial.print(\"RX:\");while(Serial.available()>0){int p=Serial.peek();int c=Serial.read();if(p==c)Serial.write((uint8_t)c);}Serial.println();}delay(150);}\n";
            File source=WireProject.sketch(root,"uno");WireProject.write(source,code);prefs.setProjectRoot(root.getPath());
            activity=startActivitySync(new Intent(getTargetContext(),com.ccs.wiredroid.ui.MainActivity.class).putExtra("wire_show",true).putExtra(com.ccs.wiredroid.ui.FileOpenActivity.EXTRA_OPEN_FILE,source.getPath()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            drawerPrefs=activity.getPreferences(Activity.MODE_PRIVATE);previousPinned=drawerPrefs.getBoolean("wire_pinned",true);
            Field field=activity.getClass().getDeclaredField("wireDrawer");field.setAccessible(true);WireDrawerController drawer=(WireDrawerController)field.get(activity);
            runOnMainSync(()->{drawer.show(false);if(drawer.canPin())drawer.setPinned(true);});web=drawer.getWebView();expect("WireApp.ready","true",15000);
            js("WireApp.checkCircuit();");expect("WireApp.getIssues().filter(i=>i.severity==='error').length","0",5000);
            js("WireApp.run()");expect("document.getElementById('serialOutput').textContent.includes('POT=256')","true",45000);
            expect("document.getElementById('serialOutput').textContent.includes('SENSOR=767')","true",10000);
            expect("document.getElementById('serialOutput').textContent.includes('RAW=512')","true",10000);
            js("WireApp.select('unknown')");expect("document.getElementById('details').textContent.includes('не реалізована')","true",5000);
            js("WireApp.setInput('unknown',.125)");expect("document.getElementById('serialOutput').textContent.includes('RAW=128')","true",10000);
            js("WireApp.setInput('pot',.9)");expect("document.getElementById('serialOutput').textContent.includes('POT=921')","true",10000);
            js("WireApp.setInput('sensor',.1)");expect("document.getElementById('serialOutput').textContent.includes('SENSOR=102')","true",10000);
            js("WireApp.pressButton('manual',true)");expect("document.getElementById('serialOutput').textContent.includes('BUTTON=0')","true",10000);js("WireApp.pressButton('manual',false)");
            js("document.getElementById('serialInput').value='A';document.getElementById('serialEnding').value='none';WireApp.sendSerial()");expect("document.getElementById('serialOutput').textContent.includes('RX:A')","true",10000);
            js("document.getElementById('serialInput').value='Ж';document.getElementById('serialEnding').value='none';WireApp.sendSerial()");expect("document.getElementById('serialOutput').textContent.includes('RX:Ж')","true",10000);
            js("document.getElementById('monitorTab').click();document.getElementById('signalSelect').value='uno:"+pin(uno,"D13")+"';document.getElementById('signalSelect').dispatchEvent(new Event('change'))");
            expect("document.getElementById('signalValue').textContent.includes('PWM')","true",5000);expect("!!document.querySelector('.plot-signal')","true",5000);
            Thread.sleep(350);
            try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getCacheDir(),"wiredroid-signals-preview.png"))){android.graphics.Bitmap image=getUiAutomation().takeScreenshot();image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);image.recycle();}
            js("document.getElementById('serialTab').click()");Thread.sleep(350);try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getCacheDir(),"wiredroid-serial-preview.png"))){android.graphics.Bitmap image=getUiAutomation().takeScreenshot();image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);image.recycle();}
            js("WireApp.stop();var u=WireApp.getScene().nodes.find(n=>n.id==='uno');WireApp.connectPin(u,{id:'"+pin(uno,"D2")+"',name:'D2'});WireApp.connectPin(u,{id:'"+pin(uno,"D3")+"',name:'D3'})");
            io.github.rosemoe.sora.widget.CodeEditor editor=activity.findViewById(com.ccs.wiredroid.R.id.editor);
            runOnMainSync(()->editor.setText("#include <Arduino.h>\nvoid setup(){pinMode(2,OUTPUT);pinMode(3,OUTPUT);} void loop(){delay(100);}"));
            js("WireApp.run()");expect("WireApp.getIssues().some(i=>i.code==='output-output')","true",45000);
            expect("document.getElementById('run').textContent.includes('Запустити')","true",5000);
            js("var u=WireApp.getScene().nodes.find(n=>n.id==='uno');WireApp.connectPin(u,{id:'"+pin(uno,"5V")+"',name:'5V'});WireApp.connectPin(u,{id:'"+pin(uno,"GND")+"',name:'GND'});WireApp.run()");
            expect("WireApp.getIssues().some(i=>i.code==='power-short')","true",5000);
            expect("document.getElementById('logs').textContent.includes('Виправте помилки з’єднання перед запуском')","true",5000);
            result.putString("stream","PASS: real Clang C++17, exact 10-bit analogRead through powered potentiometer/sensor, live input changes, manual ADC for unsupported sensor, button INPUT_PULLUP, PWM monitor trace, bidirectional Serial.available/peek/read/write/print with ASCII and UTF-8 echo; runtime output/output halt and preflight power-short halt.\n");finish(Activity.RESULT_OK,result);
        }catch(Throwable error){result.putString("stream","FAIL: "+android.util.Log.getStackTraceString(error));finish(Activity.RESULT_CANCELED,result);}
        finally {try{if(web!=null)js("WireApp.stop()");}catch(Exception ignored){}if(activity!=null){final Activity a=activity;runOnMainSync(a::finish);}if(drawerPrefs!=null)drawerPrefs.edit().putBoolean("wire_pinned",previousPinned).commit();prefs.setProjectRoot(previousRoot);prefs.removeRecentProject(root.getPath());WireProject.deleteNewProject(root);}
    }
}
