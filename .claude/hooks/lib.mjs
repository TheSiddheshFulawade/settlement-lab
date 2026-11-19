// Shared helpers for Claude Code hooks. Node only, so hooks behave the same on Windows and CI.
import { existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');

export async function readInput() {
  let raw = '';
  for await (const chunk of process.stdin) raw += chunk;
  try {
    return raw ? JSON.parse(raw) : {};
  } catch {
    return {};
  }
}

/** Exit code 2 blocks the tool call; stderr is shown to Claude as the reason. */
export function block(reason) {
  process.stderr.write(`[hook] Blocked: ${reason}\n`);
  process.exit(2);
}

export function allow() {
  process.exit(0);
}

/** Force a permission prompt for this call, whatever the permission mode or allow rules say. */
export function ask(reason) {
  process.stdout.write(
    JSON.stringify({
      hookSpecificOutput: {
        hookEventName: 'PreToolUse',
        permissionDecision: 'ask',
        permissionDecisionReason: `[hook] Needs your approval: ${reason}`,
      },
    }),
  );
  process.exit(0);
}

export const exists = (rel) => existsSync(resolve(ROOT, rel));

/** Normalise a path to forward slashes, relative to the repo root when possible. */
export function rel(p) {
  if (!p) return '';
  const abs = resolve(ROOT, p).replace(/\\/g, '/');
  const root = ROOT.replace(/\\/g, '/');
  return abs.toLowerCase().startsWith(root.toLowerCase() + '/') ? abs.slice(root.length + 1) : abs;
}
