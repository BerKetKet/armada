# ARMADA: outline of the final app

Working codename: **Armada** (rename any time). A free, open-source, root-only Android app that puts a whole network workshop on one phone: measure the WAN, probe it, reshape it, tunnel through it.

This document describes where we are *going*. It will drift as we build. When it drifts, we edit this file in the same commit that changes the plan.

Reference device: Pixel 2 XL (taimen), EvolutionX Android 15, Magisk root.

---

## 1. Ground rules

These are rules, not preferences. Anyone writing code for this project (human or agent) follows them.

1. **Root-only.** The app requires root and says so at first launch. There is no degraded non-root mode. We do not use `su` for something that works without it, but we never spend extra effort or complexity to avoid needing it. Deleting the non-root code path is the whole point of this rule.
2. **Minimal dependencies.** Libraries, remote APIs and Android support components all count. Default answer to "can we add X" is no. Section 6 holds the budget. Adding to it needs a written reason in the commit message.
3. **Self-contained.** What the app needs ships inside the APK. No call-home, no remote API required for a core feature, no account. Optional online features exist only where the tool is meaningless offline (for example "what is my public IP"), are clearly marked, and work with a user-supplied endpoint.
4. **Every change is reversible.** Anything that mutates the system (routes, iptables, MTU, sysctl, APN, tunnels) records its own undo before it applies. There is a global "revert everything" action. Risky changes use a confirm-or-roll-back timer. Boot never applies a change that previously caused a lockout.
5. **Probe, don't assume.** The Pixel 2 XL kernel is old, and other users will have other kernels. On first run the app probes which kernel and system features exist (tun, netem, nftables vs iptables, conntrack, wireguard, raw sockets, eBPF netstats) and greys out tools that cannot work, with the reason shown.
6. **Everything is scriptable.** Any tool the UI can run, a shell can run too (Section 3). The UI is one client of the engine, not the engine.
7. **Honest tools.** Every tool states what it measured, how, and what it cannot tell you. No fake precision.

---

## 2. Tool catalogue

Tags: **[v1]** first public release, **[v2]** soon after, **[v3]** ambitious, **[dream]** only if we get there.

### 2.1 Reachability and path
- Ping: ICMP, TCP-connect, UDP, HTTP(S) modes; multiple targets; live graph; loss, jitter, min/avg/max/stddev **[v1]**
- Traceroute: ICMP, UDP, TCP-SYN modes; per-hop loss and RTT; reverse DNS per hop **[v1]**
- MTR-style continuous traceroute **[v2]**
- Path MTU discovery: DF-bit binary search, per protocol, IPv4 and IPv6; MSS clamping check **[v1]**
- DNS lookup: all record types, choose resolver, raw UDP/TCP, DoT, DoH; compare several resolvers side by side **[v1]**
- DNS health: hijack and poisoning detection (compare answers across resolvers and transports), DNSSEC check, resolver latency ranking **[v2]**
- WHOIS and RDAP **[v2]**
- HTTP probe: redirect chain, header dump, timing breakdown (DNS, connect, TLS, first byte, total) **[v1]**
- TLS inspector: certificate chain, protocol and cipher support, SNI and ALPN behaviour **[v2]**
- IPv6 readiness and dual-stack comparison **[v2]**
- CGNAT detection: compare WAN-interface address, carrier gateway, and public address **[v2]**
- Public IP and ASN info using a user-supplied endpoint **[v2]**

### 2.2 Discovery and scanning
- Host discovery on the LAN: ARP sweep, ICMP sweep, mDNS, SSDP/UPnP, NetBIOS **[v1]**
- Device identification: bundled OUI vendor database, hostname, open-port guess at device type **[v1]**
- Device history: database of devices seen, "new device joined" detection, first and last seen **[v2]**
- Port scan: TCP connect **[v1]**, SYN **[v1]**, UDP **[v2]**, with service names and banner grabbing **[v2]**
- Service and version fingerprinting (own implementation, lightweight) **[v3]**
- Router security check: UPnP exposure, open management ports, default-banner hints **[v3]**
- Wake-on-LAN **[v1]**
- Optional nmap module: user installs the binary; Armada drives it and parses the XML **[v3]**

### 2.3 Quality and throughput
- Throughput test against user-chosen HTTP endpoints, multi-stream **[v1]**
- iperf3 client against user-chosen servers **[v2]**
- Latency under load (bufferbloat grade) **[v2]**
- Packet loss and jitter test **[v2]**
- Long-run monitor: leave it running, log and graph connectivity quality over hours, mark outages **[v2]**
- Per-network history, so "this carrier at midnight" can be compared with "this carrier at noon" **[v3]**

### 2.4 Traffic control (the root payoff)
- Interface info and MTU set per interface **[v1]**
- TCP and IP sysctl tuner: congestion control, buffers, ECN, tcp_mtu_probing, fast open, rp_filter, with named profiles **[v1]**
- iptables/ip6tables rule manager: view, add, remove, named rule sets, apply and revert **[v1]**
- Per-app firewall by UID, with per-interface and per-direction rules **[v2]**
- Policy routing: `ip rule` and `ip route` editor, per-UID and per-interface routing (for example force one app over cellular, another over Wi-Fi) **[v2]**
- DNS override: redirect or replace DNS for the whole device or per app **[v2]**
- TTL / hop limit rewriting (tethering behaviour) **[v2]**
- tc shaping: rate limit, delay, loss, jitter emulation per interface or per app, if the kernel supports netem **[v3]**
- Tethering and hotspot tweaks **[v3]**
- MAC address and hostname change **[v3]**
- IPv6 toggle and per-interface IPv6 behaviour **[v2]**

### 2.5 Cellular and APN
- APN viewer and editor on the telephony provider, with backup and restore **[v2]**
- APN profiles and an A/B test matrix: switch, wait, measure, compare **[v3]**
- Airplane-mode and data-toggle automation **[v2]**
- Radio basics display (leaves deep radio work to Modem Kit, with an intent hook between the apps) **[v3]**

### 2.6 Tunnels and VPN
- Direct root tun device, with policy routing steering selected apps or all traffic into it, no VpnService required **[v2]**
- WireGuard via bundled userspace implementation, or the kernel module if present **[v2]**
- SSH tunnels (local, remote, dynamic SOCKS) with a bundled client **[v2]**
- SOCKS5 and HTTP CONNECT proxy, local and upstream chaining **[v2]**
- Transparent proxying: iptables redirect into a local relay **[v3]**
- Local DoH/DoT resolver that the rest of the device uses **[v3]**
- Shadowsocks-class and Tor-class transports as optional modules **[dream]**
- Kill switch: block all non-tunnel traffic by rule, reverted cleanly **[v2]**

### 2.7 Capture and analysis
- Packet capture with pcap export, bundled tcpdump or own capture helper **[v2]**
- Live connection table mapped to apps (from `/proc/net` and conntrack) **[v1]**
- Per-app data usage from the system's netstats **[v2]**
- DNS query log: which app asked for which name **[v2]**
- Light packet decoder in the UI (Ethernet, IP, TCP, UDP, DNS, TLS handshake) **[v3]**
- Leak finder: what leaves the device outside the tunnel **[v3]**

### 2.8 Automation and glue
- Profiles: named bundles of settings (sysctl + iptables + MTU + DNS + tunnel) applied or reverted as a unit **[v2]**
- Triggers: on network change, SSID, time of day, boot **[v2]**
- Boot persistence through a small Magisk module, so profiles apply without opening the app **[v2]**
- Command-line client (`armada ping 1.1.1.1 --json`) usable from adb, Termux and grimoire spells **[v1]**
- Export and import of everything (profiles, history, logs) as plain files **[v2]**
- Per-tool built-in README with scenarios, shown in the UI **[v1]**

---

## 3. Architecture

Four pieces. The boundaries matter more than the languages.

```
 +-----------------+      local socket       +----------------------+
 |  UI app         | <---------------------> |  Root daemon         |
 |  (unprivileged  |   line-delimited JSON   |  (runs as root)      |
 |   Android app)  |                         |  tool modules,       |
 +-----------------+                         |  state, undo log     |
                                             +----------+-----------+
 +-----------------+      same socket                   | spawns / calls
 |  CLI client     | ---------------------->            v
 |  (adb, Termux)  |                         +----------------------+
 +-----------------+                         |  Native helpers      |
                                             |  (small C binaries)  |
                                             |  raw ICMP/SYN/ARP    |
                                             +----------------------+
```

**Root daemon.** Started once through `su`, via `app_process`, running our own Kotlin code as root (the same trick Shizuku-style tools use). It owns every privileged operation and every tool module. Because it stays running, we do not shell out to `su` per command, which is faster and lets one place enforce the undo log and the safety timers. Runs as root, so it can use raw sockets, change routes, write iptables and read the telephony database directly.

**UI app.** Unprivileged and thin. It starts the daemon if it is not running, sends requests, renders results. If root is denied, it shows an explanation screen and nothing else.

**CLI client.** Same protocol as the UI, so everything is scriptable and testable from a shell. This is also how the grimoire can call the app.

**Native helpers.** The only native code we write: a small C probe engine for what the JVM cannot do (raw ICMP with TTL control, SYN scanning, ARP). It speaks line-delimited JSON on stdout. It builds for both arm64 (phone) and x86_64 (the CachyOS machine), so the probing logic is developed and debugged on the desktop first.

**State.** Plain SQLite through the framework's own API for history and profiles. The undo log is a simple append-only file the daemon replays on revert.

**Privilege boundary.** The daemon accepts connections only from the app's own UID and from root, checked by peer credentials on the socket.

---

## 4. Repo layout (target)

```
armada/
  OUTLINE.md            this file
  GEMINI.md             rules and context for the coding agent
  docs/
    tools/              one README per tool, shipped into the app
    decisions/          short notes: why we chose X
  app/                  UI (unprivileged)
  daemon/               root daemon and tool modules
  proto/                request/response definitions shared by app, daemon, cli
  cli/                  command-line client
  native/               C probe helpers, builds for arm64 and x86_64
  magisk-module/        boot persistence
  scripts/              build, check and release scripts
```

---

## 5. Roadmap

| Phase | Goal | Contents |
|---|---|---|
| 0 | Skeleton | Repo, build, daemon starts through su, UI talks to it, CLI talks to it, capability probe. No tools yet, but the whole pipe works. |
| 1 | First tools | Ping, traceroute, DNS, HTTP probe, MTU discovery, TCP and SYN port scan, LAN discovery, WoL, connection table, sysctl and iptables manager, throughput test |
| 2 | Release 1.0 | Per-tool READMEs, undo log and revert-all, polish, publish |
| 3 | Control | Policy routing, per-app firewall, DNS override, TTL, profiles, triggers, boot module |
| 4 | Tunnels | Root tun, WireGuard, SSH, SOCKS, kill switch |
| 5 | Depth | Capture and decode, APN editor and test matrix, bufferbloat, device history, nmap module |
| 6 | Dreams | Transparent proxying, extra transports, leak finder |

---

## 6. Dependency budget

| Area | Decision |
|---|---|
| Language | Kotlin, standard library only |
| UI | Android framework views, custom-drawn graphs. No AppCompat, no Compose, no Material library (open decision, see Section 8) |
| Concurrency | Plain threads and executors. No coroutines unless the code becomes unreadable without them |
| Database | Framework SQLite. No Room |
| JSON | Framework `org.json` |
| Networking | Framework sockets and `HttpURLConnection`. DNS packet encoder/decoder written in-house |
| Root access | Own small su handling. No libsu |
| Charts | Own Canvas code |
| Native | Own C helpers. Bundled static binaries only for: wireguard-go, SSH client (dropbear class), tcpdump class capture, iperf3. All permissively licensed |
| Remote APIs | None required |
| nmap | Never bundled. Optional, user-installed |

---

## 7. Workflow

- **Claude** designs, decomposes, reviews, and explains. Owns this file, the architecture, and the task briefs.
- **Gemini** (Antigravity) does the token-heavy implementation from a written brief.
- **Gash** runs the loop: carries briefs to Gemini, carries diffs, errors and test output back, runs things on the terminal and the phone, and learns from the explanations along the way.
- Rules for the agent live in `GEMINI.md` in the repo so every session starts with them. The dependency rule is also enforced by a script that fails the build if dependencies change without a note.
- Every change is a real git commit with a real message. No zip files.

**Task brief template** (what Claude hands over for Gemini):

```
Goal: one sentence
Context: what exists, which files
Do: concrete list
Don't: constraints, including the ground rules that matter here
Done when: observable acceptance checks
Verify: exact commands to run
Report back: what to paste to Claude (diff, test output, errors)
```

---

## 8. Open decisions

1. **UI toolkit.** Framework views (zero dependencies, more code, small APK) versus Compose (less code, many androidx dependencies). Leaning framework views because the code-heavy part is exactly what the agent is for.
2. **License.** GPL-3.0 versus Apache-2.0. Matters once we bundle other people's binaries.
3. **Name.** Armada is a placeholder.
4. **Native helper language.** C (smallest, simplest NDK path) versus Rust. Leaning C.
5. **Minimum Android version.** Probably 10 or higher, decided by what the daemon needs.
