# One iteration of the Zaika build loop

You are one iteration of an unattended build loop. Every iteration starts with a fresh context, so the files are your memory:
- CLAUDE.md: the rules (already loaded). Follow all of them.
- PLAN.md: the task list.
- NOTES.md: what earlier iterations built and learned.

Do exactly ONE task, finish it properly, commit it, and stop. Nobody is watching: never ask questions, decide and record the decision in NOTES.md.

## Steps

1. Orient (keep this short)
   - Read PLAN.md and the last 80 lines of NOTES.md.
   - Run `git status --short` and `git log --oneline -8`.

2. Recover if needed
   - Uncommitted changes mean an earlier iteration was cut off. Look at them (`git diff`, `git status`). If they belong to the first open task, continue from them. If they are broken or unrelated, set them aside with `git stash push -u -m "recovered: <reason>"` and say so in NOTES.md.
   - If a Maven wrapper exists, run the full build (see CLAUDE.md > Commands). If it is red, making it green IS this iteration: fix it, commit as `fix: restore green build`, and stop.

3. Pick the task
   - The first line in PLAN.md starting with `- [ ]`. Never skip ahead, never work on a later task.

4. Look before you build
   - Search the existing code for what you need (Glob/Grep) and extend it. Do not create a second version of something that exists.
   - Decide which files you will add or change.

5. Implement the task completely
   - Every acceptance bullet must actually work. No TODOs, no stubs, no placeholder pages, no "coming soon".
   - UI work must meet the design system and quality bar in CLAUDE.md: styled, responsive, accessible, with empty and error states.

6. Test
   - Add or update tests that prove each acceptance bullet, including security (anonymous, wrong role, wrong owner) where relevant.
   - Run the full build and fix until it is green. Never delete or weaken a test to go green unless the test itself is wrong; if you change one, explain why in NOTES.md.

7. Review your own diff (`git diff`)
   - Security: ownership checks, CSRF, no `th:utext` on user content, no secrets.
   - Quality: N+1 queries, missing validation messages, unstyled elements, copy that sounds like a template.
   - Fix what you find and re-run the build.

8. Record
   - PLAN.md: change this task's `- [ ]` to `- [x]`. If you found necessary follow-up work, add it as a new `- [ ]` task with the next free ID in the right phase.
   - NOTES.md: append `## Txx <title> (<date>)` with 2-6 bullets: what was built, key files, decisions, gotchas the next iteration must know.

9. Commit
   - `git add -A` then `git commit -m "Txx: <task title>"`. Never push.

10. Stop
   - Final reply, one line: `DONE Txx`.

## If you are blocked
If the task cannot be finished after three genuine attempts (a tool or service is missing, or a requirement contradicts another):
1. `git stash push -u -m "blocked: Txx"`
2. In PLAN.md change the task to `- [!]` and add ` -- BLOCKED: <one-line reason>` to the end of its line.
3. Add the details and what a human must do to NOTES.md.
4. Commit PLAN.md and NOTES.md with the message `Txx: blocked`.
5. Final reply, one line: `BLOCKED Txx: <reason>`. Do not start another task.

## When everything is done
If PLAN.md has no `- [ ]` lines left: run the full build once more, make sure it is green, and reply with exactly this line:
ALL_TASKS_COMPLETE
