---
name: quiz
description: Interview-style quiz on a Settlement Lab module or today's changes, one question at a time, graded against the actual code.
disable-model-invocation: true
argument-hint: "[module, e.g. L-02, or empty for today's changes]"
---

Quiz the user on: $ARGUMENTS (empty = what changed today, from `git log --since=midnight`).

1. Read the module's code, its tests, its `docs/learn/` note and its README Decisions entry, so every
   question and grade matches what the code actually does.
2. Ask **5 questions, one at a time**, the way a bank interviewer would. Mix:
   - explain: "walk me through what happens when..."
   - why: "why this choice and not the alternative?"
   - break it: "what fails if I remove X?" or "what if two threads...?"
   - extend: "how would this work across two JVMs / after a restart?"
3. Wait for the answer. Grade it (strong / partial / missing), say what was missing in 2–4 lines,
   and point to the file and line that proves it. Do not give the answer before the user tries.
4. At the end: a score out of 5, the topics to revisit, and append the result to the day's handoff
   (create it with `/handoff` if none exists).
