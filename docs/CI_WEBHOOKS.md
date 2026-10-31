# Webhooks CI (Codemagic и GitHub Actions)

Два разных webhook'а, которые легко перепутать:

| Направление | Кто вызывает | Зачем | Где настраивается |
|---|---|---|---|
| **Входящий** (trigger) | GitHub → Codemagic | запустить сборку на push/PR/tag | Codemagic UI → App settings → Webhooks, либо `triggering:` в `codemagic.yaml` |
| **Исходящий** (notify) | Codemagic/GitHub → ваш сервис | сообщить статус сборки, коммит, артефакты | `tools/webhook_notify.py` + секреты `WEBHOOK_URL` / `WEBHOOK_SECRET` |

## Исходящие уведомления

Один скрипт на все CI: `tools/webhook_notify.py` (только стандартная библиотека Python,
никаких зависимостей). Вызывается двумя местами:

- `codemagic.yaml` → `publishing.scripts` (Codemagic выполняет publishing-скрипты **и при падении**,
  поэтому статус берётся из маркера `~/.phoneguard-build-success`, который ставит последний шаг
  `Mark build successful`);
- `.github/workflows/android-ci.yml` → job `notify` (`if: always()`, статус считается по
  `needs.*.result`).

### Что приходит на эндпоинт

`POST` с `Content-Type: application/json`, заголовки `X-PhoneGuard-Event: build.finished` и
`X-PhoneGuard-Signature: sha256=<hex>` (если задан `WEBHOOK_SECRET`).

```json
{
  "event": "build.finished",
  "status": "success",
  "provider": "codemagic",
  "repository": "vladvinys-vv/PhoneGuard",
  "branch": "main",
  "commit": "deadbeef…",
  "build_id": "abc123",
  "build_url": "https://codemagic.io/app/proj1/build/abc123",
  "workflow": "phoneguard-android",
  "job": null,
  "artifacts": [
    { "name": "app-production-release.apk", "url": "https://…", "size": 12345 }
  ],
  "source": { "host": "macos-…", "platform": "Darwin-…" },
  "sent_at": "2026-10-31T12:30:26+00:00"
}
```

`status` — `success`, `failure` или `canceled`. `artifacts` берутся из `CM_ARTIFACT_LINKS`
(Codemagic, со ссылками), иначе — из `app/build/outputs/**/*.apk|aab` (только имена и размеры).
`provider` — `codemagic`, `github-actions` или `local`.

### Семантика доставки

- 2xx — успех, любое тело ответа (до 512 байт) печатается в лог сборки.
- 3 попытки с нарастающей паузой (1, 2, 4 с; ограничение 10 с), таймаут запроса `WEBHOOK_TIMEOUT`
  (по умолчанию 10 с).
- **Сборка из-за webhook'а не падает** (`--strict` включает обратное поведение) — недоступный
  приёмник не должен ломать релиз.
- Нет `WEBHOOK_URL` — шаг печатает «уведомление пропущено» и завершается успешно, поэтому
  форк и локальная сборка ничего не требуют.

### Проверка подписи на приёмнике

```python
import hashlib, hmac

def is_valid(secret: str, body: bytes, header: str | None) -> bool:
    expected = "sha256=" + hmac.new(secret.encode(), body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, header or "")
```

```javascript
// Node.js (Express, express.json с verify)
const crypto = require('crypto')
app.post('/phoneguard', express.json({ verify: (req, _res, buf) => { req.rawBody = buf } }), (req, res) => {
  const expected = 'sha256=' + crypto.createHmac('sha256', process.env.WEBHOOK_SECRET)
    .update(req.rawBody).digest('hex')
  if (!crypto.timingSafeEqual(Buffer.from(expected), Buffer.from(req.get('X-PhoneGuard-Signature') || ''))) {
    return res.status(401).end()
  }
  console.log(req.body)
  res.json({ ok: true })
})
```

## Настройка Codemagic

1. **Секреты.** Codemagic UI → App settings → **Environment variables**:
   - `WEBHOOK_URL` — ваш эндпоинт, включите **Secret**;
   - `WEBHOOK_SECRET` — произвольная строка, тоже **Secret** (если приёмник проверяет подпись).

   Их можно собрать и в группу (например `phoneguard_webhook`) и добавить
   `environment.groups: [phoneguard_webhook]` в `codemagic.yaml`; по умолчанию группы нет
   специально — ссылка на несуществующую переменную-group валит сборку, а так конфигурация
   работает сразу после форка.
2. **Входящий webhook** (чтобы сборки запускались сами): App settings → **Webhooks** →
   *Create webhook*. Codemagic сам зарегистрирует его в GitHub с payload URL
   `https://api.codemagic.io/hooks/<appId>`; события — Push и Pull request.
   Альтернатива вручную: GitHub → Settings → Webhooks → Add webhook, тот же URL,
   `Content type: application/json`.
3. **События и ветки** уже описаны в `codemagic.yaml` (`triggering`): push, pull_request и теги
   `v*`, ветки `main`/`master`, `cancel_previous_builds: true`.
4. Проверить: Codemagic → Webhooks → **Update webhook**, затем любой push в `main`.

> Создать webhook за вас через API нельзя: `POST /repos/<owner>/<repo>/hooks` требует
> у токена права `admin:repo_hook` (иначе `403 Resource not accessible by integration` —
> проверено), а URL триггера содержит `appId` конкретного приложения Codemagic. Поэтому
> регистрация делается в UI (шаг 2) либо своим токеном:
> `curl -X POST -H "Authorization: token $TOKEN" https://api.github.com/repos/<owner>/<repo>/hooks -d '{"name":"web","active":true,"events":["push","pull_request"],"config":{"url":"https://api.codemagic.io/hooks/<appId>","content_type":"json"}}'`.

Проверка статусов на стороне Codemagic CLI (входящий триггер):

```bash
curl -s -X POST https://api.codemagic.io/builds \
  -H "x-auth-token: $CODEMAGIC_API_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"appId":"'"$CODEMAGIC_APP_ID"'","workflowId":"phoneguard-android","branch":"main"}'
```

## Настройка GitHub Actions

1. Settings → Secrets and variables → Actions: `WEBHOOK_URL` и (по желанию) `WEBHOOK_SECRET`.
2. Job `notify` в `.github/workflows/android-ci.yml` запускается всегда (`if: always()`) и
   отправляет итог по всем job'ам.
3. Если Actions заблокированы на уровне аккаунта, job'ы падают **до** первого шага с сообщением
   «The job was not started because your account is locked due to a billing issue» — это
   GitHub Billing, а не конфигурация; как проверить: `gh api repos/<owner>/<repo>/check-runs/<id>/annotations`.

## Локальная проверка

```bash
# приёмник на 127.0.0.1:8099, проверяет подпись
python3 tools/webhook_listen.py --port 8099 --secret s3cret

# в другом терминале — «как из CI»
WEBHOOK_URL=http://127.0.0.1:8099/hook WEBHOOK_SECRET=s3cret \
  CM_BUILD_ID=demo CM_PROJECT_ID=demo CM_BRANCH=main CM_COMMIT=$(git rev-parse HEAD) \
  python3 tools/webhook_notify.py --status success

# только посмотреть payload, ничего не отправляя
python3 tools/webhook_notify.py --dry-run
```

Ответ приёмника в логе: `webhook_notify: отправлено success (codemagic, попытка 1, HTTP 200)`,
на стороне приёмника — `подпись: верна` и напечатанный JSON.
