package com.ccs.wiredroid.wire;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.util.List;
import static org.junit.Assert.*;

public class CompilerDiagnosticsTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();
    @Test public void parsesAbsoluteAndRelativeClangLocationsWithSpaces()throws Exception {
        File root=folder.newFolder("project with spaces"),source=new File(root,"main.cpp");Files.write(source.toPath(),new byte[0]);
        String log="\u001b[31m"+source.getPath()+":12:7: error: undeclared identifier 'oops'\u001b[0m\nmain.cpp:4: warning: unused variable\nmain.cpp:12:7: error: undeclared identifier 'oops'\n";
        List<CompilerDiagnostics.Diagnostic> items=CompilerDiagnostics.parse(root,log);
        assertEquals(2,items.size());assertEquals("main.cpp",items.get(0).path);assertEquals(12,items.get(0).line);assertEquals(7,items.get(0).column);assertEquals(1,items.get(1).column);
    }
    @Test public void doesNotNavigateToCompilerInternalsOrOutsideSymlinks()throws Exception {
        File root=folder.newFolder("project"),outside=folder.newFile("outside.cpp");
        Files.createSymbolicLink(new File(root,"link.cpp").toPath(),outside.toPath());
        assertTrue(CompilerDiagnostics.parse(root,outside.getPath()+":1:1: error: x\nlink.cpp:1: error: y\n<command line>:1: error: z\n").isEmpty());
        try{CompilerDiagnostics.resolve(root,"../outside.cpp");fail();}catch(IOException expected){}
    }
    @Test public void ignoresOverflowLocationsAndCapsDiagnostics()throws Exception {
        File file=folder.newFile("main.cpp");StringBuilder log=new StringBuilder("main.cpp:99999999999999: error: invalid line\n");
        for(int i=1;i<300;i++)log.append("main.cpp:").append(i).append(":3: note: context\n");
        assertEquals(200,CompilerDiagnostics.parse(folder.getRoot(),log.toString()).size());
    }
}
