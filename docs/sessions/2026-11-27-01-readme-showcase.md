# 2026-11-27 · Session 1 · MIT licence, README showcase, v1.0.1 prepared (offline)

- **GitHub unreachable since 27 Nov 08:00 (Wi-Fi); push tonight.** Nothing counts as done until CI is green on GitHub.
- **Push queue** (landing order; the TONIGHT checklist below says how):
  1. `docs/handoff-2026-11-26-02` · base main · `6b45da4` · release handoff · already pushed, no PR yet · CI: four checks green.
  2. `chore/mit-licence` · base main · `ea46346` · LICENSE (MIT, "Copyright (c) 2026 Siddhesh Fulawade"), README "Open source (MIT)" + Licence section, SPEC.md line 3 · no code, no tests run · reviews: none needed · CI: four checks green.
  3. `docs/readme-showcase` · stacked on `chore/mit-licence` · `c1c91e3` · new README top, 3 animated SVGs, 3 local badges, Mermaid modules, docs/DECISIONS.md · no code, so no `mvnw verify` (runner smoke run on `-pl engine`: 12/12 PASS, 45 s) · reviews: technical + finance (fresh general-purpose agents), all findings fixed and re-confirmed · CI: four checks green.
  4. `chore/release-v1.0.1` · stacked on `docs/readme-showcase` · this commit · release badge and README links to v1.0.1, notes in `docs/releases/v1.0.1.md`, this handoff · no code · review: fresh reviewer read the notes · CI: four checks green. Not tagged (no tags offline).
- **Rendering checked locally** (headless Chrome): each SVG paused at 2 to 5 frames, light and dark schemes, inside `<img>` on a white and a #0d1117 page at 2x, and with reduced motion. Not checked: Firefox and Safari, GitHub's camo proxy, Mermaid (no renderer offline).
- **Deviations from the brief (reviewer calls):** "Run it with one command" (not "in 2 minutes": 45 s was measured only with dependencies cached); "What is checked" (not "What this proves"); "16-core" became "16 logical processors" (the runner counts `availableProcessors`).
- **Known, kept by chief's decision:** the LLD PDF keeps its own static diagrams (red duplicate, matching its text).
- **Waiting on user:** delete the empty folders `.wt/fix-runner`, `.wt/v2-04`, `.wt/v3-07`; say "GitHub is back".
- **Open TODO-ME:** unchanged. **Quiz:** none today (docs only, no new code).

## TONIGHT (git-ops checklist, start only when the user says "GitHub is back")

1. Push and open a PR for each, in order: `docs/handoff-2026-11-26-02` (already on origin), `chore/mit-licence`, `docs/readme-showcase`, `chore/release-v1.0.1`. Base each PR on `main`.
2. One at a time: wait for all four `verify (os, jdk)` checks, squash-merge on green. Then update the next stacked branch from `main` by merge (no force push), push it, and wait for its four checks again. Red at any point: stop and report; never bypass.
3. Annotated tag `v1.0.1` on the final `main` commit; push the tag; wait for CI green on the tag push.
4. `gh release create v1.0.1 --notes-file docs/releases/v1.0.1.md --title "Settlement Lab v1.0.1"`. Edit the v1.0.0 notes: "public, no licence file yet" becomes "open source (MIT) from v1.0.1".
5. Check on github.com: the three SVGs animate in the README, the badges and the Mermaid module diagram render, the `docs/DECISIONS.md` links and anchors work, and the v1.0.1 release page and source archive show `LICENSE`. Report anything broken; fix only in a new small PR.
6. Delete the merged branches locally and on origin.
7. Portfolio hand-back: the v1.0.1 release URL, the tag's commit SHA, the unchanged results commit `3eb119a`, and a one-line status.
8. Final handoff line: "Settlement Lab live: v1.0.1".
