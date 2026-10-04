# AVR runtime: походження, відтворення та межі перевірки

Перевірено 3 жовтня 2026 року. WireDroid виконує справжню AVR HEX-прошивку через локально включений AVR8js; браузер не надсилає код або прошивку на віддалений компілятор. Підтверджені MCU-профілі — Uno та класичний Nano з **ATmega328P, 16 MHz**. Профіль Nano ATmega168 має власні параметри локальної компіляції й USB, але MCU-механізм ATmega168 у WireDroid не реалізований. Інші родини MCU не отримують статус виконуваної AVR-прошивки.

## Зафіксовані джерела та ліцензії

Використані **AVR8js 0.21.0**, MIT, Copyright © 2019–2025 Uri Shaked, і **esbuild 0.25.12**, MIT, Copyright © 2020 Evan Wallace. esbuild потрібен для підготовки vendor-файлів; Android не запускає його. Джерело AVR8js: [wokwi/avr8js](https://github.com/wokwi/avr8js); джерело esbuild: [evanw/esbuild](https://github.com/evanw/esbuild/tree/v0.25.12). Релізна npm-версія та integrity є конкретною прив’язкою вихідних файлів; за відсутності підтвердженого Git SHA його не підставляють замість цієї прив’язки.

Повний MIT notice AVR8js включений у застосунок: [AVR8JS-LICENSE](../app/src/main/assets/wiredroid/vendor/AVR8JS-LICENSE). Його байти під час відтворення порівнюються з `node_modules/avr8js/LICENSE`. Ліцензія esbuild знаходиться у його npm-пакеті `LICENSE.md` та [офіційному репозиторії](https://github.com/evanw/esbuild/blob/v0.25.12/LICENSE.md).

| Пакет | Точний архів npm | Integrity SHA-512 із перевіреного lock-файлу |
| --- | --- | --- |
| avr8js 0.21.0 | [avr8js-0.21.0.tgz](https://registry.npmjs.org/avr8js/-/avr8js-0.21.0.tgz) | `sha512-kjFzxorERow5YFFV25ElhscG57TJqpT6M0m5GDDbXiMYoBmG6K99MQcq6AU1Ly4L+uB1g1o79dMmBNb5O+toxQ==` |
| esbuild 0.25.12 | [esbuild-0.25.12.tgz](https://registry.npmjs.org/esbuild/-/esbuild-0.25.12.tgz) | `sha512-bbPBYYrtZbkt6Os6FiTLCTFxvq4tt3JKall1vRwshA3fdVztsLAatFaZobhkBC8/BrPetoa0oksYoKXoG4ryJg==` |
| @esbuild/darwin-arm64 0.25.12, використаний під час перевірки | [darwin-arm64-0.25.12.tgz](https://registry.npmjs.org/@esbuild/darwin-arm64/-/darwin-arm64-0.25.12.tgz) | `sha512-N3zl+lxHCifgIlcMUP5016ESkeQjLj/959RxxNYIthIg+CQHInujFuXeWbWMgnTo4cp5XVHqFPmpyu9J65C1Yg==` |

Точні прямі версії та всі платформні пакети esbuild зафіксовані у [avr-runtime-deps/package.json](../tools/wiredroid/avr-runtime-deps/package.json) й [package-lock.json](../tools/wiredroid/avr-runtime-deps/package-lock.json). npm перевіряє integrity архівів під час встановлення. Відтворювач додатково перевіряє встановлені версії, поля lock-файлу, MIT notice та дерево `avr8js/dist` — 72 файли, SHA-256 `56d6c8d635504f157c553fd6bf27eec4688c82bb14cca1ec81c92ebb1c72d31b`. Хеш дерева: відсортований відносний шлях UTF-8, NUL, точні байти, NUL.

## Відтворення vendor без зміни assets

Потрібні Node ≥20.19 та npm ≥10 згідно з metadata AVR8js. Фактична перевірка виконана на macOS arm64, Node 26.7.0, npm 11.19.0. З кореня WireDroid:

```sh
mkdir -p /tmp/wiredroid-mcu-deps-pinned
cp tools/wiredroid/avr-runtime-deps/package.json /tmp/wiredroid-mcu-deps-pinned/package.json
cp tools/wiredroid/avr-runtime-deps/package-lock.json /tmp/wiredroid-mcu-deps-pinned/package-lock.json
npm ci --prefix /tmp/wiredroid-mcu-deps-pinned --no-audit --no-fund
node tools/wiredroid/reproduce-avr-vendor.cjs \
  --deps /tmp/wiredroid-mcu-deps-pinned \
  --output /tmp/wiredroid-avr-reproduction
```

Для вже встановлених перевірених залежностей цієї сесії виконано:

```sh
node tools/wiredroid/reproduce-avr-vendor.cjs \
  --deps /private/tmp/wiredroid-mcu-deps \
  --output /private/tmp/wiredroid-avr-reproduction-check
```

[reproduce-avr-vendor.cjs](../tools/wiredroid/reproduce-avr-vendor.cjs) створює тимчасове дерево шляхів і викликає esbuild із такими параметрами:

- Браузер: `avr8js/dist/esm/index.js`, `bundle: true`, `format: iife`, `globalName: AVR8`, `minify: true`.
- Node-тести: `avr8js/dist/cjs/index.js`, `bundle: true`, `format: cjs`, `platform: node`, без minify.

`write: false` дозволяє порівняти результат перед записом у задану тимчасову папку. Нормалізоване дерево `WireDroid-work` / `wiredroid-mcu-deps` та `preserveSymlinks: true` зберігає точні рядки шляхів у немініфікованому CommonJS-файлі. Додатковий target, sourcemap, banner або footer не задаються. Production assets відхиляються як output. Відтворювач не завантажує залежності самостійно.

Обидва результати під час перевірки збіглися **байт у байт** із замороженими vendor-файлами:

| Файл | Розмір | SHA-256 |
| --- | --- | --- |
| [avr8js.js](../app/src/main/assets/wiredroid/vendor/avr8js.js) | 55 736 B | `583c28e67ff048957aa43240fc4d3fb3a876310bae73070983f125181313e6fd` |
| [avr8js.cjs](../app/src/main/assets/wiredroid/vendor/avr8js.cjs) | 132 343 B | `aa257dbbab4d30de87618e50477a3db288c939842aeb03faa21cb3a753438b79` |

Integrity npm підтверджує вхідний архів пакета; SHA-256 у таблиці підтверджує конкретний результат bundling. Це різні об’єкти перевірки.

## Виконання й віртуальний час

`mcu.js` створює AVR CPU з 32 KiB flash, 2 KiB SRAM, портами B/C/D, Timer0/1/2, ADC, USART0, EEPROM, SPI й TWI. HEX перевіряється перед виконанням: checksum, EOF, типи адресних записів, суперечливі записи й розмір flash. Незаписані адреси заповнюються `0xff`. Breakpoint і snapshot PC показані в байтових адресах; внутрішній PC AVR8js має адреси слів.

Час події — `cycles × 1 000 000 / clockHz`, у мікросекундах; при 16 MHz один такт становить 62.5 ns. CPU виконує інструкції й викликає обробку подій периферії. Реальні скомпільовані fixtures перевіряють Blink 100 ms, Timer0 PWM, ADC, UART output, зовнішній ISR та EEPROM. Це підтвердження цих сценаріїв, а не сертифікація всіх інструкцій і режимів ATmega328P.

`avr-runtime.js` запускає плати послідовними квантами приблизно **1 ms віртуального часу** й оновлює входи перед кожним квантом. Закінчення інструкції може трохи перейти межу кванта. Кілька MCU мають окремі годинники; поширення станів між ними обмежене точками обміну квантами. Короткий імпульс іншої плати між цими точками може бути пропущений. Глобальної синхронізації всієї електронної схеми до одного CPU-такту немає. `setTimeout` керує доставленням роботи браузера; швидкість виконання на комп’ютері може бути вищою або нижчою за реальний час.

Pause/step/reset мають `requestId` й чекають відповідної відповіді worker. Типовий timeout — 5 s реального часу браузера. CPU не продовжує виконання, поки очікує зовнішню SPI/TWI-відповідь; після відповіді завершення байту планується у віртуальних CPU-тактах. Затримка доставлення відповіді через браузер не додається автоматично до часу MCU.

## Межі периферії та сигналів

| Механізм | Реалізована поведінка | Практична межа |
| --- | --- | --- |
| GPIO / таймери | Зміни стану портів, pull-up та timer PWM від регістрів MCU; події з CPU timestamp | Вхідна напруга береться зі знімка схеми; цифровий поріг — половина AVCC. Не моделюються всі фізичні пороги, гістерезис, метастабільність і затримки кристала |
| ADC | Значення каналу, AVCC/AREF, регістри й час перетворення AVR8js | Зовнішня напруга обмежується 0…AVCC; параметри AVCC/AREF тут задаються разом. Поведінка джерела, шум і точність вимірювання залежать від окремої електричної моделі |
| USART RX | Черга байтів 0…255, ліміт 8192, приймання після cyclesPerChar, RXC/UDR; збереження `0x00` | Дані Serial Monitor передаються безпосередньо USART-моделі. Фронти PD0 не декодуються як апаратний RX. Дев’ятий біт і фізичні parity/framing faults цим шляхом не вводяться |
| USART TX | Байти запису UDR потрапляють у Serial Monitor; UDRE/TXC плануються за cyclesPerChar | Callback TX спрацьовує при записі UDR. Текстова мітка Serial не є фронтом чи кінцем фізичного символу. **Хвильова форма TX на PD1/D1 не генерується**; дріт від TX не передає автоматично байти до RX іншого MCU |
| SPI | Режим і порядок бітів, частота, запит до моделі пристрою, відповідь MISO; SPIF після transferCycles | Обмін іде як абстрактна транзакція. Автоматичні SCK/MOSI/MISO фронти апаратного SPI не видаються на портові контакти; немає повної моделі затримок і електричного конфлікту шини |
| TWI / I²C | START/STOP, адреса/read/write, ACK/NACK, відповідь пристрою; address/data завершення після приблизно дев’яти SCL тактів | START завершується локально; STOP чекає відповіді моделі. Фронти SDA/SCL апаратного TWI не генеруються. Multi-master arbitration, фізичне clock stretching й повна TWI slave-модель між MCU не реалізовані |
| EEPROM | 1024 байти й регістрові операції; збереження в межах тієї самої Machine після reset | Окрема EEPROM-історія не переноситься автоматично через створення нового worker / нового запуску |
| Інша периферія | Тільки явно створені вище пристрої | Watchdog, повна поведінка sleep/power-down, analog comparator, fuse bits, bootloader/self-programming та інші MCU-функції цією інтеграцією не заявлені |

GPIO-програма, яка сама змінює контакти, може створювати цифрові фронти для монітора. Це окремий сценарій від апаратного SPI/TWI/UART. [Сигнальні декодери](WIREDROID-SIGNALS-AND-MCU-CHECKS.md) працюють із записаними фронтами; вони не перетворюють Serial text або абстрактну bus-транзакцію на відсутній фізичний сигнал. PWM, реконструйований із duty/frequency у GPIO-середовищі, має відповідну позначку; MCU timer-фронти мають джерело `mcu` і віртуальний clock. Експорт VCD із 1 ps timescale зберігає дробові timestamps і не додає точності моделі.

## Відбитки джерел і налаштувань прошивки

`FirmwareArtifacts` перевіряє `hexSha256`, `profile`, `coreVersion`, `sourceDigest` і `configDigest` перед використанням HEX у симуляторі або фізичному uploader.

`sourceDigest` — SHA-256 усіх файлів `.cpp/.cc/.cxx/.c/.h/.hpp/.hh/.hxx/.ino/.s` у проєкті, без урахування регістру розширення. Для кожного файла: відсортований відносний шлях UTF-8, NUL, точні байти, NUL. Приховані записи й `build` пропускаються. Сортування відповідає Java String, тобто UTF-16 code units; Python використовує ту саму послідовність навіть для non-BMP назв. Ліміти — 4096 файлів, 2 MiB на файл, 16 MiB сумарно, до 32 рівнів папок. Зміна джерел іншої плати теж змінює цей консервативний знімок.

`configDigest` — SHA-256 упорядкованого потоку UTF-8 token + NUL:

1. `wiredroid-config-v1`.
2. Поля `profile`, `coreVersion`, `cppStandard`: назва, значення; default core — `1.8.6`, AVR C++ standard — `gnu++11`.
3. Масиви `defines`, `sources`, `exclude`, `includePaths`, глобальний `libraries`: назва, десяткова кількість, значення в початковому порядку. Конкретна плата перевизначає `build` defaults.
4. `libraryLock`, `absent`; або `libraryLock`, `sha256`, SHA-256 точних UTF-8 байтів lock-файлу. Lock обмежений 128 KiB. Навіть зміна форматування lock-файлу змінює його відбиток.

Вбудований NUL у значеннях відхиляється. `mode` виключено: MCU та USB можуть використовувати той самий HEX. Зміна визначень, source filters, include order, стандарту, списку бібліотек, profile/core або lock вимагає нової прошивки. Старі metadata без `configDigest` потребують повторного імпорту.

Java/Python golden для стандартного Uno й відсутнього lock: `3340e25f66e7c893fbe297f5f92a4573ef53eed5f7316e0b6ab38ccd772d6664`. Для точних байтів `{"version":1,"libraries":[]}`: `0a9607135771c648fcb0482ec3fcdc0a2db2a577174b6bad8110694a31fea7c2`.

Локальний builder фіксує обидва відбитки до й після реальної компіляції. Android-імпорт записує `origin: local-import` та `sourceRelationship` зі словами **not verified as compiled from these sources or settings**. Такий імпорт є знімком на момент вибору зовнішнього HEX; це не криптографічний доказ відповідності бінарника вихідним файлам. Metadata й відбитки не є підписом або захистом від навмисного переписування всіх файлів.

## Локальна збірка C/C++ та перевірки

Потрібні локальний **avr-gcc 7.3.0** і офіційний [Arduino AVR core 1.8.6](https://github.com/arduino/ArduinoCore-avr/tree/1.8.6). Системний Android Clang для GPIO-проєкту не є AVR toolchain. Android AVR compiler у цьому релізі не включено. З кореня WireDroid:

```sh
python3 tools/wiredroid/build-avr-firmware.py \
  --project tools/wiredroid/fixtures/avr-validation \
  --entry sketches/uno.cpp --node uno \
  --toolchain /path/to/avr-gcc7.3.0 \
  --core /path/to/ArduinoCore-avr-1.8.6 \
  --output /tmp/wiredroid-avr-validation
```

На виході — `uno.elf`, `uno.hex`, `uno.build.json`; тимчасові об’єкти видаляються. Компілюються user C/C++, core, variant і вибрані source libraries, перевіряються lock, залежності, архітектура, source layout та flash/RAM. Default AVR C++ — GNU++11 без exceptions і загальної STL; `.ino` вимагає явних прототипів. Чотири фактичні fixtures Uno/Nano/multi-file й витрати flash/RAM описані в [документі плат, бібліотек і USB](WIREDROID-BOARD-LIBRARY-USB.md). Їхні digest metadata додані після реальної попередньої компіляції із збережених джерел; це позначено в `sourceRelationship`.

```sh
python3 tools/wiredroid/build-avr-firmware.test.py
node tools/wiredroid/mcu.test.cjs
node tools/wiredroid/mcu-protocol.test.cjs
node tools/wiredroid/mcu-runtime.test.cjs
node tools/wiredroid/signals.test.cjs
```

Перевірені сценарії: реальний Arduino HEX, GPIO/PWM/ADC/UART TX/ISR/EEPROM; регістрова UART RX-черга; відкладені SPI/TWI flag/status; pause/step/reset/timeout при pending bus; порядок двох MCU з квантами 1 ms; цифрові декодери та provenance VCD. Скомпільований UART echo, фізична Uno/Nano та фізичний UART/SPI/I²C loopback під час цієї перевірки не підтверджені. Збереження власної програми й успішна компіляція не розширюють ці межі автоматично.
