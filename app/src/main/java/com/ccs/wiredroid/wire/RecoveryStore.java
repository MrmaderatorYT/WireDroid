package com.ccs.wiredroid.wire;

import org.json.JSONObject;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.MessageDigest;

/** Durable text writes with a checked commit journal and one known-good backup. */
public final class RecoveryStore {
    private static final java.util.Map<String,Long> draftEpochs=new java.util.HashMap<>();
    /** Future schema data must remain intact when opened by an older application. */
    public static class NonRecoverableException extends IOException {
        public NonRecoverableException(String message){super(message);}
    }
    public interface Validator { void validate(String text) throws Exception; }
    public static final class Read {
        public final String text, reason;
        public final boolean recovered;
        Read(String text, String reason) { this.text=text;this.reason=reason;recovered=!reason.isEmpty(); }
    }
    private RecoveryStore() {}
    static File sidecar(File file, String kind) throws IOException {
        File absolute=file.getAbsoluteFile(), parent=absolute.getParentFile();
        if(parent==null)throw new IOException("Файл без папки");
        File hidden=new File(parent,".wiredroid-recovery");
        File result=new File(hidden,absolute.getName()+"."+kind);
        // Recovery files follow the same containment rule as their primary file.
        if(!result.getCanonicalFile().toPath().startsWith(parent.getCanonicalFile().toPath()))
            throw new IOException("Файл відновлення за межами папки");
        return result;
    }
    public static synchronized boolean canRecover(File file) {
        try{return file.isFile()||sidecar(file,"backup").isFile()||sidecar(file,"journal").isFile()||new File(file.getPath()+".bak").isFile();}
        catch(IOException ignored){return false;}
    }
    public static synchronized void write(File file,String text) throws IOException {
        write(file,text,null);
    }
    public static synchronized void write(File file,String text,Validator validator) throws IOException {
        write(file,text,validator,StandardCharsets.UTF_8);
    }
    public static synchronized void write(File file,String text,Validator validator,Charset charset) throws IOException {
        advanceDraftEpoch(file);
        check(text,validator);
        File pending=sidecar(file,"pending"), journal=sidecar(file,"journal"), backup=sidecar(file,"backup");
        // Resolve an interrupted earlier commit before starting the next one.
        if(journal.exists()||new File(file.getPath()+".bak").isFile())read(file,Integer.MAX_VALUE,validator,charset);
        String previous=null;
        if(file.isFile()) {
            try{previous=decode(readBytes(file,Integer.MAX_VALUE),charset);check(previous,validator);}
            catch(IOException invalidPrimary){if(validator==null||invalidPrimary instanceof NonRecoverableException)throw invalidPrimary;}
        }
        byte[] bytes=text.getBytes(charset);
        durableReplace(pending,bytes);
        try {
            JSONObject entry=new JSONObject().put("version",1).put("length",bytes.length).put("sha256",digest(bytes));
            durableReplace(journal,entry.toString().getBytes(StandardCharsets.UTF_8));
        }catch(Exception e){throw io(e);}
        // A backup write failure must not be mistaken for an invalid primary.
        if(previous!=null)durableReplace(backup,previous.getBytes(charset));
        move(pending,file);
        Files.deleteIfExists(journal.toPath());
        discardDraft(file);
    }
    public static synchronized Read read(File file,int limit,Validator validator) throws IOException {
        return read(file,limit,validator,StandardCharsets.UTF_8);
    }
    public static synchronized Read read(File file,int limit,Validator validator,Charset charset) throws IOException {
        File pending=sidecar(file,"pending"),journal=sidecar(file,"journal"),backup=sidecar(file,"backup");
        if(journal.isFile()) {
            try {
                JSONObject entry=new JSONObject(decode(readBytes(journal,4096)));
                if(entry.getInt("version")!=1)throw new IOException("Невідома версія журналу");
                if(pending.isFile()) {
                    byte[] bytes=readBytes(pending,limit);
                    if(bytes.length!=entry.getLong("length")||!digest(bytes).equals(entry.getString("sha256")))
                        throw new IOException("Неповний запис у журналі");
                    String recovered=decode(bytes,charset);check(recovered,validator);
                    move(pending,file);Files.deleteIfExists(journal.toPath());
                    return new Read(recovered,"Відновлено завершений запис після переривання");
                }
                byte[] committed=readBytes(file,limit);
                if(committed.length==entry.getLong("length")&&digest(committed).equals(entry.getString("sha256"))) {
                    String text=decode(committed,charset);check(text,validator);Files.deleteIfExists(journal.toPath());
                    return new Read(text,"Завершено перерваний запис");
                }
            }catch(NonRecoverableException futureData){throw futureData;}
            catch(Exception incomplete){
                // An unverified pending file must never replace the last valid scene/program.
            }
        }
        // Android AtomicFile used this sidecar before schema revision 2.
        File legacyBackup=new File(file.getPath()+".bak");
        if(legacyBackup.isFile()) {
            try {
                if(!legacyBackup.getCanonicalFile().toPath().startsWith(file.getAbsoluteFile().getParentFile().getCanonicalFile().toPath()))throw new IOException("Резервна копія поза папкою");
                String text=decode(readBytes(legacyBackup,limit),charset);check(text,validator);
                durableReplace(file,text.getBytes(charset));Files.deleteIfExists(legacyBackup.toPath());
                return new Read(text,"Відновлено незавершений запис попередньої версії");
            }catch(NonRecoverableException futureData){throw futureData;}
            catch(IOException invalidLegacy){/* Fall through to current/known-good data. */}
        }
        try {
            String text=decode(readBytes(file,limit),charset);check(text,validator);
            if(journal.isFile()) {
                Files.deleteIfExists(journal.toPath());Files.deleteIfExists(pending.toPath());
                return new Read(text,"Неповний запис відхилено; збережений файл залишено");
            }
            return new Read(text,"");
        }catch(IOException damaged) {
            if(damaged instanceof NonRecoverableException)throw damaged;
            try {
                String text=decode(readBytes(backup,limit),charset);check(text,validator);
                durableReplace(file,text.getBytes(charset));
                Files.deleteIfExists(journal.toPath());Files.deleteIfExists(pending.toPath());
                return new Read(text,"Відновлено останню справну резервну копію");
            }catch(IOException unavailable){damaged.addSuppressed(unavailable);throw damaged;}
        }
    }
    /** Store the editor buffer without changing the source used by the compiler. */
    public static synchronized void draft(File file,String text) throws IOException {
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
        if(bytes.length>2*1024*1024)throw new IOException("Буфер завеликий для відновлення");
        durableReplace(sidecar(file,"draft"),bytes);
    }
    public static synchronized long draftEpoch(File file)throws IOException {return draftEpochs.getOrDefault(file.getCanonicalPath(),0L);}
    public static synchronized boolean draft(File file,String text,long expectedEpoch)throws IOException {
        if(expectedEpoch!=draftEpoch(file))return false;draft(file,text);return true;
    }
    public static synchronized void checkpoint(File file,String text)throws IOException {
        advanceDraftEpoch(file);draft(file,text);
    }
    public static synchronized String readDraft(File file,int limit) throws IOException {
        File draft=sidecar(file,"draft");
        return draft.isFile()?decode(readBytes(draft,limit)):null;
    }
    public static synchronized void discardDraft(File file) throws IOException {
        advanceDraftEpoch(file);
        Files.deleteIfExists(sidecar(file,"draft").toPath());
    }
    private static void advanceDraftEpoch(File file)throws IOException {String path=file.getCanonicalPath();draftEpochs.put(path,draftEpochs.getOrDefault(path,0L)+1);}
    /** An explicit user deletion must also remove automatic recovery candidates. */
    public static synchronized void discard(File file)throws IOException {
        advanceDraftEpoch(file);
        for(String kind:new String[]{"draft","pending","journal","backup"})Files.deleteIfExists(sidecar(file,kind).toPath());
        File legacy=new File(file.getPath()+".bak");
        if(legacy.getCanonicalFile().toPath().startsWith(file.getAbsoluteFile().getParentFile().getCanonicalFile().toPath()))Files.deleteIfExists(legacy.toPath());
    }
    private static byte[] readBytes(File file,int limit)throws IOException {
        if(limit<0)throw new IOException("Некоректний ліміт");
        try(InputStream input=new FileInputStream(file);ByteArrayOutputStream output=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192];int count;
            while((count=input.read(buffer))!=-1){if((long)output.size()+count>limit)throw new IOException("Файл перевищує допустимий розмір");output.write(buffer,0,count);}
            return output.toByteArray();
        }
    }
    private static String decode(byte[] bytes)throws IOException {
        return decode(bytes,StandardCharsets.UTF_8);
    }
    private static String decode(byte[] bytes,Charset charset)throws IOException {
        try{return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException error){throw new IOException("Пошкоджено файл "+charset.name(),error);}
    }
    private static void check(String text,Validator validator)throws IOException {
        if(validator!=null)try{validator.validate(text);}catch(Exception e){throw io(e);}
    }
    private static IOException io(Exception error){return error instanceof IOException?(IOException)error:new IOException(error.getMessage(),error);}
    static String digest(byte[] bytes)throws IOException {
        try{byte[] hash=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder value=new StringBuilder();for(byte b:hash)value.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return value.toString();}
        catch(Exception error){throw io(error);}
    }
    private static void durableReplace(File target,byte[] bytes)throws IOException {
        File parent=target.getAbsoluteFile().getParentFile();
        if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Не вдалося створити папку");
        File temporary=File.createTempFile(".wiredroid-",".tmp",parent);
        try {
            try(FileOutputStream out=new FileOutputStream(temporary)){out.write(bytes);out.getFD().sync();}
            move(temporary,target);
        }finally{Files.deleteIfExists(temporary.toPath());}
    }
    private static void move(File from,File to)throws IOException {
        File parent=to.getAbsoluteFile().getParentFile();
        if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Не вдалося створити папку");
        try{Files.move(from.toPath(),to.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException error){Files.move(from.toPath(),to.toPath(),StandardCopyOption.REPLACE_EXISTING);}
        // Directory fsync is available on Android internal storage; providers such
        // as document-backed filesystems may only support the file sync above.
        try(java.nio.channels.FileChannel directory=java.nio.channels.FileChannel.open(parent.toPath(),StandardOpenOption.READ)){directory.force(true);}
        catch(IOException|UnsupportedOperationException unavailable){}
    }
}
