'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { pipeline } = require('node:stream/promises');
const { Readable } = require('node:stream');
const { execFileSync } = require('node:child_process');
const root = path.resolve(__dirname, '../..');
const desktop = path.join(root, 'desktop');
const lock = require('../runtime-lock.json');
const platform = process.env.DESKTOP_TARGET || `${process.platform}-${process.arch}`;
const pinned = lock.platforms[platform];
if (!pinned) throw new Error(`Unsupported platform: ${platform}`);
const destination = path.join(desktop, 'resources');
const downloads = path.join(desktop, '.downloads');
fs.mkdirSync(downloads, { recursive: true });

function sha(file) { return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex'); }

async function download(item) {
  const name = crypto.createHash('sha256').update(item.url).digest('hex').slice(0, 12) + '-' + new URL(item.url).pathname.split('/').pop();
  const file = path.join(downloads, name);
  if (fs.existsSync(file) && (!item.sha256 || sha(file) === item.sha256)) return file;
  for (let attempt = 0; attempt < 3; attempt++) {
    const temporary = `${file}.tmp`;
    try {
      console.log(`Downloading ${name}`);
      const response = await fetch(item.url, { signal: AbortSignal.timeout(300000) });
      if (!response.ok) throw new Error(`Download failed: ${response.status} ${item.url}`);
      await pipeline(Readable.fromWeb(response.body), fs.createWriteStream(temporary));
      if (item.sha256 && sha(temporary) !== item.sha256) throw new Error(`SHA-256 mismatch: ${name}`);
      fs.renameSync(temporary, file);
      return file;
    } catch (error) {
      fs.rmSync(temporary, { force: true });
      if (attempt === 2) throw error;
      await new Promise(resolve => setTimeout(resolve, 1500));
    }
  }
}

async function main() {
  const [pg0, jre] = await Promise.all([download(pinned.pg0), download(pinned.jre)]);
  fs.rmSync(destination, { recursive: true, force: true });
  fs.mkdirSync(path.join(destination, 'database'), { recursive: true });
  const databaseFile = path.join(destination, 'database', platform.startsWith('win32') ? 'pg0.exe' : 'pg0');
  fs.copyFileSync(pg0, databaseFile);
  fs.chmodSync(databaseFile, 0o755);
  const unpacked = path.join(downloads, `jre-${platform}`);
  fs.rmSync(unpacked, { recursive: true, force: true });
  fs.mkdirSync(unpacked, { recursive: true });
  if (jre.endsWith('.zip')) {
    execFileSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command',
      'Expand-Archive -LiteralPath $env:DESKTOP_ARCHIVE -DestinationPath $env:DESKTOP_EXTRACT -Force'], {
      stdio: 'inherit', env: { ...process.env, DESKTOP_ARCHIVE: jre, DESKTOP_EXTRACT: unpacked },
    });
  } else execFileSync('tar', ['-xzf', jre, '-C', unpacked], { stdio: 'inherit' });
  let javaHome = path.join(unpacked, fs.readdirSync(unpacked)[0]);
  if (platform.startsWith('darwin')) javaHome = path.join(javaHome, 'Contents', 'Home');
  fs.cpSync(javaHome, path.join(destination, 'jre'), { recursive: true, dereference: true });
  const backend = process.env.DESKTOP_BACKEND_JAR || path.join(root, 'backend/target/personal-ai-os-backend-0.1.0-SNAPSHOT.jar');
  if (!fs.existsSync(backend)) throw new Error('Build backend first: cd backend && ./mvnw -DskipTests package');
  const frontend = path.join(root, 'frontend/dist');
  if (!fs.existsSync(path.join(frontend, 'index.html'))) throw new Error('Build frontend first: cd frontend && npm ci && npm run build');
  fs.copyFileSync(backend, path.join(destination, 'backend.jar'));
  fs.cpSync(frontend, path.join(destination, 'frontend'), { recursive: true });
  // The actual package remains the same previously verified backend. A desktop-only
  // profile separates cookies from any web installation and keeps memory bounded.
  fs.writeFileSync(path.join(destination, 'application-desktop.properties'), [
    'server.servlet.session.cookie.name=JSESSIONID',
    'spring.datasource.hikari.maximum-pool-size=5',
    'spring.ai.openai.embedding.base-url=${SPRING_AI_OPENAI_EMBEDDING_BASE_URL}',
    'spring.ai.openai.embedding.api-key=${SPRING_AI_OPENAI_EMBEDDING_API_KEY}',
    'management.endpoints.web.exposure.include=health',
    '',
  ].join('\n'));
  const licenses = path.join(destination, 'licenses');
  fs.mkdirSync(licenses);
  for (const [name, url] of [
    ['pg0-Cargo.toml', 'https://raw.githubusercontent.com/vectorize-io/pg0/v0.16.1/Cargo.toml'],
    ['pg0-README.md', 'https://raw.githubusercontent.com/vectorize-io/pg0/v0.16.1/README.md'],
    ['PostgreSQL.txt', 'https://raw.githubusercontent.com/postgres/postgres/REL_18_1/COPYRIGHT'],
    ['pgvector.txt', 'https://raw.githubusercontent.com/pgvector/pgvector/v0.8.5/LICENSE'],
  ]) {
    const file = await download({ url });
    fs.copyFileSync(file, path.join(licenses, name));
  }
  fs.copyFileSync(path.join(desktop, 'licenses/pg0-MIT.txt'), path.join(licenses, 'pg0-MIT.txt'));
  fs.copyFileSync(path.join(root, 'LICENSE'), path.join(licenses, 'Personal-AI-OS.txt'));
  fs.copyFileSync(path.join(desktop, 'THIRD_PARTY_NOTICES.md'), path.join(licenses, 'THIRD_PARTY_NOTICES.md'));
  const revision = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim();
  const manifest = { version: require('../package.json').version, sourceRevision: revision, target: platform,
    backendSha256: sha(path.join(destination, 'backend.jar')), runtimes: lock,
    builtAt: new Date().toISOString(), includesUserData: false };
  fs.writeFileSync(path.join(destination, 'build-manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
  console.log(`Prepared self-contained resources for ${platform}`);
}

main().catch(error => { console.error(error.message); process.exitCode = 1; });
