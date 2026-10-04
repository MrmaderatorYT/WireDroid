# AVR · справжня прошивка Arduino Uno

Схема Uno + 220 Ω + LED. MCU виконує зібрану для ATmega328P прошивку Arduino AVR core 1.8.6. LED змінює стан кожні 100 ms віртуального часу.

В інспекторі плати вибрано режим AVR MCU. У «Налагодження» доступні пауза, крок, reset і регістри. Зміни C++ потрібно скомпілювати для MCU та повторно імпортувати HEX. Вбудований GPIO компілятор запускає C++ на Android; обрати його можна в профілі.

Відтворення: tools/wiredroid/build-avr-firmware.py --project <project> --entry sketches/uno.cpp --node uno --toolchain <avr-gcc> --core <avr-1.8.6> --output .wiredroid/firmware/uno

Походження HEX і SHA-256 записані у tools/wiredroid/fixtures/avr-blink.build.json.
