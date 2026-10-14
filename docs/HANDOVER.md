# Handover for the planning and review Claude

Any Claude session, on any account, should be able to pick this project up from the repository alone. Read this file, then `OUTLINE.md`, then `GEMINI.md`, then the newest file in `docs/reports/`, then `git log --oneline -15`. The current status is in those last two. This file holds only what changes slowly.

## Roles and loop

- **Claude** designs, writes task briefs, reviews reports, and explains before giving commands.
- **Gemini** (Antigravity) does the bulk implementation, one brief per fresh session. Long sessions get compacted and Gemini then stalls, so never reuse a session.
- **The human** is the go-between. He pastes briefs and report URLs and runs every step on the device. Claude never asks him to run a command that changes device state unless he asks for it. Read-only commands are fine.
- Two briefs must never run at the same time on the repository: their pushes and CI runs collide, and each report would claim the other's work.
- A brief goes to the human in a 4-backtick code block. Gemini saves it as `docs/briefs/<id>.md` and commits it first. This has worked every time, despite the "do not edit briefs" rule in `GEMINI.md`, which is about existing briefs.
- Every task ends with one line from Gemini: the commit-pinned raw URL of its report.

## Environment

- Dev machine: CachyOS (Arch-based Linux), zsh. The human's data and tools live here.
- Android SDK with platform 35: `~/Android/Sdk`, selected through `sdk.dir` in `local.properties` (gitignored). The system `ANDROID_HOME` points to a read-only SDK without platform 35. Never take the SDK from `ANDROID_HOME` alone.
- Test device: Pixel 2 XL (taimen), Android 15, EvolutionX, Magisk-based root. Package `io.github.berketket.armada`.
- Toolchain: JDK 21, Gradle 9.6.0, AGP 9.4.1, Kotlin 2.4.10, compileSdk and targetSdk 35, minSdk 29.

## Claude's limits

- Claude can only open URLs the human pastes into a message. `raw.githubusercontent.com` URLs work. GitHub diff, compare and tree pages are blocked, and blob pages may or may not be.
- Fetched files can be cut off. Reports carry the full diff as an appendix, so check the end of the fetched file. If it stops early, ask the human to look at the raw file in a browser.

## Output format the human wants

- Explanation first. All instructions and commands go once, together, at the very end of the message.
- Commands in fenced code blocks, bare and directly pasteable. No `#` comment lines in shell blocks.
- Quote URLs and logcat filters, for example `'ArmadaApp:*'`.
- For a root shell command with variables, use `adb shell "su -c '...'"` and escape `$`.

## Review checklist (these rules have been broken before)

- Every code commit is pushed and CI-green before the next step. Batch pushes are a deviation, not a norm.
- The report is written only after CI is green on the code commit. CI for the report commit cannot be inside that report, so a small follow-up commit records it. Check any run with `gh api 'repos/BerKetKet/armada/actions/runs?head_sha=<SHA>' --jq '.workflow_runs[] | {id, status, conclusion, html_url}'`.
- Test counts come from a fresh run (`--rerun-tasks`). "up-to-date" output is not evidence.
- Command output in a report is copied from captured files. Compare reflog and similar outputs with the commit the report names.
- The appended diff must be complete and end with the last line of the diff. The report itself is correctly excluded from its own appendix.
- The Deviations section must be honest: force pushes, batch pushes, invalid attempts, and anything not run on the device.
- CI "Build and test" failing is not proof that "Check dependencies" works. Both gates need their own proof.

## Known pitfalls

- Antigravity keeps a global rule file at `~/.gemini/GEMINI.md`. It once said never to write to the workspace, and after a context compaction Gemini obeyed it over the repo's `GEMINI.md`. It now allows writing when given a brief. If Gemini says it is in read-only mode, check that file first.
- `-Xjdk-release=8` on `:proto` stops Kotlin from calling JDK APIs that do not exist on Android (for example `List.removeLast()`).
- Fresh Gradle caches expose missing entries in `gradle/verification-metadata.xml`. CI on a clean runner is what catches this.

## Where the rest lives

- Design and tool catalogue: `OUTLINE.md`.
- Rules for the coding agent: `GEMINI.md`.
- Wire protocol: `docs/protocol.md`. Daemon: `docs/daemon.md`.
- Task history: `docs/briefs/` and `docs/reports/`.
