# WireDroid 1.7 reliability verification

Verified on 2026-10-03 for `1.7.0-embedded`, versionCode `38`, using JDK 17, Gradle 9.4.1, AGP 9.2.1 and Android SDK 36. The final app SHA-256 is `2fddc6e8d0846594e74d39ad673deb698a96bff2d3608cd15941671b7dd2f3e7`; its test APK SHA-256 is `5a4ff951cb13c5b5c807aea35f58bef10507cd479f166bd37d507042dd8b4050`. Both built successfully in [wiredroid-1.7-compiler-diagnostics-build.txt](wiredroid-1.7-compiler-diagnostics-build.txt).

The final APK passed all 17 bundled examples, all 3 concurrent native session checks, all 8 full-IDE checks, tablet motion/Servo checks and the creation wizard. The 6 storage checks were obtained with the earlier 1.7 app SHA `5c00d1a540ec83839bbce3f31c96af8196510b67178ae27ffba3f64b7ef88251`; their storage implementation remained unchanged during the native-session correction.

## Results

| Verification | Result | Evidence |
| --- | --- | --- |
| Full shared-editor JVM unit suite | 405 tests passed; 0 failures, errors or skips | [wiredroid-1.7-compiler-diagnostics-build.txt](wiredroid-1.7-compiler-diagnostics-build.txt); [wiredroid-1.7-jvm-results.json](wiredroid-1.7-jvm-results.json) |
| Embedded JVM subset | 62 WireDroid tests passed; canonical identity has 2 additional utility tests | [wiredroid-1.7-jvm-results.json](wiredroid-1.7-jvm-results.json) |
| App and instrumentation build | Both APKs successfully compiled, dexed and packaged | [wiredroid-1.7-compiler-diagnostics-build.txt](wiredroid-1.7-compiler-diagnostics-build.txt) |
| Native session isolation/cancellation/diagnostics JVM tests | 7 passed, including 20 fresh concurrent directory creation rounds, real canceled children and retained compiler-crash inputs/arguments/log | [wiredroid-1.7-jvm-results.json](wiredroid-1.7-jvm-results.json), suite `NativeBuildDirectoryTest` |
| Full Android example batch (final `2fddc6…`) | 17 unique bundled examples and overwrite rejection passed; no failure | [wiredroid-1.7-native-examples-validated.txt](wiredroid-1.7-native-examples-validated.txt) |
| Android native sessions (final `2fddc6…`) | All 3 actual concurrency/cancellation/restart checks passed | [wiredroid-1.7-native-session-final-device.txt](wiredroid-1.7-native-session-final-device.txt) |
| Real Android project wizard (final `2fddc6…`) | Creation and toolbar checks passed; selected user project restored afterward | [wiredroid-1.7-native-creation-validated.txt](wiredroid-1.7-native-creation-validated.txt) |
| Real Android storage | 6 checks passed on Samsung SM-T720 | [wiredroid-1.7-native-reliability.txt](wiredroid-1.7-native-reliability.txt) |
| Android gestures and Servo programs (final `2fddc6…`) | Tablet gestures and both native Servo program checks passed | [wiredroid-1.7-native-motion-validated.txt](wiredroid-1.7-native-motion-validated.txt) |
| Full IDE on Android (final `2fddc6…`) | All 8 checks passed on Samsung SM-T720 | [wiredroid-1.7-native-ide-validated.txt](wiredroid-1.7-native-ide-validated.txt) |
| Packaged AVR firmware | `uno.hex` and `uno.json` present, byte-identical to bundled sources | APK ZIP inspection |
| Hidden asset contents | Only the public AVR firmware HEX/manifest entries remain | APK ZIP inspection; [wiredroid-asset-packaging-check.txt](wiredroid-asset-packaging-check.txt) |

The Android checks launch the full editor successfully with the JVM user-program compiler payloads excluded from the embedded APK. They verify restored dirty buffers, original saved source preservation, source-location clamping, checkpointing with AutoSave disabled, explicit Save, drawer lifecycle, failed AutoSave, locked source protection, guarded drawer/JavaScript Run and successful retry after unlocking.

The storage checks cover schema revision 2 migration with legacy scale preservation, corrupt-scene backup restoration, recovery of a complete checked interrupted commit, separate editor checkpoints, project-contained compiler diagnostics, editor encoding/BOM preservation and deletion of recovery data after explicit source removal.

## Confirmed native regressions and fixes

Android exposes aliases such as `/data/user/0` and `/data/data` for the same project. Raw path comparison allowed the same physical source to open in two tabs, and a lock applied to one path did not protect the other. During Run, the dirty alias could write the locked source before another tab caused Save to fail.

`FileIdentity` now provides canonical comparison for tab lookup and read-only preferences. Unlocking through another alias removes the same physical lock. `ProjectManager.writeFile` rejects locked/read-only files before entering the atomic writer, so protection applies to direct project write calls as well as the editor's Save button.

AutoSave previously cleared the modified flag after the implicit save method returned, including after a write error or a checkpoint-only save. The save method now reports success, and AutoSave clears the flag only after the primary source commits. A failed save preserves the latest checkpoint and keeps the buffer dirty; Run checks all modified embedded tabs and refuses to start old saved code.

The Android regression test asserts one canonical source tab, locks an explicit path alias, attempts a direct project write, tests drawer Run and JavaScript Run, executes the actual queued AutoSave job, checks source/checkpoint separation and retries after unlocking. All checks pass with the corrected app.

Original failures are preserved in [wiredroid-1.7-native-ide.txt](wiredroid-1.7-native-ide.txt) and [wiredroid-1.7-native-ide-diagnostic.txt](wiredroid-1.7-native-ide-diagnostic.txt). The public evidence above records the focused save/identity investigation; this was not a complete audit of the IDE. Internal audit-cache files are excluded from the release archive.

## Native session collision and the intermittent I²C failure

One full device example batch passed its first eleven examples and then the I²C EEPROM native process exited with code 139. Android recorded a null pointer SIGSEGV in the generated `simulation.elf`. The exact failing ELF was not saved before recompilation; the available replacement binary does not match the crash offsets. Its symbolication therefore cannot establish the original cause. Original batch and crash evidence are preserved in [wiredroid-1.7-native-examples-final.txt](wiredroid-1.7-native-examples-final.txt) and [wiredroid-1.7-native-crash.txt](wiredroid-1.7-native-crash.txt) (a focused extract is [wiredroid-1.7-native-crash-focused.txt](wiredroid-1.7-native-crash-focused.txt)). The isolated I²C recheck passed, and the recompiled binary passed 24 direct launches recorded in [wiredroid-1.7-i2c-startup-stress.json](wiredroid-1.7-i2c-startup-stress.json). Constructor order was not changed based on the unmatched offsets.

A separate session collision was confirmed by re-reading the compiler and cancellation lifecycle. Every `EmbeddedRunner` had its own compiler executor, yet all workspaces with board id `uno` wrote `cache/embedded/uno/pins.h`, `unit-*.o` and `simulation.elf`. No global lock protected the object/link/launch lifetime. Stopping a session destroyed its child asynchronously, and closing a runner interrupted its executor without waiting for an in-flight copy/compiler to finish. Thus two normal project workspaces, or a canceled restart, could interleave the same build paths.

A deterministic two-owner barrier reproduces the old wrong-object schedule: A writes `unit-0.o`, B replaces it, then A reads B's bytes for link. Atomic linker replacement also permits A to finish its link, B to replace the shared executable path, and A to launch B's program. This evidence proves build contamination; it does not prove that the original I²C SIGSEGV was caused by it or that the linker truncated a live mapped executable.

Each native session now atomically allocates a fresh private directory under `cache/embedded`. The compiler and native child share only that session's immutable path. Parent creation uses `Files.createDirectories`, so simultaneous first sessions do not reject an already-created parent. Cleanup runs on the compiler/reader worker, waits for the canceled child to exit, and removes only that session's directory. If the child remains alive after the bounded wait, its directory is retained rather than removed underneath it. A restart gets another private path.

The five JVM tests verify concurrent A/B object and executable ownership across twenty fresh parents, cancellation of a real OS child before cleanup with a surviving restart artifact, retained failure bytes and independently checked SHA-256, bounded oversized diagnostics, and cleanup that does not follow an external symlink. An earlier full-suite run exposed the concurrent parent-creation race; that failed log and XML are preserved as [wiredroid-1.7-native-session-build-failed.txt](wiredroid-1.7-native-session-build-failed.txt) and [wiredroid-1.7-native-session-test-failed.xml](wiredroid-1.7-native-session-test-failed.xml). The corrected full run passed all 403 tests. Independent review then identified the cancel-before-registration window: a just-started child could be killed without entering the owner’s process field. Registration now records that child before checking cancellation, allowing cleanup to wait for the actual rejected child. The additional deterministic test uses a real OS child started after cancellation and verifies ownership, exit and cleanup. All 404 tests passed after this final cancellation correction; the compiler-diagnostic addition brought the final full suite to 405 passing tests.

On an unexpected runtime error, the runner copies the actual failed session ELF before cleanup into `cache/embedded-last-failure/simulation.elf`, with `failure.json` containing SHA-256, byte count, original/project/build paths, exit code, reason and timestamp. The single latest snapshot is protected within the app process, its ELF is limited to 16 MiB and `compiler.log` to 1 MiB; oversized ELF omission and log truncation are explicit metadata. Intentional Stop does not create an error snapshot. Capturing can fail if cache storage is unavailable, and a later failure replaces the prior snapshot. This is private diagnostic storage, not automatically exported source.

The `native-session` Android instrumentation suite exercises two actual simultaneous Clang builds for different projects that share id `uno`, checks distinct ELF paths and Serial A/B output, closes A while B continues, then stops and restarts B at a new path. All three checks passed on the final diagnostics APK; [the device log](wiredroid-1.7-native-session-final-device.txt) records the successful concurrent build, cancellation and restart results.

## Compiler crash diagnostics and remaining uncertainty

A later candidate device batch passed its first three examples, then the Nano build printed Clang’s crash-report banner. The failing unit, exact arguments and full compiler backtrace were not saved before cleanup, so the root cause is unknown. The subsequent Nano-only recheck passed on the cancellation-corrected app. No automatic retry, constructor reorder or speculative compiler flag change was used. The original batch is preserved as [wiredroid-1.7-native-examples-release.txt](wiredroid-1.7-native-examples-release.txt); the crash summary and isolated repeat are [wiredroid-1.7-native-compiler-crash.txt](wiredroid-1.7-native-compiler-crash.txt) and [wiredroid-1.7-native-nano-recheck.txt](wiredroid-1.7-native-nano-recheck.txt).

The final diagnostic addition preserves a nonzero compiler/linker exit or timeout before session cleanup in the separate `cache/embedded-last-compiler-failure` slot. `failure.json` records the argument array, exit code, reason, timestamp and project/build paths. `compiler.log` is limited to 1 MiB. The failed compile input and generated pins header are retained as `source.txt` and `pins.txt`, each limited to 2 MiB and described by original/saved paths, length and SHA-256. Arguments are capped at 128 KiB of characters with an explicit truncation flag; missing/oversized input omission and log truncation are explicit. This bounded cache preserves the latest compiler failure and never retries the failed command. The corresponding JVM test verifies exact argument, input and log survival after the original build directory is removed.

The complete seventeen-example run on final SHA `2fddc6…` subsequently passed without a compiler or runtime error. That observed pass does not establish the original crash causes or a universal stability guarantee.

The final two production differences from the first session-isolated candidate are rejected-child ownership and compiler-failure capture. C++ runtime/frontend/compiler flags remain unchanged. Device results are tied to the tested APK hash; results from an earlier candidate are not represented as exact-final-APK checks.

## Recovery contract and limits

`RecoveryStore` writes UTF text through a synced temporary file, a length/SHA-256 commit journal, atomic replacement and a previous validated backup. It can finish a verified interrupted commit or restore a valid backup; incompatible future scene revisions are preserved instead of rolled back or overwritten. Legacy Android `.bak` files are supported. Draft epochs prevent old queued checkpoints from reappearing after explicit Save, deletion or discard.

Editor checkpoints remain separate from the compiler's saved source. Restoring a checkpoint makes the buffer dirty and leaves the saved file intact until Save or an authorized Run commits it. Checkpointing is debounced; edits between checkpoints can still be lost if Android kills the process immediately. An asynchronous WebView close flush is best effort. The checks establish recovery of the tested interrupted states and do not prove storage behavior for every external provider or power-loss timing.

Compiler diagnostic navigation parses bounded GCC/Clang locations, permits existing files within the project and clamps line/column in the editor. Migration retains unknown scene fields, component sizes, wire routes and references while adding the explicit schema revision and stable legacy wire IDs.

## Packaging and fixture cleanup

Android's default asset filter omitted the `.wiredroid` directory, so the AVR example could not copy its real firmware. The pinned asset ignore pattern now retains this public directory, excludes desktop/VCS metadata and invokes a pre-build guard against unexpected hidden assets. APK inspection confirms exactly the two expected hidden firmware entries.

The creation instrumentation restores the previously selected user project and removes both raw and canonical temporary project entries from recents in `finally`. The IDE instrumentation also restores preference state and cleans up its project. Production frontend assets were not changed during this native correction.

The final creation wizard, full example execution and tablet motion evidence are linked above; MCU details have their own report. These checks do not certify all boards, libraries, components or physical upload protocols. Physical USB hardware remains separately verified only when a real target and readback evidence are available.
