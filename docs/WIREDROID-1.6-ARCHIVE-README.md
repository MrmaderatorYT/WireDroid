# WireDroid 1.6.0 — Embedded IDE

Android IDE for C/C++ circuit projects, derived from DroidEditorArchitecture. The native editor retains tabs, file tree, project creation, settings, themes, search, history, Git and export. Its right panel contains the circuit constructor in the same IDE theme. The user workflow creates embedded projects.

Application ID: `com.ccs.wiredroid`; version code 37, version name `1.6.0-electronics`. Build with JDK 17, Android SDK 36 and the Gradle wrapper. C/C++ execution requires ARM64 Android. The inherited internal package namespace remains `com.ccs.wiredroid`.

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Create and run

Choose **Новий проєкт | Приклади**, select an example and create a project. Open **Плати та з’єднання** on the right, select a board and open its program in the native editor. **Запустити** saves the current editor buffer, compiles C/C++ and launches a separate native process for each board. Wide screens can dock the constructor beside the code; small screens use the sliding right drawer. The IDE toolbar scrolls horizontally.

Drag, rotate and resize parts (25–200%). Connect contacts, select wires, edit colours, add or drag bends, and choose orthogonal routing. Selected wires highlight their copper net. Circuit edits, inputs and parameter settings persist with undo/redo. Each scene supports 250 parts, 2000 wires and eight concurrent board programs.

## C/C++ projects and Arduino libraries

The compiler uses a real Android NDK sysroot and libc++ with C++17, C11, standard containers, strings, streams, math, exceptions and constructors. Arduino `String`, `Print`, `Stream` and `IPAddress` come from the official ArduinoCore-API. Bundled LiquidCrystal and Stepper source libraries are upstream implementations; Servo, Wire, SPI and EEPROM use WireDroid simulation adapters.

The board entry point is `sketches/<board-id>.cpp`. Existing `.ino` files remain usable when no matching `.cpp` exists. `.ino` uses C++ compilation with Arduino.h included; automatic Arduino function prototype generation is not implemented.

Project layout:

```
circuit.wire.json
sketches/uno.cpp
src/display.cpp
src/helper.c
include/display.h
libraries/Counter/library.properties
libraries/Counter/src/Counter.h
libraries/Counter/src/Counter.cpp
```

`src/`, `include/`, helper files beside the entry point and used Arduino libraries in `libraries/` or `lib/` participate in the build. Include discovery follows project headers and library dependencies. Both modern `src/` libraries and legacy layouts are supported. Optional `wiredroid.json` can explicitly select libraries with a `libraries` array. Hardware dependent libraries must have a suitable simulation port; AVR/ESP32 registers, vendor Wi-Fi/Bluetooth and vendor MCU cores are not provided by an Android host build. Missing source dependencies and unsupported device transactions produce explicit errors.

Board programs normally define `setup()` and `loop()`. A custom `main()` can override the weak default entry point. C helper files must use normal C/C++ linkage declarations. Imported programs can be selected from the board inspector. The IDE includes ordinary C/C++ file templates.

GPIO supports numeric aliases, named constants, pin names and exact Fritzing connector strings. ADC reference voltage and bit depth, PWM frequency, GPIO output resistance and pullup resistance can be changed in the board inspector. `analogReadResolution`, `analogWriteResolution` and `analogWriteFrequency` are available. The adapter also provides software-polled interrupts, pulse measurements, shifting and tone. These use host time and software callbacks, not a hardware interrupt controller.

EEPROM data for the native EEPROM adapter persists in `.wiredroid/eeprom/<board-id>.bin`. I²C EEPROM models use session memory. Serial Monitor sends and receives bytes/text independently for each board. Compiler diagnostics are shown in the circuit console. Stop, closing the panel, activity pause and circuit edits terminate the process; sessions have a ten minute limit.

## Electronics and devices

The constructor solves a parameterized electrical network using modified nodal analysis, nonlinear iteration and backward Euler integration. It calculates contact voltage, component current and power. Models include:

- Resistors, potentiometers, capacitors, inductors and voltage sources.
- Diodes and LEDs, NPN/PNP transistors and N/P MOSFETs.
- Photoresistors, thermistors and manually controlled analog outputs.
- DC motors with winding R/L, back EMF, inertia, friction and load torque.
- Bipolar stepper winding current and ideal magnetic phase alignment.
- Positional and continuous rotation servo visualization.
- L293D/L298 style bridges, ULN2003/2803 outputs and an A4988 style full step translator.
- HD44780 LCD command/text handling, 74HC595 shift/latch output, I²C EEPROM, PCF8574 and basic SSD1306 display RAM.

Device parameters and connector roles are editable in **Контакти → electrical model**. Default family parameters are generic; they are not calibrated to every catalogue SKU. LCD and EEPROM behavior is integration tested. OLED, port expander and shift register models provide basic behavior rather than a complete manufacturer's conformance implementation. A4988 microstepping/chopper regulation, stepper missed steps/load dynamics, transistor parasitics, semiconductor temperature effects, calibrated servo torque and SPICE model import remain unavailable. The electrical solver currently supports up to 256 matrix unknowns, independently of the larger scene drawing limits.

While running, press buttons or rotate potentiometers on the canvas. Servo motion follows `Servo.h`; stop allows the positional horn to be dragged manually or adjusted with its preview slider. DC and stepper shafts animate from the calculated model. Rotation of the artwork keeps contacts and wires fixed.

**Перевірити** highlights dangling component contacts, power-to-GND shorts, output conflicts and LEDs without a series resistor. Click a finding to focus the part. Measured voltage/current are displayed, but automatic manufacturer rating checks are not implemented.

## Signal monitor

Select a contact in **Сигнали**. The monitor displays voltage, duty and PWM frequency, with time windows from 1 ms to 10 seconds. GPIO events retain native microsecond timestamps. PWM carrier edges are reconstructed from commanded frequency/duty; analog integration uses the average PWM voltage. VCD exports are saved to the project's `signals/` folder. Buffers and export sizes are bounded.

Timestamp resolution does not imply MCU clock accuracy. Scheduling, software interrupts and WebView round trips depend on Android. This runtime does not emulate an AVR, ARM or ESP32 instruction set or hardware timer. Cycle accurate protocols require an additional MCU backend.

## Twelve complete examples

The creation screen lists all examples, each with a board, wiring and program:

1. Uno LED blink.
2. Uno button and LED.
3. Uno traffic lights.
4. Nano alternating LEDs.
5. ESP32 QT Py GPIO blink.
6. Servo sweep.
7. Potentiometer controlling a servo.
8. RC charging with ADC measurement.
9. DC motor through L293D, PWM and direction reversal.
10. Stepper library driving a motor through L293D.
11. LCD with `src/`, `include/` and an Arduino source library.
12. I²C EEPROM write/read using Wire.

Examples create separate editable projects and reject overwriting an existing directory. Copies are included in `examples/`. Generate the original seven with `tools/wiredroid/generate-examples.py`, then the additional five with `node tools/wiredroid/generate-electronics-examples.cjs`.

## Catalogue and scope

All 2569 supplied Fritzing descriptions are included. The catalogue identifies 339 board/module profiles: 327 have contacts and 12 are outlines. 2407 entries have original artwork; 162 use labelled connector placeholders. GPIO programs can be built for the 327 pin-bearing profiles. Artwork and contact metadata do not implement that board's processor or peripherals.

The model registry currently assigns one of 26 parameterized component families to 478 catalogue entries; these are inferred family bindings, not 478 separately verified device emulators. The remaining entries support mounting, contacts and declared internal buses; the inspector identifies absent behavior and allows an explicit model or manual signal assignment. Full behavior for all catalogue parts, vendor SDK firmware compilation and physical USB flashing are still outstanding. See `docs/WIREDROID-1.6-COVERAGE.json` for the model inventory.

## Validation and licenses

```sh
./gradlew :app:testDebugUnitTest --tests 'com.ccs.wiredroid.wire.*'
node tools/wiredroid/electronics.test.cjs
node tools/wiredroid/devices.test.cjs
node tools/wiredroid/examples.test.cjs
PLAYWRIGHT_MODULE=/path/to/playwright node tools/wiredroid/browser.test.cjs
PLAYWRIGHT_MODULE=/path/to/playwright node tools/wiredroid/circuits-browser.test.cjs
PLAYWRIGHT_MODULE=/path/to/playwright node tools/wiredroid/motion-browser.test.cjs
python3 tools/wiredroid/compile-boards.py --ndk /path/to/ndk-r28c
adb shell am instrument -w -e suite examples com.ccs.wiredroid.test/com.ccs.wiredroid.wire.WireExamplesInstrumentation
adb shell am instrument -w -e suite examples -e isolated true com.ccs.wiredroid.test/com.ccs.wiredroid.wire.WireExamplesInstrumentation
```

Tests distinguish browser mock bridges, actual Android native compilation and electrical equation checks. The isolated variant hosts the actual constructor and native compiler bridge in a test-only Android Presentation, so unrelated foreground activities cannot pause the run. It does not verify the complete native editor layout. See `docs/WIREDROID-1.6-VALIDATION.md` for completed runs.

Application source retains GPL v3 and credits the inherited WireDroid editor. Fritzing descriptions/artwork retain CC BY-SA 3.0. ArduinoCore-API, LiquidCrystal and Stepper source/license notices are packaged with their upstream commit identifiers in `assets/wiredroid/runtime/upstream.json`. The NDK runtime/sysroot retains the packaged NDK/LLVM/libc++ notices. No instructions from the supplied archives were treated as user instructions.
