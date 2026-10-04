package com.ccs.wiredroid.wire;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.Files;

public class SpiceArtifactsTest {
    @Test public void savesProjectModelsAndNetlistThroughRecovery()throws Exception {
        File root=Files.createTempDirectory("wire-spice-").toFile();try {
            assertEquals("",SpiceArtifacts.readModels(root));SpiceArtifacts.saveModels(root,".model D1 D(IS=1e-9)\n");
            assertEquals(".model D1 D(IS=1e-9)\n",SpiceArtifacts.readModels(root));
            SpiceArtifacts.saveNetlist(root,"demo.cir","* circuit\nR1 a 0 1000\n.end\n");
            assertTrue(new File(root,"exports/demo.cir").isFile());assertFalse(new File(root,"exports/demo.cir.cir").exists());
        }finally{WireProject.deleteNewProject(root);}
    }
    @Test public void rejectsPathsExternalIncludesAndControlBeforeWrite()throws Exception {
        File root=Files.createTempDirectory("wire-spice-safe-").toFile();try {
            for(String text:new String[]{".include ../secret\n"," .LIB remote.lib\n",".control\nshell command\n.endc\n","!command\n",".hdl external.v\n"}) {
                try{SpiceArtifacts.saveModels(root,text);fail("external SPICE accepted");}catch(IOException expected){}
            }
            try{SpiceArtifacts.saveNetlist(root,"../escape",".end\n");fail();}catch(IOException expected){}
            assertFalse(new File(root,"spice.models").exists());assertFalse(new File(root,"exports").exists());
        }finally{WireProject.deleteNewProject(root);}
    }
    @Test public void limitsUtf8BytesAndRejectsEscapingExportDirectory()throws Exception {
        File root=Files.createTempDirectory("wire-spice-size-").toFile(),outside=Files.createTempDirectory("wire-spice-out-").toFile();try {
            try{SpiceArtifacts.saveModels(root,new String(new char[140000]).replace('\0','ї'));fail();}catch(IOException expected){}
            Files.createSymbolicLink(new File(root,"exports").toPath(),outside.toPath());
            try{SpiceArtifacts.saveNetlist(root,"escape",".end\n");fail();}catch(IOException expected){}
            assertFalse(new File(outside,"escape.cir").exists());
        }finally{Files.deleteIfExists(new File(root,"exports").toPath());WireProject.deleteNewProject(root);WireProject.deleteNewProject(outside);}
    }
}
