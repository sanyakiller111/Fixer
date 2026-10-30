# Как устроен Fixer

```
 Roblox / YouTube / браузеры                     Telegram
            │                                       │  MTProto
            ▼                                       ▼
   ┌──────────────────┐                   ┌───────────────────┐
   │  TUN (VpnService)│                   │ TgBridge          │
   │  только IPv4     │                   │ 127.0.0.1:21441   │
   └──┬──────┬─────┬──┘                   └─────────┬─────────┘
      │DNS   │TCP  │UDP (не DNS)                    │ WebSocket
      ▼      ▼     ▼                                ▼
   DoH    hev-socks5   UdpRelay            Cloudflare Worker
 Google   -tunnel      (напрямую)          tg.<домен>/apiws
 ECS 0/0     │                                      │ TCP
             ▼                                      ▼
          byedpi :21080 ──► интернет           DC Telegram :443
     (Roblox-хосты без десинхронизации)
```

## Файлы

| Файл | Роль |
|---|---|
| [FixVpnService.kt](../app/src/main/java/com/october/fixer/FixVpnService.kt) | VPN: поднимает TUN, запускает byedpi и hev, разбирает пакеты, DNS, уведомление |
| [PacketUtils.kt](../app/src/main/java/com/october/fixer/PacketUtils.kt) | Разбор/сборка IPv4/UDP/DNS, EDNS Client Subnet 0/0 (покрыт тестами) |
| [UdpRelay.kt](../app/src/main/java/com/october/fixer/UdpRelay.kt) | Прямой UDP для игр (Roblox) в обход SOCKS5, замер пинга до сервера |
| [Mm2.kt](../app/src/main/java/com/october/fixer/Mm2.kt) | Подбор сервера Murder Mystery 2: список серверов, подсети Roblox → город |
| [TProxy.kt](../app/src/main/java/com/october/fixer/TProxy.kt) | JNI-обёртка над hev-socks5-tunnel |
| [TgBridge.kt](../app/src/main/java/com/october/fixer/TgBridge.kt) | Локальный MTProto-прокси и WebSocket-клиент к Worker |
| [MainActivity.kt](../app/src/main/java/com/october/fixer/MainActivity.kt) | Экран, маскот, переключатель Telegram, диалог домена |
| [FixTileService.kt](../app/src/main/java/com/october/fixer/FixTileService.kt) | Плитка в шторке |
| [DotRingView.kt](../app/src/main/java/com/october/fixer/DotRingView.kt) | Анимированное кольцо вокруг кнопки |
| [worker/cloudflare-worker.js](../worker/cloudflare-worker.js) | WebSocket → TCP мост на стороне Cloudflare |

## Ключевые решения

**Только выбранные приложения.** В TUN попадают пакеты из списка `APPS`
(Roblox, YouTube, браузеры). Сам Fixer исключён, чтобы его DoH и сокеты byedpi
не зациклились.

**DNS: Google DoH с ECS 0/0.** Roblox CDN (`tr.rbxcdn.com`) по геоDNS отдаёт
российским подсетям пустой ответ. Google учитывает EDNS Client Subnet с
префиксом 0 и отвечает «как для всего мира». Cloudflare ECS игнорирует,
поэтому он только запасной.

**byedpi как исполняемый файл.** `libbyedpi.so` — на самом деле PIE-бинарник,
запускается через `ProcessBuilder` из `nativeLibraryDir`
(поэтому `useLegacyPackaging = true` и `extractNativeLibs="true"`).
Хосты Roblox вынесены в группу без десинхронизации — иначе CDN Roblox зависал.

**Прямой UDP.** Вход в игры Roblox через SOCKS5 UDP byedpi давал ошибку 279,
поэтому игровой UDP идёт через `UdpRelay` напрямую. QUIC (UDP 443) отбрасывается,
чтобы браузеры и YouTube перешли на TCP через byedpi.

**Только IPv4.** Телефон переключается между Wi-Fi без IPv6 и LTE с IPv6;
IPv6 через byedpi давал «Network is unreachable».

**Telegram без VPN.** IP-адреса Telegram заблокированы целиком, десинхронизация
не помогает. Поэтому Telegram подключается к локальному прокси Fixer, а тот
ходит в Cloudflare Worker по WebSocket. Подробнее — [TELEGRAM.md](TELEGRAM.md).

**Подбор сервера MM2 без аккаунта.** Регион и IP сервера Roblox отдаёт только
`gamejoin` с куки аккаунта, а Fixer её не трогает. Поэтому всё наоборот: кнопка
берёт открытый список серверов (`games.roblox.com/v1/games/142823291/servers/Public`),
заходит на случайный из лучшей половины ссылкой `roblox://experiences/start?…&gameInstanceId=…`,
а `UdpRelay` видит, к какому серверу из 128.116.0.0/17 подключился Roblox, и меряет
пинг: время от последнего отправленного пакета до первого ответа сервера (рукопожатие).
Дальше пакеты идут в обе стороны сразу, и пары «отправил → получил» дают ~0 мс. Решает пинг (≤ 120 мс сети — хорошо), город — только подпись, и только для
подсетей, где ipinfo.io и списки сообщества совпадают. Если далеко — в уведомлении
кнопка «Другой сервер» со ссылкой на следующий. Подбор не чаще раза в 20 секунд:
на частые входы Roblox отвечает капчей.
