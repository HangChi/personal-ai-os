'use strict';
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const desktop = path.resolve(__dirname, '..');
const platform = `${process.platform}-${process.arch}`;
const out = path.join(desktop, 'artifacts');
fs.mkdirSync(out, { recursive: true });
const files = fs.readdirSync(path.join(desktop, 'dist')).filter(name => /\.(exe|dmg|zip)$/.test(name));
if (!files.length) throw new Error('No installer files found');
for (const file of files) fs.copyFileSync(path.join(desktop, 'dist', file), path.join(out, file));
fs.copyFileSync(path.join(desktop, 'resources/build-manifest.json'), path.join(out, `build-manifest-${platform}.json`));
  for (const file of ['smoke.json', 'packaged-smoke.json', 'ui-smoke.json']) {
  if (!fs.existsSync(path.join(out, file))) throw new Error(`Missing smoke result: ${file}`);
  fs.renameSync(path.join(out, file), path.join(out, `${file.replace('.json', '')}-${platform}.json`));
}
if (fs.existsSync(path.join(out, 'workbench.png'))) fs.renameSync(path.join(out, 'workbench.png'), path.join(out, `workbench-${platform}.png`));
fs.copyFileSync(path.join(desktop, 'THIRD_PARTY_NOTICES.md'), path.join(out, `THIRD_PARTY_NOTICES-${platform}.md`));
async function main() {
  const sums = [];
  for (const name of fs.readdirSync(out).sort()) {
    if (name.startsWith('SHA256SUMS')) continue;
    const hash = crypto.createHash('sha256');
    for await (const chunk of fs.createReadStream(path.join(out, name))) hash.update(chunk);
    sums.push(`${hash.digest('hex')}  ${name}`);
  }
  fs.writeFileSync(path.join(out, `SHA256SUMS-${platform}.txt`), sums.join('\n') + '\n');
  console.log('Staged verified artifacts:', files.join(', '));
}
main().catch(error => { console.error(error); process.exitCode = 1; });
