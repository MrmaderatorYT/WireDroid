package com.ccs.wiredroid.wire;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import org.json.*;

/** Offline source libraries. A pinned archive and tree hash accompany each installation. */
public final class EmbeddedLibraries {
    public static final String LOCK="wiredroid-libraries.lock.json";
    private static final long LIMIT=64L*1024*1024;
    private EmbeddedLibraries(){}
    public static synchronized JSONObject install(File project,File archive,File cache)throws Exception{
        if(!archive.isFile()||archive.length()>LIMIT)throw new IOException("ZIP бібліотеки недоступний або перевищує 64 MiB");
        Files.createDirectories(cache.toPath());String archiveHash=hashFile(archive);
        File staging=Files.createTempDirectory(cache.toPath(),"import-").toFile();
        try{
            extract(archive,staging);List<File> manifests=new ArrayList<>();findManifests(staging,manifests);
            if(manifests.size()!=1)throw new IOException("ZIP має містити одну Arduino-бібліотеку з library.properties");
            File library=manifests.get(0).getParentFile();JSONObject metadata=metadata(library);String folder=slug(metadata.getString("name"));
            metadata.put("folder",folder).put("archiveSha256",archiveHash).put("treeSha256",hashTree(library)).put("origin","local-zip");
            File cached=new File(cache,archiveHash);if(!cached.exists()){
                File cacheStage=Files.createTempDirectory(cache.toPath(),"cache-").toFile();
                try{copyTree(library,new File(cacheStage,"source"));FirmwareConfig.atomicWrite(new File(cacheStage,"metadata.json"),metadata.toString(2));Files.move(cacheStage.toPath(),cached.toPath());}finally{delete(cacheStage);}
            }
            JSONObject lock=lock(project);JSONArray entries=lock.getJSONArray("libraries");
            for(int i=0;i<entries.length();i++)if(entries.getJSONObject(i).optString("folder").equals(folder)&&!entries.getJSONObject(i).getString("name").equals(metadata.getString("name")))throw new IOException("Назва папки бібліотеки конфліктує: "+folder);
            installCached(project,cached,metadata);JSONArray updated=new JSONArray();
            for(int i=0;i<entries.length();i++)if(!entries.getJSONObject(i).getString("name").equals(metadata.getString("name")))updated.put(entries.getJSONObject(i));
            updated.put(metadata);lock.put("libraries",updated);FirmwareConfig.atomicWrite(FirmwareConfig.projectFile(project,LOCK),lock.toString(2));
            return metadata;
        }finally{delete(staging);}
    }
    private static void installCached(File project,File cached,JSONObject entry)throws Exception{
        File source=new File(cached,"source");if(!hashTree(source).equals(entry.getString("treeSha256")))throw new IOException("Кеш бібліотеки пошкоджено: "+entry.getString("name"));
        File libraries=FirmwareConfig.projectFile(project,"libraries");Files.createDirectories(libraries.toPath());
        String folder=entry.getString("folder");if(!folder.matches("[a-zA-Z0-9_-]{1,100}"))throw new IOException("Некоректна папка бібліотеки");
        File target=FirmwareConfig.projectFile(project,"libraries/"+folder),tmp=Files.createTempDirectory(libraries.toPath(),".install-").toFile(),old=new File(libraries,".backup-"+UUID.randomUUID());
        try{
            copyTree(source,tmp);if(target.exists())Files.move(target.toPath(),old.toPath());
            try{Files.move(tmp.toPath(),target.toPath());}catch(Exception failure){if(old.exists())Files.move(old.toPath(),target.toPath());throw failure;}
            delete(old);
        }finally{delete(tmp);}
    }
    public static synchronized void restore(File project,File cache)throws Exception{
        JSONArray entries=lock(project).getJSONArray("libraries");for(int i=0;i<entries.length();i++){JSONObject entry=entries.getJSONObject(i);String hash=entry.getString("archiveSha256");if(!hash.matches("[a-f0-9]{64}"))throw new IOException("Некоректний запис кешу");File cached=new File(cache,hash);if(!cached.isDirectory())throw new IOException("Немає офлайн-кешу для "+entry.getString("name")+" "+entry.getString("version"));installCached(project,cached,entry);}verifyLock(project);
    }
    public static synchronized void remove(File project,String name)throws Exception{
        JSONObject lock=lock(project);JSONArray entries=lock.getJSONArray("libraries"),updated=new JSONArray();
        for(int i=0;i<entries.length();i++){JSONObject entry=entries.getJSONObject(i);if(entry.getString("name").equals(name)){String folder=entry.getString("folder");if(!folder.matches("[a-zA-Z0-9_-]{1,100}"))throw new IOException("Некоректна папка бібліотеки");delete(FirmwareConfig.projectFile(project,"libraries/"+folder));}else updated.put(entry);}
        lock.put("libraries",updated);FirmwareConfig.atomicWrite(FirmwareConfig.projectFile(project,LOCK),lock.toString(2));
    }
    public static JSONObject list(File project)throws Exception{
        JSONObject result=lock(project);JSONArray issues=new JSONArray();try{verifyLock(project);}catch(Exception e){issues.put(e.getMessage());}return result.put("issues",issues);
    }
    public static void verifyLock(File project)throws Exception{
        File lockFile=FirmwareConfig.projectFile(project,LOCK);if(!FirmwareConfig.present(lockFile))return;JSONArray entries=lock(project).getJSONArray("libraries");Map<String,JSONObject> installed=new HashMap<>();
        for(int i=0;i<entries.length();i++){JSONObject entry=entries.getJSONObject(i);String folder=entry.getString("folder");if(!folder.matches("[a-zA-Z0-9_-]{1,100}"))throw new IOException("Некоректний lock бібліотек");File library=FirmwareConfig.projectFile(project,"libraries/"+folder);if(!hashTree(library).equals(entry.getString("treeSha256")))throw new IOException("Бібліотека "+entry.getString("name")+" змінена або відсутня. Повторіть імпорт чи відновіть кеш.");installed.put(entry.getString("name"),entry);}
        for(JSONObject entry:installed.values()){JSONArray deps=entry.getJSONArray("dependencies");for(int i=0;i<deps.length();i++){JSONObject dep=deps.getJSONObject(i);JSONObject actual=installed.get(dep.getString("name"));
            if(actual==null){File libraries=FirmwareConfig.projectFile(project,"libraries");File[] dirs=libraries.listFiles();if(dirs!=null)for(File dir:dirs)if(new File(dir,"library.properties").isFile()){JSONObject meta=metadata(dir);if(meta.getString("name").equals(dep.getString("name"))){actual=meta;break;}}}
            if(actual==null)throw new IOException("Відсутня залежність "+dep.getString("name")+" для "+entry.getString("name"));
            if(!satisfies(actual.getString("version"),dep.optString("constraint")))throw new IOException("Версія "+actual.getString("version")+" бібліотеки "+dep.getString("name")+" не відповідає "+dep.optString("constraint"));
        }}
    }
    public static JSONArray compatibility(File project,String architecture,String mode)throws Exception{
        JSONArray result=new JSONArray(),entries=lock(project).getJSONArray("libraries");for(int i=0;i<entries.length();i++){JSONObject entry=entries.getJSONObject(i);JSONArray arch=entry.getJSONArray("architectures");boolean allowed=false;for(int j=0;j<arch.length();j++)if("*".equals(arch.getString(j))||architecture.equals(arch.getString(j)))allowed=true;
            result.put(new JSONObject().put("name",entry.getString("name")).put("architectureCompatible",allowed).put("status",!allowed?"incompatible":mode.equals("gpio")?"requires-peripheral-review":"architecture-declared").put("note",allowed?"Декларація архітектури не підтверджує підтримку периферії":"Архітектура відсутня у library.properties"));}return result;
    }
    private static JSONObject lock(File project)throws Exception{
        File file=FirmwareConfig.projectFile(project,LOCK);if(!FirmwareConfig.present(file))return new JSONObject().put("version",1).put("libraries",new JSONArray());JSONObject value=new JSONObject(FirmwareConfig.read(file,2*1024*1024));if(value.getInt("version")!=1||value.getJSONArray("libraries").length()>256)throw new IOException("Непідтримуваний lock бібліотек");return value;
    }
    private static JSONObject metadata(File root)throws Exception{
        Properties props=new Properties();try(Reader reader=new InputStreamReader(new FileInputStream(new File(root,"library.properties")),StandardCharsets.UTF_8)){props.load(reader);}
        String name=props.getProperty("name","").trim(),version=props.getProperty("version","").trim();if(name.isEmpty()||name.length()>160||!version.matches("[a-zA-Z0-9_.+-]{1,64}"))throw new IOException("У library.properties потрібні name та version");
        JSONArray dependencies=new JSONArray(),architectures=new JSONArray();for(String a:props.getProperty("architectures","*").split(","))if(!a.trim().isEmpty())architectures.put(a.trim());
        for(String dependency:splitDepends(props.getProperty("depends",""))){Matcher m=Pattern.compile("^(.+?)(?:\\s*\\((.*)\\))?$").matcher(dependency.trim());if(m.matches()&&!m.group(1).trim().isEmpty())dependencies.put(new JSONObject().put("name",m.group(1).trim()).put("constraint",m.group(2)==null?"":m.group(2).trim()));}
        return new JSONObject().put("name",name).put("version",version).put("architectures",architectures).put("dependencies",dependencies).put("url",props.getProperty("url",""));
    }
    private static List<String> splitDepends(String text){List<String> out=new ArrayList<>();int start=0,depth=0;for(int i=0;i<text.length();i++){if(text.charAt(i)=='(')depth++;if(text.charAt(i)==')')depth--;if(text.charAt(i)==','&&depth==0){out.add(text.substring(start,i));start=i+1;}}out.add(text.substring(start));return out;}
    static boolean satisfies(String version,String constraint)throws IOException{
        if(constraint==null||constraint.trim().isEmpty())return true;
        String expression=constraint.trim();while(expression.startsWith("(")&&expression.endsWith(")"))expression=expression.substring(1,expression.length()-1).trim();
        String[] alternatives=expression.split("\\|\\|");if(alternatives.length>1){for(String alternative:alternatives)if(satisfies(version,alternative))return true;return false;}
        String[] all=expression.split("&&|,");if(all.length>1){for(String part:all)if(!satisfies(version,part))return false;return true;}
        Matcher matcher=Pattern.compile("^([<>]=?|!?=)?\\s*(\\d+(?:\\.\\d+){0,3}(?:[-+][a-zA-Z0-9.-]+)?)$").matcher(expression);if(!matcher.matches())throw new IOException("Непідтримуване обмеження версії: "+constraint);
        int comparison=compareVersions(version,matcher.group(2));String op=matcher.group(1)==null?"=":matcher.group(1);return op.equals("=")?comparison==0:op.equals("!=")?comparison!=0:op.equals(">")?comparison>0:op.equals(">=")?comparison>=0:op.equals("<")?comparison<0:comparison<=0;
    }
    private static int compareVersions(String a,String b)throws IOException{String[] av=a.split("[-+]",2)[0].split("\\."),bv=b.split("[-+]",2)[0].split("\\.");for(int i=0;i<Math.max(av.length,bv.length);i++){try{int diff=Integer.compare(i<av.length?Integer.parseInt(av[i]):0,i<bv.length?Integer.parseInt(bv[i]):0);if(diff!=0)return diff;}catch(NumberFormatException e){throw new IOException("Нечислова версія залежності: "+a);}}String as=a.contains("-")?a.substring(a.indexOf('-')+1).split("\\+",2)[0]:"",bs=b.contains("-")?b.substring(b.indexOf('-')+1).split("\\+",2)[0]:"";return as.isEmpty()&&bs.isEmpty()?0:as.isEmpty()?1:bs.isEmpty()?-1:as.compareTo(bs);}
    private static String slug(String name){String slug=name.replaceAll("[^a-zA-Z0-9_-]","_");return slug.substring(0,Math.min(100,slug.length()));}
    private static void extract(File archive,File directory)throws IOException{
        long total=0;int count=0;Set<String> seen=new HashSet<>();try(ZipInputStream zip=new ZipInputStream(new FileInputStream(archive))){ZipEntry entry;byte[] buffer=new byte[32768];while((entry=zip.getNextEntry())!=null){String path=entry.getName();if(++count>5000||path.startsWith("/")||path.contains("\\")||path.contains(":"))throw new IOException("Некоректний ZIP бібліотеки");File target=new File(directory,path).getCanonicalFile();if(!target.toPath().startsWith(directory.getCanonicalFile().toPath())||!seen.add(target.getPath()))throw new IOException("Некоректний чи повторний шлях ZIP");if(entry.isDirectory()){Files.createDirectories(target.toPath());continue;}Files.createDirectories(target.getParentFile().toPath());try(OutputStream out=new FileOutputStream(target)){int n;while((n=zip.read(buffer))!=-1){total+=n;if(total>LIMIT)throw new IOException("Бібліотека перевищує 64 MiB після розпакування");out.write(buffer,0,n);}}}}
    }
    private static void findManifests(File directory,List<File> out)throws IOException{for(File file:children(directory)){if(Files.isSymbolicLink(file.toPath()))throw new IOException("Символічне посилання в бібліотеці");if(file.isDirectory())findManifests(file,out);else if(file.getName().equals("library.properties"))out.add(file);}}
    private static List<File> children(File file)throws IOException{File[] files=file.listFiles();if(files==null)throw new IOException("Папка бібліотеки недоступна: "+file.getName());Arrays.sort(files,Comparator.comparing(File::getName));return Arrays.asList(files);}
    private static void copyTree(File source,File target)throws IOException{if(Files.isSymbolicLink(source.toPath()))throw new IOException("Символічні посилання бібліотек не підтримуються");if(source.isDirectory()){Files.createDirectories(target.toPath());for(File child:children(source))copyTree(child,new File(target,child.getName()));}else{Files.createDirectories(target.getParentFile().toPath());Files.copy(source.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);}}
    private static void delete(File file){if(file==null||!file.exists())return;if(file.isDirectory()&&!Files.isSymbolicLink(file.toPath())){File[] files=file.listFiles();if(files!=null)for(File child:files)delete(child);}file.delete();}
    public static String hashTree(File directory)throws IOException{try{MessageDigest hash=MessageDigest.getInstance("SHA-256");hashTree(directory,directory,hash);return hex(hash.digest());}catch(NoSuchAlgorithmException e){throw new IOException(e);}}
    private static void hashTree(File root,File file,MessageDigest hash)throws IOException{if(Files.isSymbolicLink(file.toPath()))throw new IOException("Символічне посилання бібліотеки");if(file.isDirectory()){for(File child:children(file))hashTree(root,child,hash);}else{hash.update(root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar,'/').getBytes(StandardCharsets.UTF_8));hash.update((byte)0);try(InputStream in=new FileInputStream(file)){byte[] buffer=new byte[32768];int n;while((n=in.read(buffer))!=-1)hash.update(buffer,0,n);}hash.update((byte)0);}}
    private static String hashFile(File file)throws IOException{try{MessageDigest hash=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] buffer=new byte[32768];int n;while((n=in.read(buffer))!=-1)hash.update(buffer,0,n);}return hex(hash.digest());}catch(NoSuchAlgorithmException e){throw new IOException(e);}}
    private static String hex(byte[] bytes){StringBuilder text=new StringBuilder();for(byte b:bytes)text.append(String.format(Locale.ROOT,"%02x",b&255));return text.toString();}
}
