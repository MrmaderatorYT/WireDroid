package com.ccs.wiredroid.wire;

import org.json.JSONObject;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;

/** A build directory belongs to one compiler/process lifetime, never to a board id. */
final class NativeBuildDirectory implements AutoCloseable {
    static final long MAX_FAILURE_ELF=16*1024*1024;
    private static final int MAX_LOG=1024*1024;
    final File directory;
    private boolean closed;
    private NativeBuildDirectory(File directory){this.directory=directory;}

    static NativeBuildDirectory create(File cache,String id)throws IOException {
        if(id==null||!id.matches("[a-zA-Z0-9_-]{1,64}"))throw new IOException("Некоректний ідентифікатор плати");
        File parent=new File(cache,"embedded");
        Files.createDirectories(parent.toPath());
        return new NativeBuildDirectory(Files.createTempDirectory(parent.toPath(),id+"-").toFile());
    }

    static synchronized File preserveFailure(File cache,File project,String id,File build,int exit,String reason)throws Exception {
        File target=new File(cache,"embedded-last-failure");
        if(!target.isDirectory()&&!target.mkdirs())throw new IOException("Не вдалося зберегти діагностику");
        File program=new File(build,"simulation.elf"),saved=new File(target,"simulation.elf");
        JSONObject info=new JSONObject().put("version",1).put("board",id).put("project",project.getCanonicalPath())
                .put("build",build.getCanonicalPath()).put("originalExecutable",program.getPath())
                .put("exitCode",exit).put("reason",reason==null?"":reason.substring(0,Math.min(reason.length(),2000)))
                .put("timestampMs",System.currentTimeMillis());
        if(program.isFile()&&program.length()<=MAX_FAILURE_ELF) {
            byte[] bytes=readBounded(program,MAX_FAILURE_ELF);
            replace(saved,bytes);
            info.put("executable",saved.getPath()).put("elfBytes",bytes.length).put("sha256",digest(bytes));
        } else {Files.deleteIfExists(saved.toPath());info.put("elfOmitted","missing or exceeds 16 MiB");}
        File log=new File(build,"compiler.log");ByteArrayOutputStream content=new ByteArrayOutputStream();
        if(log.isFile())try(InputStream input=new FileInputStream(log)) {
            byte[] bytes=new byte[8192];int n;
            while(content.size()<MAX_LOG&&(n=input.read(bytes,0,Math.min(bytes.length,MAX_LOG-content.size())))!=-1)content.write(bytes,0,n);
        }
        replace(new File(target,"compiler.log"),content.toByteArray());
        info.put("compilerLogTruncated",log.isFile()&&log.length()>MAX_LOG);
        replace(new File(target,"failure.json"),info.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return target;
    }

    static synchronized File preserveCompilerFailure(File cache,File project,String id,File build,int exit,String reason,java.util.List<String> arguments)throws Exception {
        File target=new File(cache,"embedded-last-compiler-failure");Files.createDirectories(target.toPath());
        org.json.JSONArray argv=new org.json.JSONArray();int total=0;boolean truncated=false;
        for(String arg:arguments){int length=Math.min(arg.length(),8192);if(total+length>128*1024){truncated=true;break;}argv.put(arg.substring(0,length));total+=length;if(length!=arg.length())truncated=true;}
        JSONObject info=new JSONObject().put("version",1).put("kind","compiler").put("board",id).put("project",project.getCanonicalPath())
                .put("build",build.getCanonicalPath()).put("exitCode",exit).put("reason",reason).put("argv",argv).put("argvTruncated",truncated).put("timestampMs",System.currentTimeMillis());
        File log=new File(build,"compiler.log");ByteArrayOutputStream content=new ByteArrayOutputStream();
        if(log.isFile())try(InputStream input=new FileInputStream(log)){byte[] bytes=new byte[8192];int n;while(content.size()<MAX_LOG&&(n=input.read(bytes,0,Math.min(bytes.length,MAX_LOG-content.size())))!=-1)content.write(bytes,0,n);}
        replace(new File(target,"compiler.log"),content.toByteArray());info.put("compilerLogTruncated",log.isFile()&&log.length()>MAX_LOG);
        int compile=arguments.indexOf("-c");File source=compile>=0&&compile+1<arguments.size()?new File(arguments.get(compile+1)):null;
        saveInput(info,target,"source",source,2*1024*1024);saveInput(info,target,"pins",new File(build,"pins.h"),2*1024*1024);
        replace(new File(target,"failure.json"),info.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));return target;
    }
    private static void saveInput(JSONObject info,File target,String key,File input,long limit)throws Exception {
        File saved=new File(target,key+".txt");
        if(input!=null&&input.isFile()&&input.length()<=limit) {
            byte[] bytes=readBounded(input,limit);replace(saved,bytes);info.put(key,new JSONObject().put("originalPath",input.getCanonicalPath()).put("savedPath",saved.getPath()).put("bytes",bytes.length).put("sha256",digest(bytes)));
        } else {Files.deleteIfExists(saved.toPath());info.put(key+"Omitted","missing or exceeds 2 MiB");}
    }

    private static void replace(File file,byte[] bytes)throws IOException {
        Path temp=Files.createTempFile(file.getParentFile().toPath(),"snapshot-",".tmp");
        try {
            try(FileOutputStream out=new FileOutputStream(temp.toFile())){out.write(bytes);out.getFD().sync();}
            Files.move(temp,file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally {Files.deleteIfExists(temp);}
    }
    private static byte[] readBounded(File file,long limit)throws IOException {
        try(InputStream input=new FileInputStream(file);ByteArrayOutputStream content=new ByteArrayOutputStream()) {
            byte[] bytes=new byte[8192];int n;
            while((n=input.read(bytes))!=-1){if((long)content.size()+n>limit)throw new IOException("Діагностика перевищує 16 MiB");content.write(bytes,0,n);}
            return content.toByteArray();
        }
    }
    private static String digest(byte[] bytes)throws Exception {
        StringBuilder hash=new StringBuilder();for(byte value:MessageDigest.getInstance("SHA-256").digest(bytes))hash.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return hash.toString();
    }
    @Override public synchronized void close()throws IOException {
        if(closed)return;
        if(Files.exists(directory.toPath(),LinkOption.NOFOLLOW_LINKS)) {
            try(java.util.stream.Stream<Path> files=Files.walk(directory.toPath())) {
                java.util.List<Path> owned=files.sorted(java.util.Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList());
                for(Path path:owned)Files.deleteIfExists(path);
            }
        }
        closed=true;
    }
}
