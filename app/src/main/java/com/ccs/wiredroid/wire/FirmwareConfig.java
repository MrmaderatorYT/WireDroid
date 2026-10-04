package com.ccs.wiredroid.wire;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import org.json.*;

/** Target-specific project settings, with paths always relative to the project. */
public final class FirmwareConfig {
    public final String nodeId, profile, mode, coreVersion;
    public final List<String> sources, excludes, defines, includePaths;
    public final JSONObject raw;
    private FirmwareConfig(String id, JSONObject value) throws IOException {
        if(id==null||!id.matches("[a-zA-Z0-9_-]{1,64}"))throw new IOException("Некоректний ID плати");
        nodeId=id; raw=value; profile=value.optString("profile", ""); mode=value.optString("mode", "gpio");
        coreVersion=value.optString("coreVersion", "1.8.6");
        if(!Arrays.asList("gpio","mcu","usb").contains(mode))throw new IOException("Невідомий спосіб виконання: "+mode);
        if(!profile.isEmpty()&&!profile.matches("[a-zA-Z0-9:_@.-]{1,128}"))throw new IOException("Некоректний профіль плати");
        if(!coreVersion.matches("[a-zA-Z0-9_.-]{1,64}"))throw new IOException("Некоректна версія core");
        sources=patterns(value.optJSONArray("sources"));excludes=patterns(value.optJSONArray("exclude"));includePaths=patterns(value.optJSONArray("includePaths"));
        for(String path:includePaths)if(path.contains("*")||path.contains("?"))throw new IOException("includePaths має містити папки без glob");
        defines=new ArrayList<>();JSONArray defs=value.optJSONArray("defines");
        if(defs!=null)for(int i=0;i<defs.length();i++){String d=defs.optString(i);if(!d.matches("[A-Za-z_][A-Za-z0-9_]*(=[A-Za-z0-9_.+-]{1,80})?"))throw new IOException("Некоректний define");defines.add(d);}
    }
    private static List<String> patterns(JSONArray input)throws IOException{
        List<String> out=new ArrayList<>();if(input==null)return out;if(input.length()>256)throw new IOException("Забагато шляхів збірки");
        for(int i=0;i<input.length();i++){String path=input.optString(i);if(path.isEmpty()||path.startsWith("/")||path.contains("\\")||path.contains("\u0000")||Arrays.asList(path.split("/")).contains("..")||path.contains(":"))throw new IOException("Некоректний шлях збірки: "+path);out.add(path);}return out;
    }
    public static FirmwareConfig load(File project,String nodeId)throws Exception{
        if(nodeId==null||!nodeId.matches("[a-zA-Z0-9_-]{1,64}"))throw new IOException("Некоректний ID плати");
        File file=projectFile(project,"wiredroid.json");JSONObject config=present(file)?new JSONObject(read(file,128*1024)):new JSONObject();
        JSONObject defaults=config.optJSONObject("build"),boards=config.optJSONObject("boards"),target=boards==null?null:boards.optJSONObject(nodeId);
        JSONObject combined=defaults==null?new JSONObject():new JSONObject(defaults.toString());
        if(target!=null){Iterator<String> keys=target.keys();while(keys.hasNext()){String key=keys.next();combined.put(key,target.get(key));}}
        return new FirmwareConfig(nodeId,combined);
    }
    public static synchronized void save(File project,String nodeId,String raw)throws Exception{
        JSONObject settings=new JSONObject(raw);new FirmwareConfig(nodeId,settings);
        File file=projectFile(project,"wiredroid.json");JSONObject projectJson=present(file)?new JSONObject(read(file,128*1024)):new JSONObject();
        JSONObject boards=projectJson.optJSONObject("boards");if(boards==null){boards=new JSONObject();projectJson.put("boards",boards);}boards.put(nodeId,settings);
        atomicWrite(file,projectJson.toString(2));
    }
    public boolean selects(String relative){return sources.isEmpty()||matchesAny(sources,relative);}
    public boolean excludes(String relative){return matchesAny(excludes,relative);}
    public static boolean matchesAny(List<String> patterns,String path){for(String p:patterns)if(glob(p).matcher(path).matches())return true;return false;}
    private static Pattern glob(String value){
        StringBuilder result=new StringBuilder("^");for(int i=0;i<value.length();i++){char c=value.charAt(i);if(c=='*'){if(i+1<value.length()&&value.charAt(i+1)=='*'){i++;if(i+1<value.length()&&value.charAt(i+1)=='/'){i++;result.append("(?:.*/)?");}else result.append(".*");}else result.append("[^/]*");}else if(c=='?')result.append("[^/]");else result.append(Pattern.quote(String.valueOf(c)));}return Pattern.compile(result.append('$').toString());
    }
    static boolean present(File file){return file.isFile()||RecoveryStore.canRecover(file);}
    static String read(File file,int limit)throws IOException{return WireProject.readFile(file,limit);}
    static File projectFile(File project,String relative)throws IOException{File root=project.getCanonicalFile(),file=new File(root,relative).getCanonicalFile();if(!file.toPath().startsWith(root.toPath()))throw new IOException("Шлях виходить за межі проєкту");return file;}
    static void atomicWrite(File file,String text)throws IOException{WireProject.write(file,text);}
}
