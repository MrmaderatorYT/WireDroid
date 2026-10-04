package com.ccs.wiredroid.wire;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.File;
import java.io.IOException;

public class WireProjectTest {
    private static final String VALID = "{\"version\":1,\"nodes\":[{\"id\":\"uno\",\"x\":0,\"y\":0}],\"wires\":[]}";

    @Test public void migratesLegacySceneWithoutLosingCustomDataOrRoutes()throws Exception {
        String raw=VALID.replace("\"x\":0","\"scale\":0.5,\"custom\":{\"code\":\"sketches/uno.ino\"},\"x\":0")
            .replace("\"wires\":[]","\"wires\":[{\"a\":{\"node\":\"uno\"},\"b\":{\"node\":\"uno\"},\"points\":[{\"x\":4,\"y\":5}],\"color\":\"#abcdef\"}]");
        org.json.JSONObject scene=new org.json.JSONObject(WireProject.migrateScene(raw));
        assertEquals(2,scene.getInt("schemaVersion"));assertEquals(1,scene.getInt("version"));
        org.json.JSONObject node=scene.getJSONArray("nodes").getJSONObject(0),wire=scene.getJSONArray("wires").getJSONObject(0);
        assertEquals(.5,node.getDouble("scale"),0);assertEquals("sketches/uno.ino",node.getJSONObject("custom").getString("code"));
        assertEquals("wire_legacy_0",wire.getString("id"));assertEquals(4,wire.getJSONArray("points").getJSONObject(0).getInt("x"));
        assertEquals(scene.toString(),WireProject.migrateScene(scene.toString()));
        try{WireProject.migrateScene(VALID.replace("\"version\":1","\"version\":1,\"schemaVersion\":99"));fail();}catch(IOException expected){}
    }

    @Test public void acceptsValidSceneAndRejectsUnknownVersion() throws Exception {
        WireProject.validateScene(VALID);
        try { WireProject.validateScene(VALID.replace("\"version\":1", "\"version\":9")); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("версія")); }
    }

    @Test public void rejectsDanglingWires() throws Exception {
        String json = VALID.replace("\"wires\":[]", "\"wires\":[{\"a\":{\"node\":\"uno\"},\"b\":{\"node\":\"missing\"}}]");
        try { WireProject.validateScene(json); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("Дріт")); }
    }
    @Test public void validatesPerPartSizeWhileAcceptingOlderScenes() throws Exception {
        WireProject.validateScene(VALID);
        for(double scale:new double[]{.25,.5,1,2})WireProject.validateScene(VALID.replace("\"x\":0","\"scale\":"+scale+",\"x\":0"));
        for(double scale:new double[]{0,.1,2.01,100}) {
            try{WireProject.validateScene(VALID.replace("\"x\":0","\"scale\":"+scale+",\"x\":0"));fail();}
            catch(IOException expected){assertTrue(expected.getMessage().contains("Розмір"));}
        }
    }

    @Test public void acceptsRoutesAndInputsAndRejectsOutOfRangeData() throws Exception {
        String wire="{\"a\":{\"node\":\"uno\"},\"b\":{\"node\":\"uno\"},\"routing\":\"orthogonal\",\"points\":[{\"x\":30,\"y\":80}],\"color\":\"#ff0000\"}";
        String routed=VALID.replace("\"wires\":[]", "\"wires\":["+wire+"]").replace("\"x\":0", "\"inputValue\":0.5,\"simulation\":{\"pin\":\"connector1\"},\"x\":0");
        WireProject.validateScene(routed);
        for(String invalid:new String[]{routed.replace("orthogonal","bogus"),routed.replace("\"x\":30","\"x\":100001"),routed.replace("\"inputValue\":0.5","\"inputValue\":2")}) {
            try {WireProject.validateScene(invalid);fail();} catch(IOException expected) {}
        }
    }

    @Test public void preventsSketchPathTraversal() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"), "wire-test");
        assertTrue(WireProject.sketch(root, "uno").getPath().endsWith("sketches" + File.separator + "uno.cpp"));
        for (String bad : new String[]{"../../secret", "/tmp/file", "", "../board", "board.js"}) {
            try { WireProject.sketch(root, bad); fail(bad); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("ID")); }
        }
    }

    @Test public void preservesLegacyInoAndPrefersCppWhenBothExist() throws Exception {
        java.nio.file.Path root=java.nio.file.Files.createTempDirectory("wire-legacy-");
        java.nio.file.Path sketches=java.nio.file.Files.createDirectory(root.resolve("sketches"));
        java.nio.file.Path ino=sketches.resolve("uno.ino"),cpp=sketches.resolve("uno.cpp");
        try {
            java.nio.file.Files.write(ino,"original Arduino program".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(ino.toFile().getCanonicalFile(),WireProject.sketch(root.toFile(),"uno"));
            assertFalse(java.nio.file.Files.exists(cpp));
            java.nio.file.Files.write(cpp,"new C++ program".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(cpp.toFile().getCanonicalFile(),WireProject.sketch(root.toFile(),"uno"));
            assertEquals("original Arduino program",new String(java.nio.file.Files.readAllBytes(ino),java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            java.nio.file.Files.deleteIfExists(cpp);java.nio.file.Files.deleteIfExists(ino);
            java.nio.file.Files.deleteIfExists(sketches);java.nio.file.Files.deleteIfExists(root);
        }
    }

    @Test public void duplicatesBoardProgramWithoutOverwritingAnotherSketch()throws Exception {
        File root=java.nio.file.Files.createTempDirectory("wire-copy-").toFile();
        try {
            File source=WireProject.sketch(root,"uno");WireProject.write(source,"// custom board C++\nint counter=42;");
            WireProject.duplicateSketch(root,"uno","uno2");assertEquals(WireProject.readFile(source,4096),WireProject.readFile(WireProject.sketch(root,"uno2"),4096));
            try{WireProject.duplicateSketch(root,"uno","uno2");fail();}catch(IOException expected){}
            try{WireProject.duplicateSketch(root,"uno","../outside");fail();}catch(IOException expected){}
            assertEquals("// custom board C++\nint counter=42;",WireProject.readFile(WireProject.sketch(root,"uno2"),4096));
        }finally{WireProject.deleteNewProject(root);}
    }

    @Test public void rejectsCppAndLegacyInoSymlinksOutsideTheProject() throws Exception {
        java.nio.file.Path root=java.nio.file.Files.createTempDirectory("wire-links-");
        java.nio.file.Path outside=java.nio.file.Files.createTempFile("wire-outside-",".cpp");
        java.nio.file.Path sketches=java.nio.file.Files.createDirectory(root.resolve("sketches"));
        try {
            for(String extension:new String[]{"cpp","ino"}) {
                java.nio.file.Path link=sketches.resolve("uno."+extension);
                java.nio.file.Files.createSymbolicLink(link,outside);
                try {WireProject.sketch(root.toFile(),"uno");fail(extension);}
                catch(IOException expected){assertTrue(expected.getMessage().contains("межами"));}
                finally {java.nio.file.Files.delete(link);}
            }
        } finally {
            java.nio.file.Files.deleteIfExists(sketches);java.nio.file.Files.deleteIfExists(root);java.nio.file.Files.deleteIfExists(outside);
        }
    }
}
