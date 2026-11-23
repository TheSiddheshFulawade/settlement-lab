# 2026-11-21 · Session 1 · Housekeeping (Claude setup, SLF4J notice)

- **Done:** PR #8 Claude setup (session-start hook + tests, guard-bash fix for lower-case `$` names, 5 skills, security-auditor agent, CLAUDE.md section, SessionStart block in settings.json, which had been missing). PR #9 `slf4j-nop` 1.7.30, `optional` in engine (matches the transitive slf4j-api; stays out of api, which uses Logback); ADR-0001 entry. Both squash-merged after the user's yes; `.wt/claude-setup` removed.
- **Decisions (user):** keep the JDK 21 minimum (CI tests 21 and 25). Keep JUnit 5.14 in engine until jqwik supports JUnit 6. Keep `feature/day-7` for v2. V2-04 to V2-06 stay paused until December.
- **State:** `main` at b7dd73c, clean. `mvnw.cmd verify` green locally; CI green on Ubuntu/Windows × JDK 21/25 for both PRs. Runner prints no SLF4J lines; api tree has Logback only.
- **Notes:** the first local verify had EngineStressTest timing out (threads not done in 1 min) under machine load. Alone it passed in 1.5 s and the re-run of verify passed: watch for it, but no code cause found.
- **Next:** tonight's quiz. Then a small fix: guard-bash's "no commit on main" rule reads the hook checkout's branch, not the command's directory (blocks worktree commits), and its message still says "develop".
- **Open questions:** none.
- **Quiz:** not started (tonight). Module 1 quiz is still paused at Q2 of 5 (questions in 2026-11-19-07-windows-fix.md).
