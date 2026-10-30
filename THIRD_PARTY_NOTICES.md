# Сторонние компоненты

| Компонент | Версия | Где | Лицензия |
|---|---|---|---|
| [byedpi](https://github.com/hufrea/byedpi) — hufrea | 0.17.3 + локальный патч `-C ip:port` | `native/third_party/byedpi`, `libbyedpi.so` | MIT, [текст](native/third_party/byedpi/LICENSE) |
| [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) — hev | 2.19.3 | `native/third_party/hev-socks5-tunnel`, `libhev-socks5-tunnel.so` | MIT, [текст](native/third_party/hev-socks5-tunnel/LICENSE) |
| [tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) — Flowseal | CfWorker | `worker/cloudflare-worker.js`, схема в `TgBridge.kt` | MIT |

Внутрь `libhev-socks5-tunnel.so` статически собраны зависимости hev-socks5-tunnel:

| Компонент | Где | Лицензия |
|---|---|---|
| [lwIP](https://savannah.nongnu.org/projects/lwip/) — Swedish Institute of Computer Science | `native/third_party/hev-socks5-tunnel/third-part/lwip` | BSD-3-Clause, [текст](native/third_party/hev-socks5-tunnel/third-part/lwip/LICENSE) |
| yaml — hev, форк [libyaml](https://github.com/yaml/libyaml) (Kirill Simonov, Ingy döt Net) | `native/third_party/hev-socks5-tunnel/third-part/yaml` | MIT, [текст](native/third_party/hev-socks5-tunnel/third-part/yaml/LICENSE) |
| [hev-task-system](https://github.com/heiher/hev-task-system) — hev | `native/third_party/hev-socks5-tunnel/third-part/hev-task-system` | MIT, [текст](native/third_party/hev-socks5-tunnel/third-part/hev-task-system/LICENSE) |

`third-part/wintun` нужен только для Windows и в APK не попадает.

MIT и BSD требуют сохранять эти уведомления при распространении: в описании каждого
релиза должна быть ссылка на этот файл.
