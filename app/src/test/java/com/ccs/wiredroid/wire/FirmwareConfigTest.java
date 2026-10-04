package com.ccs.wiredroid.wire;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.Files;
public class FirmwareConfigTest {
 private static void write(File root,String path,String text)throws Exception{File f=new File(root,path);f.getParentFile().mkdirs();Files.write(f.toPath(),text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
 @Test public void scopesSourcesAndExcludesOtherBoardUnderSrc()throws Exception{
  File root=Files.createTempDirectory("wire-target-").toFile();try{
   write(root,"circuit.wire.json","{\"version\":1,\"nodes\":[{\"id\":\"uno\",\"x\":0,\"y\":0},{\"id\":\"nano\",\"x\":100,\"y\":0}],\"wires\":[]}");
   write(root,"sketches/uno.cpp","void setup(){} void loop(){}");write(root,"src/shared.cpp","int shared;");
   write(root,"src/boards/uno/helper.cpp","int uno;");write(root,"src/boards/nano/nano.cpp","void setup(){} void loop(){}");
   write(root,"src/skip.cpp","int skipped;");
   FirmwareConfig.save(root,"uno","{\"profile\":\"arduino:avr:uno\",\"mode\":\"mcu\",\"sources\":[\"src/shared.cpp\",\"src/boards/uno/**\"],\"defines\":[\"MY_PIN=13\"]}");
   EmbeddedBuild.Plan plan=EmbeddedBuild.plan(root,new File(root,"sketches/uno.cpp"),"uno");assertEquals(3,plan.sources.size());assertEquals("mcu",plan.target.mode);assertTrue(plan.target.defines.contains("MY_PIN=13"));assertFalse(plan.sources.stream().anyMatch(f->f.getName().equals("nano.cpp")||f.getName().equals("skip.cpp")));
  }finally{WireProject.deleteNewProject(root);}
 }
 @Test public void rejectsEscapingGlobsAndInvalidDefines()throws Exception{
  File root=Files.createTempDirectory("wire-settings-").toFile();try{for(String settings:new String[]{"{\"sources\":[\"../outside.cpp\"]}","{\"defines\":[\"X;evil\"]}"})try{FirmwareConfig.save(root,"uno",settings);fail();}catch(IOException expected){}assertTrue(FirmwareConfig.matchesAny(java.util.List.of("src/**/*.cpp"),"src/helper.cpp"));assertTrue(FirmwareConfig.matchesAny(java.util.List.of("src/**/*.cpp"),"src/deep/helper.cpp"));}finally{WireProject.deleteNewProject(root);}
 }
}
