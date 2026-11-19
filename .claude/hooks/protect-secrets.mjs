// PreToolUse (Read | Edit | Write | Grep | Glob): keep secrets out of Claude's context and edits,
// and keep file-tool writes out of system folders. Policy: CLAUDE.md "Agent safety".
import { homedir } from 'node:os';
import { readInput, block, allow, rel } from './lib.mjs';

const input = await readInput();
const ti = input?.tool_input ?? {};
const tool = input?.tool_name ?? '';
const candidates = [ti.file_path, ti.path, ti.notebook_path, ti.pattern && tool === 'Glob' ? ti.pattern : null]
  .filter(Boolean)
  .map(rel);

const HOME = homedir().replace(/\\/g, '/').toLowerCase();

const SECRET = [
  /(^|\/)\.env$/,
  /(^|\/)\.env\.(?!example$)[\w.-]+$/,
  /(^|\/)secrets(\/|$)/,
  /\.(pem|key|p12|pfx|jks|keystore)$/i,
  /(^|\/)id_(rsa|ed25519)(\.pub)?$/,
  // Credential stores outside the repo
  /(^|\/)id_(ecdsa|dsa)(\.pub)?$/,
  /(^|\/)\.ssh(\/|$)/,
  /(^|\/)\.gnupg(\/|$)/,
  /(^|\/)\.git-credentials$/,
  /(^|\/)[._]netrc$/,
  /(^|\/)\.aws\/(credentials|config)$/,
  /(^|\/)\.azure(\/|$)/,
  /(^|\/)\.config\/gh\/hosts\.yml$/,
  /(^|\/)gh\/hosts\.yml$/,
  /(^|\/)\.docker\/config\.json$/,
  /(^|\/)\.kube\/config$/,
  /(^|\/)\.m2\/settings(-security)?\.xml$/,
  /(^|\/)\.claude\/\.credentials\.json$/,
  /\/microsoft\/(credentials|protect|vault)(\/|$)/i,
];

const isHomeNpmrc = (p) => p.toLowerCase() === `${HOME}/.npmrc`;

for (const p of candidates) {
  if (SECRET.some((re) => re.test(p)) || isHomeNpmrc(p)) {
    block(`${p} looks like a secret. Claude must not read or change it. Ask the user to handle it.`);
  }
}

if (/^(Write|Edit|MultiEdit|NotebookEdit)$/.test(tool)) {
  for (const p of candidates) {
    // Personal documents may be read (e.g. the resume) but never written from here.
    if (/^docs\/private\//.test(p)) block(`${p} is in docs/private (personal documents). Do not modify it.`);
    // System folders: never written by Claude.
    if (/^[a-z]:\/(windows|program files( \(x86\))?|programdata)(\/|$)/i.test(p) || /^\/(etc|usr|bin|sbin|boot|lib|lib64|system|library)(\/|$)/i.test(p)) {
      block(`${p} is a system folder. Keep changes inside the project.`);
    }
  }
}

allow();
