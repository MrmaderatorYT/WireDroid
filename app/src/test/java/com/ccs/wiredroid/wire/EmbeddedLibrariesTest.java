package com.ccs.wiredroid.wire;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.Files;
import java.util.zip.*;
import org.json.*;
public class EmbeddedLibrariesTest {
 private static File zip(File root,String name,String version,String dependency)throws Exception{File file=new File(root,name+".zip");try(ZipOutputStream z=new ZipOutputStream(new FileOutputStream(file))){z.putNextEntry(new ZipEntry(name+"-release/library.properties"));z.write(("name="+name+"\nversion="+version+"\narchitectures=avr\ndepends="+dependency+"\n").getBytes());z.closeEntry();z.putNextEntry(new ZipEntry(name+"-release/src/"+name+".h"));z.write("int helper();\n".getBytes());z.closeEntry();}return file;}
 @Test public void pinsVersionRestoresOfflineAndChecksDependency()throws Exception{
  File root=Files.createTempDirectory("wire-libraries-").toFile(),cache=Files.createTempDirectory("wire-cache-").toFile();try{
   EmbeddedLibraries.install(root,zip(root,"Dependency","1.2.0",""),cache);JSONObject installed=EmbeddedLibraries.install(root,zip(root,"Display","2.0.0","Dependency (>=1.0.0 && <2.0.0)"),cache);assertEquals("2.0.0",installed.getString("version"));EmbeddedLibraries.verifyLock(root);assertEquals(2,EmbeddedLibraries.list(root).getJSONArray("libraries").length());
   Files.write(new File(root,"libraries/Display/src/Display.h").toPath(),"changed".getBytes(java.nio.charset.StandardCharsets.UTF_8));try{EmbeddedLibraries.verifyLock(root);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("Display"));}
   EmbeddedLibraries.restore(root,cache);EmbeddedLibraries.verifyLock(root);assertEquals("int helper();\n",new String(Files.readAllBytes(new File(root,"libraries/Display/src/Display.h").toPath()),java.nio.charset.StandardCharsets.UTF_8));
   assertFalse(EmbeddedLibraries.compatibility(root,"esp32","mcu").getJSONObject(0).getBoolean("architectureCompatible"));
   EmbeddedLibraries.remove(root,"Dependency");try{EmbeddedLibraries.verifyLock(root);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("Dependency"));}
  }finally{WireProject.deleteNewProject(root);WireProject.deleteNewProject(cache);}
 }
 @Test public void rejectsZipTraversalAndChecksConstraints()throws Exception{
  File root=Files.createTempDirectory("wire-zip-").toFile(),cache=Files.createTempDirectory("wire-cache-").toFile();try{
   File archive=new File(root,"bad.zip");try(ZipOutputStream z=new ZipOutputStream(new FileOutputStream(archive))){z.putNextEntry(new ZipEntry("../escaped"));z.write(1);z.closeEntry();}
   try{EmbeddedLibraries.install(root,archive,cache);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("ZIP"));}
   assertTrue(EmbeddedLibraries.satisfies("1.2.0",">=1.0 && <2.0"));assertFalse(EmbeddedLibraries.satisfies("2.0.0","<2.0"));assertTrue(EmbeddedLibraries.satisfies("3.0.0","=1.0 || >=3.0"));
  }finally{WireProject.deleteNewProject(root);WireProject.deleteNewProject(cache);}
 }
}
