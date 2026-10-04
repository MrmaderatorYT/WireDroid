# WireDroid 1.7 — звіт перевірки

Примітка публікації 2026-10-04: цей звіт описує попередню перевірку 1.7.0-embedded / 38. Поточні APK у `artifacts/` перезібрані як 1.0.0 / 1; результати запусків на пристрої в цьому звіті є історичними.

Дата: **3 жовтня 2026 року**. Версія: **1.7.0-embedded**, versionCode **38**. Перевірки цього випуску завершені. Це збірка розробки; загальний roadmap не вважається закритим, а причини двох попередніх випадкових падінь залишаються відкритими.

## Реалізовані зміни

- Збереження schema revision 2, міграції, перевірений журнал/резервна копія й окремі editor checkpoints. Відновлений буфер залишається зміненим до явного Save. Заблокований або незбережений файл має блокувати запуск попереднього коду.
- Повний нативний редактор, правий конструктор, діагностики з переходом до source location та lifecycle запуску. Групові переміщення, копіювання з проводами й програмою, вирівнювання, undo/redo.
- Реєстр усіх 339 плат із незалежними рівнями підтримки. Точні цілі Uno та Nano з Arduino AVR core 1.8.6; фізичні й виробничі можливості решти сімейств залишаються незавершеними.
- Версії/dependencies/lock/офлайн-кеш Arduino ZIP бібліотек, target-specific source plans та справжній host AVR builder. Імпортовані HEX перевіряються за хешами джерел/конфігурації/lock; зовнішній імпорт не доводить зв’язок вихідного коду з машинним кодом.
- ATmega328P instruction runtime із таймерами/PWM/ADC/UART/SPI/TWI/перериваннями/EEPROM. Pause/step/reset/continue, PC breakpoint та регістри. Записані GPIO фронти мають віртуальний час і cycles; аналогова схема та міжплатні входи узгоджуються до 1 ms.
- Багатоканальний монітор, trigger, period/high/low/duty, декодери UART/I²C/SPI та VCD 1 ps із provenance. Декодери використовують доступні фронти; транзакція Wire чи текст Serial не стає електричною waveform.
- 2570 компонентів; 484 структурно перевірені параметричні прив’язки/29 сімейств. Ratings із explicit provenance, sparse1024unknowns, dt0 memory retention, цифрові регресії та розширені механічні моделі.
- A4988 microsteps, уніполярний stepper, servo/continuous dynamics, ESC/BLDC averaged actuator та енкодер; фізичний стан синхронізований з анімацією. 17 готових проєктів.
- USB serial upstream drivers та STK500v1 upload/signature/readback/cancel. Протокол перевірений програмно; фізична плата через OTG не була під’єднана.
- Числові SPICE `.model` й аналоговий `.cir` export. Native ngspice execution ще не вбудоване.

## Збірка й тести на комп’ютері

**405 JVM unit-тестів**, включно з **62 wire-тестами** та 10 FirmwareArtifacts tests, пройшли без failures/errors/skips. Обидва APK зібрані. [Фінальний лог після нативних виправлень](wiredroid-1.7-compiler-diagnostics-build.txt), [кількість і результати](wiredroid-1.7-jvm-results.json). Збірка: JDK17/Gradle9.4.1/AGP9.2.1/SDK36/NDK28.2.13676358/CMake3.22.1; старі JVM compiler payloads вилучені з APK, залежності editor залишені. [Відтворення](EMBEDDED_BUILD.md).

**20 JavaScript наборів** пройшли: boards, circuits, devices-regression, devices, electronics-ratings, electronics-static-sparse, electronics, engine, esc-encoder, examples, mcu-protocol, mcu-runtime, mcu, mechanics, models, motion-state, motion, motor-examples, signals та spice. [Повний лог](wiredroid-1.7-js-model-tests.txt).

**8 Python builder-тестів** пройшли, включно з Java/Python golden digest contract, source/config changes та freshness metadata bundled AVR example. [Лог](wiredroid-1.7-avr-builder-tests.txt).

Фактично зібрані host AVR ELF/HEX: Blink Uno922B flash/9B RAM; периферія Uno2844/219; Blink Nano922/9; multifile Uno5198/447. Останній використовує C linkage, defines, library dependencies, LiquidCrystal/Stepper/Wire/SPI/EEPROM і виключення code іншої плати. [Команди й pinned sources](WIREDROID-BOARD-LIBRARY-USB.md).

Реальні worker/CPU тести перевірили instruction execution, 100 ms Blink, Timer0 PWM, ADC, UART output, external ISR, PC breakpoint, pause/reset/EEPROM, асинхронний requestId/timeout, UART RX bytes65/0/66/255, SPI delayed SPIF і TWI ACK/NACK register states. UART RX compiled echo та bit-level bus fronts потребують окремих наступних перевірок. [Межі й докази](WIREDROID-SIGNALS-AND-MCU-CHECKS.md).

## Браузер

Пройшли base editor, circuits, motion та platform браузерні сценарії. Base/circuits/motion перевіряють тему IDE, persistence, SVG/body rotation, масштаби, проводи й bends, preflight, problem navigation, ADC/PWM/Serial, servo/potentiometer direct gesture/keyboard, program ownership/stop/manual preview та вузький viewport412px.

Окрема platform-перевірка використовує справжній AVR worker і скомпільований HEX, а Android bridge замінює fixture: GPIO доходить до схеми, virtual pause/step/reset/resume/stop, registers, multichannel graph/trigger/VCD/decoder, SPICE model save/.cir export, 339board matrix/component matrix, library/USB controls, copy/internal wire/undo/alignment та scene search/navigation і narrow dialog. **Mock USB control не є перевіркою фізичного USB.** [Лог](wiredroid-1.7-platform-browser-test.txt).

## Android

Пристрій: **Samsung SM-T720**, ARM64, serial `R52N60FM4ZK`. Остаточний APK SHA-256: `2fddc6e8d0846594e74d39ad673deb698a96bff2d3608cd15941671b7dd2f3e7`; тестовий APK: `5a4ff951cb13c5b5c807aea35f58bef10507cd479f166bd37d507042dd8b4050`. Обидва APK встановлені. Запуск повної MainActivity підтверджений; Java compilation сама по собі цього не доводить.

**Storage reliability: PASS на попередньому candidate `5c00d1…`.** RecoveryStore після цього не змінювався; цей результат не подається як окремий прогін остаточного APK. Android schema migration/legacy scale, validated backup recovery, checked interrupted journal commit, separate editor checkpoint/discard, project-contained compiler locations, encoding/BOM та explicit deletion. [Лог](wiredroid-1.7-native-reliability.txt).

**Приклади: 17/17 PASS на остаточному APK.** 16 проєктів збираються й виконуються як native C/C++; `avr-blink` виконує справжній HEX та перевіряє LED, pause/step/reset/resume/stop. Для кожного збережений масштаб 50%; наявний проєкт не перезаписується. Повторні PASS у логах є progress та підсумковим звітом одного прогону. Приклади виконуються через Android Presentation/VirtualDisplay із actual WebView/native compiler bridge; це незалежно від foreground app, але не підтверджує жести всього нативного редактора. [Остаточний прогін](wiredroid-1.7-native-examples-validated.txt).

**Повний IDE: 8/8 PASS на остаточному APK.** Відновлений dirty buffer не переписує saved C++; source-location navigation/clamping працює; checkpoint з AutoSave off, явний Save, drawer hide/resume, failed AutoSave, readonly Run guard та retry після unlock перевірені. [Остаточний IDE-прогін](wiredroid-1.7-native-ide-validated.txt).

**Серво й анімація: PASS на остаточному APK.** Реальний touch обертає важіль без зміни корпусу/проводів та синхронізує інспектор. Під час Run C++ зберігає контроль; потенціометр змінює ціль, є проміжні animated positions, Stop freeze та calibrated Servo API/attach/writeMicroseconds/read/detach. [Android-прогін](wiredroid-1.7-native-motion-validated.txt), [знімок керування](wiredroid-1.7-servo-touch-tablet.png).

**Створення проєкту: PASS на остаточному APK.** «Новий проєкт | Приклади» містить 17 проєктів, вибраний світлофор копіюється з кодом/схемою; horizontal toolbar scroller має окремі back/overflow controls. [Остаточний прогін](wiredroid-1.7-native-creation-validated.txt).

[Memory snapshot](wiredroid-1.7-tablet-memory.txt) знято під час examples run. Це один зріз, не повний performance qualification або phone benchmark.

## Нативна ізоляція та діагностика

Під час одного повного Android-прогону I²C-програма завершилася з SIGSEGV (139). Початковий ELF був замінений наступною збіркою, тому причину саме цього падіння за адресами backtrace не встановлено. Повторний окремий I²C-приклад пройшов; додатково 24 запусків одного повторно скомпільованого ELF дали очікуваний Serial `42` без SIGSEGV. У цьому stress-сценарії код завершення 2 спричинено навмисним EOF тестового вводу; це не результат звичайного запуску прикладу. [Початковий прогін](wiredroid-1.7-native-examples-final.txt), [backtrace](wiredroid-1.7-native-crash.txt), [окремий повтор](wiredroid-1.7-native-i2c-recheck.txt), [24 запусків](wiredroid-1.7-i2c-startup-stress.json).

Незалежно підтверджена гонка старого cache: різні runner/session з однаковим ID плати записували спільні headers, objects та executable. Кожен запуск тепер атомарно створює власну папку, а worker/reader очищає її після завершення дочірнього процесу. На планшеті також пройшли дві фактичні паралельні Clang-збірки з різним Serial A/B, скасування A зі збереженням роботи B та повторний запуск у новій папці. [Android-лог](wiredroid-1.7-native-session-device.txt). Нові unit-перевірки відтворюють паралельні збірки та скасування; вони також виявили й усунули конкурентне створення parent directory. Для наступного ненормального виходу зберігається один діагностичний snapshot: точний ELF до 16 MiB, SHA-256, шлях проєкту/збірки, exit code і compiler log до 1 MiB. Ця ізоляція усуває доведену гонку, але не є доказом причини початкового SIGSEGV. [Контракт та перевірки](WIREDROID-RECOVERY-VERIFICATION.md).

Окремий повний прогін на candidate APK `10b313…` пройшов Uno/button/traffic, але Clang завершився під час Nano з загальним crash banner. Причину цього одиничного збою компілятора ще не встановлено; окремий Nano-повтор на `22a626…` пройшов. Початковий log і факт відсутнього backtrace збережені, compiler failure не маскується автоматичним retry. [Прогін](wiredroid-1.7-native-examples-release.txt), [діагностика](wiredroid-1.7-native-compiler-crash.txt), [Nano-повтор](wiredroid-1.7-native-nano-recheck.txt).

## Відкриті межі

У цьому релізі не заявляється завершена підтримка всіх плат і деталей: ESP32/RP2040/STM32 та інші vendor MCU/core, AVR toolchain на Android, NanoATmega168 runtime, manufacturer-calibrated components, supply/thermal models, повний LCD/OLED/PCF набір, phase BLDC/chopper, bit-level UART/SPI/TWI й global cycle accurate multi-MCU, source-level C++ debugger і native ngspice залишаються роботою roadmap. Фізичний USB Uno/Nano та повний UI/gestures на реальному телефоні ще не перевірені.

Каталог/структурні ролі, generic GPIO build, симулятор MCU та physical upload мають окремі докази. Успіх одного рівня не підвищує автоматично інші.

## Збережені артефакти

Вихідний проєкт, `artifacts/WireDroid-debug.apk`, тестовий APK, release-info, build-output metadata та цей звіт відповідають версії 1.7.0-embedded / 38. ZIP створюється з коренем `WireDroid/`, з перевіркою CRC, SHA APK, vendor/license bytes, firmware source/config digests і множини записів. Зовнішній `WireDroid.zip.report.json` фіксує SHA та склад архіву. [Пакувальник і відтворення](../tools/wiredroid/PACKAGE-RELEASE.md).
