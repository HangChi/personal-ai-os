'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const { verifyArtifacts } = require('../scripts/publish-draft.cjs');

test('release gating requires matching source, every native suite and exact attachment checksums', async t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'desktop-release-test-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const request = { sourceRevision: 'a'.repeat(40), version: '0.1.1' };
  for (const platform of ['darwin-arm64', 'darwin-x64', 'win32-x64']) {
    const artifact = path.join(directory, 'desktop-' + platform);
    fs.mkdirSync(artifact);
    const installer = `Personal-AI-OS-0.1.1-${platform.startsWith('darwin') ? 'mac' : 'win'}-${platform.split('-')[1]}.${platform.startsWith('darwin') ? 'dmg' : 'exe'}`;
    fs.writeFileSync(path.join(artifact, installer), 'test native artifact');
    fs.writeFileSync(path.join(artifact, `build-manifest-${platform}.json`), JSON.stringify({
      ...request, target: platform, includesUserData: false,
    }));
    for (const suite of ['smoke', 'packaged-smoke', 'ui-smoke']) fs.writeFileSync(path.join(artifact, `${suite}-${platform}.json`),
      JSON.stringify({ passed: true, platform, checks: ['native execution'] }));
    fs.writeFileSync(path.join(artifact, `THIRD_PARTY_NOTICES-${platform}.md`), 'test notice');
    const sums = fs.readdirSync(artifact).map(name => `${crypto.createHash('sha256').update(fs.readFileSync(path.join(artifact, name))).digest('hex')}  ${name}`);
    fs.writeFileSync(path.join(artifact, `SHA256SUMS-${platform}.txt`), sums.join('\n') + '\n');
  }
  assert.equal((await verifyArtifacts(directory, request)).length, 18);
  await assert.rejects(verifyArtifacts(directory, { ...request, sourceRevision: 'b'.repeat(40) }), /manifest mismatch/);
  const suite = path.join(directory, 'desktop-win32-x64/ui-smoke-win32-x64.json');
  const original = fs.readFileSync(suite);
  fs.writeFileSync(suite, JSON.stringify({ passed: false }));
  await assert.rejects(verifyArtifacts(directory, request), /evidence/);
  fs.writeFileSync(suite, original);
  fs.appendFileSync(path.join(directory, 'desktop-darwin-arm64/Personal-AI-OS-0.1.1-mac-arm64.dmg'), 'tampering');
  await assert.rejects(verifyArtifacts(directory, request), /checksum mismatch/);
});
