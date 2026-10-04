package com.ccs.wiredroid.wire;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Runs on Android storage without moving the user's foreground activity. */
public final class WireReliabilityChecks {
    private WireReliabilityChecks(){}
    public static String run(Context context)throws Exception {
        File root=new File(context.getCacheDir(),"recovery-check-"+System.nanoTime());
        if(!root.mkdirs())throw new IOException("No test folder");
        StringBuilder report=new StringBuilder();
        try {
            File scene=new File(root,WireProject.SCENE);
            String legacy="{\"version\":1,\"nodes\":[{\"id\":\"uno\",\"x\":1,\"y\":2,\"scale\":0.5}],\"wires\":[]}";
            WireProject.write(scene,legacy);
            JSONObject migrated=new JSONObject(WireProject.readFile(scene,4096));
            require(migrated.getInt("schemaVersion")==2&&migrated.getJSONArray("nodes").getJSONObject(0).getDouble("scale")==.5,"Legacy migration lost scale");
            report.append("PASS Android scene migration: schema revision 2, legacy scale preserved\n");
            String update=legacy.replace("\"x\":1","\"x\":9");WireProject.write(scene,update);
            Files.write(scene.toPath(),"{truncated".getBytes(StandardCharsets.UTF_8));
            RecoveryStore.Read recovered=WireProject.readRecovered(scene,4096);
            require(recovered.recovered&&new JSONObject(recovered.text).getJSONArray("nodes").getJSONObject(0).getInt("x")==1,"Corrupt scene backup not recovered");
            report.append("PASS Android backup: corrupt primary restored from validated scene\n");
            byte[] bytes=WireProject.migrateScene(update).getBytes(StandardCharsets.UTF_8);
            Files.write(RecoveryStore.sidecar(scene,"pending").toPath(),bytes);
            JSONObject journal=new JSONObject().put("version",1).put("length",bytes.length).put("sha256",RecoveryStore.digest(bytes));
            Files.write(RecoveryStore.sidecar(scene,"journal").toPath(),journal.toString().getBytes(StandardCharsets.UTF_8));
            recovered=WireProject.readRecovered(scene,4096);
            require(recovered.recovered&&new JSONObject(recovered.text).getJSONArray("nodes").getJSONObject(0).getInt("x")==9,"Checked journal not committed");
            report.append("PASS Android journal: complete checked interrupted commit recovered\n");
            File sketch=WireProject.sketch(root,"uno");WireProject.write(sketch,"saved");RecoveryStore.draft(sketch,"unsaved");
            require("saved".equals(WireProject.readFile(sketch,4096))&&"unsaved".equals(RecoveryStore.readDraft(sketch,4096)),"Draft changed compiler source");
            WireProject.write(sketch,"saved latest");require(RecoveryStore.readDraft(sketch,4096)==null,"Saved draft retained");
            report.append("PASS Android editor checkpoints: separate buffer, removed after explicit save\n");
            require(CompilerDiagnostics.parse(root,sketch.getPath()+":2:4: error: invalid_identifier").size()==1,"Diagnostic location missing");
            require(CompilerDiagnostics.parse(root,"/outside.cpp:1:1: error: invalid").isEmpty(),"Outside diagnostic accepted");
            report.append("PASS Android compiler navigation: project-contained locations\n");
            com.ccs.wiredroid.project.ProjectManager manager=new com.ccs.wiredroid.project.ProjectManager(context);manager.setProjectRoot(root);
            com.ccs.wiredroid.util.AppPreferences prefs=new com.ccs.wiredroid.util.AppPreferences(context);String previousEncoding=prefs.getFileEncoding();
            try {
                prefs.setFileEncoding("UTF-16 LE BOM");File encoded=new File(root,"src/encoded.cpp");
                manager.writeFile(encoded,"// український текст");require("// український текст".equals(manager.readFile(encoded)),"Editor encoding changed");
                byte[] body=Files.readAllBytes(encoded.toPath());require((body[0]&255)==255&&(body[1]&255)==254,"Editor BOM missing");
                manager.writeFile(encoded,"updated");RecoveryStore.draft(encoded,"unsaved");require(manager.deleteFile(encoded),"Editor deletion failed");require(!RecoveryStore.canRecover(encoded)&&RecoveryStore.readDraft(encoded,4096)==null,"Deleted file can be resurrected");
                report.append("PASS Android editor storage: encoding/BOM preserved; explicit deletion clears recovery\n");
            }finally{prefs.setFileEncoding(previousEncoding);}
            return report.toString();
        }finally{WireProject.deleteNewProject(root);}
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
