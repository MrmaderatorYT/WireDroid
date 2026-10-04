# Embedded implementation

`wire/WireProject.java` owns the circuit format, atomic persistence and bounded board-ID-to-`.cpp` paths with an existing `.ino` fallback. The project creator generates only circuit projects, with `sketches/uno.cpp`. Existing `.ino` is used if there is no matching `.cpp`; when both exist, `.cpp` wins. The resolver enforces path containment for both extensions. Source discovery and file templates use `.cpp`, `.c`, `.h`, `.hpp`, `.ino` and related C/C++ extensions.

`wire/WireDrawerController.java` wraps the existing `MainActivity` editor content without replacing its view tree. It adds a right `DrawerLayout` window on phones and a dock beside the editor on wide screens (840 dp or wider). Docking preference persists; closing and activity pause stop programs. A vertical Плати rail and the toolbar/menu actions reopen it.

`wire/WireWorkspace.java` hosts a local asset-only WebView and connects native execution to the canvas. The bridge saves scenes, opens sketches in the same native editor tabs and starts/stops board programs. Run saves the current native editor buffer before invoking Clang. `AppTheme` supplies all constructor colours, and the web toolbar/tab/console styling follows the IDE. Local requests are served from assets; other origins are blocked. `WireActivity` remains a compatibility host, while its normal launch helper routes to MainActivity.

The native language status updates for Arduino C++, C/C++ and C source files. The native editor uses CppLanguage, including keyword completion and source highlighting. The outline recognizes basic global C/C++ functions and classes. Java formatting controls are hidden rather than applying a Java formatter to C++ files.

`wire/EmbeddedRunner.java` generates a board-specific `pins.h` from Fritzing connector metadata, compiles the selected sketch plus the bundled runtime with Clang, links a freestanding PIE ELF with LLD and starts it via Android's system linker. Compilation happens away from the UI thread. Board processes exchange bounded GPIO/Serial messages over stdout/stdin; synchronous pin reads receive values resolved by the canvas. Cancellation terminates the child process. Native compiler libraries are bundled for ARM64.

`assets/wiredroid/runtime/Arduino.h` and `runtime.cpp` implement the simulation ABI. C++ programs execute on the Android host. This runtime is independent of a Java/JVM programming toolchain and is not a target MCU emulator, complete Arduino SDK, vendor HAL or flashing tool.

`engine.js` resolves digital nets, buses, pullups, output conflicts, resistors and supported buttons. `app.js` implements the board palette, SVG contacts, fallback contacts, movement, rotation, connections, inspector, history, persistence and native GPIO callbacks. Browser previews can edit circuits; execution requires the Android host. JavaScript board workers were removed.

The catalog includes every one of the 2569 supplied descriptors. 2407 use available original graphics; 162 use generated placeholders. There are 339 recognized board/module profiles, 327 with contacts. Any other pin-bearing component can opt into GPIO programming. Twelve outline-only profiles have no declared contacts and remain visual components.

The full inherited Android editor remains implemented in Java internally. Its JVM project choices and commands are inaccessible in the embedded edition. User-facing JDK, Java SE, Kotlin/JVM language selection, bytecode, Maven tools, JShell, Java refactoring and Java course flows have been removed. JDK/language runtime payload generation and packaging are disabled; bundled Java SE assets were removed. The package namespace remains for Android/JNI compatibility; the application ID is `com.ccs.wiredroid`.

Physical flashing, architecture-specific firmware builds, MCU execution, analogue electronics and vendor peripheral models require separate implementations and are not claimed as completed.

`WireExamples` lists the bundled manifest and copies a chosen example into a newly reserved project directory. It only allows declared circuit/source/README paths, validates the scene and rolls back only its newly created directory on failure. `.gitignore` is generated natively because Android omits dotfiles from packaged assets. The default project factory uses the Uno blink bundle. Welcome and the constructor share the gallery; a constructor launch saves the active buffer and scene first.

Nodes optionally persist `scale` (0.25–2.0). Both native and JS validators check the range. Node artwork width incorporates this size; contact overlays and wire endpoints are recalculated from the rendered geometry after each resize or rotation. Undo/redo stores size with the complete scene. Fit uses rotated visual bounds. Coloured LEDs in the examples reuse the compact generic Fritzing package, tinting its colour layers without changing connector geometry. Scene validators restrict the optional ledColor to a hex colour. Visible contact dots shrink along with the part, with a small minimum size.

## 1.4.0 circuit workbench

Copper topology for checks and net highlighting is separate from the resistor-conducting digital signal graph. The checker validates hard power shorts and LED protection, reports unused pins, and rechecks pin directions as native mode events arrive. Problems are selectable canvas targets. No voltage metadata or calibrated electrical simulation is inferred from artwork.

Wires optionally persist routing (curve/polyline/orthogonal) and points (up to 64 world coordinates). Native and web validators accept older scenes and enforce new bounds. A separate SVG layer contains bend handles above component artwork. Per-node inputValue and optional simulation.pin store manual ADC sources. Supported potentiometers and analog-output sensors derive normalized levels from explicitly connected rails. Inputs update without stopping the process.

The console has output, problems, signals and Serial panes. Traces retain up to 250 samples for at most 256 active contacts and ten seconds. PWM values represent duty. Serial uses hex-framed output to preserve print/newline semantics, a streaming UTF-8 decoder for bytes written in separate frames, and independent bounded input queues. A/C/P protocol requests report available/read/peek synchronously in EmbeddedRunner; GPIO reads continue to roundtrip through the WebView at 10-bit precision. Session locks serialize replies. Serial input never sends unframed user text into the GPIO protocol.

## 1.5.0 actuator animation

`runtime/Servo.h` is an independent freestanding adapter for positional Servo APIs. Channel allocation is bounded to twelve objects per native board process. Attach sets OUTPUT and emits an angle/pulse event; write maps 0–180° into the configured pulse interval, writeMicroseconds clamps to that interval, and detach clears the pulse and returns the pin to INPUT. Global C++ object constructors run before setup. EmbeddedRunner copies the header and validates the V protocol frame before posting it to the trusted WebView.

`engine.js` recognizes servo parts by explicit signal/power/GND contacts and excludes them from analog sensor source inference. `servoStates` requires powered supply, board-ground connectivity and exactly one active Servo source on the signal net. Servo metadata clears on ordinary writes, input mode, detach and stop. Signal duty remains available to the existing monitor as an averaged pulse/20 ms value; this does not synthesize a carrier waveform.

`motion.js` animates the original basic servo horn around its artwork gear pivot; other recognized servo artwork gets an overlaid arm. Fixed bodies, connector elements and wire endpoints do not rotate. Motion uses requestAnimationFrame with bounded elapsed steps, stopping when idle. It honors reduced-motion preferences. Potentiometers get a visible rotary indicator and a pointer/keyboard dial control, with the normalized input stored by the scene. A stopped-only servo preview has no electrical effect; starting clears it. Re-rendering retains displayed poses while rebuilding overlay elements. Actual positional motion is visualized at a fixed rate, with no calibrated inertia/torque model.

The example manifest includes seven projects. `servo-sweep` and `servo-pot` use an Uno, a powered three-pin servo and optional A0 potentiometer. Assets and source-export examples are generated from the same script.

## 1.5.1 direct servo manipulation

The servo preview now has a pointer/keyboard slider overlay centered on the artwork pivot and large enough to cover the horn. Circular pointer deltas are unwrapped and clamped to 0–180°. Captured touch events do not reach node dragging or canvas panning. Connector buttons remain above the servo control layer. Direct gestures and the inspector use the same guarded preview action, synchronizing the inspector slider without mutating the scene or electrical signals. Program execution disables manual preview in both motion and app layers; a blocked gesture gives a visible Stop instruction. Stop re-enables manual control. Body rotation and global zoom preserve the control geometry.
