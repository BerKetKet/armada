# Armada Wire Protocol Specification

Version: 1

The Armada wire protocol defines communication between clients (the Android UI app or the CLI client) and the root daemon. It operates over a stream connection (such as a Unix domain local socket) using line-delimited JSON.

---

## 1. Framing and Transport

- **Encoding:** Strict UTF-8. Malformed UTF-8 sequences are rejected as framing errors.
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
