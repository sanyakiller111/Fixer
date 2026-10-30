# Cloudflare Worker для Telegram

[cloudflare-worker.js](cloudflare-worker.js) принимает WebSocket на
`/apiws?dst=<IP дата-центра Telegram>` и пробрасывает его в TCP `dst:443`.
`dst` принимается только из списка адресов DC (`ALLOWED_DST`, тот же, что `DC_IPS`
в `TgBridge.kt`), иначе `403`: так Worker не становится открытым релеем.
Меняешь список в одном файле — поменяй и во втором.
Основан на CfWorker из [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) (MIT).

## Деплой

> Автор Fixer деплоит не этим файлом, а `wrangler.fixer.toml` (Worker `qrrqrq` на
> `tg.teneighty.bet` плюс файлы обновлений) — через `publish-update.ps1`.
> Простой `npx wrangler deploy` создал бы отдельный Worker на `tg.example.com`.

1. Впиши свой домен в [wrangler.toml](wrangler.toml) (`pattern = "tg.твой-домен"`).
2. ```bash
   npx wrangler login
   npx wrangler deploy
   ```
3. В Cloudflare: SSL/TLS → режим **Flexible**.
4. В Fixer нажми на строку Telegram и введи `твой-домен`.

Подробности и деплой через панель — [docs/TELEGRAM.md](../docs/TELEGRAM.md).

## Проверка

```bash
curl -i https://tg.твой-домен/apiws
```

Ожидается `426 Expected websocket` — значит Worker отвечает.
