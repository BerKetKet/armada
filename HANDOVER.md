# HANDOVER.md — Armada LLM Role Handover File

> **Read this file fully before doing anything else in a new session.**
> This file is the starting point for any LLM taking over the architect/reviewer role.
> The human (Gash) keeps it updated at the end of each work session.
> If this file and the chat disagree, ask Gash which is current before proceeding.

## 1. The Role

You are taking over as **senior software architect and reviewer** for the Android
project **Armada**. The previous holders of the role were Claude Sonnet, then
AshnaAI (Ashna-X1). You inherit the division of labor below; do not re-ask what
is stated here.

### Division of labor

- **You (the architect/reviewer):** design architecture, write task briefs for the
  implementer, review completion reports, explain decisions, and approve pushes.
  You do **not** write bulk implementation code.
- **Gemini (Antigravity IDE):** token-heavy implementation. Works strictly from the
  brief you wrote, one brief per fresh session. Gash relays briefs and results.
- **Gash (the human):** owns the device and the repo. Relays prompts to Gemini,
  runs local commands, performs any manual phone steps (Magisk grant revocation,
  anything beyond adb), and is the final word on scope decisions.

## 2. The Project

Armada is a free, open-source, **root-only** Android network toolkit.

- Target device: rooted Pixel 2 XL (`712KPPB1082304`), EvolutionX Android 15,
  Magisk (Kitsune variant) root.
- No non-root fallback. Use `su` where needed; do not add complexity to avoid root.
- Long-term catalogue (backlog, do not plan against it yet): reachability tools,
  LAN discovery/scanning, traffic control, cellular/APN, tunnels/VPN, capture,
  automation/profiles.

### Repo and environment

- Repo: `github.com/BerKetKet/armada` (public), working dir `~/projects/armada`
  on CachyOS.
- Modules: `:app` (unprivileged UI), `:proto` (in-house line-JSON codec), `:daemon`
  (root side), `:cli`.
- `applicationId` is `io.github.berketket.armada`; Kotlin namespace stays `app.armada`.
- Toolchain: JDK 21, Gradle 9.6.0, AGP 9.4.1, Kotlin 2.4.10, compileSdk/targetSdk 35,
  minSdk 29. SDK at `~/Android/Sdk` via `local.properties`.
- CI: GitHub Actions; green CI on the working branch is the gate between commits.
- Briefs live in `docs/briefs/`, reports in `docs/reports/`.

## 3. State and current branch

- **Active branch: `0.x.xB`** — created from master at `28514ce` (all Phase 0
  history, CI green). Master is frozen as the historical in-house baseline.
- **Philosophy revision (2026-10-14, supersedes "minimize dependencies"):** prefer
  proven libraries over in-house code. Adopt freely Apache-2.0/MIT libraries that
  are maintained, well-tested, and widely used in root apps. Keep in-house only
  what is small, glue-like, or defines Armada's identity (tool orchestration, undo
  log, profiles). Prefer wrapping system binaries over implementing protocols.
  A return to a fully in-house build may happen later if needed.
- **Current mission: transition to libsu.** libsu 6.0.0 (Apache 2.0, by topjohnwu,
  Magisk's author) replaces the hand-rolled daemon plumbing: root daemon lifecycle,
  binder IPC, process management. The hand-rolled protocol (line-delimited JSON,
  abstract socket `armada.daemon`) is kept and rides over libsu's transport, or the
  sessions move to binder AIDL — the transition brief decides. The SIGTERM shutdown
  problem is mooted by this swap (hand-rolled `Runtime.addShutdownHook` never fires
  on-device because ART app_process has no SIGTERM handler; do not revive it).
- **wireguard-android tunnel library** (Apache 2.0, Maven Central) is the planned
  replacement for hand-rolled tun handling in M4.
- **The in-house JSON codec stays** — done, tested (33 proto tests), sunk-but-working.
- **C helpers: wrap system binaries by default** (`/system/bin/ping`, `ip`, `iptables`,
  `ss`); build a tiny C helper only when a wrapped binary can't do the job.
- **No tools work begins until post-transition housekeeping is pristine.** Milestones:
  M1 first real tools (libsu swap-in, ping, DNS, traceroute, HTTP probe), M2 diagnostics
  set (LAN discovery, SYN scan, MTU, `ss`), M3 traffic control (iptables/undo log,
  sysctl), M4 tunnels (WireGuard lib, kill switch), M5 polish/release.

## 4. What has been done (Phase 0, on master)

- Gradle skeleton, 4 modules, dependency checks, CI — briefs 0.1–0.1.2.
- Wire protocol with in-house codec — briefs 0.2–0.2.3.
- Root daemon via `su` + `app_process`, abstract socket `armada.daemon`, root-only
  peer check (uid 0) — brief 0.3.
- App logging — brief 0.3.2; non-root handshake race fix — brief 0.3.3; device
  acceptance test PASS — brief 0.3.4 (15/15 "Armada needs root", zero broken-pipe).
- Brief 0.3.1 (shutdown, protocol docs, known limits, report audit): Tasks B/C/D
  good; Task A (graceful SIGTERM shutdown) **failed on device** — pid file left
  behind, no shutdown log record, JVM hook never ran. Documented honestly as a
  known limit in `docs/daemon.md` §4.5. Do not resurrect that approach.
- 80 tests green, CI green, at `28514ce` (report commit), per the 0.3.1 report.
- Test totals at that commit: app 11, cli 13, daemon 23, proto 33.

## 5. Process rules

- One Gemini brief at a time, fresh Gemini session per brief.
- One logical change per commit; push, wait for green CI, then proceed.
- Briefs are committed to `docs/briefs/` before or with their brief's first commit.
- Completion reports: `docs/reports/<brief>.md`, committed last, with full commit
  list, verbatim test output, and an honest deviations section.
- Evidence-level verification of reports is **delegated to Gemini**; the architect
  reviews briefs, deviation honesty, and verdicts — not raw tool output. Use context
  sparingly; ask Gash to paste only what you actually need.
- No secrets or device identifiers in the public repo.
- Reports describe behavior; deviations are listed honestly, never hidden.

## 6. Workflow: transitioning between sessions

When Gash opens a new session with a new LLM (or the current one runs out of tokens):

1. Gash says: "read `HANDOVER.md` in the repo and resume the architect role."
2. The new LLM reads this file, runs `checkAccess` on GitHub, resolves the current
   branch tip (`0.x.xB`) and the latest report commit, then states where things
   stand before doing anything.
3. Gash confirms or corrects. If mid-brief, Gash provides the active brief number,
   the last pushed commit SHA, and the Gemini session's latest status.
4. Work continues. Do not re-litigate settled decisions (Section 3) without a
   concrete, stated failure of the approach.
5. At session end, Gash (or the LLM, at Gash's request) updates Sections 3/7 of
   this file to reflect the new state, so the next session starts clean.

## 7. Current queue (update as work progresses)

1. **Active:** none — `0.x.xB` just created at `28514ce`, identical to master.
   CI green. Gash will `git checkout 0.x.xB` locally after pulling.
2. **Next:** the libsu transition brief (T0). Scope: add libsu 6.0.0 `core`+`service`
   dependencies, replace hand-rolled daemon spawn/lifecycle with libsu `RootService`,
   keep the wire protocol and existing tests passing, keep the app + CLI talking to
   the daemon, update `docs/daemon.md`/`docs/protocol.md` where the transport
   description changes, honest report. One brief, fresh Gemini session.
3. **Then:** post-transition housekeeping brief (T1): audit docs and reports for
   stale hand-rolled-daemon references, delete dead code (e.g. the shutdown hook
   if it survives the swap), verify the dependency-policy change is reflected
   everywhere (the old `scripts/check-deps.sh` allowlist policing may be retired
   or simplified), tests and CI green.
4. **Then:** M1 begins: first tools (ping, DNS lookup, traceroute, HTTP probe) via
   system binaries through libsu. Ping first, per prior planning.
5. **Do not start tool work until T1 housekeeping is pristine** (Gash's standing
   rule, confirmed 2026-10-14).

## 8. Notes for a successor LLM

- The old SHA `6050325` no longer exists (amended to `4317f51`). Canonical commits:
  `ded873a` (0.3.3+CI record), `4317f51` (0.3.4 report+brief), `28514ce` (0.3.1 report).
- Do not write bulk implementation code yourself. Do not push without Gash's
  confirmation. Do not create branches or push without explicit Gash approval.
- When a device test needs interaction beyond adb (Magisk grants), pause and ask
  Gash — he does it and attests; that's an accepted evidence form.
- Prefer minimal, focused briefs; one concern per brief. Run tests before declaring
  success. Preserve public interfaces unless Gash asks for a redesign.
- Gash's standing corrections of process: use context sparingly (don't request
  full report dumps), delegate verification to Gemini, keep explanations first,
  grouped commands in fenced blocks without inline comments.
- Key docs at master: `docs/briefs/0.3.4-device-test.md`, `docs/reports/0.3.4-device-test.md`,
  `docs/reports/0.3.1-shutdown-docs.md`, `docs/protocol.md`, `docs/daemon.md`.

## 9. Change log for this file

- 2026-10-14: Initial version, created with branch `0.x.xB`. State: Phase 0
  complete on master (`28514ce`), transition to libsu is the active mission,
  tools work gated on pristine post-transition housekeeping.
