package com.ccs.wiredroid.wire;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.Files;

public class EmbeddedBuildTest {
    private static void write(File root,String path,String text)throws Exception{File file=new File(root,path);file.getParentFile().mkdirs();Files.write(file.toPath(),text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    @Test public void compilesSharedSourcesAndTransitiveArduinoLibraries()throws Exception{
        File root=Files.createTempDirectory("wire-build-").toFile();
        try{
            write(root,"circuit.wire.json","{\"version\":1,\"nodes\":[{\"id\":\"uno\",\"x\":0,\"y\":0},{\"id\":\"nano\",\"x\":100,\"y\":0}],\"wires\":[]}");
            write(root,"sketches/uno.cpp","#include \"helper.h\"\nvoid setup(){} void loop(){}");
            write(root,"sketches/nano.cpp","void setup(){} void loop(){}");
            write(root,"sketches/helper.h","#include <Fancy.h>\n");
            write(root,"sketches/helper.cpp","int helper(){return 1;}");
            write(root,"src/shared.c","int shared(){return 2;}");
            write(root,"libraries/Fancy/src/Fancy.h","#include <Dependency.h>\n");
            write(root,"libraries/Fancy/src/Fancy.cpp","int fancy(){return 3;}");
            write(root,"libraries/Fancy/library.properties","name=Fancy Display\ndepends=Support (>=1.0.0)\n");
            write(root,"libraries/Support/src/Dependency.h","int support();\n");
            write(root,"libraries/Support/src/support.cpp","int support(){return 4;}");
            write(root,"libraries/Support/examples/demo/demo.cpp","void setup(){} void loop(){}");
            EmbeddedBuild.Plan plan=EmbeddedBuild.plan(root,new File(root,"sketches/uno.cpp"));
            assertEquals(5,plan.sources.size());assertTrue(plan.libraries.contains("Fancy"));assertTrue(plan.libraries.contains("Support"));
            assertFalse(plan.sources.stream().anyMatch(f->f.getName().equals("nano.cpp")||f.getPath().contains("examples")));
        }finally{WireProject.deleteNewProject(root);}
    }
    @Test public void reportsMissingDependenciesAndDoesNotCompileIncludedCppTwice()throws Exception{
        File root=Files.createTempDirectory("wire-build-").toFile();
        try{
            write(root,"sketches/uno.cpp","#include \"included.cpp\"\nvoid setup(){} void loop(){}");
            write(root,"sketches/included.cpp","int included(){return 1;}");
            assertEquals(1,EmbeddedBuild.plan(root,new File(root,"sketches/uno.cpp")).sources.size());
            write(root,"wiredroid.json","{\"libraries\":[\"Missing\"]}");
            try{EmbeddedBuild.plan(root,new File(root,"sketches/uno.cpp"));fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("Missing"));}
        }finally{WireProject.deleteNewProject(root);}
    }
}
