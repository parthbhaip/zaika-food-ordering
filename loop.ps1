<#
.SYNOPSIS
  Zaika build loop. Runs one fresh, headless Claude Code session per task in PLAN.md
  until every task is done, the loop stalls, or MaxIterations is reached.

.EXAMPLE
  .\loop.ps1
  .\loop.ps1 -MaxIterations 10
  .\loop.ps1 -Model opus -MaxTurnsPerIteration 150

.NOTES
  Works in Windows PowerShell 5.1 and PowerShell 7.
  State lives in PLAN.md, NOTES.md and git, so you can stop (Ctrl+C) and re-run at any time.
#>
[CmdletBinding()]
param(
    [int]$MaxIterations = 80,           # sessions to run in this invocation
    [int]$MaxTurnsPerIteration = 120,   # hard cap on agent turns per session
    [string]$Model = "",                # e.g. "opus" or "sonnet"; empty = your Claude Code default
    [int]$StallLimit = 3,               # stop after this many sessions in a row with no new commit
    [int]$MaxConsecutiveFailures = 5,   # stop after this many non-limit errors in a row
    [int]$LimitWaitMinutes = 30,        # wait this long when a usage/rate limit is hit
    [int]$MaxLimitWaits = 12            # give up after this many limit waits in a row (12 x 30 min = 6 h)
)

$ErrorActionPreference = "Continue"
Set-Location -LiteralPath $PSScriptRoot
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

function Stop-Loop([string]$Message, [int]$Code = 1) {
    $color = "Red"
    if ($Code -eq 0) { $color = "Green" }
    Write-Host ""
    Write-Host $Message -ForegroundColor $color
    exit $Code
}

function Get-OpenTaskCount {
    @(Select-String -LiteralPath "PLAN.md" -Pattern '^\s*- \[ \]').Count
}

function Get-NextTaskId {
    $hit = Select-String -LiteralPath "PLAN.md" -Pattern '^\s*- \[ \] (T\d+\w?)' | Select-Object -First 1
    if ($hit) { return $hit.Matches[0].Groups[1].Value }
    return "none"
}

function Get-Head {
    $head = git rev-parse --verify --quiet HEAD 2>$null
    if ($head) { return "$head".Trim() }
    return ""
}

# ---------------------------------------------------------------- preflight
foreach ($cmd in @("claude", "git", "java")) {
    if (-not (Get-Command $cmd -ErrorAction SilentlyContinue)) {
        Stop-Loop "'$cmd' was not found on PATH. Install it, open a NEW terminal, and run .\loop.ps1 again."
    }
}
foreach ($file in @("CLAUDE.md", "PROMPT.md", "PLAN.md", "NOTES.md")) {
    if (-not (Test-Path -LiteralPath $file)) { Stop-Loop "$file is missing. Keep it next to loop.ps1." }
}

$javaInfo = cmd /c "java -version 2>&1" | Out-String
if ($javaInfo -match 'version "(\d+)') {
    $javaMajor = [int]$Matches[1]
    if ($javaMajor -lt 21) {
        Stop-Loop "Java $javaMajor is the default java. Install JDK 21 or newer (for example Eclipse Temurin 21), make it the default, and open a new terminal."
    }
} else {
    Stop-Loop "Could not read the Java version from 'java -version'."
}

$claudeVersion = (& claude --version 2>$null | Out-String).Trim()
if ($claudeVersion -match '(\d+)\.(\d+)\.(\d+)') {
    $maj = [int]$Matches[1]; $min = [int]$Matches[2]; $pat = [int]$Matches[3]
    $tooOld = ($maj -lt 2) -or ($maj -eq 2 -and $min -lt 1) -or ($maj -eq 2 -and $min -eq 1 -and $pat -lt 259)
    if ($tooOld) { Stop-Loop "Claude Code $claudeVersion is too old for this loop (needs 2.1.259+). Run: claude update" }
} else {
    Write-Host "Could not read the Claude Code version ('$claudeVersion'). Continuing anyway." -ForegroundColor Yellow
}

$gitEmail = git config user.email 2>$null
if (-not $gitEmail) {
    Stop-Loop "Git has no identity yet. Run these two lines, then run .\loop.ps1 again:`n  git config --global user.name ""Your Name""`n  git config --global user.email ""you@example.com"""
}

if (-not (Test-Path -LiteralPath ".git")) {
    Write-Host "Creating a git repository for the project..."
    git init -q
}
if (-not (Get-Head)) {
    git add -A
    git commit -q -m "chore: add Claude Code build loop"
    if (-not (Get-Head)) { Stop-Loop "Could not create the first git commit. Run 'git status' to see why." }
}

New-Item -ItemType Directory -Force -Path "logs" | Out-Null

# ---------------------------------------------------------------- claude command
# Pre-approved commands. Anything not listed is denied (nobody is there to approve it).
# Hard denies (git push/reset/clean, rm -rf, ...) live in .claude/settings.json.
$allowedTools = @(
    "Read", "Edit", "Write", "Glob", "Grep",
    "Bash(./mvnw *)", "Bash(sh mvnw *)", "Bash(bash mvnw *)", "Bash(mvn *)", "Bash(chmod +x mvnw)", "Bash(java *)",
    "Bash(git init *)", "Bash(git status *)", "Bash(git diff *)", "Bash(git log *)", "Bash(git show *)",
    "Bash(git add *)", "Bash(git commit *)", "Bash(git stash push *)", "Bash(git stash list *)", "Bash(git rm *)", "Bash(git mv *)",
    "Bash(curl *)", "Bash(tar *)", "Bash(unzip *)", "Bash(mkdir *)", "Bash(cp *)", "Bash(mv *)", "Bash(touch *)",
    "PowerShell(.\mvnw.cmd *)", "PowerShell(./mvnw.cmd *)", "PowerShell(mvnw.cmd *)", "PowerShell(mvn *)", "PowerShell(java *)",
    "PowerShell(git init *)", "PowerShell(git status *)", "PowerShell(git diff *)", "PowerShell(git log *)", "PowerShell(git show *)",
    "PowerShell(git add *)", "PowerShell(git commit *)", "PowerShell(git stash push *)", "PowerShell(git stash list *)",
    "PowerShell(git rm *)", "PowerShell(git mv *)",
    "PowerShell(Invoke-WebRequest *)", "PowerShell(Expand-Archive *)", "PowerShell(New-Item *)", "PowerShell(Copy-Item *)", "PowerShell(Move-Item *)"
)

$prompt = "Read PROMPT.md in the current folder and follow it exactly. Do one iteration only."

$claudeArgs = @(
    "-p", $prompt,
    "--permission-mode", "acceptEdits",
    "--permission-prompts", "none",
    "--max-turns", "$MaxTurnsPerIteration",
    "--output-format", "text"
)
if ($Model) { $claudeArgs += @("--model", $Model) }
$claudeArgs += "--allowedTools"
$claudeArgs += $allowedTools   # keep last: it takes a list of values

# ---------------------------------------------------------------- loop
$stalls = 0
$failures = 0
$limitWaits = 0
$i = 0

Write-Host "Zaika build loop | $claudeVersion | Java $javaMajor | $(Get-OpenTaskCount) open task(s)" -ForegroundColor Cyan

while ($i -lt $MaxIterations) {
    $open = Get-OpenTaskCount
    if ($open -eq 0) { Stop-Loop "PLAN.md has no open tasks left. The build loop is finished." 0 }

    $i++
    $next = Get-NextTaskId
    $headBefore = Get-Head
    $log = Join-Path "logs" ("{0:yyyyMMdd-HHmmss}-{1}.log" -f (Get-Date), $next)

    Write-Host ""
    Write-Host ("=== Iteration {0}/{1} | next task: {2} | open tasks: {3} | started {4:HH:mm} ===" -f $i, $MaxIterations, $next, $open, (Get-Date)) -ForegroundColor Cyan

    & claude @claudeArgs 2>&1 | Tee-Object -FilePath $log
    $exitCode = $LASTEXITCODE
    $output = ""
    if (Test-Path -LiteralPath $log) { $output = Get-Content -LiteralPath $log -Raw }
    $headAfter = Get-Head
    $committed = [bool]$headAfter -and ($headAfter -ne $headBefore)

    if ($output -match 'ALL_TASKS_COMPLETE') { Stop-Loop "Claude reports every task complete. Review the result, then run the app." 0 }

    if ($exitCode -ne 0 -and -not $committed -and $output -match '(?i)usage limit|rate limit|session limit|limit reached|limit will reset|overloaded') {
        $limitWaits++
        if ($limitWaits -gt $MaxLimitWaits) { Stop-Loop "Still limited after $MaxLimitWaits waits. Run .\loop.ps1 again later; it resumes where it stopped." }
        Write-Host "Usage or rate limit hit. Waiting $LimitWaitMinutes min before retrying ($limitWaits/$MaxLimitWaits)..." -ForegroundColor Yellow
        Start-Sleep -Seconds ($LimitWaitMinutes * 60)
        $i--          # a limit wait does not use up an iteration
        continue
    }
    $limitWaits = 0

    if ($exitCode -ne 0) {
        $failures++
        if ($failures -ge $MaxConsecutiveFailures) { Stop-Loop "Claude Code failed $failures times in a row. Read the last file in logs\ and fix the cause." }
        Write-Host "Claude Code exited with code $exitCode (often the turn cap). The next session picks up the unfinished work." -ForegroundColor Yellow
    } else {
        $failures = 0
    }

    if ($committed) {
        $stalls = 0
        Write-Host ("Committed: {0}" -f (git log -1 --pretty=%s)) -ForegroundColor Green
    } else {
        $stalls++
        if ($stalls -ge $StallLimit) {
            Stop-Loop "No new commit in $StallLimit sessions in a row. Read the newest files in logs\ and the end of NOTES.md, fix the cause, then run .\loop.ps1 again."
        }
    }
}

Stop-Loop "Reached MaxIterations ($MaxIterations). $(Get-OpenTaskCount) task(s) still open. Run .\loop.ps1 again to continue." 0
