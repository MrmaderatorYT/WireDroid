package com.ccs.wiredroid.tools.compilers;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Kotlin компіляція через embeddable compiler API.
 * Використовує KotlinCoreEnvironment + KotlinToJVMBytecodeCompiler.
 */
public final class KotlinCompiler {

    private static final String TAG = "KotlinCompiler";

    private KotlinCompiler() {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static List<File> compile(File srcFile, File projectRoot, File cacheDir,
                                     File androidJar, String className,
                                     ProjectCompiler.Callback callback, Context context) {
        try {
            ProjectCompiler.postProgress(callback, "Preparing Kotlin compiler...");

            File stdlibJar = ensureKotlinStdlib(context, cacheDir);
            if (stdlibJar == null) {
                ProjectCompiler.postResult(callback, "Kotlin Error: kotlin-stdlib-2.0.21.jar not available.\n" +
                        "Please verify app installation or connect to internet.");
                return null;
            }

            File pluginRoot = ensureKotlinPluginRoot(cacheDir);

            File apkFile = new File(context.getApplicationInfo().sourceDir);
            String combinedDexPath = apkFile.getAbsolutePath() + ":" + stdlibJar.getAbsolutePath();
            ClassLoader appCl = context.getClassLoader();
            dalvik.system.PathClassLoader kotlinCl = new dalvik.system.PathClassLoader(
                    combinedDexPath, null, appCl) {
                @Override
                protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    Class<?> c = findLoadedClass(name);
                    if (c != null) return c;
                    boolean isKotlin = name.startsWith("org.jetbrains.kotlin.") || name.startsWith("kotlin.");
                    if (isKotlin) {
                        try {
                            c = findClass(name);
                            if (resolve) resolveClass(c);
                            return c;
                        } catch (ClassNotFoundException ignored) {}
                    }
                    return super.loadClass(name, resolve);
                }
            };

            Class<?> cfgClass = Class.forName("org.jetbrains.kotlin.config.CompilerConfiguration", true, kotlinCl);
            Object config = cfgClass.getDeclaredConstructor().newInstance();

            Class<?> cckClass = Class.forName("org.jetbrains.kotlin.config.CompilerConfigurationKey", true, kotlinCl);
            Class<?> cliKeysClass = Class.forName("org.jetbrains.kotlin.cli.common.CLIConfigurationKeys", true, kotlinCl);
            Class<?> jvmKeysClass = Class.forName("org.jetbrains.kotlin.config.JVMConfigurationKeys", true, kotlinCl);
            Class<?> commonKeysClass = Class.forName("org.jetbrains.kotlin.config.CommonConfigurationKeys", true, kotlinCl);

            Method putMethod = cfgClass.getMethod("put", cckClass, Object.class);
            Method addMethod = cfgClass.getMethod("add", cckClass, Object.class);

            Field pluginRootField = cliKeysClass.getField("INTELLIJ_PLUGIN_ROOT");
            Object pluginRootKey = pluginRootField.get(null);
            putMethod.invoke(config, pluginRootKey, pluginRoot.getAbsolutePath());

            Field noJdkField = jvmKeysClass.getField("NO_JDK");
            putMethod.invoke(config, noJdkField.get(null), true);

            Field outputDirField = jvmKeysClass.getField("OUTPUT_DIRECTORY");
            putMethod.invoke(config, outputDirField.get(null), cacheDir);

            Field moduleNameField = commonKeysClass.getField("MODULE_NAME");
            putMethod.invoke(config, moduleNameField.get(null), "main");

            com.ccs.wiredroid.util.AppPreferences prefs = new com.ccs.wiredroid.util.AppPreferences(context);
            String javaTarget = prefs.getJavaTarget();

            Class<?> jvmTargetEnumClass = Class.forName("org.jetbrains.kotlin.config.JvmTarget", true, kotlinCl);
            Field jvmTargetField = jvmKeysClass.getField("JVM_TARGET");
            // The bundled Kotlin compiler tops out below the Java ceiling, so walk
            // down from the requested level until the enum recognises a name.
            Object jvmTargetObj = null;
            for (String name : JavaVersions.kotlinJvmTargets(javaTarget)) {
                try {
                    jvmTargetObj = Enum.valueOf((Class<Enum>) jvmTargetEnumClass, name);
                    break;
                } catch (IllegalArgumentException ignored) {}
            }
            if (jvmTargetObj == null) {
                jvmTargetObj = Enum.valueOf((Class<Enum>) jvmTargetEnumClass, "JVM_1_8");
            }
            putMethod.invoke(config, jvmTargetField.get(null), jvmTargetObj);
            applyLanguageVersion(context, kotlinCl, commonKeysClass, config, putMethod);

            final boolean[] hadError = {false};
            final StringBuilder compilerMessages = new StringBuilder();
            Class<?> mcInterface = Class.forName("org.jetbrains.kotlin.cli.common.messages.MessageCollector", true, kotlinCl);
            Class<?> severityClass = Class.forName("org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity", true, kotlinCl);
            Method isErrorMethod = severityClass.getMethod("isError");
            Object messageCollector = Proxy.newProxyInstance(
                    mcInterface.getClassLoader(),
                    new Class<?>[]{mcInterface},
                    (InvocationHandler) (proxy, method, args) -> {
                        String methodName = method.getName();
                        if ("report".equals(methodName) && args != null && args.length >= 2) {
                            Object severity = args[0];
                            String message = (String) args[1];
                            boolean isError = false;
                            try { isError = (boolean) isErrorMethod.invoke(severity); } catch (Exception ignored) {}
                            if (isError) hadError[0] = true;
                            String prefix = isError ? "ERROR: " : "INFO: ";
                            compilerMessages.append(prefix).append(message).append("\n");
                            Log.d(TAG, prefix + message);
                            return null;
                        }
                        if ("clear".equals(methodName)) { compilerMessages.setLength(0); hadError[0] = false; return null; }
                        if ("hasErrors".equals(methodName)) return hadError[0];
                        if ("toString".equals(methodName)) return "WireDroidMessageCollector";
                        if ("hashCode".equals(methodName)) return System.identityHashCode(proxy);
                        if ("equals".equals(methodName)) return proxy == args[0];
                        Class<?> rt = method.getReturnType();
                        if (rt == boolean.class) return hadError[0];
                        if (rt == int.class) return 0;
                        return null;
                    }
            );
            Field mcKeyField = cliKeysClass.getField("MESSAGE_COLLECTOR_KEY");
            putMethod.invoke(config, mcKeyField.get(null), messageCollector);

            Field contentRootsField = cliKeysClass.getField("CONTENT_ROOTS");

            Class<?> kotlinSourceRootClass = Class.forName("org.jetbrains.kotlin.cli.common.config.KotlinSourceRoot", true, kotlinCl);
            Object sourceRoot = kotlinSourceRootClass.getDeclaredConstructor(
                    String.class, boolean.class, String.class)
                    .newInstance(srcFile.getAbsolutePath(), false, null);
            addMethod.invoke(config, contentRootsField.get(null), sourceRoot);

            // The rest of the project goes in so the file being run can reference
            // it. The two languages take different root types: KotlinSourceRoot
            // rejects anything that is not .kt outright ("Source entry is not a
            // Kotlin file"), while Java files belong in a JavaSourceRoot, where
            // they are read for resolution and left for ECJ to actually compile.
            if (projectRoot != null && projectRoot.exists()) {
                Class<?> javaSourceRootClass = Class.forName(
                        "org.jetbrains.kotlin.cli.jvm.config.JavaSourceRoot", true, kotlinCl);
                List<File> allSources = new ArrayList<>();
                collectSources(projectRoot, allSources);
                for (File f : allSources) {
                    if (f.getAbsolutePath().equals(srcFile.getAbsolutePath())) continue;
                    Object extRoot;
                    if (f.getName().endsWith(".kt")) {
                        extRoot = kotlinSourceRootClass.getDeclaredConstructor(
                                String.class, boolean.class, String.class)
                                .newInstance(f.getAbsolutePath(), false, null);
                    } else {
                        extRoot = javaSourceRootClass.getDeclaredConstructor(
                                File.class, String.class).newInstance(f, null);
                    }
                    addMethod.invoke(config, contentRootsField.get(null), extRoot);
                }
            }

            Class<?> jvmCpRootClass = Class.forName("org.jetbrains.kotlin.cli.jvm.config.JvmClasspathRoot", true, kotlinCl);
            Object cpRootAndroid = jvmCpRootClass.getDeclaredConstructor(File.class).newInstance(androidJar);
            addMethod.invoke(config, contentRootsField.get(null), cpRootAndroid);
            Object cpRootStdlib = jvmCpRootClass.getDeclaredConstructor(File.class).newInstance(stdlibJar);
            addMethod.invoke(config, contentRootsField.get(null), cpRootStdlib);

            ProjectCompiler.postProgress(callback, "Initializing Kotlin compiler environment...");
            Class<?> disposableClass = Class.forName("org.jetbrains.kotlin.com.intellij.openapi.Disposable", true, kotlinCl);
            Class<?> disposerClass = Class.forName("org.jetbrains.kotlin.com.intellij.openapi.util.Disposer", true, kotlinCl);
            Object disposable = disposerClass.getMethod("newDisposable").invoke(null);

            Class<?> envConfigClass = Class.forName("org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles", true, kotlinCl);
            Object jvmConfigFiles = Enum.valueOf((Class<Enum>) envConfigClass, "JVM_CONFIG_FILES");

            Class<?> kotlinCoreEnvClass = Class.forName("org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment", true, kotlinCl);
            Object environment = null;
            try {
                environment = kotlinCoreEnvClass.getMethod(
                        "createForProduction", disposableClass, cfgClass, envConfigClass)
                        .invoke(null, disposable, config, jvmConfigFiles);
            } catch (Exception e) {
                Log.e(TAG, "Failed to create KotlinCoreEnvironment", e);
                ProjectCompiler.postResult(callback, "Kotlin Error: Failed to initialize compiler environment.\n" +
                        e.getClass().getSimpleName() + ": " + e.getMessage());
                return null;
            }

            ProjectCompiler.postProgress(callback, "Compiling Kotlin...");
            Class<?> compilerClass = Class.forName("org.jetbrains.kotlin.cli.jvm.compiler.KotlinToJVMBytecodeCompiler", true, kotlinCl);
            Object compilerInstance = compilerClass.getField("INSTANCE").get(null);
            Method compileMethod = compilerClass.getMethod("compileBunchOfSources", kotlinCoreEnvClass);
            boolean success = (Boolean) compileMethod.invoke(compilerInstance, environment);

            List<File> classFiles = findAllClassFiles(cacheDir);

            if (!success && classFiles.isEmpty()) {
                String errDetail = compilerMessages.toString();
                if (errDetail.isEmpty()) errDetail = "compileBunchOfSources returned false with no output files.";
                ProjectCompiler.postResult(callback, "Kotlin Compilation Error:\n" + errDetail);
                return null;
            }

            Log.d(TAG, "Generated " + classFiles.size() + " class files");
            if (hadError[0] && classFiles.isEmpty()) {
                ProjectCompiler.postResult(callback, "Kotlin Compilation Error:\n" + compilerMessages);
                return null;
            }
            return classFiles.isEmpty() ? null : classFiles;

        } catch (ClassNotFoundException e) {
            Log.e(TAG, "Kotlin compiler class not found", e);
            ProjectCompiler.postResult(callback, "Kotlin Error: compiler library not integrated.\n" + e.getMessage());
        } catch (NoSuchMethodException e) {
            Log.e(TAG, "Method not found in compiler library", e);
            ProjectCompiler.postResult(callback, "Kotlin Error: incompatible compiler API.\n" + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Kotlin compilation failed", e);
            ProjectCompiler.postResult(callback, "Kotlin System Error:\n" + e.getClass().getSimpleName() +
                    ": " + e.getMessage() + "\n" + Log.getStackTraceString(e));
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean compileProject(Context context, File projectRoot,
                                         List<File> ktSources, List<File> javaSources,
                                         List<File> depJars, File androidJar,
                                         File outDir, ProjectCompiler.Callback callback) {
        if (ktSources == null || ktSources.isEmpty()) return true;
        try {
            ProjectCompiler.postProgress(callback, "Preparing Kotlin compiler...");
            File cacheDir = new File(context.getCacheDir(), "compile_cache");
            if (!cacheDir.exists()) cacheDir.mkdirs();

            File stdlibJar = ensureKotlinStdlib(context, cacheDir);
            if (stdlibJar == null) {
                ProjectCompiler.postResult(callback, "Kotlin Error: kotlin-stdlib-2.0.21.jar not available.\n" +
                        "Please verify app installation or connect to internet.");
                return false;
            }

            File pluginRoot = ensureKotlinPluginRoot(cacheDir);

            File apkFile = new File(context.getApplicationInfo().sourceDir);
            String combinedDexPath = apkFile.getAbsolutePath() + ":" + stdlibJar.getAbsolutePath();
            ClassLoader appCl = context.getClassLoader();
            dalvik.system.PathClassLoader kotlinCl = new dalvik.system.PathClassLoader(
                    combinedDexPath, null, appCl) {
                @Override
                protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    Class<?> c = findLoadedClass(name);
                    if (c != null) return c;
                    boolean isKotlin = name.startsWith("org.jetbrains.kotlin.") || name.startsWith("kotlin.");
                    if (isKotlin) {
                        try {
                            c = findClass(name);
                            if (resolve) resolveClass(c);
                            return c;
                        } catch (ClassNotFoundException ignored) {}
                    }
                    return super.loadClass(name, resolve);
                }
            };

            Class<?> cfgClass = Class.forName("org.jetbrains.kotlin.config.CompilerConfiguration", true, kotlinCl);
            Object config = cfgClass.getDeclaredConstructor().newInstance();

            Class<?> cckClass = Class.forName("org.jetbrains.kotlin.config.CompilerConfigurationKey", true, kotlinCl);
            Class<?> cliKeysClass = Class.forName("org.jetbrains.kotlin.cli.common.CLIConfigurationKeys", true, kotlinCl);
            Class<?> jvmKeysClass = Class.forName("org.jetbrains.kotlin.config.JVMConfigurationKeys", true, kotlinCl);
            Class<?> commonKeysClass = Class.forName("org.jetbrains.kotlin.config.CommonConfigurationKeys", true, kotlinCl);

            Method putMethod = cfgClass.getMethod("put", cckClass, Object.class);
            Method addMethod = cfgClass.getMethod("add", cckClass, Object.class);

            Field pluginRootField = cliKeysClass.getField("INTELLIJ_PLUGIN_ROOT");
            putMethod.invoke(config, pluginRootField.get(null), pluginRoot.getAbsolutePath());

            Field noJdkField = jvmKeysClass.getField("NO_JDK");
            putMethod.invoke(config, noJdkField.get(null), true);

            Field outputDirField = jvmKeysClass.getField("OUTPUT_DIRECTORY");
            putMethod.invoke(config, outputDirField.get(null), outDir);

            String moduleName = projectRoot != null ? projectRoot.getName() : "main";
            Field moduleNameField = commonKeysClass.getField("MODULE_NAME");
            putMethod.invoke(config, moduleNameField.get(null), moduleName);

            com.ccs.wiredroid.util.AppPreferences prefs = new com.ccs.wiredroid.util.AppPreferences(context);
            String javaTarget = prefs.getJavaTarget();

            Class<?> jvmTargetEnumClass = Class.forName("org.jetbrains.kotlin.config.JvmTarget", true, kotlinCl);
            Field jvmTargetField = jvmKeysClass.getField("JVM_TARGET");
            Object jvmTargetObj = null;
            for (String name : JavaVersions.kotlinJvmTargets(javaTarget)) {
                try {
                    jvmTargetObj = Enum.valueOf((Class<Enum>) jvmTargetEnumClass, name);
                    break;
                } catch (IllegalArgumentException ignored) {}
            }
            if (jvmTargetObj == null) {
                jvmTargetObj = Enum.valueOf((Class<Enum>) jvmTargetEnumClass, "JVM_1_8");
            }
            putMethod.invoke(config, jvmTargetField.get(null), jvmTargetObj);
            applyLanguageVersion(context, kotlinCl, commonKeysClass, config, putMethod);

            final boolean[] hadError = {false};
            final StringBuilder compilerMessages = new StringBuilder();
            Class<?> mcInterface = Class.forName("org.jetbrains.kotlin.cli.common.messages.MessageCollector", true, kotlinCl);
            Class<?> severityClass = Class.forName("org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity", true, kotlinCl);
            Method isErrorMethod = severityClass.getMethod("isError");
            Object messageCollector = Proxy.newProxyInstance(
                    mcInterface.getClassLoader(),
                    new Class<?>[]{mcInterface},
                    (InvocationHandler) (proxy, method, args) -> {
                        String methodName = method.getName();
                        if ("report".equals(methodName) && args != null && args.length >= 2) {
                            Object severity = args[0];
                            String message = (String) args[1];
                            boolean isError = false;
                            try { isError = (boolean) isErrorMethod.invoke(severity); } catch (Exception ignored) {}
                            if (isError) hadError[0] = true;
                            String prefix = isError ? "ERROR: " : "INFO: ";
                            compilerMessages.append(prefix).append(message).append("\n");
                            Log.d(TAG, prefix + message);
                            return null;
                        }
                        if ("clear".equals(methodName)) { compilerMessages.setLength(0); hadError[0] = false; return null; }
                        if ("hasErrors".equals(methodName)) return hadError[0];
                        if ("toString".equals(methodName)) return "WireDroidMessageCollector";
                        if ("hashCode".equals(methodName)) return System.identityHashCode(proxy);
                        if ("equals".equals(methodName)) return proxy == args[0];
                        Class<?> rt = method.getReturnType();
                        if (rt == boolean.class) return hadError[0];
                        if (rt == int.class) return 0;
                        return null;
                    }
            );
            Field mcKeyField = cliKeysClass.getField("MESSAGE_COLLECTOR_KEY");
            putMethod.invoke(config, mcKeyField.get(null), messageCollector);

            Field contentRootsField = cliKeysClass.getField("CONTENT_ROOTS");

            Class<?> kotlinSourceRootClass = Class.forName("org.jetbrains.kotlin.cli.common.config.KotlinSourceRoot", true, kotlinCl);
            for (File kt : ktSources) {
                Object sourceRoot = kotlinSourceRootClass.getDeclaredConstructor(
                        String.class, boolean.class, String.class)
                        .newInstance(kt.getAbsolutePath(), false, null);
                addMethod.invoke(config, contentRootsField.get(null), sourceRoot);
            }

            if (javaSources != null) {
                Class<?> javaSourceRootClass = Class.forName(
                        "org.jetbrains.kotlin.cli.jvm.config.JavaSourceRoot", true, kotlinCl);
                for (File js : javaSources) {
                    Object extRoot = javaSourceRootClass.getDeclaredConstructor(
                            File.class, String.class).newInstance(js, null);
                    addMethod.invoke(config, contentRootsField.get(null), extRoot);
                }
            }

            Class<?> jvmCpRootClass = Class.forName("org.jetbrains.kotlin.cli.jvm.config.JvmClasspathRoot", true, kotlinCl);
            if (androidJar != null && androidJar.exists()) {
                Object cpRootAndroid = jvmCpRootClass.getDeclaredConstructor(File.class).newInstance(androidJar);
                addMethod.invoke(config, contentRootsField.get(null), cpRootAndroid);
            }
            Object cpRootStdlib = jvmCpRootClass.getDeclaredConstructor(File.class).newInstance(stdlibJar);
            addMethod.invoke(config, contentRootsField.get(null), cpRootStdlib);

            if (depJars != null) {
                for (File dj : depJars) {
                    if (dj.exists()) {
                        Object cpDep = jvmCpRootClass.getDeclaredConstructor(File.class).newInstance(dj);
                        addMethod.invoke(config, contentRootsField.get(null), cpDep);
                    }
                }
            }

            ProjectCompiler.postProgress(callback, "Initializing Kotlin compiler environment...");
            Class<?> disposableClass = Class.forName("org.jetbrains.kotlin.com.intellij.openapi.Disposable", true, kotlinCl);
            Class<?> disposerClass = Class.forName("org.jetbrains.kotlin.com.intellij.openapi.util.Disposer", true, kotlinCl);
            Object disposable = disposerClass.getMethod("newDisposable").invoke(null);

            Class<?> envConfigClass = Class.forName("org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles", true, kotlinCl);
            Object jvmConfigFiles = Enum.valueOf((Class<Enum>) envConfigClass, "JVM_CONFIG_FILES");

            Class<?> kotlinCoreEnvClass = Class.forName("org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment", true, kotlinCl);
            Object environment = kotlinCoreEnvClass.getMethod(
                    "createForProduction", disposableClass, cfgClass, envConfigClass)
                    .invoke(null, disposable, config, jvmConfigFiles);

            ProjectCompiler.postProgress(callback, "Compiling Kotlin sources...");
            Class<?> compilerClass = Class.forName("org.jetbrains.kotlin.cli.jvm.compiler.KotlinToJVMBytecodeCompiler", true, kotlinCl);
            Object compilerInstance = compilerClass.getField("INSTANCE").get(null);
            Method compileMethod = compilerClass.getMethod("compileBunchOfSources", kotlinCoreEnvClass);
            boolean success = (Boolean) compileMethod.invoke(compilerInstance, environment);

            List<File> classFiles = findAllClassFiles(outDir);
            if (!success && classFiles.isEmpty()) {
                String errDetail = compilerMessages.toString();
                if (errDetail.isEmpty()) errDetail = "Kotlin compilation failed with no output files.";
                ProjectCompiler.postResult(callback, "Kotlin Compilation Error:\n" + errDetail);
                return false;
            }
            if (hadError[0] && classFiles.isEmpty()) {
                ProjectCompiler.postResult(callback, "Kotlin Compilation Error:\n" + compilerMessages);
                return false;
            }
            return true;
        } catch (ClassNotFoundException e) {
            Log.e(TAG, "Kotlin compiler class not found", e);
            ProjectCompiler.postResult(callback, "Kotlin Error: compiler library not integrated.\n" + e.getMessage());
            return false;
        } catch (NoSuchMethodException e) {
            Log.e(TAG, "Method not found in compiler library", e);
            ProjectCompiler.postResult(callback, "Kotlin Error: incompatible compiler API.\n" + e.getMessage());
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Kotlin compilation failed", e);
            ProjectCompiler.postResult(callback, "Kotlin System Error:\n" + e.getClass().getSimpleName() +
                    ": " + e.getMessage() + "\n" + Log.getStackTraceString(e));
            return false;
        }
    }

    public static File ensureKotlinStdlib(Context context, File cacheDir) {
        if (cacheDir != null && !cacheDir.exists()) cacheDir.mkdirs();
        File stdlibJar = new File(cacheDir, "kotlin-stdlib-2.0.21.jar");
        if (stdlibJar.exists() && stdlibJar.length() > 0) return sealForClassLoading(stdlibJar);

        // 1. Try to extract from APK assets (100% offline)
        if (context != null) {
            for (String assetName : new String[]{"kotlin-stdlib.jar", "kotlin-stdlib-2.0.21.jar"}) {
                try (java.io.InputStream is = context.getAssets().open(assetName);
                     java.io.FileOutputStream fos = new java.io.FileOutputStream(stdlibJar)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                    if (stdlibJar.length() > 0) {
                        Log.d(TAG, "kotlin-stdlib extracted from assets (" + assetName + "): " + stdlibJar.length() + " bytes");
                        return sealForClassLoading(stdlibJar);
                    }
                } catch (Exception ignored) {}
            }
        }

        // 2. Fallback to download if assets are not present
        String url = "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.0.21/kotlin-stdlib-2.0.21.jar";
        try {
            Log.d(TAG, "Downloading kotlin-stdlib from " + url);
            if (ProjectCompiler.downloadFile(url, stdlibJar, 30000, 60000)) {
                Log.d(TAG, "kotlin-stdlib downloaded: " + stdlibJar.length() + " bytes");
                return sealForClassLoading(stdlibJar);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to download kotlin-stdlib", e);
        }
        return null;
    }

    public static File ensureKotlinStdlib(File cacheDir) {
        return ensureKotlinStdlib(null, cacheDir);
    }

    /**
     * Drops the write bit so a {@code PathClassLoader} will accept the jar.
     *
     * <p>ART refuses to open a dex container that is still writable —
     * {@code SecurityException: Writable dex file '…' is not allowed} — because a
     * file that can change after verification is a code-injection vector. A
     * freshly downloaded jar is writable by definition, so this has to happen
     * between the download and the first class load, and again on the cached
     * path: the bit is on the file, not on the download.</p>
     *
     * @return the same file, so callers can wrap the expression
     */
    private static File sealForClassLoading(File jar) {
        try {
            // Clear the owner bit too — setWritable(false) alone leaves it group
            // writable on some ROMs, which ART counts as writable.
            if (!jar.setWritable(false, false) && jar.canWrite()) {
                Log.w(TAG, "Could not clear the write bit on " + jar.getName()
                        + "; the class loader will refuse it.");
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to seal " + jar.getName(), e);
        }
        return jar;
    }

    private static File ensureKotlinPluginRoot(File cacheDir) {
        File pluginRoot = new File(cacheDir, "kotlin_plugin_root");
        File extensions = new File(pluginRoot, "META-INF/extensions");
        File compilerXml = new File(extensions, "compiler.xml");

        if (compilerXml.exists() && compilerXml.length() > 100) return pluginRoot;

        extensions.mkdirs();

        try {
            ClassLoader cl = KotlinCompiler.class.getClassLoader();
            java.io.InputStream is = cl.getResourceAsStream("META-INF/extensions/compiler.xml");
            if (is != null) {
                java.io.FileOutputStream fos = new java.io.FileOutputStream(compilerXml);
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                fos.close();
                is.close();
                return pluginRoot;
            }
        } catch (Exception ignored) {}

        String stub = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<idea-plugin>\n" +
                "  <extensionPoints/>\n" +
                "</idea-plugin>\n";
        try {
            java.io.FileWriter fw = new java.io.FileWriter(compilerXml);
            fw.write(stub);
            fw.close();
        } catch (IOException ignored) {}
        return pluginRoot;
    }

    private static void collectSources(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                String name = f.getName();
                if (!name.equals("build") && !name.equals("target") && !name.equals(".idea")
                        && !name.equals(".git") && !name.equals(".wiredroid")) {
                    collectSources(f, out);
                }
            } else if (f.getName().endsWith(".java") || f.getName().endsWith(".kt")) {
                out.add(f);
            }
        }
    }

    private static List<File> findAllClassFiles(File dir) {
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

    /**
     * Tells the compiler which language version to compile as.
     *
     * <p>One compiler is bundled, so this is not a choice of compiler — it is
     * the same thing {@code -language-version} does, and it is what a project
     * pinned to an older Kotlin needs in order to compile at all.</p>
     *
     * <p>Applied reflectively like the rest of this file, and reported rather
     * than swallowed: a setting that silently does nothing is worse than one
     * that says it could not be applied.</p>
     */
    private static void applyLanguageVersion(Context context, ClassLoader kotlinCl,
                                             Class<?> commonKeysClass, Object config,
                                             Method putMethod) {
        String chosen = new com.ccs.wiredroid.util.AppPreferences(context)
                .getKotlinLanguageVersion();
        String constant = KotlinVersions.enumName(chosen);
        if (constant == null) return;   // the compiler's own default

        try {
            Class<?> languageVersionClass =
                    Class.forName("org.jetbrains.kotlin.config.LanguageVersion", true, kotlinCl);
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object languageVersion = Enum.valueOf((Class<Enum>) languageVersionClass, constant);

            Class<?> apiVersionClass =
                    Class.forName("org.jetbrains.kotlin.config.ApiVersion", true, kotlinCl);
            // The API version follows the language version: asking for language
            // 1.9 while leaving the API at 2.0 would still admit 2.0 library
            // declarations, which is not what "compile as 1.9" means.
            Object apiVersion = apiVersionClass
                    .getMethod("createByLanguageVersion", languageVersionClass)
                    .invoke(null, languageVersion);

            Class<?> settingsClass = Class.forName(
                    "org.jetbrains.kotlin.config.LanguageVersionSettingsImpl", true, kotlinCl);
            Object settings = null;
            for (java.lang.reflect.Constructor<?> ctor : settingsClass.getConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length < 2
                        || !params[0].equals(languageVersionClass)
                        || !params[1].equals(apiVersionClass)) {
                    continue;
                }
                // Later parameters are analysis flags and per-feature overrides;
                // Kotlin gives them defaults, which Java reflection cannot use,
                // so empty maps stand in for "nothing special".
                Object[] args = new Object[params.length];
                args[0] = languageVersion;
                args[1] = apiVersion;
                for (int i = 2; i < params.length; i++) {
                    args[i] = java.util.Map.class.isAssignableFrom(params[i])
                            ? java.util.Collections.emptyMap() : null;
                }
                settings = ctor.newInstance(args);
                break;
            }
            if (settings == null) {
                Log.w(TAG, "Kotlin language version " + chosen
                        + " not applied: no usable LanguageVersionSettingsImpl constructor");
                return;
            }

            Field field = commonKeysClass.getField("LANGUAGE_VERSION_SETTINGS");
            putMethod.invoke(config, field.get(null), settings);
            Log.d(TAG, "Kotlin language version set to " + chosen);
        } catch (Throwable t) {
            // Compilation still works at the compiler's default; refusing to
            // compile because a version could not be selected would be worse.
            Log.w(TAG, "Could not set Kotlin language version " + chosen, t);
        }
    }
}
