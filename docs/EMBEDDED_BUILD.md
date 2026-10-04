# WireDroid embedded build

The build keeps the full Sora IDE, project creation, settings, file management and the board drawer. Embedded projects use the C/C++ runner and imported MCU firmware. The ECJ and D8 adapters from the shared editor compile against host tools; their JVM user-program compiler payloads are excluded from the APK. Kotlin standard library and coroutines remain runtime dependencies of the editor.

## Pinned toolchain

| Tool | Version |
| --- | --- |
| JDK | 17 |
| Gradle wrapper | 9.4.1 |
| Android Gradle plugin | 9.2.1 |
| Android compile/target SDK | 36 |
| Android Build Tools | 36.0.0 |
| Android NDK | 28.2.13676358 |
| CMake | 3.22.1 |

The wrapper verifies Gradle's official SHA-256 `2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb`. The build verifies the bundled ARM64 Clang and LLD hashes before compiling. The Android plugin's supported Gradle/JDK/SDK combination is documented in the [AGP 9.2 release notes](https://developer.android.com/build/releases/agp-9-2-0-release-notes).

The public AVR example stores its HEX and manifest under `.wiredroid/firmware`. Asset ignore patterns explicitly retain this directory; the pre-build check rejects unexpected hidden assets. Desktop metadata and VCS files remain excluded. Inspect the generated APK when changing these patterns so the real firmware fixture remains available to Android project creation.

The shared editor currently uses the Android legacy DSL and final resource IDs. Compatibility options in `gradle.properties` are limited to AGP 9.x; migrating these before AGP 10 is required. There is no project-level R8 override.

## Compile, test and package

Set `JAVA_HOME` to an installed JDK 17 and `ANDROID_HOME` to the Android SDK containing the pinned packages. Run from the project root:

```sh
./gradlew :app:compileDebugJavaWithJavac :app:compileDebugAndroidTestJavaWithJavac
./gradlew :app:testDebugUnitTest --tests 'com.ccs.wiredroid.wire.*'
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

The first build needs network access to resolve the pinned dependencies. Subsequent builds can use `--offline` while those artifacts remain in the Gradle cache. The generated files are `app/build/outputs/apk/debug/app-debug.apk` and `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`. Unit reports are under `app/build/reports/tests/testDebugUnitTest`.

For the full shared editor's existing unit suite, omit the `--tests` filter. Java language tooling adapters are included on the unit-test classpath without being shipped to users.

## Android verification

Install both APKs on an authorized test device, then run these suites separately:

```sh
adb shell am instrument -w -e suite native-session com.ccs.wiredroid.test/com.ccs.wiredroid.wire.WireExamplesInstrumentation
adb shell am instrument -w -e suite reliability com.ccs.wiredroid.test/com.ccs.wiredroid.wire.WireExamplesInstrumentation
adb shell am instrument -w -e suite ide-reliability com.ccs.wiredroid.test/com.ccs.wiredroid.wire.WireExamplesInstrumentation
adb shell am instrument -w -e suite creation com.ccs.wiredroid.test/com.ccs.wiredroid.wire.WireExamplesInstrumentation
adb shell am instrument -w -e suite examples -e isolated true com.ccs.wiredroid.test/com.ccs.wiredroid.wire.WireExamplesInstrumentation
adb shell am instrument -w -e suite motion com.ccs.wiredroid.test/com.ccs.wiredroid.wire.WireExamplesInstrumentation
```

`reliability` exercises Android storage recovery, schema migration, checkpoint separation and compiler diagnostic containment. `ide-reliability` launches the full editor and verifies a restored dirty buffer, checkpointing with AutoSave disabled, explicit Save, source navigation and drawer lifecycle. These device checks also detect startup/class-loading problems that Java compilation cannot establish.

The examples suite executes native C/C++ board programs; the AVR example executes its imported real firmware. Android does not contain an AVR firmware compiler. Build MCU firmware using the host compiler driver and Arduino core, or import a matching HEX file. Changes to source files, board profile, core version, build settings or library lock invalidate the previous firmware manifest and require rebuilding/importing it.

USB enumeration, permission, serial transport and upload require additional checks with actual USB devices. Passing filesystem or simulator suites does not prove hardware upload compatibility.
