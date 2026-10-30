# Чек-лист выпуска

## Один раз перед первым публичным релизом

- [ ] Создать ключ подписи (хранить вне репозитория!):
      ```bash
      keytool -genkeypair -v -keystore fixer-release.jks -alias fixer -keyalg RSA -keysize 4096 -validity 10000
      ```
- [ ] Положить рядом с проектом `keystore.properties` (он в `.gitignore`):
      ```properties
      storeFile=fixer-release.jks
      storePassword=...
      keyAlias=fixer
      keyPassword=...
      ```
      Без него `assembleRelease` подписывает debug-ключом, а CI по тегу `v*` падает.
      ⚠️ APK с новой подписью нельзя установить поверх старой debug-версии —
      пользователям придётся удалить старую. Предупредить в описании релиза.
- [ ] Добавить секреты в GitHub (Settings → Secrets → Actions):
      `KEYSTORE_BASE64` (`base64 -w0 fixer-release.jks`), `KEYSTORE_PASSWORD`,
      `KEY_ALIAS`, `KEY_PASSWORD`.
- [ ] Передеплоить Worker (`npx wrangler deploy` в `worker/`): в нём белый список
      адресов Telegram, старая версия пускает на любой хост.
- [ ] Решить, остаётся ли стандартный домен Telegram общим для всех
      (лимит 100 000 запросов/сутки, см. [TELEGRAM.md](TELEGRAM.md)).

## Каждый релиз

Обновление внутри приложения: `worker/publish-update.ps1` собирает подписанный APK,
кладёт его и `version.json` в `worker/public/update` и деплоит Worker
(`worker/wrangler.fixer.toml`). У кого стоит Fixer 1.2+, тому при запуске
предложат обновиться.

```powershell
.\worker\publish-update.ps1 -Notes "Что нового, одной-двумя фразами"
```

- [ ] Поднять `versionCode` и `versionName` в `app/build.gradle.kts`
- [ ] Перенести пункты из «Не выпущено» в новую версию в [CHANGELOG.md](../CHANGELOG.md)
- [ ] `./gradlew testDebugUnitTest assembleRelease` — без ошибок
- [ ] Проверить на реальном телефоне **без VPN на ПК**:
  - [ ] Roblox: иконки грузятся, вход в игру
  - [ ] YouTube: видео играет
  - [ ] Telegram: сообщения и медиа грузятся
  - [ ] Плитка в шторке включает/выключает
  - [ ] Переключение Wi-Fi ↔ мобильный не роняет VPN
- [ ] Переименовать APK в `fixer.apk`, посчитать SHA-256
- [ ] Тег и релиз:
      ```bash
      git tag -a vX.Y -m "Fixer vX.Y"
      git push origin vX.Y
      ```
- [ ] На GitHub: Releases → Draft a new release → тег `vX.Y` → приложить APK,
      в описание — раздел из CHANGELOG и SHA-256
- [ ] Обновить [ROADMAP.md](../ROADMAP.md)
