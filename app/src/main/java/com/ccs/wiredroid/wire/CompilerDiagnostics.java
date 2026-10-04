package com.ccs.wiredroid.wire;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.regex.*;

/** Clang/GCC locations that can only navigate to source files in this project. */
public final class CompilerDiagnostics {
    private static final Pattern LOCATION=Pattern.compile("^(.+?):([1-9][0-9]*)(?::([1-9][0-9]*))?:\\s*(fatal error|error|warning|note):\\s*(.*)$");
    private static final Pattern ANSI=Pattern.compile("\\u001B\\[[0-9;]*[A-Za-z]");
    public static final class Diagnostic {
        public final File file;
        public final String path,severity,message;
        public final int line,column;
        Diagnostic(File file,String path,int line,int column,String severity,String message){this.file=file;this.path=path;this.line=line;this.column=column;this.severity=severity;this.message=message;}
        public JSONObject json(){try{return new JSONObject().put("file",path).put("line",line).put("column",column).put("severity",severity).put("message",message);}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    }
    private CompilerDiagnostics(){}
    public static File resolve(File root,String path)throws IOException {
        if(path==null||path.indexOf('\0')>=0||path.length()>4096)throw new IOException("Некоректний шлях діагностики");
        File canonical=root.getCanonicalFile();
        File input=new File(path);File file=(input.isAbsolute()?input:new File(canonical,path)).getCanonicalFile();
        if(!file.toPath().startsWith(canonical.toPath())||file.equals(canonical)||!file.isFile())throw new IOException("Файл діагностики поза проєктом або недоступний");
        return file;
    }
    public static List<Diagnostic> parse(File root,String output)throws IOException {
        File canonical=root.getCanonicalFile();List<Diagnostic> result=new ArrayList<>();
        if(output==null)return result;
        String clean=ANSI.matcher(output.length()>1024*1024?output.substring(0,1024*1024):output).replaceAll("");
        Set<String> seen=new HashSet<>();
        for(String raw:clean.split("\\r?\\n")) {
            Matcher match=LOCATION.matcher(raw);if(!match.matches())continue;
            try {
                File file=resolve(canonical,match.group(1));
                int line=Integer.parseInt(match.group(2)),column=match.group(3)==null?1:Integer.parseInt(match.group(3));
                String relative=canonical.toPath().relativize(file.toPath()).toString();
                String key=relative+":"+line+":"+column+":"+match.group(4)+":"+match.group(5);
                if(seen.add(key))result.add(new Diagnostic(file,relative,line,column,match.group(4),match.group(5)));
                if(result.size()==200)break;
            }catch(IOException|NumberFormatException ignored){}
        }
        return result;
    }
    public static String json(File root,String output)throws IOException {
        JSONArray result=new JSONArray();for(Diagnostic item:parse(root,output))result.put(item.json());return result.toString();
    }
}
