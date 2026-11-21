---
name: ship
description: Verify, push the current work branch, open a PR to main, wait for CI, and squash-merge only after the user's explicit yes.
disable-model-invocation: true
argument-hint: "[optional PR title]"
---

Ship the current branch to `main`. Title override: $ARGUMENTS

Needs the GitHub CLI (`gh auth status`). If it is missing or logged out, stop and tell the user.

1. **Branch**: must be `feature/*`, `fix/*` or `chore/*`, never `main`. The working tree must be
   clean; if not, list the changes and ask whether to commit them.
2. **Sync**: `git fetch origin` and `git rebase origin/main`. On conflicts, stop and explain.
3. **Gate**: `mvnw.cmd verify` (Windows) or `./mvnw verify`. Fix failures before continuing.
4. **Push**: `git push -u origin <branch>` (the user approves the push).
5. **PR**: `gh pr create --base main`. Title in Conventional Commit style. Body: what and why,
   SPEC task and REQ ids, tests added, the docs/learn note and README Decisions entry for the change,
   and `🤖 Generated with [Claude Code](https://claude.com/claude-code)` at the end.
   If a PR for the branch already exists, update it instead.
6. **CI**: `gh pr checks --watch` once; report all jobs (Ubuntu and Windows, JDK 21 and 25).
7. **Ask** in one line: PR link, CI result, "merge to main?". Merge only on an explicit yes in this
   session. Right before merging, re-read `gh pr view <n> --json baseRefName,headRefOid` and refuse
   if the base is not `main` or the head is not the commit you pushed. Then
   `gh pr merge <n> --squash --delete-branch`. Never use `--admin`.
8. **Clean up**: `git switch main`, `git pull --ff-only`, delete the local branch. Suggest `/handoff`.
