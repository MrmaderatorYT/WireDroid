package com.ccs.wiredroid.wire;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Actual Clang/process regression: concurrent projects share a board id, never a build. */
final class WireNativeSessionChecks {
    static String run(Context context)throws Exception {
        File batch=new File(context.getCacheDir(),"native-session-check-"+System.nanoTime());
        if(!batch.mkdirs())throw new IOException("Cannot create native session fixture");
        File a=null,b=null;EmbeddedRunner first=null,second=null;StringBuilder report=new StringBuilder();
        AtomicReference<String> failure=new AtomicReference<>();AtomicInteger aLines=new AtomicInteger(),bLines=new AtomicInteger();
        CountDownLatch aReady=new CountDownLatch(1),bReady=new CountDownLatch(1);
        try {
            a=WireExamples.createAt(context,new File(batch,"a"),"blink-uno");b=WireExamples.createAt(context,new File(batch,"b"),"blink-uno");
            WireProject.write(WireProject.sketch(a,"uno"),"#include <Arduino.h>\nvoid setup(){Serial.begin(9600);}\nvoid loop(){Serial.println(\"A\");delay(100);}\n");
            WireProject.write(WireProject.sketch(b,"uno"),"#include <Arduino.h>\nvoid setup(){Serial.begin(9600);}\nvoid loop(){Serial.println(\"B\");delay(100);}\n");
            first=new EmbeddedRunner(context,a,(id,event)->receive(event,"A",aLines,aReady,failure));
            second=new EmbeddedRunner(context,b,(id,event)->receive(event,"B",bLines,bReady,failure));
            String pins="[{\"id\":\"connector0\",\"name\":\"D13\"}]";
            String err=first.start("uno",pins);if(!err.isEmpty())throw new AssertionError(err);
            err=second.start("uno",pins);if(!err.isEmpty())throw new AssertionError(err);
            await(aReady,failure);await(bReady,failure);
            File firstDir=build(first),secondDir=build(second);
            if(firstDir.equals(secondDir)||!new File(firstDir,"simulation.elf").isFile()||!new File(secondDir,"simulation.elf").isFile())throw new AssertionError("Native builds share/miss executable");
            report.append("PASS native sessions: two actual concurrent Clang projects with id uno execute their own Serial output and private ELF paths\n");
            first.close();first=null;long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(firstDir.exists()&&System.nanoTime()<end)Thread.sleep(50);
            if(firstDir.exists())throw new AssertionError("Canceled session build not removed");
            int before=bLines.get();Thread.sleep(500);
            if(bLines.get()<=before||!new File(secondDir,"simulation.elf").isFile())throw new AssertionError("Canceling A disrupted B");
            report.append("PASS native cancellation: closed session files removed after child exit; concurrent B continues emitting Serial\n");
            second.stop("uno");end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(secondDir.exists()&&System.nanoTime()<end)Thread.sleep(50);
            if(secondDir.exists())throw new AssertionError("Stopped session build not removed");
            CountDownLatch restarted=new CountDownLatch(1);AtomicInteger restartedLines=new AtomicInteger();
            EmbeddedRunner retry=new EmbeddedRunner(context,b,(id,event)->receive(event,"B",restartedLines,restarted,failure));
            try {
                err=retry.start("uno",pins);if(!err.isEmpty())throw new AssertionError(err);await(restarted,failure);
                File next=build(retry);if(next.equals(firstDir)||next.equals(secondDir))throw new AssertionError("Restart reused prior process path");
                report.append("PASS native restart: same board/project compiles and executes again at a new private path\n");
            } finally {retry.close();}
            if(failure.get()!=null)throw new AssertionError(failure.get());return report.toString();
        } finally {
            if(first!=null)first.close();if(second!=null)second.close();
            WireProject.deleteNewProject(batch);
        }
    }
    private static void receive(JSONObject event,String marker,AtomicInteger lines,CountDownLatch ready,AtomicReference<String> failure) {
        String type=event.optString("type"),text=event.optString("text");
        if(type.equals("error")){failure.compareAndSet(null,text);ready.countDown();}
        if(type.equals("serial")) {
            if(text.contains(marker)){lines.incrementAndGet();ready.countDown();}
            if(text.contains(marker.equals("A")?"B":"A")){failure.compareAndSet(null,"Wrong project Serial: "+text);ready.countDown();}
        }
    }
    private static void await(CountDownLatch ready,AtomicReference<String> failure)throws Exception {
        if(!ready.await(150,TimeUnit.SECONDS))throw new AssertionError("Native session timeout");
        if(failure.get()!=null)throw new AssertionError(failure.get());
    }
    private static File build(EmbeddedRunner runner)throws Exception {
        Field field=EmbeddedRunner.class.getDeclaredField("sessions");field.setAccessible(true);
        Object session=((Map<?,?>)field.get(runner)).get("uno");if(session==null)throw new AssertionError("Missing session");
        field=session.getClass().getDeclaredField("build");field.setAccessible(true);return ((NativeBuildDirectory)field.get(session)).directory;
    }
}
