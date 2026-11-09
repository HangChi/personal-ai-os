'use strict';
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');
const desktop = path.resolve(__dirname, '..');
const data = fs.mkdtempSync(path.join(os.tmpdir(), 'personal-ai-os-desktop-ui-'));
const artifacts = path.join(desktop, 'artifacts');
for (const name of ['ui-smoke.json', 'workbench.png']) fs.rmSync(path.join(artifacts, name), { force: true });
for (const restart of [false, true]) {
  fs.rmSync(path.join(artifacts, 'ui-smoke.json'), { force: true });
  const result = spawnSync(require('electron'), [desktop], {
    env: { ...process.env, DESKTOP_TEST_DATA: data, DESKTOP_UI_SMOKE: artifacts,
      DESKTOP_UI_EXPECT_RESTART: restart ? '1' : '' },
    stdio: 'inherit', timeout: 240000,
  });
  if (result.error) throw result.error;
  assert.equal(result.status, 0, `Desktop window test failed. Logs retained in ${data}`);
  assert.equal(JSON.parse(fs.readFileSync(path.join(artifacts, 'ui-smoke.json'))).passed, true);
}
console.log('First-run wizard and Electron workbench verified.');
