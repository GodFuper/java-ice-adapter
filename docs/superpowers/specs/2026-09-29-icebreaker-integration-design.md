# Design Document: faf-ice-adapter Integration with faf-icebreaker Server

## 1. Context and Goals

### 1.1. Context
Historically, `faf-ice-adapter` (Java) interacts with the FAF infrastructure through an intermediate client (FAF Client) via the local JSON-RPC 2.0 protocol over TCP. The exchange of SDP offers, answers, and ICE candidates between peers is relayed through the Python FAF lobby server.

The new FAForever backend — **`faf-icebreaker`** (Quarkus/Kotlin) — moves signaling to a dedicated service (REST API + Server-Sent Events) and centrally manages access to TURN servers and dynamic Hetzner Cloud Firewall whitelisting.

The new Go adapter **`faf-pioneer`** already works directly with `faf-icebreaker`. The goal is to provide full support for `faf-ice-adapter` to operate with `faf-icebreaker`.

### 1.2. Goals
1. Implement a direct client for `faf-icebreaker` in `faf-ice-adapter` using REST and SSE protocols.
2. Support retrieving a session token, the list of ICE/TURN servers, and automatic dual-stack (IPv4/IPv6) client IP address registration for the Hetzner firewall.
3. Enable bidirectional WebRTC message exchange (`CandidatesMessage`, `ConnectedMessage`, `PeerClosingMessage`) via `faf-icebreaker`.
4. Maintain full backward compatibility: if `--icebreaker-url` and `--access-token` are not provided, the adapter operates in classic mode (JSON-RPC from FAF Client).

---

## 2. Solution Architecture

```mermaid
flowchart TD
    subgraph FAF_Infrastructure["FAF Infrastructure"]
        IB["faf-icebreaker (REST / SSE)"]
        HFW["Hetzner Cloud Firewall"]
        TURN["TURN/STUN Servers (Coturn / Cloudflare / Xirsys)"]
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

## 3. Components and Responsibilities

### 3.1. Command-Line Arguments (`IceAdapter.java`)
Add new options to Picocli:
* `--icebreaker-url`: Base URL of the icebreaker service (e.g., `https://api.faforever.com/ice`).
* `--access-token`: Player's JWT access token (contains the `ext.hmac` claim).
* `--force-turn-relay`: Flag to force routing through TURN (`ICETransportPolicy.RELAY`).

### 3.2. Icebreaker Client (`com.faforever.iceadapter.icebreaker`)
Package includes:
* **`IcebreakerClient`**:
  * Extract HMAC signature from JWT token (`extractHmac(accessToken)`) and add `X-HMAC: <hmac>` header.
  * `fetchSessionToken(long gameId)`: exchange access token for session token via `POST /session/token`.
  * `fetchGameSession(long gameId)`: retrieve list of TURN/STUN servers and `forceRelay` parameter via `GET /session/game/{gameId}`.
  * `registerAddresses(long gameId)`: parallel POST requests to `/session/game/{gameId}/addresses` over IPv4 and IPv6 for Hetzner Firewall whitelisting.
  * `sendEvent(long gameId, EventMessage message)`: send candidate messages and peer closing notifications via `POST /session/game/{gameId}/events` with exponential retry.
* **`IcebreakerSseListener`**:
  * Read SSE stream `GET /session/game/{gameId}/events` using `HttpClient` and `HttpResponse.BodyHandlers.ofLines()`.
  * Parse `event:` and `data:` lines, deserialize events using Jackson.
  * Automatic reconnection on connection drop with exponential backoff (1s .. 30s).
* **Data Models (`dto`)**:
  * `SessionTokenRequest`, `SessionTokenResponse`.
  * `SessionGameResponse`, `IceServerDto`.
  * `EventMessage` (interface/record) with types: `connected`, `candidates`, `peerClosing`.

### 3.3. WebRTC Message Conversion (`IcebreakerMessageConverter`)
* The internal `CandidatesMessage` in `faf-ice-adapter` contains:
  `int srcId, int destId, String password, String ufrag, List<CandidatePacket> candidates`.
* In `faf-icebreaker`, the `candidates` message structure is:
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
* The converter transforms the SDP offer/answer and `webrtc-java` candidate list into the `session` and `candidates` structure and vice versa.

### 3.4. Signaling Abstraction (`SignalingProvider`)
Introduce a signaling interface:
```java
public interface SignalingProvider {
    void init();
    void sendSignalingMessage(CandidatesMessage message);
    void close();
}
```
* **`RpcSignalingProvider`**: current logic via `RPCService.onIceMsg(...)` and RPC `iceMsg`.
* **`IcebreakerSignalingProvider`**: logic via `IcebreakerClient` (POST) and `IcebreakerSseListener` (SSE).

### 3.5. Role Determination (Offerer / Answerer)
Similar to `faf-pioneer`, the offerer role is determined deterministically:
* If `localId < remoteId`: local peer creates the Offer (`isOfferer = true`).
* If `localId > remoteId`: local peer waits for the Offer and responds with an Answer (`isOfferer = false`).

---

## 4. Testing and Verification

1. **Unit Tests**:
   * HMAC extraction from JWT.
   * Serialization and deserialization of DTOs and `EventMessage`.
   * `IcebreakerMessageConverter` tests (correctness of SDP offer/answer assembly and candidate field mapping).
2. **Integration Tests with `MockIcebreakerServer`**:
   * Lightweight mock HTTP server based on `com.sun.net.httpserver.HttpServer`.
   * Verify lifecycle: token handshake, server list retrieval, IP registration, receiving and sending messages via SSE and REST.
   * Verify SSE resilience to connection drops (reconnect).
