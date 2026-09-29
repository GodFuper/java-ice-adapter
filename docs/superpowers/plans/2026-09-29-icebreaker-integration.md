# Implementation Plan: faf-ice-adapter Integration with faf-icebreaker Server

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement direct interaction support between `faf-ice-adapter` and the `faf-icebreaker` server to retrieve STUN/TURN servers, register client IP addresses in Hetzner Firewall, and exchange WebRTC signaling via REST and Server-Sent Events (SSE).

**Architecture:** Introduce a modular HTTP/SSE client `IcebreakerClient` using standard Java 21 `HttpClient` and Jackson, abstracting signaling via the `SignalingProvider` interface. Maintain full backward compatibility with the classic JSON-RPC mode: when `--icebreaker-url` and `--access-token` CLI flags are passed, the adapter automatically connects to `faf-icebreaker`, retrieves ICE servers, registers IP addresses, and processes WebRTC messages over SSE and REST.

**Tech Stack:** Java 21, `java.net.http.HttpClient`, Jackson, `webrtc-java`, JUnit 5, AssertJ, Mockito, Spotless (Palantir Java Format).

**Spec:** [2026-09-29-icebreaker-integration-design.md](../specs/2026-09-29-icebreaker-integration-design.md)

## Global Constraints
- Use only standard Java 21 networking I/O libraries (`java.net.http.HttpClient`, `CompletableFuture`) without introducing heavy external HTTP frameworks.
- Strictly adhere to Spotless formatting rules (`.\gradlew spotlessApply`).
- Avoid FQCN (Fully Qualified Class Names), use clean imports.
- Do not break backward compatibility with existing integration tests and JSON-RPC mode.

## Review Focus
1. **Handling SSE Connection Drops:** automatic reconnect with backoff without message loss or thread starvation.
2. **Parallel Dual-Stack Address Registration (IPv4 and IPv6):** no deadlocks/blocking, gracefully handle environments where client has no IPv6 connectivity.
3. **`CandidatesMessage` Format Correctness:** strict compliance with `faf-icebreaker` JSON schema (`session.type`, `session.sdp` fields, `candidates` array).
4. **Deterministic Role Selection (Glare handling):** peer with lower ID always initiates Offer, peer with higher ID responds with Answer.
5. **WebRTC Thread Isolation:** HTTP/SSE network calls must not block native `webrtc-java` threads.

---

### Task 1: DTO Models and JWT HMAC Extraction Utility

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
- [x] Write failing test `HmacExtractorTest`: extract `claims.ext.hmac` from a valid JWT token and handle tokens without HMAC.
- [x] Write failing test `EventMessageDtoTest`: serialization/deserialization of polymorphic events `connected`, `candidates`, `peerClosing`.
- [x] Run tests and verify failure (`.\gradlew :ice-adapter:test --tests "com.faforever.iceadapter.icebreaker.*"`).
- [x] Implement `HmacExtractor` (decode Base64 payload and parse via Jackson).
- [x] Implement all DTO records with Jackson annotations.
- [x] Run tests and verify they pass.
- [x] Run `.\gradlew spotlessApply` and commit changes.

---

### Task 2: Bidirectional Message Converter `IcebreakerMessageConverter`

**Files:**
- Create: `ice-adapter/src/main/java/com/faforever/iceadapter/icebreaker/IcebreakerMessageConverter.java`
- Test: `ice-adapter/src/test/java/com/faforever/iceadapter/icebreaker/IcebreakerMessageConverterTest.java`

**Steps:**
- [x] Write failing test `IcebreakerMessageConverterTest`:
  * Conversion of internal `CandidatesMessage` (SDP offer/answer + list of `CandidatePacket`) to `EventMessageDto.Candidates`.
  * Reverse conversion from `EventMessageDto.Candidates` to internal `CandidatesMessage`.
- [x] Run tests and confirm failure.
- [x] Implement `IcebreakerMessageConverter`, handling candidate type mapping (`host`, `srflx`, `relay`, `prflx`) and SDP formats.
- [x] Run tests and verify they pass.
- [x] Run `.\gradlew spotlessApply` and commit changes.

---

### Task 3: REST Client `IcebreakerHttpClient` and IP Address Registration

**Files:**
- Create: `ice-adapter/src/main/java/com/faforever/iceadapter/icebreaker/IcebreakerHttpClient.java`
- Test: `ice-adapter/src/test/java/com/faforever/iceadapter/icebreaker/IcebreakerHttpClientTest.java`

**Steps:**
- [x] Create mock HTTP server based on `com.sun.net.httpserver.HttpServer` in `IcebreakerHttpClientTest`.
- [x] Write failing test:
  * Verify `POST /session/token` request with `Bearer <token>` and `X-HMAC: <hmac>`.
  * Verify `GET /session/game/{gameId}` request and server list parsing.
  * Verify parallel `POST /session/game/{gameId}/addresses` calls over IPv4 and IPv6.
  * Verify `POST /session/game/{gameId}/events` with retries on transient 5xx errors.
- [x] Implement `IcebreakerHttpClient` with asynchronous methods using `CompletableFuture` and `HttpClient`.
- [x] Run tests and verify they pass.
- [x] Run `.\gradlew spotlessApply` and commit changes.

---

### Task 4: SSE Client `IcebreakerSseListener` with Auto-Reconnect

**Files:**
- Create: `ice-adapter/src/main/java/com/faforever/iceadapter/icebreaker/IcebreakerSseListener.java`
- Test: `ice-adapter/src/test/java/com/faforever/iceadapter/icebreaker/IcebreakerSseListenerTest.java`

**Steps:**
- [x] Write failing test `IcebreakerSseListenerTest`:
  * Open SSE stream on mock server, emit `connected`, `candidates`, `peerClosing` events, and verify callback invocation.
  * Simulate connection drop and verify automatic reconnection with exponential backoff.
- [x] Implement `IcebreakerSseListener` using asynchronous stream `HttpResponse.BodyHandlers.ofLines()`.
- [x] Run tests and verify they pass.
- [x] Run `.\gradlew spotlessApply` and commit changes.

---

### Task 5: `SignalingProvider` Abstraction and Integration into `WebRtcSignalingService`

**Files:**
- Create: `ice-adapter/src/main/java/com/faforever/iceadapter/signaling/SignalingProvider.java`
- Create: `ice-adapter/src/main/java/com/faforever/iceadapter/signaling/RpcSignalingProvider.java`
- Create: `ice-adapter/src/main/java/com/faforever/iceadapter/signaling/IcebreakerSignalingProvider.java`
- Modify: `ice-adapter/src/main/java/com/faforever/iceadapter/webrtc/WebRtcSignalingService.java`
- Modify: `ice-adapter/src/main/java/com/faforever/iceadapter/ice/GameSession.java`
- Test: `ice-adapter/src/test/java/com/faforever/iceadapter/signaling/IcebreakerSignalingProviderTest.java`

**Steps:**
- [x] Write failing unit test `IcebreakerSignalingProviderTest`, verifying message routing via `IcebreakerHttpClient` and `IcebreakerSseListener`.
- [x] Extract `SignalingProvider` interface and implement existing RPC mechanism in `RpcSignalingProvider`.
- [x] Implement `IcebreakerSignalingProvider`, connecting SSE events to `peer.iceMessageFromRPC(...)` and candidates dispatch via `IcebreakerHttpClient.sendEvent(...)`.
- [x] Implement deterministic role selection: peer with `localId < remoteId` creates Offer, peer with `localId > remoteId` responds with Answer.
- [x] Run tests and verify they pass.
- [x] Run `.\gradlew spotlessApply` and commit changes.

---

### Task 6: CLI Flags and Startup in `IceAdapter.java`

**Files:**
- Modify: `ice-adapter/src/main/java/com/faforever/iceadapter/IceAdapter.java`
- Test: `ice-adapter/src/test/java/com/faforever/iceadapter/IceAdapterArgsTest.java`

**Steps:**
- [x] Write failing test `IceAdapterArgsTest` for parsing `--icebreaker-url`, `--access-token`, `--force-turn-relay` flags.
- [x] Add `@Option`-annotated fields to `IceAdapter` class.
- [x] Add startup branching: if `--icebreaker-url` and `--access-token` are specified, initialize and launch `IcebreakerSignalingProvider`, request session servers, and register addresses.
- [x] Run CLI argument tests.
- [x] Run `.\gradlew spotlessApply` and commit changes.

---

### Task 7: End-to-End Integration Test for Icebreaker Operation

**Files:**
- Create: `ice-adapter/src/test/java/com/faforever/iceadapter/icebreaker/IcebreakerIntegrationTest.java`

**Steps:**
- [x] Write E2E test with two `IceAdapter` (or `WebRtcSession`) instances connected via local mock `faf-icebreaker`.
- [x] Verify full lifecycle: authorization, SDP offer/answer exchange via SSE, candidate gathering, WebRTC DataChannel connection establishment between peers.
- [x] Verify graceful shutdown and sending `peerClosing`.
- [x] Run full project test suite: `.\gradlew test`.
- [x] Run `.\gradlew spotlessCheck`.
- [x] Commit final changes.
