# Дизайн-документ: Интеграция faf-ice-adapter с сервером faf-icebreaker

## 1. Контекст и цели

### 1.1. Контекст
Исторически `faf-ice-adapter` (Java) взаимодействует с инфраструктурой FAF через промежуточный клиент (FAF Client) посредством локального протокола JSON-RPC 2.0 по TCP. Обмен SDP-офферами, ответами и ICE-кандидатами между пирами транслируется через Python лобби-сервер FAF.

Новый бэкенд FAForever — **`faf-icebreaker`** (Quarkus/Kotlin) — переносит сигналинг в выделенный сервис (REST API + Server-Sent Events) и централизованно управляет доступом к TURN-серверам и динамическим whitelist Hetzner Cloud Firewall.

Новый Go-адаптер **`faf-pioneer`** уже работает напрямую с `faf-icebreaker`. Задача — обеспечить полноценную поддержку работы `faf-ice-adapter` с `faf-icebreaker`.

### 1.2. Цели
1. Реализовать в `faf-ice-adapter` прямой клиент к `faf-icebreaker` по протоколам REST и SSE.
2. Поддержать получение сессионного токена, списка ICE/TURN-серверов и автоматическую двухстековую (IPv4/IPv6) регистрацию IP-адресов клиента для файрвола Hetzner.
3. Обеспечить двусторонний обмен WebRTC-сообщениями (`CandidatesMessage`, `ConnectedMessage`, `PeerClosingMessage`) через `faf-icebreaker`.
4. Сохранить полную обратную совместимость: если параметры `--icebreaker-url` и `--access-token` не переданы, адаптер функционирует в классическом режиме (JSON-RPC от FAF Client).

---

## 2. Архитектура решения

```mermaid
flowchart TD
    subgraph FAF_Infrastructure["Инфраструктура FAF"]
        IB["faf-icebreaker (REST / SSE)"]
        HFW["Hetzner Cloud Firewall"]
        TURN["TURN/STUN Серверы (Coturn / Cloudflare / Xirsys)"]
        IB -->|Rules sync| HFW
        HFW -->|Traffic filter| TURN
    end

    subgraph JavaIceAdapter["faf-ice-adapter (Java 21)"]
        CLI["Picocli CLI Args (--icebreaker-url, --access-token)"]
        IBC["IcebreakerClient (java.net.http.HttpClient)"]
        SSE["IcebreakerSseListener (SSE stream reader)"]
        Conv["IcebreakerMessageConverter"]
        SM["SignalingManager (SignalingProvider)"]
        WebRTC["WebRtcSession / WebRtcSignalingService"]
        Peers["GameSession / PeerManager"]
        GPGNet["GPGNetServer"]
    end

    CLI --> IBC
    IBC -->|POST /session/token| IB
    IBC -->|GET /session/game/{id}| IB
    IBC -->|POST /session/game/{id}/addresses| IB
    IBC -->|POST /session/game/{id}/events| IB
    IB -->|SSE: /session/game/{id}/events| SSE
    SSE --> Conv
    Conv --> SM
    SM --> WebRTC
    Peers <--> WebRTC
    GPGNet <--> Peers
    WebRTC -->|P2P WebRTC DataChannel| TURN
```

---

## 3. Компоненты и ответственность

### 3.1. Параметры командной строки (`IceAdapter.java`)
Добавляются новые опции в Picocli:
* `--icebreaker-url`: Базовый URL сервиса icebreaker (например, `https://api.faforever.com/ice`).
* `--access-token`: JWT access token игрока (содержит claim `ext.hmac`).
* `--force-turn-relay`: Флаг принудительной маршрутизации через TURN (`ICETransportPolicy.RELAY`).

### 3.2. Клиент icebreaker (`com.faforever.iceadapter.icebreaker`)
Пакет включает:
* **`IcebreakerClient`**:
  * Извлечение HMAC-подписи из JWT токена (`extractHmac(accessToken)`) и добавление заголовка `X-HMAC: <hmac>`.
  * `fetchSessionToken(long gameId)`: обмен access-токена на session-токен через `POST /session/token`.
  * `fetchGameSession(long gameId)`: получение списка TURN/STUN серверов и параметра `forceRelay` через `GET /session/game/{gameId}`.
  * `registerAddresses(long gameId)`: параллельные POST-запросы на `/session/game/{gameId}/addresses` по IPv4 и IPv6 для белого списка Hetzner Firewall.
  * `sendEvent(long gameId, EventMessage message)`: отправка сообщений кандидатов и выхода пира через `POST /session/game/{gameId}/events` с экспоненциальным retry.
* **`IcebreakerSseListener`**:
  * Чтение SSE-потока `GET /session/game/{gameId}/events` с использованием `HttpClient` и `HttpResponse.BodyHandlers.ofLines()`.
  * Разбор строк `event:` и `data:`, парсинг событий в Jackson.
  * Автоматический реконнект при обрыве соединения с exponential backoff (1s .. 30s).
* **Модели данных (`dto`)**:
  * `SessionTokenRequest`, `SessionTokenResponse`.
  * `SessionGameResponse`, `IceServerDto`.
  * `EventMessage` (интерфейс/record) с типами: `connected`, `candidates`, `peerClosing`.

### 3.3. Преобразование WebRTC сообщений (`IcebreakerMessageConverter`)
* Внутренний `CandidatesMessage` в `faf-ice-adapter` содержит:
  `int srcId, int destId, String password, String ufrag, List<CandidatePacket> candidates`.
* В `faf-icebreaker` сообщение `candidates` имеет вид:
  ```json
  {
    "eventType": "candidates",
    "gameId": 100,
    "senderId": 1,
    "recipientId": 2,
    "session": { "type": "offer", "sdp": "..." },
    "candidates": [
      {
        "foundation": "1",
        "priority": 2122260223,
        "address": "192.168.1.100",
        "protocol": "udp",
        "port": 54321,
        "type": "host",
        "component": 1
      }
    ]
  }
  ```
* Конвертер преобразует SDP offer/answer и список кандидатов `webrtc-java` в структуру `session` и `candidates` и обратно.

### 3.4. Абстракция сигналинга (`SignalingProvider`)
Вводится интерфейс сигналинга:
```java
public interface SignalingProvider {
    void init();
    void sendSignalingMessage(CandidatesMessage message);
    void close();
}
```
* **`RpcSignalingProvider`**: текущая логика через `RPCService.onIceMsg(...)` и RPC `iceMsg`.
* **`IcebreakerSignalingProvider`**: логика через `IcebreakerClient` (POST) и `IcebreakerSseListener` (SSE).

### 3.5. Определение ролей (Offerer / Answerer)
Аналогично `faf-pioneer`, роль офферера определяется детерминированно:
* Если `localId < remoteId`: локальный пир создает Offer (`isOfferer = true`).
* Если `localId > remoteId`: локальный пир ожидает Offer и отвечает Answer (`isOfferer = false`).

---

## 4. Тестирование и верификация

1. **Модульные тесты**:
   * Тестирование извлечения HMAC из JWT.
   * Сериализация и десериализация DTO и сообщений `EventMessage`.
   * Тесты конвертера `IcebreakerMessageConverter` (корректность сборки SDP offer/answer и маппинга полей кандидатов).
2. **Интеграционные тесты с `MockIcebreakerServer`**:
   * Легковесный mock HTTP-сервер на базе `com.sun.net.httpserver.HttpServer`.
   * Проверка жизненного цикла: handshake токена, получение серверов, регистрация IP, получение и отправка сообщений через SSE и REST.
   * Проверка устойчивости SSE к разрывам соединения (reconnect).
