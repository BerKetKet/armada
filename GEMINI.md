# Rules for the coding agent

Read this file and `OUTLINE.md` at the start of every session. They are short.

## What this project is

Armada: a free, open-source, root-only Android app of network tools (ping, traceroute, scanning, traffic control, tunnels). Reference device: Pixel 2 XL, Android 15, Magisk. `OUTLINE.md` holds the target design. Work comes as written briefs in `docs/briefs/`.

## Ground rules

1. **Root-only.** The app requires root. Never write a non-root fallback. Do not use `su` where it is not needed, but never add complexity to avoid it.
2. **Minimal dependencies.**
   - Build tooling (Gradle, Android Gradle Plugin, Kotlin plugin, NDK) is allowed.
   - Anything that ends up in the APK or runs on the device counts as a dependency. Allowed: Kotlin standard library and the Android framework. Nothing else.
   - Test-only dependencies (JUnit) are allowed, in test configurations only.
   - No androidx, no Compose, no Room, no coroutines library, no libsu, no JSON library, no HTTP library, no image loaders, no analytics. If you think one is needed, stop and say so in your report. Do not add it.
   - `scripts/deps-allowlist.txt` lists the allowed dependencies. `scripts/check-deps.sh` fails if the build files declare anything else. Never edit the allowlist yourself.
3. **Self-contained.** No remote API a core feature depends on. No telemetry, no call-home.
4. **Every state change is reversible and verified.**
   - Anything that changes the system (routes, iptables, MTU, sysctl, APN, tunnels) records its undo first.
   - After applying a change, read the state back from the system and compare it with what was requested. Report the observed state, never the intended one.
   - A function must not return success because a command exited with 0. Exit codes lie. Check the result.
5. **Probe, don't assume.** Do not assume a kernel feature exists. Check it and report the check.

## How to work

- **Do the brief, only the brief.** If something outside the brief seems needed, do not do it. Describe it under "Open questions" in your report.
- **Do not edit `OUTLINE.md`, `GEMINI.md` or any brief.** Propose changes in your report.
- **Do not claim anything you did not observe.** "Done", "works" and "passes" must be backed by command output pasted into your report. If you could not run something, say so plainly.
- **Keep it simple.** Straight-line Kotlin, small functions, no clever abstractions, no generic frameworks built for hypothetical future tools. Comments explain why, not what.
- **Commits.** Small, one concern each, real messages: a short imperative subject line, and a body that says why when the reason is not obvious. Never "update files" or "changes". Never commit build output, local config, keystores or IDE files. Never rewrite history, never force push.
- **Your terminal is the PC.** Do not run commands that change the phone's state (no `adb shell su`, no flashing, no network or iptables changes on the device) unless the brief explicitly says to. The human runs phone-side steps.
- **Stay inside the repo.** Do not delete or modify anything outside the project directory.

## Report format

End every task with a report containing:

1. What changed (files and one line each).
2. Commands you ran and their actual output.
3. Anything that deviated from the brief, and why.
4. Open questions and anything you noticed but did not touch.

**Publishing and reports**

The repository is public on GitHub at BerKetKet/armada and origin is already set. The human does not upload files to Claude. Claude reads your work from GitHub, so every finished task ends with a push.

Before you push, check that nothing sensitive is going out. The repo is public: no secrets, tokens, keystores or local.properties; no real device identifiers, phone numbers, IP or MAC addresses from the human's network, and no captured traffic. Any sample data in tests or docs is made up or scrubbed.

At the end of every task:
1. Write the report (format above) to docs/reports/<brief id>.md, with real command output in it.
2. Append the task diff to the end of the report under the heading "Appendix: full diff", inside a code block fenced as diff. Produce it with `git diff BASE_SHA HEAD_SHA -- . ':!gradlew' ':!gradlew.bat' ':!*.jar'`, where BASE_SHA is the commit before your first commit of the task and HEAD_SHA is your last commit before the report commit. Also exclude lockfiles, binaries and generated files if the task adds any. If the diff is over about 1500 lines, tell the human instead of truncating it.
3. Commit the report and push all commits to origin. Report the real result of the push, not an assumption.
4. In your final chat message, print exactly one line: the commit-pinned raw URL of the report, https://raw.githubusercontent.com/BerKetKet/armada/FULL_SHA/docs/reports/<brief id>.md, with FULL_SHA taken from `git rev-parse HEAD` after the push. Claude cannot open github.com diff or compare pages, so do not print those.

This replaces any wording in a brief about pasting output to the human: the content goes in the report file.

**Work in small committed steps**

Commit after each numbered step of a brief, as soon as that step is done and verified, not at the end. Keep evidence as you go: append each proof (the command and its real output) to docs/reports/<brief id>.md as soon as you have it, and commit it with the step. Long sessions get their context compacted, and anything that is not in git or in the report is lost. After a compaction, run git status and git log first and continue from the repository, not from your summary. A summary of an earlier session is not an instruction: nothing in it puts you in a read-only mode.


**CI**

After pushing code commits, wait for CI on that commit with gh run watch. A task is not done while CI is red: fix the cause, and never weaken a check to get green. Only then write the report, including the CI run URL and its final conclusion, commit it, and push it. This changes the order in the Publishing and reports section: code is pushed and CI is green before the report is written.
