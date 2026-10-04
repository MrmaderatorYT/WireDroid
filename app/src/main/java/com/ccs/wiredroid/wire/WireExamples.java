package com.ccs.wiredroid.wire;

import android.app.Activity;
import android.content.Context;
import android.widget.EditText;
import android.widget.Toast;
import com.ccs.wiredroid.maven.MavenPaths;
import com.ccs.wiredroid.project.ProjectTemplates;
import com.ccs.wiredroid.ui.Dialogs;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.function.Consumer;

/** Bundled examples are copied into new projects; opening one never replaces user files. */
public final class WireExamples {
    private WireExamples() {}
    public static JSONArray list(Context context) throws Exception {
        try(InputStream in=context.getAssets().open("wiredroid/examples/index.json")) {
            return new JSONArray(WireProject.read(in,64*1024));
        }
    }
    private static JSONObject find(Context context,String id) throws Exception {
        if(id==null||!id.matches("[a-z0-9-]{1,50}"))throw new IOException("Невідомий приклад");
        JSONArray all=list(context);
        for(int i=0;i<all.length();i++)if(id.equals(all.getJSONObject(i).getString("id")))return all.getJSONObject(i);
        throw new IOException("Невідомий приклад");
    }
    public static File create(Context context,String id,String name) throws Exception {
        String safe=ProjectTemplates.sanitizeName(name);
        if(safe.isEmpty())throw new IOException("Вкажіть назву проєкту");
        return createAt(context,MavenPaths.projectDir(context,safe),id);
    }
    static File createAt(Context context,File root,String id) throws Exception {
        JSONObject example=find(context,id);
        File parent=root.getParentFile();
        if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Папка проєктів недоступна");
        if(!root.mkdir())throw new IOException("Проєкт уже існує або папка недоступна: "+root.getName());
        try {
            JSONArray files=example.getJSONArray("files");
            for(int i=0;i<files.length();i++) {
                String path=files.getString(i);
                if(!path.matches("(?:circuit\\.wire\\.json|wiredroid\\.json|README\\.md|\\.gitignore|\\.wiredroid/firmware/[a-zA-Z0-9_-]{1,64}\\.(?:hex|json)|(?:sketches|src|include|libraries)(?:/[a-zA-Z0-9_-]{1,64})*/[a-zA-Z0-9_.-]{1,80})"))throw new IOException("Некоректний файл прикладу");
                File target=new File(root,path).getCanonicalFile();
                if(!target.getPath().startsWith(root.getCanonicalPath()+File.separator))throw new IOException("Файл за межами проєкту");
                try(InputStream in=context.getAssets().open("wiredroid/examples/"+id+"/"+path)) {
                    String text=WireProject.read(in,2*1024*1024);
                    if(path.equals(WireProject.SCENE)) {
                        JSONObject scene=new JSONObject(text);scene.put("name",root.getName());text=scene.toString(2);
                        WireProject.validateScene(text);
                    }
                    WireProject.write(target,text);
                }
            }
            WireProject.write(new File(root,".gitignore"),".wiredroid/\n.wiredroid-recovery/\n.wiredroid/eeprom/\n*.bak\n");
            if(!WireProject.isCircuit(root)||!WireProject.sketch(root,example.getString("board")).isFile())throw new IOException("Приклад не містить схеми й програми");
            return root;
        }catch(Exception error){WireProject.deleteNewProject(root);throw error;}
    }
    public static void show(Activity activity,Consumer<File> open) {
        try {
            JSONArray all=list(activity);String[] titles=new String[all.length()];
            for(int i=0;i<titles.length;i++){JSONObject item=all.getJSONObject(i);titles[i]=item.getString("title")+"\n"+item.getString("description");}
            Dialogs.rounded(activity).setTitle("Готові приклади схем").setItems(titles,(dialog,which)->{
                try {
                    JSONObject item=all.getJSONObject(which);String id=item.getString("id"),baseName=item.getString("projectName"),suggested=baseName;
                    File base=MavenPaths.getWireDroidBase(activity);int suffix=2;
                    while(new File(base,suggested).exists())suggested=baseName+"_"+suffix++;
                    EditText input=new EditText(activity);input.setSingleLine(true);input.setText(suggested);input.setSelectAllOnFocus(true);input.setHint("Назва нового проєкту");
                    Dialogs.rounded(activity).setTitle(item.getString("title")).setMessage(item.getString("description")+"\n\nСхема, з’єднання та C++ програма відкриються в окремому редагованому проєкті.")
                        .setView(input).setNegativeButton("Скасувати",null).setPositiveButton("Створити й відкрити",(d,w)->{
                            String name=input.getText().toString().trim();
                            new Thread(()->{
                                try{File root=create(activity,id,name);activity.runOnUiThread(()->{if(!activity.isFinishing()&&!activity.isDestroyed())open.accept(root);});}
                                catch(Exception error){activity.runOnUiThread(()->Toast.makeText(activity,error.getMessage(),Toast.LENGTH_LONG).show());}
                            },"WireDroid-example").start();
                        }).show();
                }catch(Exception error){Toast.makeText(activity,error.getMessage(),Toast.LENGTH_LONG).show();}
            }).setNegativeButton("Закрити",null).show();
        }catch(Exception error){Toast.makeText(activity,error.getMessage(),Toast.LENGTH_LONG).show();}
    }
}
