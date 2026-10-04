package com.ccs.wiredroid.util;

import java.io.File;
import java.io.IOException;
import java.util.Set;

/** One physical source can be reached through Android storage or symlink aliases. */
public final class FileIdentity {
    private FileIdentity(){}
    public static boolean same(File left,File right) {
        if(left==null||right==null)return false;
        if(left.equals(right))return true;
        try{return left.getCanonicalFile().equals(right.getCanonicalFile());}
        catch(IOException unavailable){return false;}
    }
    public static boolean locked(Set<String> paths,String path) {
        if(path==null||paths==null)return false;
        if(paths.contains(path))return true;
        File file=new File(path);
        for(String entry:paths)if(entry!=null&&same(file,new File(entry)))return true;
        return false;
    }
}
