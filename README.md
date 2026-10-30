<div align="center">

**Русский | [English](README.en.md)**

# Fixer

**Одна кнопка, и Roblox, YouTube и Telegram снова работают на Android.**

[![Сборка](https://github.com/sanyakiller111/Fixer/actions/workflows/android.yml/badge.svg)](https://github.com/sanyakiller111/Fixer/actions/workflows/android.yml)
![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)
[![Версия](https://img.shields.io/github/v/release/sanyakiller111/Fixer?label=версия&color=orange)](../../releases/latest)
[![Лицензия](https://img.shields.io/badge/лицензия-MIT-green)](LICENSE)

[Скачать APK](../../releases/latest) | [Поддержать](https://pay.cloudtips.ru/p/03c080d5) | [Установка](docs/INSTALL.md) | [Проблемы и решения](docs/TROUBLESHOOTING.md) | [Дорожная карта](ROADMAP.md)

<img src="docs/images/main.png" width="300" alt="Главный экран Fixer">

</div>

> [!CAUTION]
> **Антивирусы могут ругаться на APK.** Пара движков на VirusTotal помечает файл как
> «dropper». Причина в том, что приложение умеет ставить собственные обновления
> (разрешение `REQUEST_INSTALL_PACKAGES`) и поднимает локальный VPN. Никаких других
> приложений Fixer не скачивает и не ставит. Исходный код открыт, а SHA-256 каждого
> релиза написан в его описании: сверь хеш скачанного файла с ним.
> [Отчёт VirusTotal](https://www.virustotal.com/gui/file/ace2f13708bb11a6bd2e1190ea61ce13b757ffd5175732900cd340f9687abf98)

## Что это

Fixer это локальный VPN для Android. Он **не гонит трафик через чужой сервер**: всё
обрабатывается прямо на телефоне. Приложение чинит три вещи, которые ломаются у
российских провайдеров:

| Сервис | Что сломано | Что делает Fixer |
|---|---|---|
| **Roblox** | Не грузятся иконки и аватарки, не заходит в игры | DNS через Google DoH с ECS 0/0, игровой UDP идёт напрямую |
| **YouTube** | Видео не открывается вообще | Обход DPI через [byedpi](https://github.com/hufrea/byedpi) |
| **Telegram** | IP-адреса заблокированы полностью | Локальный MTProto-прокси, затем WebSocket, затем Cloudflare Worker |

В VPN попадают только нужные приложения (Roblox, YouTube, браузеры). Остальной
трафик телефона Fixer не трогает.

## Быстрый старт

1. Скачай `fixer.apk` со страницы [Releases](../../releases/latest).
2. Установи (разреши установку из неизвестных источников).
3. Открой Fixer, нажми большую кнопку и разреши VPN.
4. Для Telegram включи переключатель **Telegram** и подтверди добавление прокси.

Подробно, с плиткой в шторке: [docs/INSTALL.md](docs/INSTALL.md).

## Как это работает

```
Roblox, YouTube, браузер  →  TUN  →  Fixer  →  DNS через DoH, byedpi, прямой UDP
Telegram                  →  127.0.0.1 (MTProto)  →  WebSocket  →  Cloudflare Worker  →  Telegram
```

Подробности и схема по файлам: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Документация

| Документ | О чём |
|---|---|
| [docs/INSTALL.md](docs/INSTALL.md) | Установка и первое включение |
| [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) | Не работает, что проверить |
| [docs/TELEGRAM.md](docs/TELEGRAM.md) | Как устроен Telegram и как поднять свой домен |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Как устроен Fixer внутри |
| [docs/BUILD.md](docs/BUILD.md) | Сборка APK и нативных библиотек |
| [docs/RELEASE.md](docs/RELEASE.md) | Чек-лист выпуска версии |
| [ROADMAP.md](ROADMAP.md) | Что дальше |
| [CHANGELOG.md](CHANGELOG.md) | История версий |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Как помочь проекту |

## Структура репозитория

```
.
├── app/                    Android-приложение (Kotlin, без зависимостей)
│   └── src/main/
│       ├── java/com/october/fixer/   код: VPN, DNS, Telegram-мост, UI
│       ├── jniLibs/                  собранные byedpi и hev-socks5-tunnel
│       └── res/                      разметка, иконки, анимации маскота
├── native/                 исходники нативных библиотек и скрипт сборки
│   ├── build.ps1
│   └── third_party/        byedpi 0.17.3 (с патчем), hev-socks5-tunnel 2.19.3
├── worker/                 Cloudflare Worker для Telegram
├── docs/                   документация
└── .github/                CI, шаблоны issue и PR
```

## Сборка

```bash
./gradlew assembleRelease
```

APK появится в `app/build/outputs/apk/release/`. Нужен JDK 17 и Android SDK 34.
Нативные библиотеки уже лежат в репозитории, пересобирать их нужно только при
изменении `native/`. Подробнее: [docs/BUILD.md](docs/BUILD.md).

## Приватность

Fixer не собирает статистику и не имеет своего сервера. DNS-запросы выбранных
приложений уходят в Google или Cloudflare DoH. Трафик Telegram идёт через Cloudflare
Worker на домене, указанном в настройках (по умолчанию домен автора). Свой домен
можно указать в приложении, см. [docs/TELEGRAM.md](docs/TELEGRAM.md).

## Поддержать проект

Fixer бесплатный и без рекламы. Сбор идёт на четыре конкретные вещи:

| На что | Сколько | Зачем |
|---|---|---|
| Сервер для Discord | 450 ₽ в месяц | Discord у российских провайдеров режется по имени сайта, и обход на телефоне (byedpi) его не пробивает: проверено на практике. Для работы нужен свой прокси-сервер за границей. **Сервер ещё не запущен**, сбор идёт на его запуск и оплату каждый месяц |
| Сервер для Instagram | 450 ₽ в месяц | Instagram и сайты Meta закрыты так же. Нужен отдельный прокси-сервер. **Ещё не запущен**, сбор идёт на запуск и ежемесячную оплату |
| Cloudflare Workers Paid | 5 $ в месяц | Бесплатный тариф даёт 100 000 подключений Telegram в сутки на всех пользователей. Когда лимит кончается, Telegram отваливается у всех до 03:00 МСК. Платный даёт 10 млн в месяц |
| Аккаунт разработчика Google Play | 25 $ один раз | Установка и автообновления из Google Play вместо APK |

**[Поддержать через CloudTips](https://pay.cloudtips.ru/p/03c080d5)**. Та же кнопка есть
в приложении внизу главного экрана.

## Благодарности

[hufrea/byedpi](https://github.com/hufrea/byedpi) даёт обход DPI (MIT).
[heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) даёт TUN → SOCKS5 (MIT).
[Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) подсказал схему MTProto-over-WebSocket и Worker (MIT).

Полный список: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Лицензия

[MIT](LICENSE). Автор в Telegram: [@sanyakiller111](https://t.me/sanyakiller111).
