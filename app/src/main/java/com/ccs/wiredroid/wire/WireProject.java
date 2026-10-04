package com.ccs.wiredroid.wire;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** On-disk circuit and independent board programs, shared by both editors. */
public final class WireProject {
    public static final String SCENE = "circuit.wire.json";
    /** Schema revision is independent of the legacy visual-engine version. */
    public static final int SCHEMA_VERSION = 2;
    public static final String DEFAULT_CODE = "#include <Arduino.h>\n\nvoid setup() {\n  pinMode(13, OUTPUT);\n  Serial.begin(9600);\n}\n\nvoid loop() {\n  digitalWrite(13, HIGH);\n  Serial.println(\"LED on\");\n  delay(500);\n  digitalWrite(13, LOW);\n  delay(500);\n}";
    private WireProject() {}

    public static boolean isCircuit(File root) { return root != null && RecoveryStore.canRecover(new File(root, SCENE)); }

    /** Add a circuit to imported embedded sources without replacing existing files. */
    public static void initializeImported(File root) throws IOException {
        if (isCircuit(root)) return;
        if (!root.isDirectory()) throw new IOException("Папка проєкту недоступна");
        write(new File(root, SCENE), "{\"version\":1,\"name\":" + JSONObject.quote(root.getName())
                + ",\"nodes\":[],\"wires\":[]}");
    }

    public static File create(Context context, String name) throws Exception {
        return WireExamples.create(context, "blink-uno", name);
    }

    static void deleteNewProject(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteNewProject(child);
        file.delete();
    }

    public static String read(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > limit) throw new IOException("Файл перевищує допустимий розмір");
            output.write(buffer, 0, count);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    public static String readFile(File file, int limit) throws IOException {
        return readRecovered(file,limit).text;
    }

    public static RecoveryStore.Read readRecovered(File file,int limit)throws IOException {
        return RecoveryStore.read(file,limit,SCENE.equals(file.getName())?WireProject::validateScene:null);
    }

    /** Pure migration: retains unknown component fields, routes, code references and scale. */
    public static String migrateScene(String raw)throws Exception {
        validateScene(raw);
        JSONObject scene=new JSONObject(raw);
        int previous=scene.optInt("schemaVersion",1);
        if(previous<SCHEMA_VERSION) {
            JSONArray wires=scene.getJSONArray("wires");
            Set<String> ids=new HashSet<>();
            JSONArray nodes=scene.getJSONArray("nodes");
            for(int i=0;i<nodes.length();i++)ids.add(nodes.getJSONObject(i).getString("id"));
            for(int i=0;i<wires.length();i++){JSONObject wire=wires.getJSONObject(i);if(wire.has("id"))ids.add(wire.getString("id"));}
            for(int i=0;i<wires.length();i++) {
                JSONObject wire=wires.getJSONObject(i);
                if(!wire.has("id")) {String id="wire_legacy_"+i;while(ids.contains(id))id+="_";ids.add(id);wire.put("id",id);}
            }
            scene.put("schemaVersion",SCHEMA_VERSION);
        }
        validateScene(scene.toString());
        return scene.toString();
    }

    public static synchronized void write(File file, String text) throws IOException {
        if(SCENE.equals(file.getName())) {
            try{RecoveryStore.write(file,migrateScene(text),WireProject::validateScene);}
            catch(Exception error){throw error instanceof IOException?(IOException)error:new IOException(error.getMessage(),error);}
        }else RecoveryStore.write(file,text);
    }

    public static File sketch(File root, String id) throws IOException {
        if (id == null || !id.matches("[a-zA-Z0-9_-]{1,64}")) throw new IOException("Некоректний ID плати");
        File canonical = root.getCanonicalFile();
        File cpp = programPath(canonical, id, ".cpp");
        if (RecoveryStore.canRecover(cpp)) return cpp;
        // Keep older Arduino projects working without creating a second program
        // that would hide the user's existing sketch. C++ wins when both exist.
        File ino = programPath(canonical, id, ".ino");
        return RecoveryStore.canRecover(ino) ? ino : cpp;
    }

    private static File programPath(File canonical, String id, String extension) throws IOException {
        File file = new File(canonical, "sketches/" + id + extension).getCanonicalFile();
        if (!file.getPath().startsWith(canonical.getPath() + File.separator)) throw new IOException("Програма за межами проєкту");
        return file;
    }

    public static synchronized void ensureSketch(File root, String id, String code) throws IOException {
        File file = sketch(root, id);
        if (!file.exists()&&RecoveryStore.canRecover(file))readFile(file,2*1024*1024);
        if (!file.exists()) write(file, code);
    }
    public static synchronized void duplicateSketch(File root,String from,String to)throws IOException {
        File source=sketch(root,from),target=sketch(root,to);
        if(source.equals(target)||RecoveryStore.canRecover(target))throw new IOException("Програма для нової плати вже існує");
        ensureSketch(root,from,DEFAULT_CODE);write(target,readFile(source,512*1024));
    }

    public static void validateScene(String raw) throws Exception {
        if (raw.getBytes(StandardCharsets.UTF_8).length > 2 * 1024 * 1024) throw new IOException("Схема завелика");
        JSONObject scene = new JSONObject(raw);
        if (scene.getInt("version") != 1) throw new RecoveryStore.NonRecoverableException("Невідома версія схеми");
        int schema=scene.optInt("schemaVersion",1);
        if(schema<1||schema>SCHEMA_VERSION)throw new RecoveryStore.NonRecoverableException("Невідома версія формату схеми: "+schema);
        JSONArray nodes = scene.getJSONArray("nodes"), wires = scene.getJSONArray("wires");
        if (nodes.length() > 250 || wires.length() > 2000) throw new IOException("Перевищено ліміт деталей / дротів");
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.getJSONObject(i);
            if(node.has("inputValue")){double value=node.getDouble("inputValue");if(!Double.isFinite(value)||value<0||value>1)throw new IOException("Некоректне значення ADC");}
            if(node.has("simulation")&&!node.getJSONObject("simulation").getString("pin").matches("[a-zA-Z0-9_-]{1,128}"))throw new IOException("Некоректний контакт сигналу");
            if(node.has("electrical")){JSONObject model=node.getJSONObject("electrical");if(!model.getString("type").matches("[a-zA-Z]{1,32}"))throw new IOException("Некоректна модель");JSONObject params=model.optJSONObject("parameters");if(params!=null){java.util.Iterator<String> keys=params.keys();while(keys.hasNext()){double value=params.getDouble(keys.next());if(!Double.isFinite(value)||Math.abs(value)>1e12)throw new IOException("Некоректний параметр моделі");}}}
            if(node.has("boardProfile")){JSONObject profile=node.getJSONObject("boardProfile");for(String name:new String[]{"logicVoltage","adcBits","pwmFrequency","outputResistance","pullupResistance"})if(profile.has(name)){double value=profile.getDouble(name);if(!Double.isFinite(value)||value<=0||value>1e9)throw new IOException("Некоректний профіль плати");}}
            String id = node.getString("id");
            if (!id.matches("[a-zA-Z0-9_-]{1,64}") || !ids.add(id)) throw new IOException("Некоректний ID деталі");
            for (String axis : new String[]{"x", "y"}) {
                double value = node.getDouble(axis);
                if (!Double.isFinite(value) || Math.abs(value) > 100000) throw new IOException("Некоректні координати");
            }
            if(node.has("scale")) {
                double scale=node.getDouble("scale");
                if(!Double.isFinite(scale)||scale<.25||scale>2)throw new IOException("Розмір деталі має бути від 25% до 200%");
            }
            if(node.has("ledColor")&&!node.getString("ledColor").matches("#[0-9a-fA-F]{6}"))throw new IOException("Некоректний колір LED");
        }
        Set<String> wireIds=new HashSet<>();
        for (int i = 0; i < wires.length(); i++) {
            JSONObject wire = wires.getJSONObject(i);
            if(wire.has("id")&&(ids.contains(wire.getString("id"))||!wireIds.add(wire.getString("id"))))throw new IOException("Повторний ID дроту / деталі");
            String routing=wire.optString("routing", "curve");
            if(!routing.equals("curve")&&!routing.equals("polyline")&&!routing.equals("orthogonal"))throw new IOException("Некоректний маршрут дроту");
            if(wire.has("points")) {
                JSONArray points=wire.getJSONArray("points");if(points.length()>64)throw new IOException("До 64 точок дроту");
                for(int j=0;j<points.length();j++) {JSONObject point=points.getJSONObject(j);double x=point.getDouble("x"),y=point.getDouble("y");if(!Double.isFinite(x)||!Double.isFinite(y)||Math.abs(x)>100000||Math.abs(y)>100000)throw new IOException("Некоректна точка дроту");}
            }
            if(wire.has("color")&&!wire.getString("color").matches("#[0-9a-fA-F]{6}"))throw new IOException("Некоректний колір дроту");
            for (String endpoint : new String[]{"a", "b"}) {
                if (!ids.contains(wire.getJSONObject(endpoint).getString("node"))) throw new IOException("Дріт без деталі");
            }
        }
    }
}
