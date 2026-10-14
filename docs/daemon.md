# Armada Root Daemon

The Armada root daemon runs as a privileged process (effective UID 0) on Android, providing background network services and tools over a line-delimited JSON Unix domain stream socket in the abstract namespace (`armada.daemon`).

---

## 1. Starting the Daemon

The daemon is packaged inside the Armada APK (`io.github.berketket.armada`) and executed using Android's `app_process` runtime under root (`su`).

### Exact Start Command

```sh
su -c 'CLASSPATH=$(pm path io.github.berketket.armada | cut -d: -f2) app_process /system/bin app.armada.daemon.Main'
```

*Note: Verified in codebase; marked unverified on device until confirmed on physical hardware by the human.*

### Prerequisites and Startup Checks
1. **Root Verification:** On startup, `Main` checks its effective UID using `android.system.Os.getuid()`. If `uid != 0`, the process terminates immediately with exit code 1.
2. **Socket Conflict Check:** Before binding, `Main` probes whether another live daemon already holds the abstract socket `armada.daemon`. If connection succeeds, the new process aborts with exit code 1.
3. **PID File:** The process writes its PID to `/data/local/tmp/armada/daemon.pid`.

---

## 2. Stopping the Daemon

To stop a running daemon:

```sh
su -c 'kill $(cat /data/local/tmp/armada/daemon.pid)'
```

Alternatively:

```sh
su -c 'pkill -f app.armada.daemon.Main'
```

On receiving `SIGTERM` or `SIGINT`, the Java runtime shutdown hook deletes `/data/local/tmp/armada/daemon.pid`. Sockets and active sessions are closed.

---

## 3. Handshake and Security Rules

Communication takes place over the abstract Unix domain socket `armada.daemon`.

1. **Daemon Greeting:** Upon accepting a new client connection, the daemon sends `{"type":"hello","v":1}`.
2. **Root Credential Enforcement:**
   - The daemon inspects peer credentials using `LocalSocket.getPeerCredentials()`.
   - If `peer.uid != 0`, the daemon immediately sends a handshake error and closes the connection without processing any client frames:
     ```json
     {"type":"error","id":"handshake","code":"denied","message":"peer is not root"}
     ```
3. **Client Handshake Reply:**
   - A root client must reply with `{"type":"hello","v":1}` matching `Protocol.VERSION`.
   - If the client sends an invalid greeting, unexpected message type, or mismatched version `v`, the daemon sends an error with `id: "handshake"` and closes the connection:
     ```json
     {"type":"error","id":"handshake","code":"bad_request","message":"handshake failed: expected hello with v=1"}
     ```
4. **Error ID Extraction Rule:**
   - When decoding incoming frames after the handshake, if a frame fails parsing or decoding, the daemon extracts the `"id"` string field from the raw JSON object if present.
   - If a valid `id` is extracted, the failure response uses that `id`.
   - If the frame is malformed JSON, not an object, or contains no extractable `id`, the error response uses `id: "handshake"`.
5. **Duplicate In-Flight IDs:**
   - If a request arrives with an `id` matching a currently in-flight request, the daemon rejects the second request with code `bad_request` (`duplicate in-flight id`) and leaves the first request running.
6. **Cancellation Semantics:**
   - Sending `{"type":"cancel","id":"<id>"}` interrupts the worker thread running that request. The daemon emits a terminal error message with code `cancelled`.
   - Cancelling an `id` that is not currently in flight is silently ignored to prevent race conditions when a request has already completed.

---

## 4. Known Limits and Invariants

1. **Frame Length Limit:** 1 MiB (1,048,576 bytes) max frame length. Oversize frames raise `FrameTooLongException` and terminate the session.
2. **JSON Nesting Depth:** Maximum nesting depth of 64 levels. Nesting depth 65 is rejected.
3. **Echo Tool Limits:**
   - `text`: Required string, max length 4,096 characters (longer text returns `bad_args`).
   - `delayMs`: Optional integer, range 0 to 10,000 milliseconds (values > 10,000 or < 0 return `bad_args`).
4. **Lifecycle Guarantee:** Every request produces exactly one terminal message (`result` or `error`). Progress messages are guaranteed never to arrive after the terminal message for that request ID.
