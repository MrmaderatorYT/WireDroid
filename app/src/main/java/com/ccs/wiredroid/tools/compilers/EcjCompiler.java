package com.ccs.wiredroid.tools.compilers;

import org.eclipse.jdt.internal.compiler.batch.Main;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles ECJ (Eclipse Compiler for Java) batch compilation, classpath preparation,
 * and source parsing helpers.
 */
public final class EcjCompiler {

    /** System.out/System.err are process-global; serialize compiler access */
    public static final Object SYSTEM_STREAM_LOCK = new Object();

    private EcjCompiler() {}

    public static String compileEcj(File androidJar, String classpath, File outDir,
                                    String javaTarget, File... srcFiles) {
        ByteArrayOutputStream ecjOut = new ByteArrayOutputStream();
        ByteArrayOutputStream ecjErr = new ByteArrayOutputStream();
        PrintWriter outWriter = new PrintWriter(new OutputStreamWriter(ecjOut, StandardCharsets.UTF_8), true);
        PrintWriter errWriter = new PrintWriter(new OutputStreamWriter(ecjErr, StandardCharsets.UTF_8), true);
        Main ecj = new Main(outWriter, errWriter, false, null, null);
        List<String> args = new ArrayList<>();
        for (String flag : ecjVersionFlags(javaTarget)) args.add(flag);
        args.add("-proc:none");
        boolean isJava9OrAbove = javaTarget != null && !javaTarget.equals("1.8") && !javaTarget.startsWith("1.");
        if (isJava9OrAbove) {
            args.add("-classpath");
            if (classpath != null && !classpath.isEmpty()) {
                args.add(androidJar.getAbsolutePath() + File.pathSeparator + classpath);
            } else {
                args.add(androidJar.getAbsolutePath());
            }
        } else {
            args.add("-bootclasspath");
            args.add(androidJar.getAbsolutePath());
            if (classpath != null && !classpath.isEmpty()) {
                args.add("-classpath");
                args.add(classpath);
            }
        }
        args.add("-d");
        args.add(outDir.getAbsolutePath());
        for (File s : srcFiles) args.add(s.getAbsolutePath());

        // Filter out non-jar files from classpath to suppress ZipException warnings
        String filteredCp = filterClasspath(classpath);
        if (filteredCp != null && !filteredCp.equals(classpath)) {
            args.clear();
            for (String flag : ecjVersionFlags(javaTarget)) args.add(flag);
            args.add("-proc:none");
            if (isJava9OrAbove) {
                args.add("-classpath");
                args.add(androidJar.getAbsolutePath() + File.pathSeparator + filteredCp);
            } else {
                args.add("-bootclasspath");
                args.add(androidJar.getAbsolutePath());
                args.add("-classpath");
                args.add(filteredCp);
            }
            args.add("-d");
            args.add(outDir.getAbsolutePath());
            for (File s : srcFiles) args.add(s.getAbsolutePath());
        }

        boolean ok;
        synchronized (SYSTEM_STREAM_LOCK) {
            PrintStream oldErr = System.err;
            System.setErr(new PrintStream(new ByteArrayOutputStream()));
            try {
                ok = ecj.compile(args.toArray(new String[0]));
            } finally {
                System.setErr(oldErr);
            }
        }

        outWriter.flush();
        errWriter.flush();
        if (!ok) {
            String errStr = utf8Stream(ecjErr);
            String outStr = utf8Stream(ecjOut);
            if (!errStr.trim().isEmpty()) {
                return errStr;
            }
            if (!outStr.trim().isEmpty()) {
                return outStr;
            }
            return "ECJ: compilation failed (" + ecj.globalErrorsCount
                    + " error(s)); diagnostic streams were empty.";
        }
        return null;
    }

    public static String compileEcjMulti(File androidJar, String classpath, File outDir,
                                         String javaTarget, File[] srcFiles) {
        return compileEcj(androidJar, classpath, outDir, javaTarget, srcFiles);
    }

    public static String[] ecjVersionFlags(String javaTarget) {
        String level = JavaVersions.effective(javaTarget);
        return new String[]{"-source", level, "-target", level};
    }

    public static String filterClasspath(String classpath) {
        if (classpath == null || classpath.isEmpty()) return classpath;
        StringBuilder filtered = new StringBuilder();
        for (String entry : classpath.split(File.pathSeparator)) {
            File f = new File(entry);
            if (f.isDirectory() || entry.endsWith(".jar") || entry.endsWith(".zip")) {
                if (filtered.length() > 0) filtered.append(File.pathSeparator);
                filtered.append(entry);
            }
        }
        return filtered.toString();
    }

    public static String classpath(List<File> jars) {
        if (jars == null || jars.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < jars.size(); i++) {
            if (i > 0) sb.append(File.pathSeparatorChar);
            sb.append(jars.get(i).getAbsolutePath());
        }
        return sb.toString();
    }

    public static void writeUtf8(File f, String s) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static String extractClassName(String source) {
        Matcher m = Pattern.compile("public\\s+class\\s+(\\w+)").matcher(source);
        if (m.find()) return m.group(1);
        m = Pattern.compile("(?m)\\bclass\\s+(\\w+)").matcher(source);
        if (m.find()) return m.group(1);
        m = Pattern.compile("(?m)\\bobject\\s+(\\w+)").matcher(source);
        if (m.find()) return m.group(1);
        return "Main";
    }

    public static String extractPackageName(String source) {
        Matcher m = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;").matcher(source);
        return m.find() ? m.group(1) : "";
    }

    public static File findClassFile(File dir, String className) {
        File direct = new File(dir, className + ".class");
        if (direct.exists()) return direct;
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    File found = findClassFile(child, className);
                    if (found != null) return found;
                }
            }
        }
        return null;
    }

    public static File findCompiledClass(File outDir, String sourceText) {
        String simple = extractClassName(sourceText);
        String pkg = extractPackageName(sourceText);
        if (pkg != null && !pkg.isEmpty()) {
            File f = new File(outDir, pkg.replace('.', File.separatorChar) + "/" + simple + ".class");
            if (f.exists()) {
                return f;
            }
        }
        File f2 = new File(outDir, simple + ".class");
        if (f2.exists()) {
            return f2;
        }
        return findFileNamedRecursive(outDir, simple + ".class");
    }

    public static File findFileNamedRecursive(File dir, String fileName) {
        File[] list = dir.listFiles();
        if (list == null) return null;
        for (File f : list) {
            if (f.isDirectory()) {
                File r = findFileNamedRecursive(f, fileName);
                if (r != null) return r;
            } else if (fileName.equals(f.getName())) {
                return f;
            }
        }
        return null;
    }

    public static List<File> findAllClassFiles(File dir) {
        List<File> result = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) return result;
        for (File f : files) {
            if (f.isDirectory()) {
                result.addAll(findAllClassFiles(f));
            } else if (f.getName().endsWith(".class")) {
                result.add(f);
            }
        }
        return result;
    }

    public static void collectClasses(File dir, List<java.nio.file.Path> out) {
        File[] ch = dir.listFiles();
        if (ch == null) return;
        for (File f : ch) {
            if (f.isDirectory()) collectClasses(f, out);
            else if (f.getName().endsWith(".class")) out.add(f.toPath());
        }
    }

    public static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] c = f.listFiles();
            if (c != null) for (File child : c) deleteRecursive(child);
        }
        f.delete();
    }

    private static String utf8Stream(ByteArrayOutputStream baos) {
        try {
            return baos.toString("UTF-8");
        } catch (Exception e) {
            return baos.toString();
        }
    }
}
