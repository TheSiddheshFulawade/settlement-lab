// SessionStart: print task status from SPEC.md and the latest handoff. Stdout is added to Claude's
// context, so a new session (local or cloud) starts from where the last one stopped.
// Handoffs are named docs/sessions/YYYY-MM-DD-NN-topic.md; the latest is chosen by date, then by
// NN. Modified times are not used: git checkout and clone reset them.
import { readdirSync, readFileSync, existsSync } from "node:fs";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { ROOT } from "./lib.mjs";

/** Sort key for a handoff file name; names without NN sort before NN=01 on the same day. */
export function handoffKey(name) {
  const m = /^(\d{4}-\d{2}-\d{2})(?:-(\d{2}))?-/.exec(name);
  return m ? `${m[1]}-${m[2] ?? "00"}` : null;
}

/** The latest handoff file name, or undefined. Ignores files that do not start with a date. */
export function latestHandoff(names) {
  return names
    .filter((n) => n.endsWith(".md") && handoffKey(n))
    .sort(
      (a, b) =>
        handoffKey(a).localeCompare(handoffKey(b)) || a.localeCompare(b),
    )
    .at(-1);
}

/** Done and open counts from SPEC.md checkbox lines, plus the first open task. */
export function taskSummary(text) {
  const done = (text.match(/^\s*- \[x\]/gim) ?? []).length;
  const open = text.match(/^\s*- \[ \].*$/gm) ?? [];
  const next = open[0]?.replace(/^\s*- \[ \]\s*/, "").slice(0, 100);
  return { done, open: open.length, next };
}

function main() {
  const out = [];
  const spec = resolve(ROOT, "SPEC.md");
  if (existsSync(spec)) {
    const t = taskSummary(readFileSync(spec, "utf8"));
    out.push(
      `SPEC.md tasks: ${t.done}/${t.done + t.open} done` +
        (t.next ? ` · next open: ${t.next}` : ""),
    );
  }
  const sessDir = resolve(ROOT, "docs/sessions");
  if (existsSync(sessDir)) {
    const last = latestHandoff(readdirSync(sessDir));
    if (last) {
      const text = readFileSync(resolve(sessDir, last), "utf8");
      out.push(
        `Latest handoff (docs/sessions/${last}):\n${text.slice(0, 2000)}`,
      );
    }
  }
  out.push(
    "Reminder: check the latest handoff for a feature pause before starting new SPEC tasks, and follow the 'Defend every line' rules in CLAUDE.md.",
  );
  process.stdout.write(out.join("\n\n") + "\n");
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href)
  main();
