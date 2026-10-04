package com.ccs.wiredroid.wire;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Validated, project-local firmware imports used by the simulator and USB uploader. */
public final class FirmwareArtifacts {
    private FirmwareArtifacts() {}
    private static File location(File root, String id) throws IOException {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) throw new IOException("Некоректний ID плати");
        return FirmwareConfig.projectFile(root, ".wiredroid/firmware/" + id + ".hex");
    }
    public static String read(File root, String id) throws Exception {
        File file = location(root,id);
        if (!FirmwareConfig.present(file)) throw new IOException("Імпортуйте прошивку HEX для цієї плати у її налаштуваннях");
        String hex = WireProject.readFile(file, 512 * 1024);
        validate(root,id,hex);
        File metadata=FirmwareConfig.projectFile(root,".wiredroid/firmware/"+id+".json");
        if(!FirmwareConfig.present(metadata))throw new IOException("Прошивка не має відомостей про вихідні файли. Імпортуйте HEX через налаштування плати");
        JSONObject stamp=new JSONObject(WireProject.readFile(metadata,128*1024));
        if(!stamp.getString("hexSha256").equals(hash(hex.getBytes(StandardCharsets.US_ASCII))))throw new IOException("Прошивка змінилася після імпорту. Імпортуйте HEX повторно");
        FirmwareConfig config=FirmwareConfig.load(root,id);
        if(!stamp.getString("profile").equals(config.profile)||!stamp.getString("coreVersion").equals(config.coreVersion))throw new IOException("Ціль плати або core змінилися. Зберіть та імпортуйте відповідну прошивку");
        if(!stamp.getString("sourceDigest").equals(sourceDigest(root)))throw new IOException("Вихідні файли C/C++ змінилися після імпорту HEX. Зберіть прошивку та імпортуйте її повторно");
        if(!stamp.optString("configDigest").equals(configDigest(root,id)))throw new IOException("Налаштування збірки або версії бібліотек змінилися. Зберіть та імпортуйте прошивку повторно");
        return hex;
    }
    public static void importFile(File root, String id, File source) throws Exception {
        File canonical = source.getCanonicalFile();
        if (!canonical.isFile() || canonical.length() > 512 * 1024) throw new IOException("Прошивка має бути HEX до 512 КБ");
        String hex = new String(Files.readAllBytes(canonical.toPath()),StandardCharsets.US_ASCII);
        validate(root,id,hex);
        WireProject.write(location(root,id),hex);
        FirmwareConfig config=FirmwareConfig.load(root,id);
        JSONObject stamp=new JSONObject().put("version",1).put("profile",config.profile).put("coreVersion",config.coreVersion)
                .put("hexSha256",hash(hex.getBytes(StandardCharsets.US_ASCII))).put("sourceDigest",sourceDigest(root))
                .put("configDigest",configDigest(root,id))
                .put("origin","local-import").put("sourceRelationship","Source and configuration snapshot at import; external HEX is not verified as compiled from these sources or settings");
        WireProject.write(FirmwareConfig.projectFile(root,".wiredroid/firmware/"+id+".json"),stamp.toString(2));
    }
    private static String hash(byte[] bytes)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte value:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return out.toString();}
    private static void token(MessageDigest digest,String value)throws IOException{if(value.indexOf('\0')>=0)throw new IOException("Некоректне значення налаштувань збірки");digest.update(value.getBytes(StandardCharsets.UTF_8));digest.update((byte)0);}
    private static void field(MessageDigest digest,String name,String value)throws IOException{token(digest,name);token(digest,value);}
    private static void array(MessageDigest digest,String name,List<String> values)throws IOException{token(digest,name);token(digest,Integer.toString(values.size()));for(String value:values)token(digest,value);}
    /**
     * Versioned NUL-delimited UTF-8 stream mirrored by build-avr-firmware.py.
     * Scalars: profile, coreVersion, cppStandard. Arrays: defines, sources,
     * exclude, includePaths, libraries (count then ordered values).
     * libraryLock: absent, or sha256 then the hash of exact UTF-8 lock contents.
     * Execution mode is omitted: MCU simulation and USB use the same firmware.
     */
    public static String configDigest(File project,String id)throws Exception{
        FirmwareConfig cfg=FirmwareConfig.load(project,id);
        MessageDigest digest=MessageDigest.getInstance("SHA-256");token(digest,"wiredroid-config-v1");
        field(digest,"profile",cfg.profile);field(digest,"coreVersion",cfg.coreVersion);field(digest,"cppStandard",cfg.raw.optString("cppStandard","gnu++11"));
        array(digest,"defines",cfg.defines);array(digest,"sources",cfg.sources);array(digest,"exclude",cfg.excludes);array(digest,"includePaths",cfg.includePaths);
        File settings=FirmwareConfig.projectFile(project,"wiredroid.json");
        JSONObject root=FirmwareConfig.present(settings)?new JSONObject(FirmwareConfig.read(settings,128*1024)):new JSONObject();
        JSONArray selected=root.optJSONArray("libraries");List<String> libraries=new ArrayList<>();if(selected!=null)for(int i=0;i<selected.length();i++)libraries.add(selected.getString(i));
        array(digest,"libraries",libraries);token(digest,"libraryLock");
        File lock=FirmwareConfig.projectFile(project,"wiredroid-libraries.lock.json");
        if(FirmwareConfig.present(lock)){token(digest,"sha256");token(digest,hash(FirmwareConfig.read(lock,128*1024).getBytes(StandardCharsets.UTF_8)));}
        else token(digest,"absent");
        return hex(digest.digest());
    }
    /** Sorted relative UTF-8 path, NUL, exact file bytes, NUL for each C/C++ source/header. */
    public static String sourceDigest(File project)throws Exception{
        File root=project.getCanonicalFile();List<File> files=new ArrayList<>();collect(root,root,files,new HashSet<>(),0);
        files.sort(Comparator.comparing(f->root.toPath().relativize(f.toPath()).toString().replace(File.separatorChar,'/')));
        MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;
        for(File file:files){total+=file.length();if(file.length()>2*1024*1024||total>16*1024*1024)throw new IOException("Вихідні файли перевищують ліміт перевірки прошивки");
            digest.update(root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar,'/').getBytes(StandardCharsets.UTF_8));digest.update((byte)0);
            digest.update(Files.readAllBytes(file.toPath()));digest.update((byte)0);}
        return hex(digest.digest());
    }
    private static void collect(File root,File directory,List<File> files,Set<String> visited,int depth)throws IOException{
        if(depth>32)throw new IOException("Забагато вкладених папок");File canonical=directory.getCanonicalFile();if(!canonical.toPath().startsWith(root.toPath()))throw new IOException("Джерела поза проєктом");if(!visited.add(canonical.getPath()))return;
        File[] entries=canonical.listFiles();if(entries==null)throw new IOException("Папка джерел недоступна");
        for(File file:entries){if(file.getName().startsWith(".")||file.getName().equals("build"))continue;File candidate=file.getCanonicalFile();if(!candidate.toPath().startsWith(root.toPath()))throw new IOException("Джерела поза проєктом");
            if(candidate.isDirectory())collect(root,candidate,files,visited,depth+1);else if(candidate.isFile()&&file.getName().matches("(?i).+\\.(?:cpp|cc|cxx|c|h|hpp|hh|hxx|ino|s)")){files.add(candidate);if(files.size()>4096)throw new IOException("Забагато вихідних файлів");}}
    }
    private static void validate(File root,String id,String hex) throws Exception {
        FirmwareConfig cfg=FirmwareConfig.load(root,id);
        // All currently executable/importable targets are AVR328/168. Unknown targets fail closed.
        int size;
        if(cfg.profile.equals("arduino:avr:nano:atmega168")) size=16*1024;
        else if(cfg.profile.equals("arduino:avr:uno") || cfg.profile.equals("arduino:avr:nano:atmega328") || cfg.profile.equals("arduino:avr:nano:atmega328old")) size=32*1024;
        else throw new IOException("Оберіть підтверджений AVR-профіль перед імпортом прошивки");
        IntelHex.parse(hex,size);
    }
}
