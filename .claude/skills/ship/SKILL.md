---
name: ship
description: Verify the current work branch, then hand it to the git-ops agent, which commits, pushes, opens the PR to main, waits for the required CI checks and squash-merges on green.
disable-model-invocation: true
argument-hint: "[optional PR title]"
---

Ship the current branch to `main`. Title override: $ARGUMENTS

1. **Branch**: must be `feature/*`, `fix/*`, `chore/*` or `docs/*`, never `main`.
2. **Gate**: `mvnw.cmd verify` (Windows) or `./mvnw verify`, plus `-Pdb` when the change touches
   `ledger-postgres` or SQL. Fix failures before continuing. Note the SHA it passed on (commit first
   if the tree is dirty, on the work branch).
3. **Checklist** before handing off: SPEC.md task ticked, `docs/learn` note and README Decisions
   entry written, one `// TODO-ME:` left for the user, `security-auditor` run if `api`, pom files or
   `.github/workflows` changed.
4. **Delegate** to the `git-ops` agent with: the task id, a summary for the PR body (what and why,
   SPEC task and REQ ids, tests, learn note, Decisions entry, risks) and the verified SHA. git-ops
   pushes, opens or updates the PR, waits for the four required `verify (os, jdk)` checks and
   squash-merges on green. No user yes is needed for the merge.
5. **Red CI**: git-ops reports the failing log; fix the cause on the branch and hand back to git-ops.
   Never bypass a check, never `--admin`.
6. **After merge**: add the task's line to the day's handoff (at most 20 lines), then start the next task.
