---
name: handoff
description: Write the end-of-session handoff note (at most 20 lines, with the day's quiz) so the next session, local or cloud, resumes without re-reading everything.
disable-model-invocation: true
---

Write `docs/sessions/YYYY-MM-DD-NN-<short-topic>.md`: today's date, and NN = the next two-digit
sequence number for that date (01 for the first note of the day). The SessionStart hook picks the
latest note by date and NN, so never reuse or skip a number.

At most 20 lines:

- **Done**: tasks ticked in SPEC.md, PRs, decisions made on the user's behalf
- **State**: branch, what is merged, `mvnw verify` and CI green or red, anything uncommitted,
  any pause the user asked for
- **Next**: the very next task and why
- **Open questions** for the user (or "none")
- **Quiz**: the questions asked today, and which ones the user got wrong (to revisit)

No secrets or personal data. Commit it on the current work branch.
