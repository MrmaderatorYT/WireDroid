package com.ccs.wiredroid.wire;

import org.json.JSONObject;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class RecoveryStoreTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();
    private File file() {return new File(folder.getRoot(),"circuit.wire.json");}
    private static String scene(String name){return "{\"version\":1,\"name\":\""+name+"\",\"nodes\":[{\"id\":\"uno\",\"x\":0,\"y\":0}],\"wires\":[]}";}
    private static void direct(File file,String raw)throws Exception{file.getParentFile().mkdirs();Files.write(file.toPath(),raw.getBytes(StandardCharsets.UTF_8));}
    private static void pending(File file,String value,boolean complete)throws Exception {
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
        direct(RecoveryStore.sidecar(file,"pending"),complete?value:value.substring(0,value.length()/2));
        direct(RecoveryStore.sidecar(file,"journal"),new JSONObject().put("version",1).put("length",bytes.length).put("sha256",RecoveryStore.digest(bytes)).toString());
    }
    @Test public void commitsCheckedPendingAfterInterruptedWrite()throws Exception {
        File file=file();RecoveryStore.write(file,scene("old"),WireProject::validateScene);
        pending(file,scene("new"),true);
        RecoveryStore.Read result=RecoveryStore.read(file,4096,WireProject::validateScene);
        assertTrue(result.recovered);assertTrue(result.text.contains("new"));assertFalse(RecoveryStore.sidecar(file,"journal").exists());
        assertEquals(result.text,new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
    }
    @Test public void rejectsPartialPendingWithoutDiscardingLastSavedScene()throws Exception {
        File file=file();RecoveryStore.write(file,scene("old"),WireProject::validateScene);pending(file,scene("new"),false);
        RecoveryStore.Read result=RecoveryStore.read(file,4096,WireProject::validateScene);
        assertTrue(result.recovered);assertTrue(result.text.contains("old"));assertFalse(RecoveryStore.sidecar(file,"pending").exists());
    }
    @Test public void recoversBackupAfterPrimaryCorruption()throws Exception {
        File file=file();RecoveryStore.write(file,scene("old"),WireProject::validateScene);RecoveryStore.write(file,scene("new"),WireProject::validateScene);
        direct(file,"{\"version\":1,");RecoveryStore.Read result=RecoveryStore.read(file,4096,WireProject::validateScene);
        assertTrue(result.recovered);assertTrue(result.text.contains("old"));WireProject.validateScene(result.text);
    }
    @Test public void doesNotRestoreAStaleBackupOverAnExternalEdit()throws Exception {
        File file=file();RecoveryStore.write(file,scene("old"),WireProject::validateScene);RecoveryStore.write(file,scene("new"),WireProject::validateScene);
        direct(file,scene("external"));RecoveryStore.Read result=RecoveryStore.read(file,4096,WireProject::validateScene);
        assertFalse(result.recovered);assertTrue(result.text.contains("external"));
    }
    @Test public void journalCanFinishAfterRenameWithoutPendingFile()throws Exception {
        File file=file();String value=scene("committed");pending(file,value,true);
        Files.move(RecoveryStore.sidecar(file,"pending").toPath(),file.toPath());
        RecoveryStore.Read result=RecoveryStore.read(file,4096,WireProject::validateScene);
        assertTrue(result.recovered);assertEquals(value,result.text);assertFalse(RecoveryStore.sidecar(file,"journal").exists());
    }
    @Test public void validatesBeforeReplacingAnyProjectData()throws Exception {
        File file=file();RecoveryStore.write(file,scene("old"),WireProject::validateScene);
        try{RecoveryStore.write(file,"bad",WireProject::validateScene);fail();}catch(IOException expected){}
        assertTrue(RecoveryStore.read(file,4096,WireProject::validateScene).text.contains("old"));
        assertFalse(RecoveryStore.sidecar(file,"journal").exists());
    }
    @Test public void recoversMissingCppWithoutGeneratingDefaultSketch()throws Exception {
        File sketches=folder.newFolder("sketches");File cpp=new File(sketches,"uno.cpp");
        RecoveryStore.write(cpp,"original C++");RecoveryStore.write(cpp,"latest C++");Files.delete(cpp.toPath());
        assertEquals(cpp.getCanonicalFile(),WireProject.sketch(folder.getRoot(),"uno"));
        WireProject.ensureSketch(folder.getRoot(),"uno",WireProject.DEFAULT_CODE);
        assertEquals("original C++",WireProject.readFile(cpp,4096));
    }
    @Test public void editorCheckpointDoesNotOverwriteProgramAndClearsAfterSave()throws Exception {
        File file=folder.newFile("main.cpp");RecoveryStore.write(file,"saved");RecoveryStore.draft(file,"unsaved buffer");
        assertEquals("saved",RecoveryStore.read(file,4096,null).text);assertEquals("unsaved buffer",RecoveryStore.readDraft(file,4096));
        RecoveryStore.write(file,"explicitly saved");assertNull(RecoveryStore.readDraft(file,4096));
    }
    @Test public void refusesCorruptBackupAndHonorsReadLimit()throws Exception {
        File file=file();direct(file,"broken");direct(RecoveryStore.sidecar(file,"backup"),"also broken");
        try{RecoveryStore.read(file,4096,WireProject::validateScene);fail();}catch(IOException expected){}
        direct(file,scene("bounded"));try{RecoveryStore.read(file,8,WireProject::validateScene);fail();}catch(IOException expected){}
    }
    @Test public void recoveryCannotFollowHiddenFolderSymlinkOutsideProject()throws Exception {
        File outside=folder.newFolder("outside"),project=folder.newFolder("project");
        Files.createSymbolicLink(new File(project,".wiredroid-recovery").toPath(),outside.toPath());
        try{RecoveryStore.write(new File(project,"main.cpp"),"code");fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("межами"));}
    }
    @Test public void preservesFutureSchemaInsteadOfRollingItBackToAnOlderBackup()throws Exception {
        File file=file();RecoveryStore.write(file,scene("old"),WireProject::validateScene);RecoveryStore.write(file,scene("current"),WireProject::validateScene);
        String future=scene("future").replace("\"version\":1","\"version\":1,\"schemaVersion\":99");direct(file,future);
        try{RecoveryStore.read(file,4096,WireProject::validateScene);fail();}catch(RecoveryStore.NonRecoverableException expected){}
        try{RecoveryStore.write(file,scene("downgrade"),WireProject::validateScene);fail();}catch(RecoveryStore.NonRecoverableException expected){}
        assertFalse(RecoveryStore.sidecar(file,"journal").exists());
        assertEquals(future,new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
    }
    @Test public void recoversLegacyAndroidAtomicFileBackup()throws Exception {
        File file=file();direct(new File(file.getPath()+".bak"),scene("legacy"));direct(file,"partial");
        RecoveryStore.Read read=RecoveryStore.read(file,4096,WireProject::validateScene);
        assertTrue(read.recovered);assertTrue(read.text.contains("legacy"));assertFalse(new File(file.getPath()+".bak").exists());
    }
    @Test public void explicitSaveResolvesLegacyBackupBeforeStartingNewCommit()throws Exception {
        File file=file();direct(new File(file.getPath()+".bak"),scene("legacy"));direct(file,"partial");
        RecoveryStore.write(file,scene("new save"),WireProject::validateScene);
        assertTrue(RecoveryStore.read(file,4096,WireProject::validateScene).text.contains("new save"));
        assertFalse(new File(file.getPath()+".bak").exists());
    }
    @Test public void aQueuedOldEditorCheckpointCannotReappearAfterExplicitSave()throws Exception {
        File file=folder.newFile("main.cpp");RecoveryStore.write(file,"saved");long epoch=RecoveryStore.draftEpoch(file);
        RecoveryStore.write(file,"latest saved");assertFalse(RecoveryStore.draft(file,"old queued buffer",epoch));assertNull(RecoveryStore.readDraft(file,4096));
    }
    @Test public void backgroundingSnapshotCannotBeOverwrittenByAnOlderQueuedBuffer()throws Exception {
        File file=folder.newFile("main.cpp");long epoch=RecoveryStore.draftEpoch(file);
        RecoveryStore.checkpoint(file,"latest when backgrounded");assertFalse(RecoveryStore.draft(file,"older queued",epoch));
        assertEquals("latest when backgrounded",RecoveryStore.readDraft(file,4096));
    }
    @Test public void preservesUtf16EncodingAcrossInterruptedWrites()throws Exception {
        File file=folder.newFile("main.cpp");java.nio.charset.Charset encoding=StandardCharsets.UTF_16LE;
        RecoveryStore.write(file,"\uFEFFsaved український текст",null,encoding);RecoveryStore.write(file,"\uFEFFnew",null,encoding);
        Files.delete(file.toPath());RecoveryStore.Read read=RecoveryStore.read(file,4096,null,encoding);
        assertEquals("\uFEFFsaved український текст",read.text);assertEquals((byte)0xff,Files.readAllBytes(file.toPath())[0]);
    }
    @Test public void explicitlyDiscardedFileHasNoCandidatesToResurrectIt()throws Exception {
        File file=folder.newFile("main.cpp");RecoveryStore.write(file,"old");RecoveryStore.write(file,"new");RecoveryStore.draft(file,"draft");
        RecoveryStore.discard(file);Files.delete(file.toPath());assertFalse(RecoveryStore.canRecover(file));assertNull(RecoveryStore.readDraft(file,4096));
    }
}
