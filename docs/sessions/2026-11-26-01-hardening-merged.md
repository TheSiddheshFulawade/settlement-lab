# 2026-11-26 · Session 1 · Hardening merged, V2-06 dropped

- **Done:** PR #28 squash-merged as `fc5a263` (pushed head `054aa7d`): L-3, L-6, L-8, L-10 to L-12, N-1 to N-3, H-1, M-1. CI run 45729194506 green on Ubuntu and Windows × JDK 21 and 25; Ubuntu ran `verify -Pdb` (SchemaTest 16/16; engine 179, ledger-postgres 119 with 5 skipped, api 462 with 2 skipped).
- **Audit:** fresh read of the final diff: no high or medium open. Lows left: 415/405 text repeats the visitor's Content-Type or method; one Tomcat INFO line per window for a malformed request line; ORIGIN accepts ports above 65535; MultipartNotParsedHttpTest leaves its Tomcat folder.
- **Branches:** only `main` (`fc5a263`) is left; `fix/v2-hardening`, `feature/v2-05-load-check`, `feature/v3-01` (local and origin) and `docs/handoff-2026-11-21-02` deleted. `.wt/v2-04` and `.wt/v3-07` are empty; the user deletes them.
- **User decision (26 Nov):** V2-06 is dropped: no cloud account, no card. "Live" means GitHub release v1.0.0. SPEC.md V2-06 still reads open; record the decision there in the release PR.
- **Release steps (v1.0.0):**
  1. SHA-pin the actions (`chore/` PR): checkout v5.1.0 `fbc6f3992d24b796d5a048ff273f7fcc4a7b6c09`, setup-java v5.7.0 `b6effb05e454b25005698d916606bdc6ffcbf961`.
  2. `docs/` PR: SPEC V2-06 marked dropped (user, 2026-11-26); README "What v2 adds" says local only; explainer 2.9× to 4.6× and the 30.6 % sentence; licence once chosen.
  3. On `main`, green CI: `git tag -a v1.0.0 -m "Settlement Lab 1.0.0"` and `git push origin v1.0.0` (git-ops).
  4. `gh release create v1.0.0 --title "Settlement Lab 1.0.0" --notes-file <notes>`: what it proves, how to run, the results with machine, JDK, run count and date.
- **Still open:** README database command (needs Docker running); load-check classes 29.95 s in one local run (target 20 s, cause not measured); explainer PDF rebuild.
- **Waiting on user:** licence.
- **Open TODO-ME:** unchanged (`ClientIpTest.slashZeroTrustsEveryone`, `HttpLoadCheckTodoTest.sameIdWithDifferentAmount` and earlier ones).
- **Quiz:** the five questions in `docs/learn/fix-02-hardening.md`.
