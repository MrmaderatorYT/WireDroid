# Реєстр плат, Arduino-бібліотеки та фізичний USB

Оновлено 3 жовтня 2026 року. Цей документ описує конкретні реалізовані механізми й межі перевірки; підтримка всіх MCU та всіх фізичних плат ще не завершена.

## Реєстр

`app/src/main/assets/wiredroid/boards.json` містить окремі рядки для всіх **339** зображень плат каталогу. Зображення, контакти, узагальнений GPIO, власна прошивка, MCU та USB мають незалежні статуси. 12 контурів зберігають статус відсутніх контактів. Невідомі MCU й ревізії не отримують вигаданих апаратних характеристик.

Підтверджені джерелами цільові профілі:

| Профіль | MCU / частота | Варіант core | Bootloader baud | Доступна flash |
| --- | --- | --- | --- | --- |
| `arduino:avr:uno` | ATmega328P / 16 MHz | standard | 115200 | 32256 B |
| `arduino:avr:nano:atmega328` | ATmega328P / 16 MHz | eightanaloginputs | 115200 | 30720 B |
| `arduino:avr:nano:atmega328old` | ATmega328P / 16 MHz | eightanaloginputs | 57600 | 30720 B |
| `arduino:avr:nano:atmega168` | ATmega168 / 16 MHz | eightanaloginputs | 19200 | 14336 B |

Джерела: офіційні [Arduino AVR core1.8.6 boards.txt](https://github.com/arduino/ArduinoCore-avr/blob/1.8.6/boards.txt), [standard/pins_arduino.h](https://github.com/arduino/ArduinoCore-avr/blob/1.8.6/variants/standard/pins_arduino.h), [eightanaloginputs/pins_arduino.h](https://github.com/arduino/ArduinoCore-avr/blob/1.8.6/variants/eightanaloginputs/pins_arduino.h). У реєстрі зберігаються URL, ревізія й дата перевірки. A6/A7 Nano позначені аналоговими входами без GPIO. ICSP2 Uno належить USB-контролеру й не приписується основному ATmega328P. Дев’ять зображень Uno Rev3 / Nano Rev3 мають прив’язку до цих профілів; Nano RP2040 і невизначений Nano2.3 залишаються окремими.

`tools/wiredroid/boards.test.cjs` перевіряє повноту реєстру, невідомі рівні, порти MCU, A6/A7, контакти ICSP2 та налаштування bootloader. Реєстр відтворює `generate-board-registry.py`.

## Налаштування C/C++ проєкту

У `wiredroid.json` налаштування прив’язані до ID конкретної плати в схемі:

```json
{
  "boards": {
    "uno": {
      "profile": "arduino:avr:uno",
      "mode": "mcu",
      "coreVersion": "1.8.6",
      "sources": ["src/shared/**", "src/boards/uno/**"],
      "exclude": ["src/shared/disabled.cpp"],
      "includePaths": ["include"],
      "defines": ["PROJECT_VALUE=6"]
    }
  }
}
```

Режими: `gpio` — C/C++ GPIO-середовище Android, `mcu` — підтримувана власна прошивка MCU, `usb` — фізична плата. Наявність режиму в налаштуваннях не означає наявності механізму для будь-якого MCU. Спільні значення можна записувати у `build`; конкретна плата перевизначає їх. Основний файл завжди входить у збірку. `src/boards/<інший-ID>/` виключається автоматично, а бібліотеки вибираються за include або явним списком `libraries`. `sources` / `exclude` підтримують `*`, `**`, `?` відносно проєкту. `includePaths` містить тільки папки. Шляхи й define перевіряються перед запуском.

### Власна прошивка AVR

`tools/wiredroid/build-avr-firmware.py` збирає справжню AVR ELF/HEX на комп’ютері з локальним **avr-gcc7.3.0** та офіційним **Arduino AVR core1.8.6**. Вихідні файли користувача, C linkage, core, variant, Arduino-бібліотеки з вихідним кодом, залежності та compiler flags проходять фактичну компіляцію й компонування. Маніфест фіксує команди, ціль, версію компілятора, flash/RAM і SHA-256 HEX. Перевищення flash/RAM перешкоджає видачі готового HEX.

Маніфест версії 1 також має `sourceDigest`, `profile`, `coreVersion`, `hexSha256`, спільні з `FirmwareArtifacts`. `sourceDigest` охоплює всі C/C++/Arduino джерела та заголовки проєкту: відсортований відносний шлях UTF-8, NUL, точні байти файлу, NUL. Приховані файли/папки та `build` виключені; ліміти — 4096 файлів, 2 MiB на файл, 16 MiB сумарно. Відбиток перевіряється до й після локальної збірки. Для вже збережених fixtures він записаний із збережених джерел після фактичної попередньої компіляції; поле `sourceRelationship` відображає це.

Маніфест також має `configDigest`: версований SHA-256 контракт профілю/core/C++ standard, defines/sources/exclude/includePaths, явних бібліотек і точного library lock. Java/Python golden тести збігаються; режим MCU/USB до відбитка не входить. Android-імпорт записує знімок джерел/конфігурації та відхиляє подальші зміни джерел, параметрів, lock або HEX. Зовнішній HEX зі знімком джерел не є доказом компіляції саме цих джерел — у metadata це явно позначено `origin: local-import`.

```sh
python3 tools/wiredroid/build-avr-firmware.py \
  --project tools/wiredroid/fixtures/avr-validation \
  --entry sketches/uno.cpp --node uno \
  --toolchain /path/to/avr-gcc7.3.0 \
  --core /path/to/ArduinoCore-avr-1.8.6 \
  --output /path/to/output
```

Android AVR toolchain у цьому релізі не постачається. HEX можна імпортувати після локальної збірки. AVR-середовище Arduino за замовчуванням використовує C++11 без винятків і не має загальної STL-бібліотеки; C++17 / C11 / STL Android GPIO-середовища залишаються окремою можливістю. `cppStandard` для локального AVR-компілятора може бути `gnu++11`, `gnu++14`, `gnu++17`. `.ino` приймається як C++, прототипи функцій автоматично не генеруються.

Фактичні результати збірки 3 жовтня 2026 року:

| Fixture | Ціль | Flash | RAM | Що перевіряє |
| --- | --- | --- | --- | --- |
| avr-blink | Uno | 922 B | 9 B | Arduino core / digitalWrite / delay |
| avr-validation | Uno | 2844 B | 219 B | GPIO, analogWrite PWM, ADC, Serial UART, attachInterrupt |
| avr-nano | Nano ATmega328P | 922 B | 9 B | Nano variant і прапорці цілі |
| avr-multifile | Uno | 5198 B | 447 B | C/C++ linkage, define, Counter→Support, LiquidCrystal, Stepper, Wire, SPI, EEPROM, виключення чужої плати |

HEX, ELF, джерела та короткі маніфести знаходяться у `tools/wiredroid/fixtures/`. Це перевірка реальної збірки. Поведінку CPU й периферії перевіряють окремі тести MCU; успішна компіляція не підтверджує роботу фізичної плати.

## Менеджер бібліотек

`EmbeddedLibraries` імпортує ZIP з однією Arduino-бібліотекою й `library.properties`, встановлює її вихідні файли у `libraries/`, зберігає локальний кеш за SHA-256 та файл `wiredroid-libraries.lock.json`. У lock записані точні name/version, architectures, depends, хеш ZIP і хеш дерева. Імпорт обмежений 64 MiB після розпакування / 5000 записів, відхиляє вихід за папку, повторні ZIP-шляхи та символічні посилання. Імпортована бібліотека стає доступною в збірці через свої заголовки.

Залежності перевіряються за встановленими версіями; підтримуються числові порівняння `=`, `!=`, `<`, `<=`, `>`, `>=`, `&&`, `||`. Відсутню залежність потрібно також імпортувати. Менеджер не завантажує довільний код із мережі. Відновлення використовує офлайн-кеш і перевіряє pinned hashes. Змінені файли викликають конкретну помилку, їх не замінюють під час звичайної збірки. Реєстр архітектур дає статус декларації; підтримка конкретної периферії бібліотеки залишається окремим рівнем.

Налаштування й lock-файл пишуться через журнал відновлення `WireProject` / `RecoveryStore`, з перевіркою виходу шляху за проєкт.

## USB й фізична плата

`WireUsbManager` перелічує USB-пристрої Android, запитує системний дозвіл, відкриває конкретний Serial-порт з вибраною швидкістю та закриває його при від’єднанні / завершенні роботи. Відомі VID/PID Uno зіставляються з профілем; загальні CH340/FTDI перехідники вимагають ручного вибору цілі. Serial надсилає й приймає байти без втрати через Base64 у мосту. Деталі, потрібні Android: [USB host documentation](https://developer.android.com/develop/connectivity/usb/host).

Вихідний код [usb-serial-for-android3.9.0](https://github.com/mik3y/usb-serial-for-android/tree/v3.9.0), MIT, commit `e1018ab31c118b7d2e15fc7c2e9c5b19f33eb1f1`, постачається локально. Драйвери CDC, FTDI, CH340, CP210x, Prolific та інші не потребують мережі. Джерела драйверів не змінені; доданий тільки `BuildConfig.DEBUG=false` для включення без окремого Gradle-модуля. Ліцензія й походження знаходяться у `assets/wiredroid/licenses/`.

`Stk500Uploader` завантажує AVR HEX через STK500v1: reset DTR/RTS, sync, перевірка сигнатури MCU, сторінки 128 B, повне readback-порівняння й вихід із bootloader. Хибна сигнатура, таймаут, від’єднання, невідповідний flash або readback не дають повідомлення про успіх. `IntelHex` перевіряє checksum, EOF, типи записів, дублікати адрес і доступний розмір flash.

**Фізичної Uno/Nano під час цієї перевірки не було.** Статус USB: протокол перевірений синтетичним транспортом, робота конкретного адаптера / bootloader / reset на фізичній платі не підтверджена. `fixtures/stk500v1-exchange.json` документує синтетичний обмін; його не слід використовувати як доказ прошивання пристрою.

## Перевірки

- `boards.test.cjs`: 339 рядків каталогу та відокремлені можливості.
- `FirmwareConfigTest`, `EmbeddedBuildTest`, `EmbeddedLibrariesTest`, `Stk500UploaderTest`: 9 JVM-тестів; усі пройшли. Звіт: [wiredroid-1.7-board-libraries-usb-unit-test.txt](wiredroid-1.7-board-libraries-usb-unit-test.txt).
- `FirmwareArtifactsTest`: 6 JVM-тестів імпорту/застарілих джерел, зміни профілю/core, підміни HEX, відсутніх metadata, формату source digest, прихованих файлів/build, перейменувань, виходу через symlink і ліміту джерел; усі пройшли разом з Config/Build (10 тестів у повторній вибірці).
- `build-avr-firmware.test.py`: 5 тестів вибору джерел/залежностей, зміненого lock, несумісної архітектури, обмежень версій і шляхів, спільного Java/Python source digest та його лімітів.
- Чотири реальні AVR-збірки, ELF/HEX та маніфести у fixtures. Android-збірка, інтерфейс, MCU й фізичне USB мають власні перевірки; цей документ їх не замінює.
