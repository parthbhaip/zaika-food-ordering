# How to run the Zaika build loop (Windows 11)

## What's in this folder
| File | Purpose |
|---|---|
| `CLAUDE.md` | The rulebook: stack, architecture, design system, security, testing. Claude Code loads it every session. |
| `PLAN.md` | 58 tasks in 10 phases, in dependency order, each with acceptance criteria. The loop's to-do list. |
| `PROMPT.md` | What ONE iteration does: pick the first open task, build it, test it, commit it, stop. |
| `NOTES.md` | Memory between iterations. Claude appends what it built and learned. |
| `loop.ps1` | Runs a fresh `claude -p` session per task until PLAN.md is done. |
| `.claude/settings.json` | Pre-approved commands, plus hard blocks (no git push/reset/clean, no `rm -rf`). |
| `.gitignore` | Keeps build output, logs, H2 data, uploads and `.env` out of git. |

## 1. Install once
1. **JDK 21 or newer** (Eclipse Temurin 21 LTS is fine). Check with `java -version`.
2. **Git for Windows**, then set your identity:
   ```powershell
   git config --global user.name "Your Name"
   git config --global user.email "you@example.com"
   ```
3. **Claude Code, up to date**: run `claude update`. The loop needs v2.1.259 or newer.
4. You do NOT need Maven (the project ships its own wrapper), Node, or MySQL to run the loop. MySQL is only for the final demo (step 7).

## 2. Set up the project folder
1. Create an empty folder on a local drive, e.g. `D:\projects\zaika` (not inside OneDrive, which locks files mid-build).
2. Copy everything from this pack into it, including the hidden `.claude` folder and `.gitignore`.
3. Open `CLAUDE.md` and change the brand name, package, or demo city now if you want to. Renaming after T01 is painful.
4. Open PowerShell in the folder and run `claude` once. Log in if asked, accept the "trust this folder" prompt, then type `/exit`.
5. If Windows blocks the script: `Unblock-File .\loop.ps1`. If it is still blocked, run `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`.

## 3. Run it
```powershell
.\loop.ps1                      # runs until PLAN.md is done (max 80 sessions)
.\loop.ps1 -MaxIterations 5     # run only 5 tasks, then stop and review
.\loop.ps1 -Model opus          # force a model
```
Each iteration starts a fresh headless session. It reads PROMPT.md, takes the first `- [ ]` task, builds it, runs the full test suite, ticks the task, writes NOTES.md, and commits. Then the next session starts.

The script stops on its own when:
- no open tasks are left;
- 3 sessions in a row produce no commit (something is stuck: read `logs\` and NOTES.md);
- 5 sessions in a row fail with errors;
- you hit your usage limit. It then waits 30 minutes and retries, up to 6 hours.

Stop it any time with **Ctrl+C**. Re-run `.\loop.ps1` to resume. All state lives in PLAN.md, NOTES.md and git.

**Watch progress:** `git log --oneline`, the ticks in PLAN.md, the end of NOTES.md, and one log file per session in `logs\`.

## 4. Check the website between phases
With the loop stopped:
```powershell
.\mvnw.cmd spring-boot:run
```
Open http://localhost:8080. Once T14 is done, log in with the demo accounts (password `Demo@1234` for all):
`customer@zaika.test`, `owner1@zaika.test`, `rider1@zaika.test`, `admin@zaika.test`.
Press Ctrl+C to stop the server before you restart the loop.

## 5. Steer it
- **Found a bug or want a change?** Add a task to PLAN.md, e.g. `- [ ] T59 Fix: cart badge doesn't update on mobile`. Put it above the remaining open tasks if it's urgent; the loop always takes the first open line.
- **Blocked task (`- [!]`)**: read the reason in NOTES.md and fix the cause (for example, install something). Change the line back to `- [ ]` and re-run. The half-done code is in `git stash list`.
- **Denied command**: if a log shows Claude needed a safe command that was refused, add it to `$allowedTools` in `loop.ps1` AND to `.claude/settings.json`.

## 6. Alternative: watch it work live (`/goal`)
For a session you sit in front of and steer:
```powershell
claude --permission-mode acceptEdits
```
Then paste:
```
/goal Follow PROMPT.md one task at a time and keep going until PLAN.md has no lines starting with "- [ ]". Before finishing, show a full ./mvnw verify run that ends in BUILD SUCCESS. Stop after 300 turns.
```
For long unattended runs, `loop.ps1` is the better choice: every task gets a fresh context, so quality doesn't decay over 58 tasks.

## 7. MySQL for the final demo
T56 writes the full guide to `docs/SETUP-MYSQL.md`. The short version:
```powershell
$env:DB_URL="jdbc:mysql://localhost:3306/zaika"; $env:DB_USER="zaika"; $env:DB_PASSWORD="your-password"; $env:SEED_DEMO="true"
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=mysql"
```
(Use the exact variable names from `.env.example`, which T02 creates.)

## Reality check
- 58 tasks means 58+ long sessions. On a Pro plan you will hit usage limits many times and the run will take days. A Max plan makes it practical. Run it in chunks (`-MaxIterations 5` to `10`) and review each phase.
- Your team has to explain this code in the viva. After each phase, read the commits (`git log -p`), run the site, and make sure each of the four of you owns and understands one area (for example customer flow, owner panel, rider + admin, API).
- The loop builds and tests on H2. MySQL compatibility is reviewed in T56 but must be run once by a human.
