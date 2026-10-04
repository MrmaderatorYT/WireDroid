package com.ccs.wiredroid.wire;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Bounded project-local SPICE text exports. No simulator or command execution. */
public final class SpiceArtifacts {
    private static final int MODEL_LIMIT=256*1024, NETLIST_LIMIT=2*1024*1024;
    private static final Pattern EXTERNAL_DIRECTIVE=Pattern.compile("(?im)^\\s*(?:\\.(?:include|inc|lib|control|shell|exec|hdl)\\b|!|(?:shell|source|exec)\\s)");
    private SpiceArtifacts(){}
    public static String readModels(File root)throws IOException {
        File file=FirmwareConfig.projectFile(root,"spice.models");
        return RecoveryStore.canRecover(file)?WireProject.readFile(file,MODEL_LIMIT):"";
    }
    public static void saveModels(File root,String text)throws IOException {
        check(text,MODEL_LIMIT);WireProject.write(FirmwareConfig.projectFile(root,"spice.models"),text);
    }
    public static void saveNetlist(File root,String name,String text)throws IOException {
        if(name==null||!name.matches("[a-zA-Z0-9_-]{1,64}(?:\\.cir)?"))throw new IOException("Вкажіть назву netlist: латинські літери, цифри, _ або -");
        check(text,NETLIST_LIMIT);
        String filename=name.endsWith(".cir")?name:name+".cir";
        WireProject.write(FirmwareConfig.projectFile(root,"exports/"+filename),text);
    }
    private static void check(String text,int limit)throws IOException {
        if(text==null||text.length()>limit||text.getBytes(StandardCharsets.UTF_8).length>limit)throw new IOException("SPICE текст перевищує допустимий розмір");
        if(text.indexOf('\0')>=0)throw new IOException("SPICE файл має містити текст");
        if(EXTERNAL_DIRECTIVE.matcher(text).find())throw new IOException("SPICE експорт підтримує локальний текст без include та команд виконання");
    }
}
