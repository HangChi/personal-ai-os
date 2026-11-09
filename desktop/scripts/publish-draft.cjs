'use strict';
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const os = require('node:os');
const { execFileSync } = require('node:child_process');
const platforms = ['darwin-arm64', 'darwin-x64', 'win32-x64'];

async function digest(file) {
  const hash = crypto.createHash('sha256');
  for await (const chunk of fs.createReadStream(file)) hash.update(chunk);
  return hash.digest('hex');
}

async function verifyArtifacts(directory, request) {
  const selected = [];
  for (const platform of platforms) {
    const artifact = path.join(directory, 'desktop-' + platform);
    const manifest = JSON.parse(fs.readFileSync(path.join(artifact, `build-manifest-${platform}.json`), 'utf8'));
    if (manifest.sourceRevision !== request.sourceRevision || manifest.target !== platform ||
        manifest.version !== request.version || manifest.includesUserData !== false) throw new Error(`Build manifest mismatch: ${platform}`);
    for (const suite of ['smoke', 'packaged-smoke', 'ui-smoke']) {
      const evidence = JSON.parse(fs.readFileSync(path.join(artifact, `${suite}-${platform}.json`), 'utf8'));
      if (evidence.passed !== true || evidence.platform !== platform || !evidence.checks?.length) throw new Error(`Failed/missing native evidence: ${suite}/${platform}`);
    }
    const sums = new Map(fs.readFileSync(path.join(artifact, `SHA256SUMS-${platform}.txt`), 'utf8').trim().split('\n')
      .map(line => [line.slice(66), line.slice(0, 64)]));
    const installers = fs.readdirSync(artifact).filter(name => /\.(exe|dmg)$/.test(name));
    if (installers.length !== 1) throw new Error(`Expected exactly one native installer: ${platform}`);
    const expected = `Personal-AI-OS-${request.version}-${platform.startsWith('darwin') ? 'mac' : 'win'}-${platform.split('-')[1]}.${platform.startsWith('darwin') ? 'dmg' : 'exe'}`;
    if (installers[0] !== expected) throw new Error(`Unexpected installer name: ${installers[0]}`);
    for (const name of [...installers, `build-manifest-${platform}.json`, `smoke-${platform}.json`, `packaged-smoke-${platform}.json`, `ui-smoke-${platform}.json`, `THIRD_PARTY_NOTICES-${platform}.md`]) {
      const file = path.join(artifact, name);
      const sha256 = await digest(file);
      if (sums.get(name) !== sha256) throw new Error(`Artifact checksum mismatch: ${name}`);
      selected.push({ file, name, sha256, size: fs.statSync(file).size });
    }
  }
  return selected;
}

function gh(args, options = {}) {
  return (execFileSync('gh', args, { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024, ...options }) || '').trim();
}

async function main() {
  const request = require('../../specs/040-desktop-installers/release-request.json');
  if (!/^\d+$/.test(String(request.buildRunId)) || !/^[0-9a-f]{40}$/.test(request.sourceRevision) ||
      !/^\d+\.\d+\.\d+$/.test(request.version)) throw new Error('Invalid explicit release request');
  const repo = process.env.GITHUB_REPOSITORY;
  if (repo !== 'HangChi/personal-ai-os') throw new Error('Refusing to publish to an unexpected repository');
  const runPath = `repos/${repo}/actions/runs/${request.buildRunId}`;
  const deadline = Date.now() + 35 * 60 * 1000;
  let run;
  let previous;
  do {
    run = JSON.parse(gh(['api', runPath]));
    if (run.head_sha !== request.sourceRevision || run.head_repository.full_name !== repo ||
        run.path !== '.github/workflows/desktop.yml') throw new Error('Requested run does not match reviewed installer source');
    if (run.status !== previous) { console.log(`Native installer build: ${run.status}`); previous = run.status; }
    if (run.status === 'completed') break;
    if (Date.now() > deadline) throw new Error('Installer build did not complete before release timeout');
    await new Promise(resolve => setTimeout(resolve, 10000));
  } while (true);
  if (run.conclusion !== 'success') throw new Error(`Native build failed: ${run.conclusion}; no release created`);
  const directory = fs.mkdtempSync(path.join(process.env.RUNNER_TEMP || os.tmpdir(), 'desktop-release-'));
  gh(['run', 'download', String(request.buildRunId), '--repo', repo, '--pattern', 'desktop-*', '--dir', directory], { stdio: 'inherit' });
  const selected = await verifyArtifacts(directory, request);
  const output = path.join(directory, 'release');
  fs.mkdirSync(output);
  for (const item of selected) fs.copyFileSync(item.file, path.join(output, item.name));
  const releaseManifest = path.join(output, 'desktop-release-manifest.json');
  fs.writeFileSync(releaseManifest, JSON.stringify({ version: request.version, sourceRevision: request.sourceRevision,
    buildRunId: request.buildRunId, nativeBuildUrl: run.html_url, publisherSigned: false, appleNotarized: false,
    assets: selected.map(({ file, ...item }) => item) }, null, 2) + '\n');
  const sums = selected.map(item => `${item.sha256}  ${item.name}`);
  sums.push(`${await digest(releaseManifest)}  desktop-release-manifest.json`);
  fs.writeFileSync(path.join(output, 'SHA256SUMS'), sums.join('\n') + '\n');
  const tag = 'v' + request.version;
  const source = `https://github.com/${repo}/blob/${request.sourceRevision}/`;
  const notes = path.join(directory, 'notes.md');
  fs.writeFileSync(notes, `# Personal AI OS ${tag} · 完整本地桌面版预览

Windows / macOS 安装后自带 Java 21 和独立 PostgreSQL/pgvector，无需安装 Java、Node 或 Docker。
首次启动配置 OpenAI 兼容对话服务和输出 **1024 维**向量的嵌入服务；不附带模型权重。

| 系统 | 安装包 |
| --- | --- |
| Windows 10/11 x64 | \`Personal-AI-OS-${request.version}-win-x64.exe\` |
| macOS 14+ Apple Silicon | \`Personal-AI-OS-${request.version}-mac-arm64.dmg\` |
| macOS 15+ Intel | \`Personal-AI-OS-${request.version}-mac-x64.dmg\` |

Windows 按向导安装；Mac 打开 DMG 后将应用拖入 Applications。先校验附件 SHA-256。

**这是没有受信发布者签名的预览版。** Mac 包具有通过完整性校验的 ad-hoc 签名，未做 Apple
notarization；Windows 未做发布者签名，系统可能阻止直接打开或提示未知发布者。

## 已完成

- 三个平台均在各自原生 runner 构建，并执行内置运行时、全新数据库 V1–V49 迁移、认证/CSRF、
  模型 HTTP 桩、知识库上传和独立 1024 维嵌入、重启持久化、正常停库验证。
- 首次配置表单、自动登录、真实 Vue 工作台和界面偏好跨重启保留均通过 Electron 窗口回归。
- 打包后的 JRE/数据库/Java 后端再次执行完整原生启动回归；Mac 应用包通过 codesign 深度完整性校验。
- 配置使用系统加密服务保存，系统加密不可用时提示用户目录权限保护回退；本地服务只监听回环地址，
  不继承开发环境的数据库和模型配置。

证据与源码：[原生构建记录](${run.html_url})、[桌面版指南](${source}desktop/README.md)、
[源码提交](https://github.com/${repo}/commit/${request.sourceRevision})。附件包含三平台机器结果、
构建来源清单和 SHA256SUMS；使用合成模型验证，无收费模型调用。

## 数据与边界

- 数据保存在系统用户目录，菜单可打开数据及日志目录；退出正常停止服务，卸载保留数据。
- 桌面库是独立 PostgreSQL 18.1，不接管现有 PostgreSQL 17 / Docker 实例；不要直接搬入已有物理数据目录。
- 对话模型和知识嵌入服务可分别配置。更换已有知识库的嵌入模型需要按运维说明重建索引。
- Java 仍为主后端；Go 候选生产切换门禁保持开放。原业务验收报告与已知边界继续有效。
- 本次提供安装附件，不部署或改写现有运行实例；原 v0.1.0 Java 发布保留。
`);
  let existing;
  try { existing = JSON.parse(gh(['release', 'view', tag, '--repo', repo, '--json', 'isDraft,targetCommitish'])); }
  catch { /* first publication */ }
  if (existing) {
    if (!existing.isDraft || existing.targetCommitish !== request.sourceRevision) throw new Error('Refusing to replace a published or mismatched release');
  } else {
    gh(['release', 'create', tag, '--repo', repo, '--target', request.sourceRevision,
      '--title', `Personal AI OS ${tag} — 完整本地桌面版（预览）`, '--notes-file', notes, '--draft', '--prerelease']);
  }
  gh(['release', 'upload', tag, '--repo', repo, '--clobber', ...fs.readdirSync(output).map(name => path.join(output, name))], { stdio: 'inherit' });
  gh(['release', 'edit', tag, '--repo', repo, '--notes-file', notes]);
  console.log(`Verified desktop release draft uploaded: ${tag}`);
}

if (require.main === module) main().catch(error => { console.error(error.message); process.exitCode = 1; });
module.exports = { verifyArtifacts };
