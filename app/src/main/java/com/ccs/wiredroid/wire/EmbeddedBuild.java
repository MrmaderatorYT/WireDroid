package com.ccs.wiredroid.wire;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import org.json.*;

/** A board entry point plus shared C/C++ sources and source Arduino libraries. */
public final class EmbeddedBuild {
    public static final String SDK_VERSION = "ndk-r28c-v2";
    public static final class Plan {
        public final List<File> sources = new ArrayList<>(), includes = new ArrayList<>();
        public final List<String> libraries = new ArrayList<>();
        public FirmwareConfig target;
    }
    private static final Pattern INCLUDE = Pattern.compile("(?m)^\\s*#\\s*include\\s*[<\"]([^>\"]+)[>\"]");
    private static boolean source(File f) {return f.getName().matches(".*\\.(c|cpp|cc|cxx|S)$");}
    private static File contained(File root, File f) throws IOException {
        File real=f.getCanonicalFile();
        if(!real.toPath().startsWith(root.getCanonicalFile().toPath()))throw new IOException("Файл поза проєктом: "+f.getName());
        return real;
    }
    private static List<File> files(File root, File dir) throws IOException {
        List<File> out=new ArrayList<>();if(!dir.isDirectory())return out;
        ArrayDeque<File> queue=new ArrayDeque<>();Set<String> visited=new HashSet<>();queue.add(dir);
        while(!queue.isEmpty()) {
            File current=contained(root,queue.removeFirst());if(!visited.add(current.getPath()))continue;
            File[] children=current.listFiles();if(children==null)throw new IOException("Немає доступу до "+current.getName());
            Arrays.sort(children,Comparator.comparing(File::getName));
            for(File f:children) {
                if(f.getName().startsWith(".")||Arrays.asList("build","examples","test","tests").contains(f.getName()))continue;
                contained(root,f);if(f.isDirectory())queue.addLast(f);else if(f.isFile())out.add(f);
                if(out.size()>4096)throw new IOException("Проєкт перевищує 4096 файлів збірки");
            }
        }return out;
    }
    public static Plan plan(File project, File entry) throws Exception {
        return plan(project,entry,entry.getName().replaceFirst("\\.[^.]+$", ""));
    }
    public static Plan plan(File project, File entry, String nodeId) throws Exception {
        File root=project.getCanonicalFile();entry=contained(root,entry);Plan plan=new Plan();plan.target=FirmwareConfig.load(root,nodeId);
        LinkedHashSet<File> sources=new LinkedHashSet<>(),includes=new LinkedHashSet<>();sources.add(entry);
        for(String path:new String[]{"include","src"}){File dir=new File(root,path);if(dir.isDirectory())includes.add(contained(root,dir));}
        includes.add(entry.getParentFile());
        for(String path:plan.target.includePaths){File include=contained(root,new File(root,path));if(!include.isDirectory())throw new IOException("Папка заголовків відсутня: "+path);includes.add(include);}
        
        Set<String> boardNames=new HashSet<>();File circuit=new File(root,WireProject.SCENE);
        if(circuit.isFile()){JSONArray nodes=new JSONObject(readFile(circuit,2*1024*1024)).getJSONArray("nodes");for(int i=0;i<nodes.length();i++)boardNames.add(nodes.getJSONObject(i).getString("id"));}
        LinkedHashSet<File> candidates=new LinkedHashSet<>(files(root,new File(root,"src")));
        candidates.addAll(files(root,entry.getParentFile()));
        for(File f:candidates)if(source(f)){
            String relative=root.toPath().relativize(f.toPath()).toString().replace(File.separatorChar,'/');
            if(relative.startsWith("libraries/")||relative.startsWith("lib/"))continue;
            boolean otherTarget=relative.startsWith("src/boards/")&&!relative.startsWith("src/boards/"+nodeId+"/");
            if(otherTarget||plan.target.excludes(relative)||!plan.target.selects(relative))continue;
            String base=f.getName().replaceFirst("\\.[^.]+$","");
            if(f.equals(entry)||!boardNames.contains(base)&&!containsEntryPoint(f))sources.add(f);
        }
        LinkedHashMap<String,File> libraryDirs=new LinkedHashMap<>();
        for(String location:new String[]{"libraries","lib"}) {
            File dir=new File(root,location);File[] children=dir.listFiles();if(children==null)continue;
            Arrays.sort(children,Comparator.comparing(File::getName));for(File lib:children)if(lib.isDirectory())libraryDirs.put(lib.getName(),contained(root,lib));
        }
        LinkedHashSet<String> wanted=new LinkedHashSet<>();File config=new File(root,"wiredroid.json");
        if(FirmwareConfig.present(config)){JSONArray libs=new JSONObject(readFile(config,128*1024)).optJSONArray("libraries");if(libs!=null)for(int i=0;i<libs.length();i++)wanted.add(libs.getString(i));}
        Set<File> scanned=new HashSet<>(),includedSources=new HashSet<>();
        ArrayDeque<File> queue=new ArrayDeque<>(sources);
        boolean changed=true;
        while(changed){changed=false;
            for(String name:new ArrayList<>(wanted)) {
                if(plan.libraries.contains(name))continue;
                File library=libraryDirs.get(name);
                if(library==null)for(File candidate:libraryDirs.values()){
                    File properties=new File(candidate,"library.properties");if(!properties.isFile())continue;
                    Properties props=new Properties();try(Reader reader=new InputStreamReader(new FileInputStream(properties),StandardCharsets.UTF_8)){props.load(reader);}
                    if(name.equals(props.getProperty("name"))){library=candidate;break;}
                }
                if(library==null)continue;
                String folder=library.getName();if(plan.libraries.contains(folder)){plan.libraries.add(name);continue;}
                plan.libraries.add(name);if(!folder.equals(name))plan.libraries.add(folder);
                File src=new File(library,"src"),base=src.isDirectory()?src:library;includes.add(base);
                File utility=new File(library,"utility");if(!src.isDirectory()&&utility.isDirectory())includes.add(utility);
                for(File f:files(root,base)){if(source(f))sources.add(f);if(source(f)||f.getName().matches(".*\\.(h|hpp|hh)$"))queue.addLast(f);}
                File properties=new File(library,"library.properties");
                if(properties.isFile()){Properties props=new Properties();try(Reader reader=new InputStreamReader(new FileInputStream(properties),StandardCharsets.UTF_8)){props.load(reader);}
                    for(String dependency:props.getProperty("depends","").split(",")){String dep=dependency.replaceFirst("\\s*\\(.*","").trim();if(!dep.isEmpty())wanted.add(dep);}}
                changed=true;
            }
            while(!queue.isEmpty()) {
                File file=contained(root,queue.removeFirst());if(!scanned.add(file))continue;
                Matcher matcher=INCLUDE.matcher(readFile(file,2*1024*1024));
                while(matcher.find()) {
                    String header=matcher.group(1);File resolved=new File(file.getParentFile(),header);
                    if(!resolved.isFile())for(File include:includes){File candidate=new File(include,header);if(candidate.isFile()){resolved=candidate;break;}}
                    if(resolved.isFile()) {
                        resolved=contained(root,resolved);queue.addLast(resolved);if(source(resolved))includedSources.add(resolved);
                    } else {
                        String library=libraryForHeader(libraryDirs,header);if(!library.isEmpty()&&wanted.add(library))changed=true;
                    }
                }
                if(scanned.size()>4096)throw new IOException("До 4096 файлів залежностей");
            }
        }
        includedSources.remove(entry);sources.removeAll(includedSources);
        for(String name:wanted)if(!name.isEmpty()&&!plan.libraries.contains(name))throw new IOException("Бібліотеку "+name+" не знайдено. Додайте її папку до libraries/.");
        if(sources.size()>256)throw new IOException("До 256 файлів C/C++ в одній програмі плати");
        EmbeddedLibraries.verifyLock(root);
        plan.sources.addAll(sources);plan.includes.addAll(includes);return plan;
    }
    private static String readFile(File file,int limit)throws IOException{return WireProject.readFile(file,limit);}
    private static String libraryForHeader(Map<String,File> libs,String header){for(Map.Entry<String,File> e:libs.entrySet())if(new File(e.getValue(),"src/"+header).isFile()||new File(e.getValue(),header).isFile())return e.getKey();return "";}
    private static boolean containsEntryPoint(File f)throws IOException{return Pattern.compile("\\bvoid\\s+(setup|loop)\\s*\\(").matcher(readFile(f,2*1024*1024)).find();}
    public static synchronized File sdk(Context context) throws IOException {
        File sdk=new File(context.getFilesDir(),"embedded-sdk/"+SDK_VERSION),stamp=new File(sdk,"ready");
        if(stamp.isFile())return sdk;
        if(!sdk.isDirectory()&&!sdk.mkdirs())throw new IOException("Не вдалося підготувати C++ SDK");
        long total=0;int count=0;
        try(ZipInputStream zip=new ZipInputStream(context.getAssets().open("wiredroid/sdk.zip"))){ZipEntry item;byte[] buffer=new byte[32768];
            while((item=zip.getNextEntry())!=null){if(++count>8192)throw new IOException("Надто багато файлів SDK");
                File target=new File(sdk,item.getName()).getCanonicalFile();if(!target.toPath().startsWith(sdk.getCanonicalFile().toPath()))throw new IOException("Некоректний шлях SDK");
                if(item.isDirectory()){target.mkdirs();continue;}File parent=target.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Не вдалося створити SDK");
                try(OutputStream out=new FileOutputStream(target)){int n;while((n=zip.read(buffer))!=-1){total+=n;if(total>128*1024*1024)throw new IOException("SDK перевищує 128 MiB");out.write(buffer,0,n);}}
            }
        }WireProject.write(stamp,SDK_VERSION);return sdk;
    }
}
