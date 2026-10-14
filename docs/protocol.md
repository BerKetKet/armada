# Armada Wire Protocol Specification

Version: 1

The Armada wire protocol defines communication between clients (the Android UI app or the CLI client) and the root daemon. It operates over a stream connection (such as a Unix domain local socket) using line-delimited JSON.

---

## 1. Framing and Transport

- **Transport:** Unix domain stream socket in Linux abstract namespace (`armada.daemon`, `LocalSocketAddress.Namespace.ABSTRACT`). No filesystem node is created.
- **Encoding:** Strict UTF-8. Malformed UTF-8 sequences are rejected as framing errors.
- **Codec:** In-house line-delimited JSON codec implemented in `:proto` (`app.armada.proto.FrameReader`, `FrameWriter`, `JsonParser`, `JsonWriter`).
- **Surrogates:** Encoders reject lone surrogates.
- **Framing:** Line-delimited. Each frame is a single UTF-8 line terminated by `\n` (0x0A) or optional `\r\n` (0x0D 0x0A).
- **Line Contents:** A single compact JSON object. No raw newline characters may appear within a frame.
- **Blank Lines:** Empty or whitespace-only lines are ignored by the reader.
- **Maximum Frame Length:** 1 MiB (1,048,576 bytes). If a frame line exceeds this limit, the reader raises `FrameTooLongException` and the connection must be closed.
- **Stream Termination:** If the stream terminates without a trailing newline while bytes are buffered, `TruncatedFrameException` is raised.

---

## 2. Limits and Validation

- **Maximum Nesting Depth:** 64 levels for objects and arrays. Nesting depth 65 is rejected.
- **Maximum Payload Size:** 1 MiB (1,048,576 bytes) per JSON document.
- **Identifier (`id`):** 1 to 64 ASCII characters matching `^[A-Za-z0-9._-]{1,64}$`.
- **Tool Name (`tool`):** 1 to 64 lowercase alphanumeric ASCII characters matching `^[a-z][a-z0-9_.]{0,63}$`.
- **Extra Fields:** Unknown fields in message objects are ignored to maintain forward compatibility.
- **Numbers:** Integers within the 64-bit signed range `[-9223372036854775808, 9223372036854775807]` decode to `Long`. Floating-point numbers decode to finite `Double`. Leading zeros, leading plus signs (`+1`), leading decimal points (`.5`), trailing decimal points (`1.`), bare minuses (`-`), hex notation, NaN, and Infinity are strictly rejected.

---

## 3. Message Types

Every message on the wire is a JSON object with a required `"type"` string property.

### 3.1 Hello (`hello`)
Exchanged at handshake to negotiate protocol versions.

```json
{"type":"hello","v":1}
```

- `v` (integer, required): Protocol version (must be >= 1).

### 3.2 Request (`request`)
Sent by the client to invoke a tool.

```json
{"type":"request","id":"r1","tool":"ping","args":{"host":"1.1.1.1","count":4}}
```

- `id` (string, required): Request correlation identifier (1..64 chars `[A-Za-z0-9._-]`).
- `tool` (string, required): Target tool name matching `[a-z][a-z0-9_.]*`.
- `args` (object, required): Tool-specific arguments object.

### 3.3 Cancel (`cancel`)
Sent by the client to cancel an ongoing request.

```json
{"type":"cancel","id":"r1"}
```

- `id` (string, required): Identifier of the request to cancel.

### 3.4 Progress (`progress`)
Sent by the daemon to report incremental streaming output before request completion.

```json
{"type":"progress","id":"r1","data":{"seq":1,"rttMs":12.4}}
```

- `id` (string, required): Request correlation identifier.
- `data` (object, required): Incremental progress payload.

### 3.5 Result (`result`)
Sent by the daemon to signal successful terminal completion of a request.

```json
{"type":"result","id":"r1","data":{"sent":4,"received":4}}
```

- `id` (string, required): Request correlation identifier.
- `data` (object, required): Final result payload.

### 3.6 Failure (`error`)
Sent by the daemon to signal terminal failure of a request. The wire type is `"error"`.

```json
{"type":"error","id":"r1","code":"bad_args","message":"count must be 1..100"}
```

- `id` (string, required): Request correlation identifier.
- `code` (string, required): Error code from the closed set defined below.
- `message` (string, required): Human-readable error description.

---

## 4. Error Codes

Error codes form a closed set of string constants:

| Error Code | Meaning |
|---|---|
| `bad_request` | Malformed frame, JSON syntax error, missing required field, wrong field type, or unknown message type. |
| `unknown_tool` | The requested tool name is not registered or supported by the daemon. |
| `bad_args` | Arguments supplied in `args` failed validation (e.g. missing required arg, type mismatch, out of range). |
| `denied` | Permission denied or caller UID unauthorized for the requested operation. |
| `unsupported` | Feature or kernel capability not available on the current device. |
| `failed` | Tool executed but failed during execution (command failure, network error, etc.). |
| `timeout` | Operation timed out before completion. |
| `cancelled` | Request was cancelled by the client via a `cancel` message. |
| `internal` | Unexpected daemon internal error or unhandled failure. |

---

## 5. Lifecycle and Terminal Message Invariants

1. **Exactly One Terminal Message:** Every `request` sent to the daemon produces exactly one terminal message: either a `result` message or an `error` message.
2. **Progress Sequencing:** A daemon may emit zero or more `progress` messages for an `id` while the request is running. Progress messages may only arrive before the terminal message, never after it.
3. **Cancellation:** When a `cancel` message is processed for an in-flight request, the daemon terminates the task and responds with an `error` message with code `"cancelled"`.

---

## 6. Protocol Evolution Rules

- **Compatible Changes (Minor):**
  - Adding optional fields to message objects or data payloads. Parsers ignore unknown fields.
  - Adding new tools or new optional arguments to existing tools.
- **Incompatible Changes (Major):**
  - Removing, renaming, or changing the type or semantics of an existing field.
  - Altering framing delimiters, JSON syntax constraints, or existing error codes.
  - Any incompatible change requires incrementing `Protocol.VERSION`.

---

## 7. Handshake and Connection Errors

- **Daemon Greeting:** Upon establishing a connection, for a root peer the daemon sends `{"type":"hello","v":1}` first. For a peer that is not root, the daemon sends only the denied error (id `"handshake"`) and closes, with no hello. A client which receives an error frame in place of the hello treats it as the handshake outcome. The protocol version stays 1 because nothing is released yet.
- **Client Greeting:** The client must reply with a `hello` message whose `v` equals `Protocol.VERSION`. Otherwise the daemon closes the connection.
- **Connection-Level Errors:** A connection-level error uses `id` `"handshake"`, so it fits the `Failure` shape: `{"type":"error","id":"handshake","code":"<code >","message":"..."}`. The daemon sends it and closes the connection. Error codes used this way are `denied` (for example, when a peer is not root) and `bad_request` (such as invalid handshake framing or protocol violation) only.
- **Duplicate In-Flight Identifier:** If a client sends a second request with an `id` that matches a currently in-flight request, the daemon answers the second request with `{"type":"error","id":"<that id>","code":"bad_request","message":"duplicate in-flight id"}` and leaves the first request running.
- **Cancellation of Non-In-Flight Identifier:** A `cancel` message for an `id` that is not currently in flight is silently ignored. This prevents race conditions where a client cancels an operation that has already completed and whose terminal response has crossed the wire.

---

## 8. Registered Tools and Frame Shapes

### 8.1 Echo Tool (`echo`)

Used for end-to-end verification and latency / cancellation tests.

#### Request Shape
```json
{"type":"request","id":"e1","tool":"echo","args":{"text":"sample text","delayMs":50}}
```
- `text` (string, required): String to echo back (1..4096 characters).
- `delayMs` (integer, optional): Sleep duration in milliseconds before replying (0..10,000 ms, default 0).

#### Progress Shape (emitted only when `delayMs > 0`)
```json
{"type":"progress","id":"e1","data":{"seq":1,"elapsedMs":52}}
```
- `seq` (integer): Progress sequence counter (starts at 1).
- `elapsedMs` (integer): Milliseconds elapsed since the delay began.

#### Result Shape
```json
{"type":"result","id":"e1","data":{"text":"sample text"}}
```
- `text` (string): Exact echoed text.

---

## 9. Command-Line Client Operations

The CLI client (`app.armada.cli.Main` / `armada-cli`) connects to the daemon abstract socket `armada.daemon` and executes commands synchronously from the command line.

### 9.1 `hello`
Verifies daemon connectivity and protocol compatibility.

- **Syntax:** `armada-cli hello`
- **Wire interaction:** Exchanges `hello` handshake frames with `v=1`.
- **Output on stdout:**
  ```json
  {"v":1}
  ```
- **Exit code:** 0 on success, 1 on daemon or connection failure, 2 on syntax error.

### 9.2 `echo`
Tests round-trip request handling and optional delays.

- **Syntax:** `armada-cli echo <text> [--delay-ms N]` (or `--delay-ms=N`)
- **Wire interaction:** Performs handshake, sends `request` frame for tool `"echo"`, awaits terminal `result` frame (ignoring intermediate progress).
- **Output on stdout:**
  ```json
  {"text":"<echoed text>"}
  ```
- **Exit code:** 0 on success, 1 on daemon or connection failure, 2 on invalid arguments or missing text.

