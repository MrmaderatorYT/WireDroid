package com.ccs.wiredroid.wire;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.regex.*;

/** Real C++ compilation and separate native GPIO simulation processes. */
public final class EmbeddedRunner implements AutoCloseable {
    private final Context context;
    private final File root;
    private final BiConsumer<String, JSONObject> listener;
    private final ExecutorService compiler = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private static final class Session {
        final String id; final JSONArray pins; final JSONObject profile;
        volatile Process process; volatile boolean cancelled;
        volatile NativeBuildDirectory build;
        final ArrayDeque<Integer> serialInput = new ArrayDeque<>();
        final java.nio.ByteBuffer serialBytes = java.nio.ByteBuffer.allocate(8196);
        final java.nio.charset.CharsetDecoder serialDecoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE);
        Session(String id, JSONArray pins, JSONObject profile) { this.id=id;this.pins=pins;this.profile=profile; }
        synchronized void stop() {cancelled=true;if(process!=null)process.destroyForcibly();}
        synchronized boolean setProcess(Process next) {
            process=next;if(cancelled){next.destroyForcibly();return false;}return true;
        }
    }
    public EmbeddedRunner(Context context, File root, BiConsumer<String, JSONObject> listener) {
        this.context=context;this.root=root;this.listener=listener;
    }
    public synchronized String start(String id, String rawPins) {
        try {
            WireProject.sketch(root,id);
            JSONObject options=rawPins.trim().startsWith("{")?new JSONObject(rawPins):new JSONObject();
            JSONArray pins=options.has("pins")?options.getJSONArray("pins"):new JSONArray(rawPins);
            JSONObject profile=options.optJSONObject("profile");if(profile==null)profile=new JSONObject();
            if(pins.length()<1||pins.length()>1024)return "Некоректні контакти плати";
            if(sessions.size()>=8&&!sessions.containsKey(id))return "Одночасно можна виконувати до 8 програм плат";
            stop(id);Session s=new Session(id,pins,profile);sessions.put(id,s);
            compiler.execute(()->compileAndRun(s));return "";
        }catch(Exception e){return String.valueOf(e.getMessage());}
    }
    private void emit(Session s, String type, String text) {
        if(s.cancelled || sessions.get(s.id)!=s)return;
        try{JSONObject event=new JSONObject().put("type",type).put("text",text);listener.accept(s.id,event);}catch(Exception ignored){}
    }
    private void compileAndRun(Session s) {
        boolean readerStarted=false;
        try {
            File nativeDir=new File(context.getApplicationInfo().nativeLibraryDir);
            File clang=new File(nativeDir,"libwiredroid_clang.so"),lld=new File(nativeDir,"libwiredroid_lld.so");
            if(!clang.canExecute()||!lld.canExecute())throw new IOException("Вбудована C++-симуляція потребує ARM64 Android-пристрою");
            if(s.cancelled)return;
            s.build=NativeBuildDirectory.create(context.getCacheDir(),s.id);File dir=s.build.directory;
            File source=WireProject.sketch(root,s.id);
            WireProject.ensureSketch(root,s.id,WireProject.DEFAULT_CODE);
            File sdk=EmbeddedBuild.sdk(context);
            copyRuntime("wiredroid/runtime",dir);
            WireProject.write(new File(dir,"pins.h"),pinHeader(s.pins,s.profile));
            EmbeddedBuild.Plan plan=EmbeddedBuild.plan(root,source,s.id);
            String code=WireProject.readFile(source,2*1024*1024);
            if(code.startsWith("\uFEFF"))code=code.substring(1);
            File sketch=new File(dir,"sketch.cpp");
            WireProject.write(sketch,"#line 1 "+cppQuote(source.getAbsolutePath())+"\n"+code);
            List<File> units=new ArrayList<>();units.add(sketch);for(File f:plan.sources)if(!f.equals(source))units.add(f);
            for(String name:new String[]{"runtime.cpp","peripherals.cpp","conversions.cpp","api/Common.cpp","api/Print.cpp","api/Stream.cpp","api/String.cpp","api/IPAddress.cpp"})units.add(new File(dir,name));
            File libraries=new File(dir,"libraries");File[] bundled=libraries.listFiles();
            if(bundled!=null)for(File library:bundled) {
                if(plan.libraries.contains(library.getName()))continue;
                File libsrc=new File(library,"src");plan.includes.add(libsrc);
                File[] files=libsrc.listFiles();if(files!=null)for(File f:files)if(f.getName().endsWith(".cpp"))units.add(f);
            }
            List<String> objects=new ArrayList<>();
            emit(s,"log",source.getName()+" · Clang · C++17 / C11 · "+units.size()+" файлів · "+String.join(", ",plan.libraries));
            for(int index=0;index<units.size();index++) {
                File input=units.get(index),object=new File(dir,"unit-"+index+".o");objects.add(object.getPath());
                boolean c=input.getName().endsWith(".c"),assembler=input.getName().endsWith(".S");
                List<String> args=new ArrayList<>(Arrays.asList(clang.getPath(),"--target=aarch64-linux-android26","--sysroot="+sdk.getPath(),
                    "-O1","-Wno-main","-fPIC","-ffunction-sections","-fdata-sections","-DHOST","-DEXTENDED_PIN_MODE","-I",dir.getPath()));
                if(!assembler)args.add(c?"-std=c11":"-std=c++17");
                if(!c&&!assembler){args.addAll(Arrays.asList("-nostdinc++","-isystem",new File(sdk,"usr/include/c++/v1").getPath()));}
                args.addAll(Arrays.asList("-isystem",new File(sdk,"clang/include").getPath(),"-isystem",new File(sdk,"usr/include/aarch64-linux-android").getPath()));
                if(!assembler)args.addAll(Arrays.asList("-include",new File(dir,"pins.h").getPath()));
                for(String define:plan.target.defines)args.add("-D"+define);
                for(File include:plan.includes)args.addAll(Arrays.asList("-I",include.getPath()));
                args.addAll(Arrays.asList("-c",input.getPath(),"-o",object.getPath()));executeTool(s,args,dir);
            }
            File program=new File(dir,"simulation.elf"),lib=new File(sdk,"usr/lib");
            List<String> link=new ArrayList<>(Arrays.asList(lld.getPath(),"-flavor","gnu","--dynamic-linker","/system/bin/linker64","-pie",
                "--eh-frame-hdr","--gc-sections","-z","max-page-size=16384","-rpath",nativeDir.getPath(),"-o",program.getPath(),
                new File(lib,"crtbegin_dynamic.o").getPath()));
            link.addAll(objects);link.addAll(Arrays.asList("-L"+lib.getPath(),"-L"+nativeDir.getPath(),"-lc++_shared","-lc","-lm","-ldl",
                new File(lib,"libunwind.a").getPath(),new File(lib,"libclang_rt.builtins-aarch64-android.a").getPath(),new File(lib,"crtend_android.o").getPath()));
            executeTool(s,link,dir);
            if(s.cancelled)return;
            ProcessBuilder builder=new ProcessBuilder("/system/bin/linker64",program.getAbsolutePath()).directory(root).redirectErrorStream(true);
            File storage=new File(root,".wiredroid/eeprom");if(!storage.isDirectory())storage.mkdirs();
            builder.environment().put("WIRE_EEPROM_FILE",new File(storage,s.id+".bin").getPath());
            builder.environment().put("LD_LIBRARY_PATH",nativeDir.getPath());Process process=builder.start();
            if(!s.setProcess(process))return;
            emit(s,"log","C++ скомпільовано · запуск GPIO-моделі");
            timers.schedule(()->{if(sessions.get(s.id)==s&&!s.cancelled){emit(s,"error","Ліміт сеансу 10 хвилин. Запустіть схему повторно.");s.stop();}},10,TimeUnit.MINUTES);
            Thread reader=new Thread(()->readProgram(s,process),"WireDroid-"+s.id);reader.setDaemon(true);reader.start();readerStarted=true;
        }catch(Exception error){if(!s.cancelled)emit(s,"error",String.valueOf(error.getMessage()));s.stop();}
        finally{if(!readerStarted){s.stop();finishBuild(s);}}
    }
    private void copyRuntime(String asset,File target)throws IOException {
        String[] children=context.getAssets().list(asset);
        if(children!=null&&children.length>0){if(!target.isDirectory()&&!target.mkdirs())throw new IOException("Не вдалося створити Arduino API");for(String name:children)copyRuntime(asset+"/"+name,new File(target,name));}
        else{try(InputStream in=context.getAssets().open(asset);OutputStream out=new FileOutputStream(target)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}}
    }
    private void executeTool(Session s,List<String> args,File dir) throws Exception {
        if(s.cancelled)throw new InterruptedException("Збірку скасовано");
        File log=new File(dir,"compiler.log");
        Process process=new ProcessBuilder(args).directory(root).redirectErrorStream(true).redirectOutput(log).start();
        if(!s.setProcess(process))throw new InterruptedException("Збірку скасовано");
        if(!process.waitFor(120,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(3,TimeUnit.SECONDS);if(!s.cancelled)preserveCompilerFailure(s,args,dir,process.isAlive()?-1:process.exitValue(),"timeout");throw new IOException("Компіляція перевищила 120 секунд");}
        if(s.cancelled)throw new InterruptedException("Збірку скасовано");
        if(process.exitValue()!=0)preserveCompilerFailure(s,args,dir,process.exitValue(),"nonzero exit");
        String text=WireProject.readFile(log,1024*1024);
        if(process.exitValue()!=0)throw new IOException(text.isEmpty()?"Компілятор завершився з кодом "+process.exitValue():text);
        if(!text.trim().isEmpty())emit(s,"log",text);
    }
    private void readProgram(Session s,Process process) {
        int total=0,events=0;long window=System.nanoTime();
        try(InputStream in=process.getInputStream()) {
            ByteArrayOutputStream line=new ByteArrayOutputStream();int b;
            while(!s.cancelled&&(b=in.read())!=-1) {
                if(++total>32*1024*1024)throw new IOException("Програма перевищила ліміт виводу 32 MiB");
                if(b=='\n') {
                    if(System.nanoTime()-window>1000000000L){events=0;window=System.nanoTime();}
                    if(++events>20000)throw new IOException("Понад 20000 GPIO/Serial-операцій за секунду");
                    event(s,line.toString("UTF-8"));line.reset();
                }else if(line.size()<8192)line.write(b);
                else throw new IOException("Рядок виводу завеликий");
            }
            if(!s.cancelled){int exit=process.waitFor();if(exit!=0)preserveFailure(s,exit,"Програма завершилась, код "+exit);emit(s,"error","Програма завершилась, код "+exit);}
        }catch(Exception e){if(!s.cancelled){preserveFailure(s,-1,String.valueOf(e.getMessage()));emit(s,"error",String.valueOf(e.getMessage()));}}
        finally{s.stop();finishBuild(s);}
    }
    private void preserveFailure(Session s,int exit,String reason) {
        if(s.build==null)return;
        try{File saved=NativeBuildDirectory.preserveFailure(context.getCacheDir(),root,s.id,s.build.directory,exit,reason);emit(s,"log","Діагностику C++ збережено: "+saved.getPath());}
        catch(Exception error){emit(s,"log","Не вдалося зберегти діагностику C++: "+error.getMessage());}
    }
    private void preserveCompilerFailure(Session s,List<String> args,File dir,int exit,String reason) {
        try{File saved=NativeBuildDirectory.preserveCompilerFailure(context.getCacheDir(),root,s.id,dir,exit,reason,args);emit(s,"log","Діагностику компілятора збережено: "+saved.getPath());}
        catch(Exception error){emit(s,"log","Не вдалося зберегти діагностику компілятора: "+error.getMessage());}
    }
    private void finishBuild(Session s) {
        Process process=s.process;boolean interrupted=false;
        if(process!=null) {
            for(int attempt=0;attempt<3&&process.isAlive();attempt++) {
                try{process.waitFor(1,TimeUnit.SECONDS);}catch(InterruptedException e){interrupted=true;Thread.interrupted();}
            }
        }
        try{if(s.build!=null&&(process==null||!process.isAlive()))s.build.close();}catch(IOException ignored){}
        if(interrupted)Thread.currentThread().interrupt();
    }
    private void event(Session s,String line) throws Exception {
        if(line.equals("@W A")||line.equals("@W C")||line.equals("@W P")) {
            synchronized(s) {
                int value=line.equals("@W A")?s.serialInput.size():s.serialInput.isEmpty()?-1:line.equals("@W C")?s.serialInput.removeFirst():s.serialInput.peekFirst();
                writeReply(s,value);
            }
            return;
        }
        if(line.startsWith("@W S ")) {
            String hex=line.substring(5);if(hex.length()>8192||hex.length()%2!=0)throw new IOException("Некоректний Serial frame");
            byte[] bytes=new byte[hex.length()/2];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);
            s.serialBytes.put(bytes).flip();java.nio.CharBuffer chars=java.nio.CharBuffer.allocate(8196);
            s.serialDecoder.decode(s.serialBytes,chars,false);s.serialBytes.compact();chars.flip();
            if(chars.hasRemaining())emit(s,"serial",chars.toString());return;
        }
        if(line.startsWith("@W READY")){emit(s,"ready","");return;}
        if(line.startsWith("@W L ")){emit(s,"log",line.substring(5));return;}
        if(line.startsWith("@W E ")){emit(s,"error",line.substring(5));return;}
        if(!line.startsWith("@W ")){emit(s,"log",line);return;}
        if(line.startsWith("@W ADC ")) {int bits=Integer.parseInt(line.substring(7));if(bits<1||bits>16)throw new IOException("ADC: 1–16 біт");listener.accept(s.id,new JSONObject().put("type","adc-resolution").put("bits",bits));return;}
        if(line.startsWith("@W BUS ")) {
            String[] pieces=line.split(" ");if(pieces.length!=9)throw new IOException("Некоректний BUS frame");
            JSONArray pins=new JSONArray();for(String raw:pieces[7].split(",")){int pin=Integer.parseInt(raw);if(pin<-1||pin>=s.pins.length())throw new IOException("Некоректний контакт шини");pins.put(pin<0?JSONObject.NULL:s.pins.getJSONObject(pin).getString("id"));}
            String hex=pieces[8].equals("-")?"":pieces[8];if(!hex.matches("[0-9a-f]{0,4096}")||hex.length()%2!=0)throw new IOException("Некоректні дані шини");
            int count=Integer.parseInt(pieces[4]);if(count<0||count>1024)throw new IOException("До 1024 байтів запиту");
            listener.accept(s.id,new JSONObject().put("type","bus").put("bus",pieces[2]).put("address",Integer.parseInt(pieces[3]))
                .put("count",count).put("frequency",Long.parseLong(pieces[5])).put("time",Long.parseLong(pieces[6])).put("pins",pins).put("data",hex));return;
        }
        String[] parts=line.split(" ");
        if(parts.length<3)return;int index=Integer.parseInt(parts[2]);
        if(index<0||index>=s.pins.length())throw new IOException("Невідомий GPIO-контакт");
        JSONObject event=new JSONObject().put("pin",s.pins.getJSONObject(index).getString("id"));
        switch(parts[1]) {
            case "M":
                int mode=Integer.parseInt(parts[3]);
                if(mode<0||mode>4)throw new IOException("Некоректний pinMode");
                event.put("type","mode").put("mode",new String[]{"INPUT","OUTPUT","INPUT_PULLUP","INPUT_PULLDOWN","OUTPUT_OPENDRAIN"}[mode]);break;
            case "W":event.put("type","write").put("value",Integer.parseInt(parts[3])/255.0);break;
            case "PWM":
                double duty=Double.parseDouble(parts[3]),hz=Double.parseDouble(parts[4]);
                if(!Double.isFinite(duty)||duty<0||duty>1||!Double.isFinite(hz)||hz<=0||hz>1000000)throw new IOException("Некоректний PWM");
                event.put("type","pwm").put("value",duty).put("frequency",hz).put("time",Long.parseLong(parts[5]));break;
            case "V":
                int pulse=Integer.parseInt(parts[3]),angle=Integer.parseInt(parts[4]);
                if((pulse!=0&&(pulse<100||pulse>5000))||angle<0||angle>180)throw new IOException("Некоректний сигнал Servo");
                event.put("type","servo").put("pulse",pulse).put("angle",angle);break;
            case "R":event.put("type","read");break;
            default:return;
        }
        if(!event.has("time")&&parts.length>(parts[1].equals("V")?5:parts[1].equals("R")?3:4))event.put("time",Long.parseLong(parts[parts.length-1]));
        if(!s.cancelled&&sessions.get(s.id)==s)listener.accept(s.id,event);
    }
    private void writeReply(Session s,int value) throws IOException {
        if(s.cancelled||s.process==null)return;
        OutputStream out=s.process.getOutputStream();out.write((value+"\n").getBytes(StandardCharsets.UTF_8));out.flush();
    }
    public void reply(String id,int value) {
        Session s=sessions.get(id);if(s==null)return;
        synchronized(s){try{writeReply(s,Math.max(0,Math.min(65535,value)));}catch(IOException e){s.stop();}}
    }
    public void replyBytes(String id,String hex){
        Session s=sessions.get(id);if(s==null||s.cancelled)return;
        if(hex==null||(!hex.matches("[0-9a-f]{0,4096}")&&!hex.matches("ERR:[^\\r\\n]{1,500}"))){s.stop();return;}
        synchronized(s){try{OutputStream out=s.process.getOutputStream();out.write((hex+"\n").getBytes(StandardCharsets.UTF_8));out.flush();}catch(IOException e){s.stop();}}
    }
    public String sendSerial(String id,String text) {
        Session s=sessions.get(id);if(s==null||s.cancelled)return "Програму плати не запущено";
        if(text==null)return "Порожні дані Serial";
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
        synchronized(s){if(bytes.length+s.serialInput.size()>4096)return "Буфер Serial: до 4096 байтів";for(byte b:bytes)s.serialInput.addLast(b&255);}
        return "";
    }
    public void stop(String id){Session s=sessions.remove(id);if(s!=null)s.stop();}
    public void stopAll(){for(String id:new ArrayList<>(sessions.keySet()))stop(id);}
    @Override public void close(){stopAll();compiler.shutdownNow();timers.shutdownNow();}

    static String cppQuote(String value) {
        StringBuilder out=new StringBuilder("\"");
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if(c=='\\')out.append("\\\\");
            else if(c=='\"')out.append("\\\"");
            else if(c<32 || c==127)out.append(String.format(java.util.Locale.ROOT,"\\%03o",(int)c));
            else out.append(c);
        }
        return out.append('"').toString();
    }

    static String pinHeader(JSONArray pins) throws Exception {return pinHeader(pins,new JSONObject());}
    static String pinHeader(JSONArray pins,JSONObject profile) throws Exception {
        int adc=Math.max(1,Math.min(16,profile.optInt("adcBits",10)));
        StringBuilder declarations=new StringBuilder("#pragma once\n#define WIRE_ADC_BITS "+adc+"\n#include \"Arduino.h\"\n");
        StringBuilder numeric=new StringBuilder("inline int wire_pin(int value){if(value>=200000&&value<201024)return value-200000;switch(value){\n");
        StringBuilder named=new StringBuilder("inline int wire_pin(const char*name){if(!name)return -1;\n");
        Set<Integer> numbers=new HashSet<>();Set<String> macros=new HashSet<>(),aliases=new HashSet<>();
        Pattern digital=Pattern.compile("(?:D|GPIO|IO)?(\\d+)");
        for(int i=0;i<pins.length();i++) {
            JSONObject pin=pins.getJSONObject(i);String id=pin.getString("id"),name=pin.optString("name",id);
            String base=name.toUpperCase(Locale.ROOT).split("[ /]")[0];
            Matcher match=digital.matcher(base);Integer code=null;
            if(match.matches()){try{code=Integer.parseInt(match.group(1));}catch(NumberFormatException ignored){}}
            else if(base.matches("A\\d+")){try{code=1000+Integer.parseInt(base.substring(1));}catch(NumberFormatException ignored){}}
            if(code==null)code=2000+i;
            if(numbers.add(code))numeric.append("case ").append(code).append(":return ").append(i).append(";\n");
            List<String> tokens=new ArrayList<>(Arrays.asList(name.toUpperCase(Locale.ROOT).split("[ /]+")));tokens.add(id);tokens.add(name);tokens.add(base);
            for(String alias:tokens) {
                String clean=alias.replaceFirst("-.*","");
                if(clean.matches("(?:(?:A|D|GPIO|IO|P[A-K]|[B-K])\\d+|SDA\\d*|SCL\\d*|SCK|MOSI|MISO|SS|LED_BUILTIN)")&&macros.add(clean))
                    declarations.append("#define ").append(clean).append(' ').append(code).append('\n');
                if(aliases.add(alias))named.append("if(wire_equal(name,").append(cppQuote(alias)).append("))return ").append(i).append(";\n");
            }
        }
        declarations.append("#ifdef __cplusplus\ninline int wire_bus_pin(int type){static const int pins[]={");
        String[] functions={"SDA","SCL","SCK","MOSI","MISO","SS"};
        for(int type=0;type<functions.length;type++){if(type>0)declarations.append(',');int found=-1;for(int i=0;i<pins.length();i++)if(Arrays.asList(pins.getJSONObject(i).optString("name").toUpperCase(Locale.ROOT).split("[ /]+")).contains(functions[type])){if(found<0)found=i;if(pins.getJSONObject(i).optString("name").matches("(?i)^(D|GPIO|IO)\\d+.*")){found=i;break;}}declarations.append(found);}
        declarations.append("};return type>=0&&type<6?pins[type]:-1;}\n");
        double frequency=profile.optDouble("pwmFrequency",490);if(!Double.isFinite(frequency)||frequency<=0||frequency>1000000)frequency=490;
        declarations.append("inline double wire_default_pwm_frequency(int){return ").append(frequency).append(";}\n#endif\n");
        numeric.append("default:return -1;}}\n");named.append("return -1;}\n");
        return declarations.append("#ifdef __cplusplus\ninline bool wire_equal(const char*a,const char*b){while(*a&&*a==*b){a++;b++;}return *a==*b;}\n").append(numeric).append(named).append("#endif\n").toString();
    }
}
