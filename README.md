<div align="center">

# FreeTurn

**Android-клиент для [vk-turn-proxy](https://github.com/cacggghp/vk-turn-proxy)** — проброс WireGuard / Hysteria через TURN-серверы VK.

![Android](https://img.shields.io/badge/Android-6.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF?logo=kotlin&logoColor=white)
![Material 3](https://img.shields.io/badge/Material-3-757575?logo=materialdesign&logoColor=white)
![License](https://img.shields.io/badge/license-GPL--3.0-blue)
[![Telegram](https://img.shields.io/badge/Telegram-канал-26A5E4?logo=telegram&logoColor=white)](https://t.me/+5BdkU4q_CGQyNTdi)

<p float="left">
  <img src="docs/screenshots/1.jpg" width="230" />
  <img src="docs/screenshots/2.jpg" width="230" />
</p>

</div>

> **Disclaimer.** Проект предназначен **исключительно для образовательных и исследовательских целей.**

---

## Содержание

1. [Принцип работы](#принцип-работы)
2. [Возможности](#возможности)
3. [Требования](#требования)
4. [Как это работает в связке с VPN](#как-это-работает-в-связке-с-vpn)
5. [Настройка по шагам](#настройка-по-шагам)
6. [Broadcast API](#broadcast-api)
7. [Под капотом](#под-капотом)
8. [Благодарности](#благодарности)
9. [Лицензия](#лицензия)

---

## Принцип работы

Пакеты шифруются DTLS 1.2 (или оборачиваются в VLESS) и отправляются на TURN-сервер по протоколу STUN ChannelData (TCP или UDP). TURN пересылает трафик по UDP на ваш VPS, где он расшифровывается и уходит в WireGuard / Hysteria. Учётные данные TURN генерируются автоматически из ссылки на звонок.

---

## Возможности

| Категория | Что умеет |
|---|---|
| Профили | Несколько именованных конфигов, быстрое переключение |
| Транспорты | TCP, UDP, VLESS (+ опциональный `vless-bond`) |
| Wrap | Обёртка трафика общим 64-hex ключом |
| Управление сервером | Установка, запуск/остановка, генерация wrap-ключа, логи по SSH прямо из приложения |
| Автоустановка | Бинарник на VPS разворачивается из приложения одним нажатием |
| Автообновление | Проверка новых релизов и установка APK без ручного скачивания |
| Watchdog | Автопереподключение при обрыве и смене Wi-Fi / Mobile |
| Шифрование секретов | Пароли, ключи и wrap-key в EncryptedSharedPreferences (Android Keystore) |
| Broadcast API | `START_PROXY` / `STOP_PROXY` для автоматизации |
| Кастомное ядро | Подмена встроенного `libvkturn.so` |

---

## Требования

- Android **6.0+** (API 23)
- ARM64 (`arm64-v8a`)
- VPS с поднятым WireGuard или Hysteria
- Ссылка на звонок VK

---

## Как это работает в связке с VPN

> **FreeTurn — это не VPN.** Туннель он не поднимает.

FreeTurn — транспортный слой: принимает UDP-пакеты на `127.0.0.1:9000` и пробрасывает их через TURN до вашего VPS. Сам трафик создаёт **WireGuard / AmneziaWG**, у которого `Endpoint` указан на этот локальный порт.

**Без WireGuard-клиента, направленного на `127.0.0.1:9000`, трафика не будет.**

---

## Настройка по шагам

> Пример с **AmneziaVPN**. Для чистого WireGuard всё аналогично.

### 1. Установите APK

Берите свежий релиз из [Releases](../../releases).

### 2. Поднимите серверную часть

При первом запуске **онбординг сам предложит** ввести SSH-данные VPS и развернуть сервер. Позже это всегда доступно на экране **Сервер**:

```
Сервер → SSH-данные → [Установить] → [Запустить]
```

Бинарник загрузится на VPS и запустится автоматически.

<details>
<summary>Ручная установка (если SSH-менеджером не пользуетесь)</summary>

```bash
wget https://github.com/cacggghp/vk-turn-proxy/releases/latest/download/server-linux-amd64
chmod +x server-linux-amd64
nohup ./server-linux-amd64 -listen 0.0.0.0:56000 -connect 127.0.0.1:<порт_wg> > server.log 2>&1 &
```

</details>

### 3. Согласуйте порты сервера

На экране **Сервер**:

| Поле | Значение |
|---|---|
| **Listen-порт** | `56000` по умолчанию или любой свободный. **Должен совпадать** с полем *Адрес vk-turn-proxy сервера* на экране **Клиент**. |
| **Адрес TURN-клиента** (`-connect`) | `127.0.0.1:<порт_WireGuard/AmneziaWG>` на VPS. |

### 4. Подготовьте конфиг WireGuard / AmneziaWG

1. В AmneziaVPN добавьте нового пользователя в формате **оригинального WireGuard / AmneziaWG**.
2. Скачайте `.conf` на устройство.
3. Откройте в текстовом редакторе и замените:

   ```diff
   - Endpoint = your.vps.ip:51820
   + Endpoint = 127.0.0.1:9000
   ```

4. Сохраните и **импортируйте обратно** в клиент AmneziaWG.

### 5. Исключите FreeTurn из VPN

В AmneziaWG включите раздельное туннелирование:

> **Режим:** «Приложения из списка не должны работать через VPN»
> **Список:** добавьте **FreeTurn**.

Без этого пакеты самого FreeTurn зациклятся в туннель.

### 6. Настройте клиент FreeTurn

На экране **Клиент**:

| Поле | Значение |
|---|---|
| **Ссылка** | URL VK-звонка |
| **Адрес vk-turn-proxy сервера** | `IP_VPS:<listen-порт сервера>` |
| **Локальный адрес** | `127.0.0.1:9000` (тот же, что `Endpoint` в `.conf`) |

### 7. Запустите прокси

На главном экране FreeTurn нажмите **Запуск**.

### 8. Включите VPN

В AmneziaWG включите подключение. Готово — трафик идёт через TURN.

---

## Режим полного туннеля

В Android-клиенте теперь есть два режима:

- `Локальный прокси`: прежнее поведение. Приложение запускает встроенный `libvkturn.so`, слушает локальный endpoint, а внешний WireGuard/VLESS/Hysteria-клиент использует его вручную.
- `Полный туннель`: приложение запускает `libvkturn.so`, ждёт хотя бы один активный TURN/DTLS-поток, затем поднимает встроенный sing-box/libbox core через `VpnService`.

Full-tunnel backend встроен в APK на этапе сборки как `app/libs/libbox-android-2.1.0.aar`; runtime download ядер не используется. Генерируемая sing-box-конфигурация использует TUN inbound и поддерживает схемы:

- `vless://`
- `hysteria://`
- `hysteria2://`
- `hy2://`

Ссылка должна уже указывать на локальный listener `vk-turn-proxy`, обычно `127.0.0.1:9000`. FreeTurn валидирует ссылку и предупреждает, если host не выглядит локальным, но не переписывает host/SNI/TLS/obfs-параметры. Чтобы не получить цикл маршрутизации, `FullTunnelVpnService` всегда вызывает `builder.addDisallowedApplication(packageName)` и дополнительно отдаёт `VpnService.protect(fd)` в libbox через `autoDetectInterfaceControl`.

---

## VLESS bonding in full tunnel

For `vless://` full-tunnel links FreeTurn can pass `-vless -vless-bond` to the embedded Go client and `--vless --vless-bond` to the SSH server control script. Hysteria/Hysteria2/Hy2 links do not enable `vless-bond`.

`activeConnectionCount >= 1` is only the readiness gate for starting the Android VPN. It is not a one-stream limit: the Go client keeps starting the configured `-n` VLESS bond paths and adds them to the same KCP/smux transport as they connect. FreeTurn 2.6.2 bundles a Go core where `-vless-bond` is packet-level bonding, so one long-lived VLESS/full-tunnel TCP connection can use several TURN/DTLS paths instead of being pinned to one stream.

If the installed server binary does not advertise `-vless-bond`, the SSH control script fails early with:

```text
Installed server binary does not support -vless-bond. Please update server.
```

## Embedded Go core compatibility

FreeTurn 2.6.2 bundles a `libvkturn.so` that accepts all Android wrapper flags emitted by the app: `-streams-per-cred`, `-dns`, `-dns-servers`, `-wrap`, `-wrap-key`, `-gen-wrap-key`, and `-vless-bond`. This prevents saved profiles with DNS, credential-cache, or WRAP settings from crashing the local proxy with Go flag exit code `2`.

The bundled core also sends VK `captchaNotRobot` requests to `api.vk.com`, includes a generated non-empty `adFp` fingerprint, and uses the VK slider step slicing formula for `answer.value`. Without these details VK can return `status=ERROR` on `captchaNotRobot.check` / `captchaNotRobot.getContent` or `status=BOT` after a visually correct slider attempt.

The public Go core currently accepts `-wrap/-wrap-key` as compatibility flags and logs that packet wrapping is not implemented in this build. Use a WRAP-capable server/client core if real packet wrapping is required.

---

## Broadcast API

Управление прокси через `adb` или Tasker:

```bash
# запуск
adb shell am broadcast -a com.freeturn.app.START_PROXY -n com.freeturn.app/.ProxyReceiver

# остановка
adb shell am broadcast -a com.freeturn.app.STOP_PROXY  -n com.freeturn.app/.ProxyReceiver
```

---

## Под капотом

<details>
<summary>Стек технологий</summary>

- **Kotlin** + **Jetpack Compose** + **Material 3**
- **Coroutines / StateFlow** — реактивная архитектура
- **DataStore** — настройки и профили
- **EncryptedSharedPreferences** + **Android Keystore** — секреты
- **JSch** — SSH-клиент
- Нативное ядро на **Go** — `libvkturn.so` (arm64-v8a)

</details>

---

## Благодарности

- [Moroka8/vk-turn-proxy](https://github.com/Moroka8/vk-turn-proxy) — [@Moroka8](https://github.com/Moroka8), форк ядра vk-turn-proxy
- [alexmac6574/vk-turn-proxy](https://github.com/alexmac6574/vk-turn-proxy) — [@alexmac6574](https://github.com/alexmac6574), форк ядра vk-turn-proxy
- [cacggghp/vk-turn-proxy](https://github.com/cacggghp/vk-turn-proxy) — [@cacggghp](https://github.com/cacggghp), оригинальное vk-turn-proxy
- [MYSOREZ/vk-turn-proxy-android](https://github.com/MYSOREZ/vk-turn-proxy-android) — [@MYSOREZ](https://github.com/MYSOREZ), оригинальный Android-клиент

---

## Лицензия

[**GPL-3.0**](LICENSE)
