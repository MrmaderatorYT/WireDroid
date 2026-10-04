package com.ccs.wiredroid.wire;

import org.junit.Test;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class NativeBuildDirectoryTest {
    @Test public void concurrentSameBoardCompilersCannotMixObjectsOrExecutables()throws Exception {
        File cache=Files.createTempDirectory("native-isolation-").toFile();
        ExecutorService threads=Executors.newFixedThreadPool(2);CyclicBarrier written=new CyclicBarrier(2);
        java.util.List<NativeBuildDirectory> builds=new java.util.concurrent.CopyOnWriteArrayList<>();
        try {
            for(int round=0;round<20;round++) {
            File current=new File(cache,"round-"+round);java.util.List<Future<File>> results=new java.util.ArrayList<>();
            for(String owner:new String[]{"project A","project B"})results.add(threads.submit(()->{
                NativeBuildDirectory build=NativeBuildDirectory.create(current,"uno");builds.add(build);
                Files.write(new File(build.directory,"unit-0.o").toPath(),owner.getBytes(StandardCharsets.UTF_8));
                written.await(5,TimeUnit.SECONDS);
                byte[] object=Files.readAllBytes(new File(build.directory,"unit-0.o").toPath());
                assertEquals(owner,new String(object,StandardCharsets.UTF_8));
                File program=new File(build.directory,"simulation.elf");Files.write(program.toPath(),object);return program;
            }));
            File first=results.get(0).get(10,TimeUnit.SECONDS),second=results.get(1).get(10,TimeUnit.SECONDS);
            assertNotEquals(first.getCanonicalPath(),second.getCanonicalPath());
            for(NativeBuildDirectory build:builds)if(build.directory.equals(first.getParentFile()))build.close();
            assertFalse(first.exists());assertTrue(second.isFile());
            for(NativeBuildDirectory build:builds)build.close();builds.clear();
            }
        } finally {threads.shutdownNow();for(NativeBuildDirectory build:builds)build.close();remove(cache);}
    }

    @Test public void cancelledProcessExitsBeforeOwnedBuildIsRemovedAndRestartSurvives()throws Exception {
        File cache=Files.createTempDirectory("native-cancel-").toFile();
        NativeBuildDirectory old=NativeBuildDirectory.create(cache,"uno"),restart=NativeBuildDirectory.create(cache,"uno");
        EmbeddedRunner runner=new EmbeddedRunner(null,cache,(id,event)->{});Process process=null;
        try {
            File program=new File(restart.directory,"simulation.elf");Files.write(program.toPath(),new byte[]{4,2});
            process=new ProcessBuilder("/bin/sh","-c","exec sleep 60").start();assertTrue(process.isAlive());
            Class<?> type=Class.forName("com.ccs.wiredroid.wire.EmbeddedRunner$Session");
            java.lang.reflect.Constructor<?> ctor=type.getDeclaredConstructor(String.class,JSONArray.class,JSONObject.class);ctor.setAccessible(true);
            Object session=ctor.newInstance("uno",new JSONArray(),new JSONObject());
            java.lang.reflect.Field build=type.getDeclaredField("build"),child=type.getDeclaredField("process");build.setAccessible(true);child.setAccessible(true);build.set(session,old);child.set(session,process);
            java.lang.reflect.Method stop=type.getDeclaredMethod("stop");stop.setAccessible(true);stop.invoke(session);
            java.lang.reflect.Method finish=EmbeddedRunner.class.getDeclaredMethod("finishBuild",type);finish.setAccessible(true);finish.invoke(runner,session);
            assertFalse(process.isAlive());assertFalse(old.directory.exists());assertArrayEquals(new byte[]{4,2},Files.readAllBytes(program.toPath()));
        } finally {if(process!=null)process.destroyForcibly();runner.close();old.close();restart.close();remove(cache);}
    }

    @Test public void processStartedAfterCancellationStillBelongsToCleanup()throws Exception {
        File cache=Files.createTempDirectory("native-rejected-child-").toFile();NativeBuildDirectory owned=NativeBuildDirectory.create(cache,"uno");
        EmbeddedRunner runner=new EmbeddedRunner(null,cache,(id,event)->{});Process process=null;
        try {
            Class<?> type=Class.forName("com.ccs.wiredroid.wire.EmbeddedRunner$Session");
            java.lang.reflect.Constructor<?> ctor=type.getDeclaredConstructor(String.class,JSONArray.class,JSONObject.class);ctor.setAccessible(true);
            Object session=ctor.newInstance("uno",new JSONArray(),new JSONObject());
            java.lang.reflect.Field build=type.getDeclaredField("build"),child=type.getDeclaredField("process");build.setAccessible(true);child.setAccessible(true);build.set(session,owned);
            java.lang.reflect.Method stop=type.getDeclaredMethod("stop");stop.setAccessible(true);stop.invoke(session);
            process=new ProcessBuilder("/bin/sh","-c","exec sleep 60").start();
            java.lang.reflect.Method register=type.getDeclaredMethod("setProcess",Process.class);register.setAccessible(true);
            assertEquals(false,register.invoke(session,process));assertSame(process,child.get(session));
            java.lang.reflect.Method finish=EmbeddedRunner.class.getDeclaredMethod("finishBuild",type);finish.setAccessible(true);finish.invoke(runner,session);
            assertFalse(process.isAlive());assertFalse(owned.directory.exists());
        } finally {if(process!=null)process.destroyForcibly();runner.close();owned.close();remove(cache);}
    }

    @Test public void failedElfAndDigestSurviveCleanupAndOnlyLastFailureIsKept()throws Exception {
        File cache=Files.createTempDirectory("native-diagnostics-").toFile();NativeBuildDirectory build=NativeBuildDirectory.create(cache,"uno");
        try {
            byte[] elf={0x7f,'E','L','F',1,2,3};Files.write(new File(build.directory,"simulation.elf").toPath(),elf);
            Files.write(new File(build.directory,"compiler.log").toPath(),"link warning".getBytes(StandardCharsets.UTF_8));
            File saved=NativeBuildDirectory.preserveFailure(cache,cache,"uno",build.directory,139,"SIGSEGV");build.close();
            JSONObject info=new JSONObject(new String(Files.readAllBytes(new File(saved,"failure.json").toPath()),StandardCharsets.UTF_8));
            assertEquals(139,info.getInt("exitCode"));assertEquals(64,info.getString("sha256").length());assertArrayEquals(elf,Files.readAllBytes(new File(saved,"simulation.elf").toPath()));
            byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(elf);StringBuilder expected=new StringBuilder();for(byte b:digest)expected.append(String.format(java.util.Locale.ROOT,"%02x",b&255));assertEquals(expected.toString(),info.getString("sha256"));
            NativeBuildDirectory next=NativeBuildDirectory.create(cache,"nano");try {Files.write(new File(next.directory,"simulation.elf").toPath(),new byte[]{8});assertEquals(saved,NativeBuildDirectory.preserveFailure(cache,cache,"nano",next.directory,2,"error"));}finally{next.close();}
            JSONObject latest=new JSONObject(new String(Files.readAllBytes(new File(saved,"failure.json").toPath()),StandardCharsets.UTF_8));assertEquals("nano",latest.getString("board"));assertEquals(3,saved.list().length);
        } finally {build.close();remove(cache);}
    }

    @Test public void oversizedFailureDoesNotKeepPreviousElfAndLogIsBounded()throws Exception {
        File cache=Files.createTempDirectory("native-bounds-").toFile();NativeBuildDirectory build=NativeBuildDirectory.create(cache,"uno");
        try {
            File elf=new File(build.directory,"simulation.elf");Files.write(elf.toPath(),new byte[]{1});File saved=NativeBuildDirectory.preserveFailure(cache,cache,"uno",build.directory,139,"first");
            try(RandomAccessFile file=new RandomAccessFile(elf,"rw")){file.setLength(NativeBuildDirectory.MAX_FAILURE_ELF+1);}
            try(RandomAccessFile file=new RandomAccessFile(new File(build.directory,"compiler.log"),"rw")){file.setLength(2*1024*1024);}
            NativeBuildDirectory.preserveFailure(cache,cache,"uno",build.directory,139,"oversized");
            JSONObject info=new JSONObject(new String(Files.readAllBytes(new File(saved,"failure.json").toPath()),StandardCharsets.UTF_8));assertTrue(info.has("elfOmitted"));assertFalse(new File(saved,"simulation.elf").exists());assertTrue(info.getBoolean("compilerLogTruncated"));assertEquals(1024*1024,new File(saved,"compiler.log").length());
        } finally {build.close();remove(cache);}
    }

    @Test public void cleanupDoesNotFollowLinksOutsideTheSession()throws Exception {
        File cache=Files.createTempDirectory("native-links-").toFile();NativeBuildDirectory build=NativeBuildDirectory.create(cache,"uno");
        try {File outside=new File(cache,"keep");Files.write(outside.toPath(),new byte[]{9});Files.createSymbolicLink(new File(build.directory,"outside").toPath(),outside.toPath());build.close();assertTrue(outside.exists());}
        finally {build.close();remove(cache);}
    }
    @Test public void compilerCrashRetainsExactArgsLogAndInputsBeforeCleanup()throws Exception {
        File cache=Files.createTempDirectory("compiler-diagnostic-").toFile();NativeBuildDirectory build=NativeBuildDirectory.create(cache,"nano");
        try {
            File source=new File(build.directory,"sketch.cpp");byte[] code="#include <Arduino.h>\nvoid setup(){}".getBytes(StandardCharsets.UTF_8);Files.write(source.toPath(),code);
            Files.write(new File(build.directory,"pins.h").toPath(),"#define D13 13".getBytes(StandardCharsets.UTF_8));
            String output="PLEASE submit a bug report\n#0 stackframe\n";Files.write(new File(build.directory,"compiler.log").toPath(),output.getBytes(StandardCharsets.UTF_8));
            java.util.List<String> args=java.util.Arrays.asList("/app/libwiredroid_clang.so","-std=c++17","-c",source.getPath(),"-o",new File(build.directory,"unit-0.o").getPath());
            File saved=NativeBuildDirectory.preserveCompilerFailure(cache,cache,"nano",build.directory,139,"nonzero exit",args);build.close();
            JSONObject info=new JSONObject(new String(Files.readAllBytes(new File(saved,"failure.json").toPath()),StandardCharsets.UTF_8));assertEquals(139,info.getInt("exitCode"));assertEquals(new JSONArray(args).toString(),info.getJSONArray("argv").toString());assertFalse(info.getBoolean("argvTruncated"));
            assertEquals(output,new String(Files.readAllBytes(new File(saved,"compiler.log").toPath()),StandardCharsets.UTF_8));assertArrayEquals(code,Files.readAllBytes(new File(saved,"source.txt").toPath()));assertEquals(64,info.getJSONObject("source").getString("sha256").length());
        } finally {build.close();remove(cache);}
    }
    private static void remove(File root)throws IOException {try(java.util.stream.Stream<Path> files=Files.walk(root.toPath())){for(Path file:files.sorted(java.util.Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList()))Files.deleteIfExists(file);}}
}
