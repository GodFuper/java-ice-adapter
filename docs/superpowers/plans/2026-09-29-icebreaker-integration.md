# План реализации: Интеграция faf-ice-adapter с сервером faf-icebreaker

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Реализовать в `faf-ice-adapter` поддержку прямого взаимодействия с сервером `faf-icebreaker` для получения STUN/TURN серверов, регистрации IP в файрволе Hetzner и обмена WebRTC сигналингом через REST и Server-Sent Events (SSE).

**Architecture:** Внедряется модульный HTTP/SSE-клиент `IcebreakerClient` на стандартном Java 21 `HttpClient` и Jackson, абстрагирующий сигналинг через интерфейс `SignalingProvider`. Сохраняется полная обратная совместимость с классическим JSON-RPC режимом: при передаче CLI-флагов `--icebreaker-url` и `--access-token` адаптер автоматически подключается к `faf-icebreaker`, получает ICE-серверы, регистрирует IP-адреса и обрабатывает WebRTC-сообщения по SSE и REST.

**Tech Stack:** Java 21, `java.net.http.HttpClient`, Jackson, `webrtc-java`, JUnit 5, AssertJ, Mockito, Spotless (Palantir Java Format).

**Spec:** [2026-09-29-icebreaker-integration-design.md](file:///C:/Users/User/Desktop/faf/java-ice-adapter-fork/docs/superpowers/specs/2026-09-29-icebreaker-integration-design.md)

## Global Constraints
- Использовать только Java 21 стандартные библиотеки сетевого ввода-вывода (`java.net.http.HttpClient`, `CompletableFuture`) без привлечения тяжелых сторонних HTTP-фреймворков.
- Строго соблюдать правила форматирования Spotless (`.\gradlew spotlessApply`).
- Избегать FQCN (Fully Qualified Class Names), использовать чистые импорты.
- Не нарушать обратную совместимость с существующими интеграционными тестами и режимом JSON-RPC.

## Review Focus
1. **Обработка разрывов SSE-соединения:** автореконнект с backoff без потери сообщений и без зависания тредов.
2. **Параллельная двухстековая регистрация адресов (IPv4 и IPv6):** отсутствие блокировок и корректная обработка ситуаций, когда у клиента нет IPv6.
3. **Корректность формата `CandidatesMessage`:** строгое соответствие JSON-схеме `faf-icebreaker` (поля `session.type`, `session.sdp`, массив `candidates`).
4. **Детерминированный выбор роли (Glare handling):** пир с меньшим ID всегда инициирует Offer, пир с большим ID отвечает Answer.
5. **Изоляция потоков WebRTC:** сетевые вызовы HTTP/SSE не должны блокировать нативные потоки `webrtc-java`.

---

### Task 1: DTO модели и утилита извлечения HMAC из JWT

**Files:**
- Create: `ice-adapter/src/main/java/com/faforever/iceadapter/icebreaker/dto/SessionTokenRequest.java`
- Create: `ice-adapter/src/main/java/com/faforever/iceadapter/icebreaker/dto/SessionTokenResponse.java`
- Create: `ice-adapter/src/main/java/com/faforever/iceadapter/icebreaker/dto/SessionGameResponse.java`
- Create: `ice-adapter/src/main/java/com/faforever/iceadapter/icebreaker/dto/IceServerDto.java`
- Create: `ice-adapter/src/main/java/com/faforever/iceadapter/icebreaker/dto/EventMessageDto.java`
- Create: `ice-adapter/src/main/java/com/faforever/iceadapter/icebreaker/util/HmacExtractor.java`
- Test: `ice-adapter/src/test/java/com/faforever/iceadapter/icebreaker/util/HmacExtractorTest.java`
- Test: `ice-adapter/src/test/java/com/faforever/iceadapter/icebreaker/dto/EventMessageDtoTest.java`

**Steps:**
- [x] Написать failing test `HmacExtractorTest`: извлечение `claims.ext.hmac` из валидного JWT токена и обработка токенов без HMAC.
- [x] Написать failing test `EventMessageDtoTest`: сериализация/десериализация полиморфных событий `connected`, `candidates`, `peerClosing`.
- [x] Запустить тесты и убедиться в падении (`.\gradlew :ice-adapter:test --tests "com.faforever.iceadapter.icebreaker.*"`).
- [x] Реализовать `HmacExtractor` (декодирование Base64 payload и чтение через Jackson).
- [x] Реализовать все DTO-рекорды с аннотациями Jackson.
- [x] Запустить тесты и убедиться в успешном прохождении.
- [x] Запустить `.\gradlew spotlessApply` и закоммитить изменения.

---

### Task 2: Двусторонний конвертер сообщений `IcebreakerMessageConverter`

**Files:**
- Create: `ice-adapter/src/main/java/com/faforever/iceadapter/icebreaker/IcebreakerMessageConverter.java`
- Test: `ice-adapter/src/test/java/com/faforever/iceadapter/icebreaker/IcebreakerMessageConverterTest.java`

**Steps:**
- [x] Написать failing test `IcebreakerMessageConverterTest`:
  * Конвертация внутреннего `CandidatesMessage` (SDP offer/answer + список `CandidatePacket`) в `EventMessageDto.Candidates`.
  * Обратная конвертация из `EventMessageDto.Candidates` во внутренний `CandidatesMessage`.
- [x] Запустить тесты и подтвердить ошибку.
- [x] Реализовать `IcebreakerMessageConverter`, учитывающий маппинг типов кандидатов (`host`, `srflx`, `relay`, `prflx`) и форматов SDP.
- [x] Запустить тесты и убедиться в успешном прохождении.
- [x] Запустить `.\gradlew spotlessApply` и закоммитить изменения.

---

### Task 3: REST клиент `IcebreakerHttpClient` и регистрация IP-адресов

**Files:**
- Create: `ice-adapter/src/main/java/com/faforever/iceadapter/icebreaker/IcebreakerHttpClient.java`
- Test: `ice-adapter/src/test/java/com/faforever/iceadapter/icebreaker/IcebreakerHttpClientTest.java`

**Steps:**
- [x] Создать тестовый HTTP сервер (mock) на базе `com.sun.net.httpserver.HttpServer` в тесте `IcebreakerHttpClientTest`.
- [x] Написать failing test:
  * Проверка вызова `POST /session/token` с передачей `Bearer <token>` и `X-HMAC: <hmac>`.
  * Проверка вызова `GET /session/game/{gameId}` и парсинга серверов.
  * Проверка параллельных вызовов `POST /session/game/{gameId}/addresses` по IPv4 и IPv6.
  * Проверка `POST /session/game/{gameId}/events` с повторными попытками (retry) при временных 5xx ошибках.
- [x] Реализовать `IcebreakerHttpClient` с асинхронными методами на базе `CompletableFuture` и `HttpClient`.
- [x] Запустить тесты и убедиться в прохождении.
- [x] Запустить `.\gradlew spotlessApply` и закоммитить изменения.

---

### Task 4: SSE-клиент `IcebreakerSseListener` с автореконнектом

**Files:**
- Create: `ice-adapter/src/main/java/com/faforever/iceadapter/icebreaker/IcebreakerSseListener.java`
- Test: `ice-adapter/src/test/java/com/faforever/iceadapter/icebreaker/IcebreakerSseListenerTest.java`

**Steps:**
- [x] Написать failing test `IcebreakerSseListenerTest`:
  * Открытие потока SSE на mock-сервере, отправка эвентов `connected`, `candidates`, `peerClosing` и проверка вызова коллбэков.
  * Имитация обрыва соединения и проверка автоматического переподключения с exponential backoff.
- [x] Реализовать `IcebreakerSseListener` через асинхронный поток `HttpResponse.BodyHandlers.ofLines()`.
- [x] Запустить тесты и убедиться в успешном прохождении.
- [x] Запустить `.\gradlew spotlessApply` и закоммитить изменения.

---

### Task 5: Абстракция `SignalingProvider` и интеграция в `WebRtcSignalingService`

**Files:**
- Create: `ice-adapter/src/main/java/com/faforever/iceadapter/signaling/SignalingProvider.java`
- Create: `ice-adapter/src/main/java/com/faforever/iceadapter/signaling/RpcSignalingProvider.java`
- Create: `ice-adapter/src/main/java/com/faforever/iceadapter/signaling/IcebreakerSignalingProvider.java`
- Modify: `ice-adapter/src/main/java/com/faforever/iceadapter/webrtc/WebRtcSignalingService.java`
- Modify: `ice-adapter/src/main/java/com/faforever/iceadapter/ice/GameSession.java`
- Test: `ice-adapter/src/test/java/com/faforever/iceadapter/signaling/IcebreakerSignalingProviderTest.java`

**Steps:**
- [ ] Написать failing unit-тест `IcebreakerSignalingProviderTest`, проверяющий маршрутизацию сообщений через `IcebreakerHttpClient` и `IcebreakerSseListener`.
- [ ] Выделить интерфейс `SignalingProvider` и имплементировать существующий RPC-механизм в `RpcSignalingProvider`.
- [ ] Реализовать `IcebreakerSignalingProvider`, связывающий SSE-события с вызовом `peer.iceMessageFromRPC(...)` и отправку кандидатов через `IcebreakerHttpClient.sendEvent(...)`.
- [ ] Внедрить детерминированный выбор роли: пир с `localId < remoteId` создает Offer, с `localId > remoteId` отвечает Answer.
- [ ] Запустить тесты и убедиться в прохождении.
- [ ] Запустить `.\gradlew spotlessApply` и закоммитить изменения.

---

### Task 6: CLI флаги и запуск в `IceAdapter.java`

**Files:**
- Modify: `ice-adapter/src/main/java/com/faforever/iceadapter/IceAdapter.java`
- Test: `ice-adapter/src/test/java/com/faforever/iceadapter/IceAdapterArgsTest.java`

**Steps:**
- [ ] Написать failing test `IceAdapterArgsTest` на разбор флагов `--icebreaker-url`, `--access-token`, `--force-turn-relay`.
- [ ] Добавить аннотированные `@Option` поля в класс `IceAdapter`.
- [ ] Добавить ветвление инициализации: если заданы `--icebreaker-url` и `--access-token`, инициализировать и запустить `IcebreakerSignalingProvider`, запросить серверы сессии и зарегистрировать адреса.
- [ ] Запустить тесты CLI аргументов.
- [ ] Запустить `.\gradlew spotlessApply` и закоммитить изменения.

---

### Task 7: End-to-End интеграционный тест работы с icebreaker

**Files:**
- Create: `ice-adapter/src/test/java/com/faforever/iceadapter/icebreaker/IcebreakerIntegrationTest.java`

**Steps:**
- [ ] Написать E2E тест с двумя инстансами `IceAdapter` (или `WebRtcSession`), соединенными через локальный mock `faf-icebreaker`.
- [ ] Проверить полный цикл: авторизация, обмен SDP offer/answer через SSE, сбор кандидатов, установление WebRTC DataChannel соединения между пирами.
- [ ] Проверить graceful shutdown и отправку `peerClosing`.
- [ ] Запустить полный набор тестов проекта: `.\gradlew test`.
- [ ] Запустить `.\gradlew spotlessCheck`.
- [ ] Закоммитить финальные изменения.
