# ── Obfuscation only, deliberately ───────────────────────────────────────────
#
# R8 does three separate jobs: shrinking, optimization and obfuscation. Google
# Play's metric is about the third. The first two were measured on this app at
# close to an hour of build time, because R8 has to analyse the whole bundled
# toolchain — ECJ, the Kotlin compiler, R8 itself — and they buy little here:
# nearly all of that code is kept anyway, so there is nothing to remove.
#
# They also carry the risk that matters in an app that runs compilers: code
# reached only by reflection or ServiceLoader looks unused, and an optimizer
# that inlines or merges classes breaks the assumptions such code makes.
# Neither shrinking nor optimization, only obfuscation — which is the one of
# R8's three jobs the Play metric is about. Shrinking is under suspicion for
# breaking the bundled Kotlin compiler: its IntelliJ core registers extension
# points by reflection and reads listeners from resources R8 cannot see, so a
# class that looks unused is not.
-dontshrink
-dontoptimize

# ── What R8 must not rename ──────────────────────────────────────────────────
#
# minifyEnabled is on for release, so everything below is load-bearing rather
# than precautionary. Google Play measures how much of the app's own code is
# obfuscated; the bundled third-party toolchain is kept wholesale because it is
# what would break, and it is not what the measurement is about.

# Stack traces stay readable: line numbers are kept and the source file name is
# replaced by a placeholder rather than removed, so a crash report still maps
# back through the mapping file.
# Attributes, not just classes and members. R8 strips every attribute that is
# not listed here, and the bundled Kotlin compiler carries IntelliJ's component
# machinery, which registers extension points by reading annotations at run
# time. With the annotations gone, registration ran against a null component
# manager and the compiler environment failed to initialise at all:
#
#   AssertionError: Attempt to invoke ComponentManager.getService on a null
#   object reference — at ExtensionPointImpl.registerExtension
#
# Signature and InnerClasses matter for the same reason: reflection over
# generic types and nested classes is how these libraries find things.
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions,
                *Annotation*,RuntimeVisible*,RuntimeInvisible*,
                AnnotationDefault,MethodParameters,
                SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── JNI ──────────────────────────────────────────────────────────────────────
#
# The C symbols in java_se_launcher.c and native_compiler_jni.c spell out the
# package, class and method name of the Java side. Renaming either class breaks
# the symbol lookup at load time, with no compile-time warning.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.ccs.wiredroid.NativeCompiler { *; }
-keep class com.ccs.wiredroid.javase.JavaSeNativeLauncher { *; }

# ── Names the app looks up as strings ────────────────────────────────────────
#
# BuildConfig is read by name in a few places and costs nothing to keep.
-keep class com.ccs.wiredroid.BuildConfig { *; }

# ── Bundled compilers and tools ──────────────────────────────────────────────
#
# These are compilers running inside the app. They load classes by name, use
# ServiceLoader, and reflect over their own internals; renaming any of it fails
# at runtime, in the middle of a user's build.

# Eclipse JDT (ECJ) — the Java compiler
-keep class org.eclipse.jdt.** { *; }
-dontwarn org.eclipse.jdt.**

# Kotlin compiler (embeddable) and its runtime
-keep class org.jetbrains.kotlin.** { *; }
-keep class org.jetbrains.kotlinx.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-keep class org.jetbrains.annotations.** { *; }
-keep class gnu.trove.** { *; }
-keep class com.intellij.** { *; }
-dontwarn org.jetbrains.kotlin.**
-dontwarn kotlin.**
-dontwarn kotlinx.**
-dontwarn com.intellij.**

# R8 / D8 — the dexer the app runs on user code
-keep class com.android.tools.** { *; }
-dontwarn com.android.tools.**

# ASM — bytecode viewer and class decompiler
-keep class org.objectweb.asm.** { *; }
-dontwarn org.objectweb.asm.**

# JGit — pure-Java git, heavy on ServiceLoader
-keep class org.eclipse.jgit.** { *; }
-dontwarn org.eclipse.jgit.**
-dontwarn org.slf4j.**
-dontwarn javax.servlet.**
-dontwarn org.apache.http.**

# Archive readers
-keep class org.apache.commons.compress.** { *; }
-keep class org.tukaani.xz.** { *; }
-keep class com.github.junrar.** { *; }
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn com.github.junrar.**

# XML pull parser — the pom reader binds to the implementation by name
-keep class org.xmlpull.** { *; }
-keep class org.kxml2.** { *; }
-dontwarn org.xmlpull.**

# The editor: its language and colour-scheme classes are named in
# configuration and looked up reflectively by the widget.
-keep class io.github.rosemoe.sora.** { *; }
-dontwarn io.github.rosemoe.sora.**

# ── Service registrations ────────────────────────────────────────────────────
#
# ServiceLoader finds implementations by the class name written in
# META-INF/services; a renamed implementation is no longer findable.
-keep,allowobfuscation @interface com.google.auto.service.AutoService
-keepnames class * implements java.nio.file.spi.FileSystemProvider

# ── Classes that do not exist on Android ─────────────────────────────────────
#
# R8 fails a build that references a class it cannot find, and these are all
# reached from code paths the app never takes: JGit carries transports and
# authentication backends for desktop and server environments, and the
# annotation packages are compile-time only. Every one of them is a Java SE or
# Windows API that is simply not part of Android.

# JGit: S3 transport, Windows credentials, LDAP and JMX
-dontwarn software.amazon.awssdk.**
-dontwarn com.sun.jna.**
-dontwarn waffle.**
-dontwarn javax.naming.**
-dontwarn javax.management.**
-dontwarn javax.security.auth.**
-dontwarn javax.sql.**
-dontwarn java.sql.**
-dontwarn java.lang.management.**
-dontwarn org.ietf.jgss.**

# Desktop-only APIs referenced by the bundled compilers
-dontwarn java.awt.**
-dontwarn javax.xml.transform.stax.**

# Compile-time annotations with no runtime presence
-dontwarn org.checkerframework.**
-dontwarn org.jetbrains.annotations.**

# ── More Java SE APIs absent from Android ────────────────────────────────────
#
# These appear once shrinking is off: with nothing discarded, R8 has to resolve
# references inside code the app never reaches — annotation processors shipped
# inside libraries, OSGi and JTA hooks, and the javax.tools compiler interface
# that ECJ implements for desktop use.
-dontwarn javax.lang.model.**
-dontwarn javax.tools.**
-dontwarn javax.annotation.processing.**
-dontwarn javax.transaction.**
-dontwarn org.osgi.**
-dontwarn java.lang.invoke.MethodHandleProxies
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn javaslang.**
-dontwarn com.google.errorprone.**
-dontwarn org.apache.commons.lang3.**
