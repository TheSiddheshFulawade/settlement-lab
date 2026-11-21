// Run: node --test .claude/hooks/*.test.mjs
import { test } from "node:test";
import assert from "node:assert/strict";
import { latestHandoff, taskSummary } from "./session-start.mjs";

test("latest handoff is chosen by date, then sequence number, not by name", () => {
  const names = [
    "2026-11-19-07-windows-fix.md",
    "2026-11-19-10-later.md",
    "2026-11-19-01-day-1.md",
    "2026-11-18-99-older.md",
    "README.md",
  ];
  assert.equal(latestHandoff(names), "2026-11-19-10-later.md");
});

test("a new day beats any sequence number on an earlier day", () => {
  assert.equal(
    latestHandoff(["2026-11-19-07-a.md", "2026-11-20-01-b.md"]),
    "2026-11-20-01-b.md",
  );
});

test("no dated handoffs gives undefined", () => {
  assert.equal(latestHandoff(["notes.md"]), undefined);
});

test("task summary counts done and open tasks and names the next one", () => {
  const spec =
    "- [x] L-01 skeleton\n- [X] L-02 ledger\n- [ ] V2-04 SSE stream\n- [ ] V2-05 README\n";
  assert.deepEqual(taskSummary(spec), {
    done: 2,
    open: 2,
    next: "V2-04 SSE stream",
  });
});
