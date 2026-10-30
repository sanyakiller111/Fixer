# Сборка

## Требования

- JDK 17
- Android SDK: platform 34, build-tools 34
- Для пересборки нативных библиотек — Android NDK (r26+) и PowerShell

Путь к SDK берётся из `ANDROID_HOME` или из `local.properties`
(`sdk.dir=C:\\Users\\<имя>\\AppData\\Local\\Android\\Sdk`).

## APK

```bash
./gradlew assembleRelease
```

На Windows — `gradlew.bat assembleRelease`.
Результат: `app/build/outputs/apk/release/app-release.apk`.

Отладочная сборка и установка на подключённый телефон:

```bash
./gradlew installDebug
```

## Тесты

```bash
./gradlew testDebugUnitTest
```

Юнит-тесты покрывают разбор пакетов и DNS ([PacketUtilsTest.kt](../app/src/test/java/com/october/fixer/PacketUtilsTest.kt)).

## Нативные библиотеки

Готовые `.so` лежат в `app/src/main/jniLibs/` и закоммичены, поэтому для
обычной сборки NDK не нужен. Пересобирать — только после изменений в `native/`:

```powershell
pwsh native/build.ps1
```

Скрипт собирает для `arm64-v8a` и `armeabi-v7a`:

| Библиотека | Исходники | Что это |
|---|---|---|
| `libbyedpi.so` | `native/third_party/byedpi` (0.17.3) | PIE-исполняемый файл, SOCKS5-прокси с десинхронизацией |
| `libhev-socks5-tunnel.so` | `native/third_party/hev-socks5-tunnel` (2.19.3) | JNI-библиотека TUN → SOCKS5 |

В byedpi есть локальный патч: опция `-C` принимает `ip:порт`.
Флаг `-Wl,-z,max-page-size=16384` нужен для устройств с 16 КБ страницами (Android 15).
