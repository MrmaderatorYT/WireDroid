package com.ccs.wiredroid.util;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
public class FileIdentityTest {
    @Test public void resolvesRawCanonicalAndSymlinkAliases()throws Exception {
        File root=Files.createTempDirectory("wire-identity-").toFile();try {
            File source=new File(root,"main.cpp"),relative=new File(root,"./main.cpp"),alias=new File(root,"linked.cpp");
            Files.write(source.toPath(),new byte[]{1});Files.createSymbolicLink(alias.toPath(),source.toPath());
            assertFalse(source.equals(relative));assertTrue(FileIdentity.same(source,relative));assertTrue(FileIdentity.same(alias,source));
            assertFalse(FileIdentity.same(source,new File(root,"other.cpp")));assertFalse(FileIdentity.same(null,source));
        }finally {for(File file:root.listFiles())Files.deleteIfExists(file.toPath());Files.deleteIfExists(root.toPath());}
    }
    @Test public void locksApplyInBothDirectionsAcrossAliases()throws Exception {
        File root=Files.createTempDirectory("wire-lock-").toFile();try {
            File source=new File(root,"main.cpp"),alias=new File(root,"./main.cpp");Files.write(source.toPath(),new byte[]{1});
            assertTrue(FileIdentity.locked(Collections.singleton(alias.getPath()),source.getPath()));
            assertTrue(FileIdentity.locked(Collections.singleton(source.getPath()),alias.getPath()));
            assertFalse(FileIdentity.locked(Collections.singleton(source.getPath()),new File(root,"other.cpp").getPath()));
            assertFalse(FileIdentity.locked(Collections.emptySet(),source.getPath()));
        }finally {Files.deleteIfExists(new File(root,"main.cpp").toPath());Files.deleteIfExists(root.toPath());}
    }
}
