# Архів вихідного WireDroid

[package-release.py](package-release.py) створює ZIP з коренем `WireDroid/`, перевіряє готовий APK і не видаляє вихідний проєкт. Потрібен Python 3.9+; залежності стандартної бібліотеки, мережа не використовується. `build-avr-firmware.py` має бути у вихідному проєкті для спільного контракту firmware digest.

Перед пакуванням потрібно завершити збірку й записати актуальний `artifacts/release-info.json` із `versionName`, `versionCode`, `apkSha256`. Можна додати `applicationId` та `androidTestApkSha256` (саме це поле перевіряє пакувальник). З кореня вихідного проєкту:

```sh
python3 tools/wiredroid/package-release.py \
  --source /path/to/WireDroid \
  --apk /path/to/WireDroid/artifacts/WireDroid-debug.apk \
  --metadata /path/to/WireDroid/app/build/outputs/apk/debug/output-metadata.json \
  --release-info /path/to/WireDroid/artifacts/release-info.json \
  --output /tmp/WireDroid.zip --check-only
```

`--check-only` перевіряє APK та план архіву, виводить report і не створює ZIP або sidecar. Після завершення перевірок та сама команда без `--check-only` створює ZIP та `/tmp/WireDroid.zip.report.json`. Існуючий ZIP або report не перезаписуються. Для report можна задати `--report /path/to/new-report.json`. Optional `--android-test-apk /path/to/app-debug-androidTest.apk` включає APK інструментальних перевірок.

У папці `WireDroid/artifacts/` архів містить:

- `WireDroid-debug.apk` — єдиний основний APK, перевірений за SHA-256;
- `WireDroid-androidTest.apk`, коли його явно передано;
- `release-info.json` — переданий перевірений release-info;
- `build-output-metadata.json` — передані відомості збірки. Вони можуть містити первісне ім’я `app-debug.apk`; його байти порівнюються з canonical `WireDroid-debug.apk`.

Версія та application ID порівнюються між literal налаштуваннями `app/build.gradle` / `build.gradle.kts`, build output metadata та release-info. APK за шляхом `outputFile` у metadata має існувати й бути тим самим файлом або мати той самий SHA. Пакувальник не виводить версію шляхом розбору binary AndroidManifest; provenance береться з наданих результатів збірки та відповідного хешу APK.

Для APK перевіряються CRC всіх ZIP-записів, унікальність імен, готовий `avr-blink/.wiredroid/firmware/uno.hex` та `.json`, `wiredroid.json`, C++ джерело, AVR vendor bundle, AVR MIT notice і USB MIT notice. Приклад тимчасово розпаковується з перевіркою шляхів і лімітів. Його HEX, `sourceDigest`, `configDigest`, profile/core мають відповідати APK-вмісту та поточним вихідним assets. Vendor та license байти теж мають відповідати поточним assets.

В архіві зберігаються вихідні файли, документація, приклади, vendor, npm locks і приховані **`.wiredroid/firmware`**. Виключаються `build`, `.gradle`, `.cxx`, `.idea`, `.git`, `node_modules`, `__pycache__`, `.pytest_cache`, `.bug-hunter`, `.DS_Store`, `local.properties`, відомі signing-properties, приватні keystore/key-файли й PEM із private-key header. Старі APK та source ZIP із `artifacts` замінені перевіреними артефактами. Символічні посилання за межі вихідного проєкту або цикли відхиляються.

Створення використовує тимчасовий ZIP. Перед появою фінального файлу перевіряються точна множина й кількість записів, CRC кожного запису та SHA упакованого APK. Зміна розміру, часу запису або файлової ідентичності джерел під час пакування відхиляє результат. JSON report містить перевірки APK/firmware, версію, exclusion counts, повний список ZIP-записів, розміри та SHA архіву. Ці хеші є перевіркою цілісності, а не цифровим підписом релізу.

Перевірки [package-release.test.py](package-release.test.py):

```sh
python3 tools/wiredroid/package-release.test.py
```

Шість невеликих synthetic проєктів перевіряють збереження прихованих firmware/locks, виключення intermediates/ключів/старих APK, canonical APK names, CRC/SHA/count/report, `--check-only`, відсутні APK assets, version/SHA mismatch, застарілі source/configDigest, escaping symlink та відмову перезаписувати ZIP. Тести не створюють фінальний релізний ZIP проєкту.
