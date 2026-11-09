'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const desktop = path.resolve(__dirname, '..');
let resources;
if (process.platform === 'darwin') {
  const folders = fs.readdirSync(path.join(desktop, 'dist')).filter(name => name === 'mac' || name.startsWith('mac-'));
  const folder = folders.find(name => name.endsWith(process.arch)) || folders[0];
  resources = path.join(desktop, 'dist', folder, 'Personal AI OS.app/Contents/Resources/runtime');
  execFileSync('codesign', ['--verify', '--deep', '--strict', path.join(desktop, 'dist', folder, 'Personal AI OS.app')], { stdio: 'inherit' });
} else {
  resources = path.join(desktop, 'dist/win-unpacked/resources/runtime');
}
for (const file of ['backend.jar', 'frontend/index.html', 'build-manifest.json', 'licenses/Personal-AI-OS.txt']) {
  if (!fs.statSync(path.join(resources, file)).isFile()) throw new Error(`Missing packaged resource: ${file}`);
}
execFileSync(process.execPath, [path.join(__dirname, 'smoke.cjs')], {
  stdio: 'inherit', env: { ...process.env, DESKTOP_SMOKE_RESOURCES: resources,
    DESKTOP_SMOKE_REPORT: path.join(desktop, 'artifacts/packaged-smoke.json') }, timeout: 300000,
});
console.log('Packaged offline runtimes verified.');
