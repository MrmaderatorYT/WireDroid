WireDroid bundled toolchains
============================

Java SE 21 runtime
------------------
Source release:
https://github.com/PojavLauncherTeam/PojavLauncher/releases/tag/gladiolus

Source APK:
https://github.com/PojavLauncherTeam/PojavLauncher/releases/download/gladiolus/PojavLauncher.apk
SHA-256: cc8479e1600e3a094d2184bbb88b19809ce41a0f8f7882aefd4527c9d032fc56

The files in java-se/ were copied without modification from
assets/components/jre-21/ and the architecture binpacks in that APK.

universal.tar.xz
SHA-256: fddfc0b8a8e8b56be8efbf3f413ac787a0d7b96d711cb5cb59573076d299a0c7

bin-arm.tar.xz
SHA-256: c18520eb81d78532f6af2158d3a574d0bb5923f9a46c3d51b2f1ff1c96bd1e5c

bin-arm64.tar.xz
SHA-256: fe948068b5fec393b2b081d2c550f15777ba2829f36ae9931e2b5fdda2ec087f

bin-x86.tar.xz
SHA-256: ddb77f5fb27ab62b8a6d316a74aa2752df7242992f86878d0a86c2d90ca4f288

bin-x86_64.tar.xz
SHA-256: 77de3c0e8a4ab578c2ba177a52be83a3a2b1aa227cb2d88d90c32b94dd0c8865

Runtime source repository:
https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch

OpenJDK is distributed under GPL-2.0 with the Classpath Exception. License
and legal files from the upstream runtime remain inside the payload.


Android NDK r29 for an ARM64 host — downloaded on demand
--------------------------------------------------------
Source release:
https://github.com/lzhiyong/termux-ndk/releases/tag/android-ndk

Source archive:
https://github.com/lzhiyong/termux-ndk/releases/download/android-ndk/android-ndk-r29-aarch64.tar.xz
SHA-256: 02e10e4ddfe8deaeb0bd0cf29d04c981ed5bc8a5d6b560ebb9e7661f472d684b
Size:    360538712 bytes (344 MiB)

This archive is NOT bundled. It used to ship here, split byte-for-byte into
part-00 through part-05 to stay under GitHub's 100 MB per-file limit, but at
344 MiB of already-compressed payload it took most of an App Bundle base
module's 500 MB compressed ceiling on its own — and only projects with C++
sources ever need it.

NdkManager downloads it from the URL above when the user taps "Download C++
NDK" in Settings, verifies the SHA-256 and the byte count before extracting
anything, and deletes the archive once the toolchain is installed. The
download resumes: the host answers Range requests, and a partial transfer is
kept under files/ndk/ between attempts. Removing the NDK in Settings deletes
that directory, so an abandoned download goes with it.

The URL, hash and length are pinned in NdkManager.java. verifyBundledToolchains
fails the build if they stop matching the values recorded here, because a URL
bumped without its hash would build cleanly and then fail for every user.

Android does not allow a target-SDK 36 app to execute binaries from its
writable files directory. The following two unmodified files are therefore
also packaged as native libraries, which Android extracts to the app's
read-only executable nativeLibraryDir:

toolchains/llvm/prebuilt/linux-x86_64/bin/clang-21
Packaged name: libwiredroid_clang.so
SHA-256: bfe63185a80144d8d236d31462725807cba97fdc7e1d481fa511804a4f19f95a

toolchains/llvm/prebuilt/linux-x86_64/bin/lld
Packaged name: libwiredroid_lld.so
SHA-256: 5a7432c0537a9740e4bbab89c879f53b85cb2c1f8e412f86d9bb14878843add9

At runtime WireDroid creates the names clang++ and ld.lld as symlinks to
these read-only files. The resolved executable remains trusted by Android,
while each LLVM multi-call driver receives the basename it expects.

The NDK archive contains its upstream NOTICE, NOTICE.toolchain, README.md,
component license markers, and toolchain NOTICE files. Those files are
preserved when WireDroid installs the bundled NDK.
