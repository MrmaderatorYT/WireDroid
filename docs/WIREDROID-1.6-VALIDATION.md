# WireDroid 1.6.0 validation

Build: version code 37, `1.6.0-electronics`. Validation performed September 30–October 1, 2026, on macOS and Samsung Galaxy Tab S5e (SM-T720, Android 11, ARM64).

## Completed checks

| Check | Result | Evidence |
| --- | --- | --- |
| Android app and instrumentation APK assembly | PASS | `wiredroid-1.6-final-build.txt` |
| Wire project, generated pins and multi-file build planning | 13 JVM tests passed, zero failures/errors | `wiredroid-1.6-gradle-test.txt` and Gradle XML results |
| All generated GPIO board profiles | 327 ARM64 Android programs compiled and linked against Arduino API and libc++ | `wiredroid-1.6-board-compile-test.txt` |
| Electrical equations | PASS: divider voltage/current, capacitor and inductor transients, diode, NPN/MOS switches, DC motor direction/inertia and short detection | `wiredroid-1.6-electronics-test.txt` |
| Device transactions and signal export | PASS: I²C EEPROM ACK/NACK/wiring/read/write, bridge direction/PWM, LCD initialization/text, microsecond PWM reconstruction and VCD | `wiredroid-1.6-devices-test.txt` |
| Bundled project integrity | PASS: twelve projects, source files, scene references and original example behavior | `wiredroid-1.6-examples-test.txt` |
| Browser workspace integration | PASS: resizing, contact/wire alignment, persistence, undo/redo, library, native bridge mock, diagnostics, themes and desktop/phone layout | `wiredroid-1.6-browser-test.txt` |
| Browser circuit integration | PASS: interactive inputs, native read/PWM/Serial bridge mock, circuit checking and wire editing | `wiredroid-1.6-circuits-browser-test.txt` |
| Browser motor interaction | PASS: manual servo horn/slider, potentiometer, running C++ ownership, continuous animation and fixed contacts | `wiredroid-1.6-motion-browser-test.txt` |

Browser tests use a mocked Android bridge. Equation/device tests check the JavaScript models directly. The 327-board check establishes host buildability of generated GPIO profiles, not vendor CPU or firmware compatibility.

## Android integration

The original full IDE test compiled and ran Uno blink, button, traffic lights, Nano alternating LEDs and ESP32-profile blink. A separate application's foreground tests then paused WireDroid as designed. The retained earlier report (`wiredroid-1.6-native-examples-test.txt`) records that interrupted run; it is not the final release verdict.

The isolated suite instantiates the production WireWorkspace in an Android Presentation on a private virtual display. It copies each bundled project, verifies resize persistence, runs the actual on-device C/C++ compiler and native process, and checks electrical or visual behavior through the real WebView bridge. It does not exercise the native editor layout or editor buffer saving.

An initial isolated run passed ten projects and exposed a global-constructor initialization bug in the LCD project. Official LiquidCrystal calls `delayMicroseconds()` during its global constructor. Runtime maps had not yet been constructed because the sketch object linked first. Shared runtime maps/timebase now initialize on first use; Serial and bus adapters initialize before default-priority sketch constructors. The earlier failing report is retained as `wiredroid-1.6-native-constructor-regression.txt` for diagnosis. The LCD regression now passes on the tablet (`wiredroid-1.6-native-lcd-test.txt`), including the linked C helper, `std::vector`, custom source library and negative floating-point Serial output. The final complete rerun passed all twelve projects and the existing-project overwrite check (`wiredroid-1.6-native-isolated-test.txt`, successful instrumentation result -1). It verified LEDs/buttons, Uno/Nano/ESP32 GPIO profiles, servo sweep and potentiometer input, RC ADC voltage, DC motor direction/PWM, Stepper library motion, LCD text with C/C++ source and I²C EEPROM byte 42. Every project also saved a 50% component size.

The next run passed eleven projects and found that `Wire.write(0)` was ambiguous between the byte and string overloads inherited from Print. Wire and Serial adapters now provide numeric forwarding overloads; the original EEPROM source now compiles and reads back byte 42 on Android (`wiredroid-1.6-native-i2c-test.txt`). The failing run is retained as `wiredroid-1.6-native-wire-regression.txt`. The Wire overload signatures follow the [official Arduino AVR Wire API](https://github.com/arduino/ArduinoCore-avr/blob/master/libraries/Wire/src/Wire.h).

## Scope and limits

The catalogue contains 2569 Fritzing entries. 327 pin-bearing board profiles support the generic host GPIO runtime. 478 entries infer one of 26 parameterized component families; these counts do not represent device-specific conformity tests. `WIREDROID-1.6-COVERAGE.json` lists each binding and each absent model.

The solver is parameterized and limited to 256 matrix unknowns. It does not yet provide calibrated vendor ratings, semiconductor temperature/parasitics, SPICE imports, A4988 microstepping/chopper regulation or loaded stepper missed-step dynamics. Basic OLED, expander and shift-register behavior needs further conformance coverage.

Native event timestamps have microsecond resolution; Android scheduling and WebView round trips remain host timed. AVR/ARM/ESP32 instruction execution, vendor hardware timers, vendor Wi-Fi/Bluetooth SDK firmware and physical USB flashing are not implemented. Hardware-specific Arduino libraries require a simulation port. Imported `.ino` uses C++ but does not generate Arduino function prototypes.
