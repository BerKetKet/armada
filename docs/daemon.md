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

### Graceful Shutdown Sequence

When shutdown is triggered, the daemon executes the following sequence in strict order:
1. **Stop Accepting Connections:** Closes the listening abstract socket (`armada.daemon`).
2. **In-Flight Cancellation and Drain:** Cancels active in-flight requests and awaits their completion up to a bounded 5-second timeout (`DaemonLifecycle.SHUTDOWN_TIMEOUT_MS = 5000L`).
3. **Executor Teardown:** Shuts down all thread pools and executors (`sessionExecutor`, worker threads) without leaking threads.
4. **PID File Cleanup:** Deletes `/data/local/tmp/armada/daemon.pid`.
5. **Log Shutdown Record:** Appends a shutdown record (`daemon shutdown: clean exit`) to `/data/local/tmp/armada/daemon.log`.
6. **Exit:** Terminates the process with exit code 0.

---

## 3. Handshake and Security Rules

Communication takes place over the abstract Unix domain socket `armada.daemon`.

1. **Daemon Greeting:** Upon accepting a new client connection, for a root peer the daemon sends `{"type":"hello","v":1}` first. For a peer that is not root, the daemon sends only the denied error (id `"handshake"`) and closes, with no hello. A client which receives an error frame in place of the hello treats it as the handshake outcome. The protocol version stays 1 because nothing is released yet.
2. **Root Credential Enforcement:**
   - The daemon inspects peer credentials using `LocalSocket.getPeerCredentials()`.
   - If `peer.uid != 0`, the daemon immediately sends only the denied handshake error (with no hello) and closes the connection without processing any client frames:
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

### 4.1 Persistence and Process Lifecycle
- **No Persistence Across Reboot:** The daemon runs as a transient background process launched via `app_process` under `su`. It does not survive device reboot. A Magisk module or init service is required for automatic boot persistence (planned for Phase 3).
- **No Auto-Restart on Crash:** There is no supervisor or watchdog automatically reviving the daemon if it crashes or is killed by the kernel OOM killer. If terminated, it remains stopped until re-invoked.

### 4.2 Instance and PID Semantics
- **Single Daemon Instance:** Only one daemon instance may run at any time.
- **Startup Conflict Detection:** On startup, `Main` actively connects to the abstract socket `armada.daemon`. If another live instance responds, the new process aborts immediately with exit code 1.
- **PID File Semantics:** The daemon records its PID in `/data/local/tmp/armada/daemon.pid`. Stale PID files left behind by an unclean kill do not prevent subsequent startup, as liveness is determined by testing socket connectivity rather than PID existence.

### 4.3 Log and PID File Locations
- **PID File:** `/data/local/tmp/armada/daemon.pid`
- **Daemon Log:** `/data/local/tmp/armada/daemon.log`
- Both files reside in `/data/local/tmp/armada`, which is created and managed with root (UID 0) permissions.

### 4.4 Client Authentication and Trust Boundary
- **Root UID Verification Only:** Peer authentication is strictly limited to inspecting `LocalSocket.peerCredentials.uid == 0`.
- **Any Root Process May Connect:** Any process on the device running with UID 0 (root) can connect, negotiate the handshake, and execute daemon tools. The daemon does not enforce cryptographic keys, shared secrets, or client process whitelisting beyond UID 0 verification.
- **Non-Root Rejection:** Non-root peers (including the unprivileged Armada Android app UID) are immediately sent an error frame with code `denied` and disconnected without processing requests.

### 4.5 Operating System Signal Handling on Android
- **Android Runtime Signal Disposition:** Android's `app_process` runtime does not install userspace handlers for termination signals such as `SIGTERM` (signal 15) or `SIGINT` (signal 2).
- **Default Kernel Action:** Under Android ART, delivering raw `SIGTERM` via `kill` or `pkill` triggers the Linux kernel default disposition (`SIG_DFL`), terminating the process immediately before JVM shutdown hooks or `finally` blocks can execute. Clean shutdown sequences require invocation through userspace control or standard JVM shutdown mechanisms.

### 4.6 Wire Protocol and Tool Constraints
- **Frame Length Limit:** 1 MiB (1,048,576 bytes) max frame length. Oversize frames raise `FrameTooLongException` and terminate the session.
- **JSON Nesting Depth:** Maximum nesting depth of 64 levels. Nesting depth 65 is rejected.
- **Echo Tool Limits:**
  - `text`: Required string, max length 4,096 characters (longer text returns `bad_args`).
  - `delayMs`: Optional integer, range 0 to 10,000 milliseconds (values > 10,000 or < 0 return `bad_args`).
- **Lifecycle Guarantee:** Every request produces exactly one terminal message (`result` or `error`). Progress messages are guaranteed never to arrive after the terminal message for that request ID.

