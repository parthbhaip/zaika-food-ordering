# NOTES - Zaika build log

Append-only memory between build iterations. Read the end of this file before starting work.

## Summary so far
Nothing compressed yet. When this file passes ~300 lines, fold the oldest entries into this section.

---

## Setup (2026-10-04)
- JDK: Eclipse Temurin 21.0.12.1 at `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot` (JAVA_HOME and PATH set machine-wide). Shells started before the install do not see it; set `JAVA_HOME` and `PATH` explicitly there. An old Oracle Java 8 stub (`...\Oracle\Java\java8path`) is also on PATH, after Temurin; `java -version` must report 21.
- The project path contains a space (`D:\New folder`, Git Bash `/d/New folder`). Always quote paths.
- The build is driven by Claude Code workflows inside one session (a builder agent plus an independent reviewer per task) rather than `loop.ps1`. Installed Claude Code is 2.1.234, below loop.ps1's 2.1.259 minimum; run `claude update` before using loop.ps1.
