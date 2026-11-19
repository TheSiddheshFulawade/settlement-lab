// PreToolUse (Bash | PowerShell): block destructive or policy-breaking shell commands,
// and force a permission prompt for system-level or irreversible ones.
// Policy: CLAUDE.md "Agent safety". Pattern rules are a safety net, not a sandbox: they read
// the command text, so an obfuscated command can still slip past. Deny wins over ask.
import { execFileSync } from 'node:child_process';
import { readInput, block, allow, ask, ROOT } from './lib.mjs';

const input = await readInput();
const cmd = String(input?.tool_input?.command ?? '');
const c = cmd.replace(/\s+/g, ' ').trim();

// Build output folders that are safe to delete recursively.
const SAFE_DELETE = /^(\.\/)?(apps\/(web|api)\/)?(node_modules|dist|target|coverage|\.angular|playwright-report|test-results|\.lighthouseci)\/?$/;

// ---------------------------------------------------------------------------------------------
// 1. Repository rules (unchanged).
const repoRules = [
  [/\bgit\s+push\b.*(\s--force(-with-lease)?\b|\s-f\b)/, 'force push is not allowed'],
  [/\bgit\s+push\b(?!.*--dry-run)[^;&|\n]*[\s:](refs\/heads\/)?(main|master|develop)(?=\s|$|;|&|\|)/, 'direct push to main or develop is not allowed; push a work branch and open a pull request (/ship)'],
  [/\bgh\s+pr\s+merge\b[^;&|\n]*--admin\b/, 'bypassing branch protection with --admin is not allowed'],
  [/--no-verify\b/, 'skipping git hooks (--no-verify) is not allowed'],
  [/\bgit\s+(commit|merge|rebase)\b.*\s-c\s+commit\.gpgsign=false/, 'disabling commit signing is not allowed'],
  [/\b(curl|wget|iwr|Invoke-WebRequest)\b[^|]*\|\s*(ba|z)?sh\b/, 'piping a download into a shell is not allowed'],
  [/\b(iex|Invoke-Expression)\b.*\b(iwr|Invoke-WebRequest|DownloadString)\b/, 'executing downloaded scripts is not allowed'],
  [/(^|[\s;&|])(cat|type|Get-Content|gc|less|more|head|tail|grep|findstr|Select-String)\s+[^|;&]*\.env(?!\.example\b)(\.[\w-]+)?(?![\w.])/i, 'reading .env files is not allowed'],
  [/\bgit\s+add\b[^;&|\n]*docs[\\/]private/,'docs/private holds personal documents and must never be committed'],
  [/\bgit\s+add\s+(-f|--force)\b/, 'force-adding ignored files is not allowed'],
];

// ---------------------------------------------------------------------------------------------
// 2. Never allowed: security controls, OS damage, credential theft, malware techniques.
const SYS_DIR = /([a-z]:[\\/]+(Windows|Program Files( \(x86\))?|ProgramData)\b|\$env:(windir|SystemRoot|ProgramFiles|ProgramData)\b|%(windir|SystemRoot|ProgramFiles|ProgramData)%|(^|[\s"'=])\/(etc|usr|bin|sbin|boot|lib|lib64|System|Library)(\/|\s|$))/i;
const WRITE_VERB = /\b(rm|del|erase|rd|rmdir|Remove-Item|ri|mv|move|Move-Item|ren|rename|Rename-Item|cp|copy|Copy-Item|xcopy|robocopy|Set-Content|Add-Content|Out-File|New-Item|icacls|takeown|chmod|chown|tee|mklink|ln)\b|\bsed\s+-i|(?<![0-9&])>/i;

// Credential stores and key material, whatever program is used to open them.
const CRED_PATH = /(\.ssh[\\/]|(^|[\s"'\/\\=])id_(rsa|ed25519|ecdsa|dsa)(\.pub)?(?![\w.])|\.git-credentials\b|(^|[\s"'\/\\])[._]netrc\b|\.aws[\\/](credentials|config)\b|\.azure[\\/]|gh[\\/]hosts\.yml|\.docker[\\/]config\.json|\.kube[\\/]config\b|\.claude[\\/]\.credentials\.json|\.gnupg[\\/]|Microsoft[\\/](Credentials|Protect|Vault)[\\/]|\.m2[\\/]settings(-security)?\.xml|(~|\$HOME|\$env:USERPROFILE|%USERPROFILE%|[\\/]Users[\\/][^\\/\s"']+)[\\/]\.npmrc\b|(^|[\s"'\/\\])secrets[\\/]|[\w-]\.(pem|p12|pfx|jks|keystore)(?![\w.])|[\w-]\.key(?![\w.(]))/i;

const neverRules = [
  // Security protections
  [/\b(Set|Add)-MpPreference\b/i, 'changing Microsoft Defender settings or exclusions is not allowed'],
  [/\b(DisableAntiSpyware|DisableRealtimeMonitoring|DisableBehaviorMonitoring|DisableIOAVProtection|DisableScriptScanning|TamperProtection)\b/i, 'disabling antivirus protection is not allowed'],
  [/\b(sc(\.exe)?|net)\s+(stop|config|delete|pause)\s+"?(WinDefend|WdNisSvc|WdFilter|Sense|mpssvc|BFE|wscsvc|SecurityHealthService|EventLog)\b/i, 'stopping or reconfiguring a security service is not allowed'],
  [/\b(Stop|Set|Remove|Suspend)-Service\b.*\b(WinDefend|WdNisSvc|Sense|mpssvc|BFE|wscsvc|SecurityHealthService|EventLog)\b/i, 'stopping or reconfiguring a security service is not allowed'],
  [/\bnetsh\b.*\b(advfirewall|firewall)\b.*\b(off|disable|delete|reset)\b/i, 'turning off or resetting the firewall is not allowed'],
  [/\b(Set-NetFirewallProfile|Disable-NetFirewallRule|Remove-NetFirewallRule)\b/i, 'turning off firewall profiles or rules is not allowed'],
  [/\b(EnableLUA|ConsentPromptBehaviorAdmin|SmartScreenEnabled|EnableSmartScreen)\b/i, 'changing UAC or SmartScreen is not allowed'],
  [/\b(AmsiUtils|amsiInitFailed|AmsiScanBuffer)\b/i, 'tampering with AMSI is not allowed'],
  [/\bwevtutil\s+(cl|clear-log)\b|\bClear-EventLog\b|\bauditpol\s+\/(set|clear|remove)\b/i, 'clearing event logs or audit policy is not allowed'],
  [/\bbcdedit\b/i, 'changing boot configuration is not allowed'],
  [/\bSet-ExecutionPolicy\b(?!.*-Scope\s+Process\b).*\b(Unrestricted|Bypass)\b/i, 'weakening the PowerShell execution policy beyond the current process is not allowed'],
  [/\bclaude\b.*--dangerously-skip-permissions|--permission-mode\s+bypassPermissions\b|\bdisableAllHooks\b/i, 'turning off Claude Code permissions or hooks is not allowed'],
  // OS and data destruction
  [/(^|[\s;&|(])format(\.com)?\s+[a-z]:/i, 'formatting a drive is not allowed'],
  [/\b(diskpart|Format-Volume|Clear-Disk|Initialize-Disk|Remove-Partition|Set-Partition|mkfs(\.\w+)?|fdisk|sfdisk)\b/i, 'disk partitioning or formatting is not allowed'],
  [/\bdd\b[^;&|]*\bof=\/dev\//, 'writing raw devices is not allowed'],
  [/\bvssadmin\b.*\b(delete|resize)\b|\bwmic\b.*\bshadowcopy\b.*\bdelete\b|\bwbadmin\s+delete\b|Win32_ShadowCopy\b.*\b(Delete|Remove)/i, 'deleting backups or shadow copies is not allowed'],
  [/\bcipher(\.exe)?\s+\/w\b|\b(shred|sdelete|wipe)\b/i, 'secure-erase tools are not allowed'],
  [/:\(\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;\s*:/, 'fork bombs are not allowed'],
  [/\bgit\s+clean\b[^;&|]*\s-[a-zA-Z]*[xX]/, 'git clean -x/-X deletes ignored files, including docs/private and local .env files'],
  // Credentials
  [/\bgh\s+auth\s+token\b|\bgh\s+auth\s+status\b.*(--show-token|\s-t\b)/i, 'printing the GitHub token is not allowed'],
  [/\bgit\s+credential(-\w+)?\s+(fill|get)\b/i, 'reading stored git credentials is not allowed'],
  [/\bcmdkey\s+\/list\b|\bvaultcmd\b|\bmimikatz\b|\bsekurlsa\b|\bprocdump\b.*\blsass\b|\bcomsvcs(\.dll)?\b.*\bMiniDump\b|\breg(\.exe)?\s+save\s+hklm\\(sam|system|security)\b/i, 'dumping system credentials is not allowed'],
  [/\$(\{|env:)?[A-Za-z_]*(TOKEN|SECRET|PASSWORD|PASSWD|API_?KEY|PRIVATE_?KEY|ACCESS_?KEY)[A-Za-z_]*\}?|%[A-Za-z_]*(TOKEN|SECRET|PASSWORD|API_?KEY)[A-Za-z_]*%/i, 'expanding a secret environment variable is not allowed'],
  [CRED_PATH, 'credential files and private keys must not be read, copied or changed'],
  // Malware techniques
  [/\b(powershell|pwsh)(\.exe)?\b.*\s-(e|ec|en|enc|enco|encodedcommand)\s+[A-Za-z0-9+\/=]{16,}/i, 'encoded PowerShell commands are not allowed'],
  [/FromBase64String\b.*\b(iex|Invoke-Expression|Invoke-Command)\b|\b(iex|Invoke-Expression)\b.*FromBase64String/i, 'executing decoded payloads is not allowed'],
  [/\bbase64\s+(-d|--decode)\b[^;&]*\|\s*(ba|z|da)?sh\b/i, 'executing decoded payloads is not allowed'],
  [/\b(irm|Invoke-RestMethod)\b.*\|\s*(iex|Invoke-Expression)\b/i, 'executing downloaded scripts is not allowed'],
  [/\bcertutil(\.exe)?\b.*-(urlcache|decode|decodehex)\b|\bbitsadmin\b.*\/transfer\b|\bmshta(\.exe)?\b|\bregsvr32\b.*\/i:|\brundll32\b.*(javascript|https?:)/i, 'living-off-the-land download or execution is not allowed'],
  [/\b(nc|ncat|netcat)\b.*\s-(e|c)\s|\/dev\/tcp\/|System\.Net\.Sockets\.TCPClient|\bsocat\b.*\bexec:/i, 'reverse shells are not allowed'],
  [/\b(reg(\.exe)?\s+(add|import)|Set-ItemProperty|New-ItemProperty)\b.*\\(Run|RunOnce|Winlogon|Image File Execution Options)\b/i, 'adding autostart entries is not allowed'],
  [/(Start Menu[\\\/]Programs[\\\/]Startup|shell:startup)/i, 'writing to the Startup folder is not allowed'],
  [/\b(SetWindowsHookEx|GetAsyncKeyState)\b/i, 'keystroke capture is not allowed'],
];

for (const [re, reason] of [...repoRules, ...neverRules]) {
  if (re.test(c)) block(`${reason}.\nCommand: ${cmd}`);
}
if (SYS_DIR.test(c) && WRITE_VERB.test(c)) {
  block(`changing system folders is not allowed. Keep changes inside the project.\nCommand: ${cmd}`);
}

// ---------------------------------------------------------------------------------------------
// 3. Recursive deletes: only build output folders (rm, cmd rd/del, PowerShell aliases).
const unsafeTargets = (args) =>
  args
    .filter((t) => t && !/^[-\/][a-zA-Z]+$/.test(t))
    .map((t) => t.replace(/\\/g, '/').replace(/^["']|["']$/g, ''))
    .filter((t) => !SAFE_DELETE.test(t));

const rm = c.match(/\brm\s+(-[a-zA-Z]*r[a-zA-Z]*f?[a-zA-Z]*|-[a-zA-Z]*f[a-zA-Z]*r[a-zA-Z]*|-r\s+-f|-f\s+-r)\s+([^;&|]+)/);
if (rm) {
  const targets = rm[2].trim().split(/\s+/);
  const unsafe = targets.filter((t) => !SAFE_DELETE.test(t.replace(/\\/g, '/').replace(/^["']|["']$/g, '')));
  if (unsafe.length) block(`recursive delete outside build folders: ${unsafe.join(', ')}. Ask the user to delete it.`);
}
if (/\bRemove-Item\b.*-Recurse/i.test(c)) {
  const m = c.match(/Remove-Item\s+(-\w+\s+)*["']?([^"'\s]+)/i);
  const target = (m?.[2] ?? '').replace(/\\/g, '/');
  if (!SAFE_DELETE.test(target)) block(`recursive delete outside build folders: ${target || '(unknown)'}. Ask the user to delete it.`);
}
const cmdDel = c.match(/(?:^|[\s;&|(])(rd|rmdir)\s+([^;&|]*\/s\b[^;&|]*)/i) ?? c.match(/(?:^|[\s;&|(])(del|erase)\s+([^;&|]*\/s\b[^;&|]*)/i);
if (cmdDel) {
  const unsafe = unsafeTargets(cmdDel[2].trim().split(/\s+/));
  if (unsafe.length) block(`recursive delete outside build folders: ${unsafe.join(', ')}. Ask the user to delete it.`);
}
const psAlias = c.match(/(?:^|[\s;&|(])(ri|rm|del|erase|rmdir|rd)\s+((?:[^;&|]*\s)?-r(ecurse)?\b[^;&|]*)/i);
if (psAlias && !rm) {
  const unsafe = unsafeTargets(psAlias[2].trim().split(/\s+/));
  if (unsafe.length) block(`recursive delete outside build folders: ${unsafe.join(', ')}. Ask the user to delete it.`);
}

// ---------------------------------------------------------------------------------------------
// 4. Needs the user's approval: system-wide, irreversible or outward-facing operations.
const SETTINGS_PATH = /\.claude[\\/](settings(\.local)?\.json|hooks[\\/])|(^|[\s"'\/\\])\.mcp\.json\b/i;
const SHELL_WRITE = /\bsed\s+-i|(?<![0-9&])>|\btee\b|\b(cp|mv|rm|del|copy|move|Set-Content|Add-Content|Out-File|Remove-Item|Copy-Item|Move-Item|New-Item|Rename-Item)\b|\b(writeFile|appendFile|unlink|rmSync|renameSync|copyFile)/i;

const askRules = [
  [/\b(npm|pnpm|yarn)\s+(i|install|add|uninstall|remove|rm|update|up)\b[^;&|]*\s(-g|--global|--location[= ]global)\b/i, 'installs or removes a global package'],
  [/\bnpm\s+(link|config\s+(set|delete|edit)|set)\b/i, 'changes npm user configuration or global links'],
  [/\b(winget|choco|scoop|msiexec|apt(-get)?|brew|dnf|yum|pacman|snap)\b[^;&|]*\s(install|uninstall|upgrade|update|remove|purge|\/i|\/x)\b/i, 'installs or removes system software'],
  [/\bpip3?\s+install\b|\bpython3?\s+-m\s+pip\s+install\b|\b(Install|Uninstall|Update)-(Module|Package|Script)\b/i, 'installs packages outside the project lockfiles'],
  [/\bsetx\b|\[(System\.)?Environment\]::SetEnvironmentVariable\b/i, 'changes persistent environment variables'],
  [/\breg(\.exe)?\s+(add|delete|import|restore|load|unload|copy)\b|\b(Set|New|Remove|Rename)-ItemProperty\b|\b(New|Remove)-Item\b[^;&|]*\bHK(LM|CU|CR|U|CC):/i, 'changes the Windows registry'],
  [/\bschtasks(\.exe)?\s+\/(create|delete|change|run)\b|\b(Register|Unregister|Set|Start)-ScheduledTask\b/i, 'changes scheduled tasks'],
  [/\bsc(\.exe)?\s+(create|config|delete|stop|start|failure)\b|\b(New|Set|Stop|Start|Restart|Remove)-Service\b|\bnet\s+(stop|start|user|localgroup)\b/i, 'changes Windows services or accounts'],
  [/\b(shutdown|logoff|Restart-Computer|Stop-Computer|reboot|poweroff)\b/i, 'shuts down or restarts the machine'],
  [/\b(icacls|cacls|takeown|Set-Acl)\b|\bchmod\s+-R\b|\bchown\b/i, 'changes file ownership or permissions'],
  [/\b(sudo|runas|gsudo)\b|\bStart-Process\b.*-Verb\s+RunAs\b/i, 'runs with elevated privileges'],
  [/\bSet-ExecutionPolicy\b(?!.*-Scope\s+Process\b)/i, 'changes the PowerShell execution policy'],
  [/\b(New-NetFirewallRule|Set-NetFirewallRule|Enable-PSRemoting|netsh)\b|\bSet-Item\s+WSMan:/i, 'changes network or remoting configuration'],
  [/\bwsl(\.exe)?\s+--(unregister|install|import|set-default)\b/i, 'changes WSL distributions'],
  [/\bgit\s+(reset\s+--hard|clean\b|checkout\s+(--\s+)?\.(\s|$)|checkout\s+--\s+\S|restore\s+(?!--staged\b)\S|stash\s+(drop|clear)\b|filter-branch\b|filter-repo\b|reflog\s+(expire|delete)\b|gc\b[^;&|]*--prune|update-ref\s+-d\b|push\b[^;&|]*--mirror\b)/i, 'discards work or rewrites git history'],
  [/\bgh\s+(repo\s+(delete|archive|rename|edit|transfer)\b|secret\b|variable\b|release\s+delete\b|ruleset\b|api\b[^;&|]*(-X|--method)\s*(DELETE|PUT|PATCH)\b)/i, 'changes GitHub repository settings, secrets or releases'],
  [/\b(curl|wget)\b[^;&|]*\s(-d|--data(-binary|-raw|-urlencode)?|-F|--form|-T|--upload-file)\s*['"]?[\w-]*=?@|\b(Invoke-RestMethod|Invoke-WebRequest|irm|iwr)\b[^;&|]*-InFile\b|\bscp\b|\brsync\b[^;&|]*\s[\w.-]+@[\w.-]+:/i, 'uploads a local file to a remote host'],
  [/\bfind\b[^;&|]*\s-delete\b|\bfind\b[^;&|]*-exec\s+rm\b|\bxargs\b[^;&|]*\brm\b/i, 'bulk-deletes files'],
  [/(^|[;&|]\s*)(printenv|env|set|Get-ChildItem\s+env:|gci\s+env:|dir\s+env:|ls\s+env:)\s*($|[;&|])/i, 'prints every environment variable, which can include tokens'],
  [/\bgit\s+commit\b[^;&|]*--amend\b/i, 'rewrites the last commit (--amend)'],
];

// Local commits run without a prompt (ADR-0003), so they must land on a work branch:
// main and develop change only through pull requests.
if (/\bgit\s+commit\b/.test(c)) {
  let branch = '';
  try {
    branch = execFileSync('git', ['branch', '--show-current'], { cwd: ROOT, encoding: 'utf8' }).trim();
  } catch {
    // Not a git checkout or git unavailable: nothing to protect here.
  }
  if (['main', 'master', 'develop'].includes(branch)) {
    block(`committing directly on ${branch} is not allowed; create a work branch from develop first.\nCommand: ${cmd}`);
  }
}

for (const [re, reason] of askRules) {
  if (re.test(c)) ask(`this command ${reason}.`);
}
if (SETTINGS_PATH.test(c) && SHELL_WRITE.test(c)) {
  ask('this command changes Claude Code settings, hooks or MCP configuration.');
}

allow();
