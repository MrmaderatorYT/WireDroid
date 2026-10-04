package com.ccs.wiredroid.wire;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** The firmware snapshot is freshness evidence; imported HEX is not build provenance. */
public class FirmwareArtifactsTest {
    private static final String HEX=":0400000001020304F2\n:00000001FF\n";
    private static File write(File root,String path,String text)throws Exception {
        File file=new File(root,path);file.getParentFile().mkdirs();Files.write(file.toPath(),text.getBytes(StandardCharsets.UTF_8));return file;
    }
    private static File project()throws Exception {
        File root=Files.createTempDirectory("wire-firmware-").toFile();
        FirmwareConfig.save(root,"uno","{\"profile\":\"arduino:avr:uno\",\"mode\":\"mcu\",\"coreVersion\":\"1.8.6\"}");
        write(root,"sketches/uno.cpp","void setup(){} void loop(){}\n");return root;
    }
    private static void imported(File root)throws Exception {
        FirmwareArtifacts.importFile(root,"uno",write(root,"download.hex",HEX));
    }
    private static void rejectsRead(File root,String contains)throws Exception {
        try{FirmwareArtifacts.read(root,"uno");fail("Expected rejected firmware");}
        catch(IOException expected){assertTrue(expected.getMessage(),expected.getMessage().contains(contains));}
    }
    @Test public void readsVerifiedImportedHexAndRecordsSnapshotScope()throws Exception {
        File root=project();try {
            imported(root);assertEquals(HEX,FirmwareArtifacts.read(root,"uno"));
            JSONObject metadata=new JSONObject(WireProject.readFile(new File(root,".wiredroid/firmware/uno.json"),128*1024));
            assertEquals(1,metadata.getInt("version"));assertEquals("local-import",metadata.getString("origin"));
            assertEquals(FirmwareArtifacts.sourceDigest(root),metadata.getString("sourceDigest"));
            assertEquals(FirmwareArtifacts.configDigest(root,"uno"),metadata.getString("configDigest"));
            assertTrue(metadata.getString("sourceRelationship").contains("not verified"));
        }finally{WireProject.deleteNewProject(root);}
    }
    @Test public void rejectsSourceChangesOnAnyBoardAndHeaders()throws Exception {
        File root=project();try {
            write(root,"src/boards/nano/other.cpp","int unrelatedBoard;\n");imported(root);
            write(root,"src/boards/nano/other.cpp","int changedBoard;\n");rejectsRead(root,"C/C++");
            imported(root);write(root,"include/config.h","#define PIN 12\n");rejectsRead(root,"C/C++");
        }finally{WireProject.deleteNewProject(root);}
    }
    @Test public void rejectsProfileAndCoreChanges()throws Exception {
        File root=project();try {
            imported(root);FirmwareConfig.save(root,"uno","{\"profile\":\"arduino:avr:nano:atmega328\",\"coreVersion\":\"1.8.6\"}");rejectsRead(root,"core");
            FirmwareConfig.save(root,"uno","{\"profile\":\"arduino:avr:uno\",\"coreVersion\":\"1.8.6\"}");imported(root);
            FirmwareConfig.save(root,"uno","{\"profile\":\"arduino:avr:uno\",\"coreVersion\":\"1.8.7\"}");rejectsRead(root,"core");
        }finally{WireProject.deleteNewProject(root);}
    }
    @Test public void rejectsChecksumValidHexTamperingAndMissingMetadata()throws Exception {
        File root=project();try {
            imported(root);WireProject.write(new File(root,".wiredroid/firmware/uno.hex"),":0400000000000000FC\n:00000001FF\n");rejectsRead(root,"після імпорту");
            WireProject.deleteNewProject(new File(root,".wiredroid/firmware"));
            WireProject.write(new File(root,".wiredroid/firmware/uno.hex"),HEX);rejectsRead(root,"відомостей");
        }finally{WireProject.deleteNewProject(root);}
    }
    @Test public void digestHasStablePathByteContractAndSkipsHiddenBuild()throws Exception {
        File root=Files.createTempDirectory("wire-digest-").toFile();try {
            write(root,"src/Z.CPP","Z\r\n");write(root,"include/a.HXX","A\n");
            write(root,"src/readme.txt","ignored");write(root,".cache/skip.cpp","ignored");write(root,"build/skip.cpp","ignored");write(root,"src/.skip.cpp","ignored");
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            digest.update("include/a.HXX\0A\n\0src/Z.CPP\0Z\r\n\0".getBytes(StandardCharsets.UTF_8));
            StringBuilder expected=new StringBuilder();for(byte value:digest.digest())expected.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
            assertEquals(expected.toString(),FirmwareArtifacts.sourceDigest(root));
            String previous=FirmwareArtifacts.sourceDigest(root);Files.move(new File(root,"src/Z.CPP").toPath(),new File(root,"src/A.CPP").toPath());assertNotEquals(previous,FirmwareArtifacts.sourceDigest(root));
        }finally{WireProject.deleteNewProject(root);}
    }
    @Test public void digestRejectsEscapingSourceSymlinkAndOversizedSource()throws Exception {
        File root=Files.createTempDirectory("wire-digest-bounds-").toFile(),external=Files.createTempDirectory("wire-external-").toFile();try {
            File other=write(external,"outside.cpp","int secret;");Files.createSymbolicLink(new File(root,"alias.cpp").toPath(),other.toPath());
            try{FirmwareArtifacts.sourceDigest(root);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("поза"));}
            Files.delete(new File(root,"alias.cpp").toPath());Files.write(new File(root,"oversized.cpp").toPath(),new byte[2*1024*1024+1]);
            try{FirmwareArtifacts.sourceDigest(root);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("ліміт"));}
        }finally{WireProject.deleteNewProject(root);WireProject.deleteNewProject(external);}
    }
    @Test public void rejectsDefinesSourcesExcludesIncludesAndStandardWithoutSourceChanges()throws Exception {
        File root=project();try {
            String source=FirmwareArtifacts.sourceDigest(root);
            for(String setting:new String[]{"\"defines\":[\"VALUE=2\"]","\"sources\":[\"src/**\"]","\"exclude\":[\"src/skipped.cpp\"]","\"includePaths\":[\"headers\"]","\"cppStandard\":\"gnu++17\""}) {
                FirmwareConfig.save(root,"uno","{\"profile\":\"arduino:avr:uno\",\"coreVersion\":\"1.8.6\"}");imported(root);
                FirmwareConfig.save(root,"uno","{\"profile\":\"arduino:avr:uno\",\"coreVersion\":\"1.8.6\","+setting+"}");
                assertEquals(source,FirmwareArtifacts.sourceDigest(root));rejectsRead(root,"Налаштування");
            }
        }finally{WireProject.deleteNewProject(root);}
    }
    @Test public void rejectsLockVersionAndPresenceChangesButAllowsMcuUsbModeChange()throws Exception {
        File root=project();try {
            imported(root);FirmwareConfig.save(root,"uno","{\"profile\":\"arduino:avr:uno\",\"coreVersion\":\"1.8.6\",\"mode\":\"usb\"}");assertEquals(HEX,FirmwareArtifacts.read(root,"uno"));
            String source=FirmwareArtifacts.sourceDigest(root);
            write(root,"wiredroid-libraries.lock.json","{\"version\":1,\"libraries\":[]}");rejectsRead(root,"Налаштування");imported(root);
            write(root,"wiredroid-libraries.lock.json","{\"version\":2,\"libraries\":[]}");assertEquals(source,FirmwareArtifacts.sourceDigest(root));rejectsRead(root,"Налаштування");
            imported(root);Files.delete(new File(root,"wiredroid-libraries.lock.json").toPath());rejectsRead(root,"Налаштування");
        }finally{WireProject.deleteNewProject(root);}
    }
    @Test public void configDigestMatchesPythonGoldenOrderedNulStream()throws Exception {
        File root=project();try {
            String stream="wiredroid-config-v1\0profile\0arduino:avr:uno\0coreVersion\0"+"1.8.6\0cppStandard\0gnu++11\0defines\0"+"0\0sources\0"+"0\0exclude\0"+"0\0includePaths\0"+"0\0libraries\0"+"0\0libraryLock\0absent\0";
            MessageDigest digest=MessageDigest.getInstance("SHA-256");digest.update(stream.getBytes(StandardCharsets.UTF_8));StringBuilder expected=new StringBuilder();for(byte value:digest.digest())expected.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
            assertEquals(expected.toString(),FirmwareArtifacts.configDigest(root,"uno"));
            assertEquals("3340e25f66e7c893fbe297f5f92a4573ef53eed5f7316e0b6ab38ccd772d6664",expected.toString());
            write(root,"wiredroid-libraries.lock.json","{\"version\":1,\"libraries\":[]}");
            assertEquals("0a9607135771c648fcb0482ec3fcdc0a2db2a577174b6bad8110694a31fea7c2",FirmwareArtifacts.configDigest(root,"uno"));
        }finally{WireProject.deleteNewProject(root);}
    }
    @Test public void configDigestPreservesDefineIncludeOrderAndExplicitLibraries()throws Exception {
        File root=project();try {
            FirmwareConfig.save(root,"uno","{\"profile\":\"arduino:avr:uno\",\"defines\":[\"VALUE=1\",\"VALUE=2\"],\"includePaths\":[\"first\",\"second\"]}");String first=FirmwareArtifacts.configDigest(root,"uno");
            FirmwareConfig.save(root,"uno","{\"profile\":\"arduino:avr:uno\",\"defines\":[\"VALUE=2\",\"VALUE=1\"],\"includePaths\":[\"first\",\"second\"]}");assertNotEquals(first,FirmwareArtifacts.configDigest(root,"uno"));
            imported(root);JSONObject settings=new JSONObject(WireProject.readFile(new File(root,"wiredroid.json"),128*1024));settings.put("libraries",new org.json.JSONArray().put("Extra"));WireProject.write(new File(root,"wiredroid.json"),settings.toString());rejectsRead(root,"Налаштування");
            settings.put("libraries",new org.json.JSONArray().put("X\0Y"));WireProject.write(new File(root,"wiredroid.json"),settings.toString());try{FirmwareArtifacts.configDigest(root,"uno");fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("Некоректне"));}
        }finally{WireProject.deleteNewProject(root);}
    }
}
