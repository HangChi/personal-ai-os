'use strict';

const fs = require('node:fs');
const path = require('node:path');
const net = require('node:net');
const { spawn, execFile } = require('node:child_process');
const { promisify } = require('node:util');
const { pathToFileURL } = require('node:url');
const { privateDirectory } = require('./config.cjs');
const { startProxy } = require('./proxy.cjs');
const exec = promisify(execFile);
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

async function waitForExit(child, timeout) {
  if (child.exitCode !== null || child.signalCode !== null) return;
  await new Promise(resolve => {
    const finish = () => { clearTimeout(timer); child.removeListener('exit', finish); resolve(); };
    const timer = setTimeout(finish, timeout);
    child.once('exit', finish);
  });
}

function systemEnvironment(source = process.env) {
  const environment = {};
  // Do not inherit project .env, JAVA_TOOL_OPTIONS, Spring overrides or model keys.
  for (const key of ['PATH', 'SystemRoot', 'SYSTEMROOT', 'WINDIR', 'COMSPEC', 'PATHEXT',
    'TEMP', 'TMP', 'TMPDIR', 'HOME', 'USERPROFILE', 'LOCALAPPDATA', 'APPDATA',
    'LANG', 'LC_ALL', 'DISPLAY']) {
    if (source[key] !== undefined) environment[key] = source[key];
  }
  return { ...environment, TZ: 'UTC', LC_ALL: 'C' };
}

function backendEnvironment(config, directory, databasePort, backendPort) {
  return {
    ...systemEnvironment(),
    SERVER_ADDRESS: '127.0.0.1', SERVER_PORT: String(backendPort),
    APP_DOTENV_ENABLED: 'false', SPRING_PROFILES_ACTIVE: 'desktop',
    POSTGRES_HOST: '127.0.0.1', POSTGRES_PORT: String(databasePort),
    POSTGRES_DB: 'personal_ai_os', POSTGRES_USER: 'postgres', POSTGRES_PASSWORD: config.databasePassword,
    AUTH_ENABLED: 'true', AUTH_USERNAME: 'desktop', AUTH_PASSWORD: config.authPassword,
    APP_SECURITY_CSRF_ENABLED: 'true', SESSION_COOKIE_SECURE: 'false',
    MODEL_KEY_ENCRYPTION_KEY: config.encryptionKey,
    AI_CHAT_PROVIDER: 'openai', AI_EMBEDDING_PROVIDER: 'openai',
    OPENAI_BASE_URL: config.chatUrl, OPENAI_API_KEY: config.chatKey || 'local-no-api-key',
    OPENAI_CHAT_MODEL: config.chatModel, OPENAI_EMBEDDING_MODEL: config.embeddingModel,
    SPRING_AI_OPENAI_EMBEDDING_BASE_URL: config.embeddingUrl,
    SPRING_AI_OPENAI_EMBEDDING_API_KEY: config.embeddingKey || 'local-no-api-key',
    AI_KNOWLEDGE_EMBEDDING_DIMENSIONS: '1024',
    AI_CHAT_UPLOAD_DIR: path.join(directory, 'uploads'),
    REDIS_ENABLED: 'false', RABBITMQ_ENABLED: 'false', S3_ENABLED: 'false', OPENSEARCH_ENABLED: 'false',
    MCP_ENABLED: 'false', PERSONAL_MCP_SERVER_ENABLED: 'false',
    WEB_PUSH_ENABLED: 'false', REMINDER_WEBHOOK_ENABLED: 'false',
    APP_MEMORY_BACKFILL_ENABLED: 'false', AI_CONTEXT_AUTO_MEMORY_ENABLED: 'false',
  };
}

async function freePort() {
  const server = net.createServer();
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  const port = server.address().port;
  await new Promise(resolve => server.close(resolve));
  return port;
}

function redact(text, config) {
  let result = String(text);
  for (const secret of [config.databasePassword, config.authPassword, config.encryptionKey, config.chatKey, config.embeddingKey]) {
    if (secret) result = result.split(secret).join('[redacted]');
  }
  return result.replace(/(postgres(?:ql)?:\/\/[^:\s]+:)[^@\s]+@/g, '$1[redacted]@');
}

class LocalRuntime {
  constructor(resources, directory, config, onStatus = () => {}) {
    this.resources = resources;
    this.directory = directory;
    this.config = config;
    this.onStatus = onStatus;
    this.backend = null;
    this.proxy = null;
    this.startedDatabase = false;
    privateDirectory(directory);
    privateDirectory(path.join(directory, 'logs'));
    this.pg0 = path.join(resources, 'database', process.platform === 'win32' ? 'pg0.exe' : 'pg0');
    this.pgEnvironment = {
      ...systemEnvironment(), PG0_HOME: path.join(directory, 'database'),
      PG0_INSTALLATION_DIR: path.join(directory, 'database-runtime'),
    };
  }

  status(stage, message) { this.onStatus({ stage, message }); }

  async pg(args) {
    try {
      return await exec(this.pg0, args, {
        env: this.pgEnvironment, cwd: this.directory, timeout: 120000,
        maxBuffer: 4 * 1024 * 1024, windowsHide: true,
      });
    } catch (error) {
      // pg0 prints credentials on start and info. Never surface raw stdout/arguments.
      fs.appendFileSync(path.join(this.directory, 'logs', 'database-launch.log'),
        redact(`${new Date().toISOString()} ${error.stderr || error.stdout || error.code}\n`, this.config), { mode: 0o600 });
      throw new Error('本地数据库未能启动或停止，请打开日志目录查看 database-launch.log');
    }
  }

  async databaseInfo() {
    return JSON.parse((await this.pg(['info', '--name', 'desktop', '-o', 'json'])).stdout);
  }

  async start() {
    if (!this.config.configured) throw new Error('请先完成模型配置');
    try {
      this.status('database', '正在准备本地数据库，首次启动需要解压运行文件…');
      privateDirectory(this.pgEnvironment.PG0_HOME);
      let info = await this.databaseInfo();
      if (!info.running) {
        const port = await freePort();
        await this.pg(['start', '--name', 'desktop', '--port', String(port),
          '--username', 'postgres', '--password', this.config.databasePassword,
          '--database', 'personal_ai_os', '-c', 'listen_addresses=127.0.0.1',
          '-c', 'shared_buffers=128MB', '-c', 'maintenance_work_mem=128MB', '-c', 'work_mem=8MB']);
        this.startedDatabase = true;
        info = await this.databaseInfo();
      } else {
        // A previous interrupted desktop session can leave its own database alive.
        this.startedDatabase = true;
      }
      if (!info.running || info.username !== 'postgres' || info.database !== 'personal_ai_os' ||
          !path.resolve(info.data_dir).startsWith(path.resolve(this.pgEnvironment.PG0_HOME) + path.sep)) {
        throw new Error('数据库目录或运行状态异常；已停止启动，请保留数据并查看日志');
      }
      this.databasePort = info.port;
      this.backendPort = await freePort();
      this.status('backend', '正在启动工作台并检查数据结构…');
      const java = path.join(this.resources, 'jre', 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
      const settings = path.join(this.resources, 'application-desktop.properties');
      const logFile = fs.openSync(path.join(this.directory, 'logs', 'backend.log'), 'a', 0o600);
      this.backend = spawn(java, ['-Xms128m', '-Xmx1024m', '-jar', path.join(this.resources, 'backend.jar'),
        `--spring.config.additional-location=${pathToFileURL(settings).href}`], {
        cwd: this.directory, env: backendEnvironment(this.config, this.directory, info.port, this.backendPort),
        stdio: ['ignore', logFile, logFile], windowsHide: true,
      });
      fs.closeSync(logFile);
      let launchError;
      this.backend.on('error', error => { launchError = error; });
      const deadline = Date.now() + 150000;
      let healthy = false;
      while (Date.now() < deadline) {
        if (launchError || this.backend.exitCode !== null) throw new Error('工作台服务启动失败，请打开日志目录查看 backend.log');
        try {
          const response = await fetch(`http://127.0.0.1:${this.backendPort}/api/v1/health`, { signal: AbortSignal.timeout(1500) });
          if (response.ok) { healthy = true; break; }
        } catch { /* wait for JVM, Flyway and embedded HTTP server */ }
        await delay(500);
      }
      if (!healthy) throw new Error('工作台启动超过等待时间，请查看 backend.log 后重试');
      this.status('frontend', '正在打开你的本地工作台…');
      this.proxy = await startProxy(path.join(this.resources, 'frontend'), this.backendPort, this.config.frontendPort || 0);
      const cookie = await this.login();
      await this.initializeModelConfiguration(cookie);
      this.status('ready', '工作台已就绪');
      return { origin: this.proxy.origin, cookie };
    } catch (error) {
      await this.stop();
      throw error;
    }
  }

  async login() {
    const base = `http://127.0.0.1:${this.backendPort}/api/v1`;
    const response = await fetch(`${base}/auth/csrf`, { signal: AbortSignal.timeout(10000) });
    const csrf = (await response.json()).data.token;
    const initialCookie = response.headers.getSetCookie().find(value => value.startsWith('JSESSIONID='))?.split(';')[0];
    const login = await fetch(`${base}/auth/login`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf, Cookie: initialCookie || '' },
      body: JSON.stringify({ username: 'desktop', password: this.config.authPassword }),
      signal: AbortSignal.timeout(15000),
    });
    if (!login.ok || (await login.json()).data?.username !== 'desktop') throw new Error('本地服务自动登录失败，请重新启动应用');
    const cookie = login.headers.getSetCookie().find(value => value.startsWith('JSESSIONID='))?.split(';')[0];
    if (!cookie) throw new Error('本地服务未返回登录会话，请重新启动应用');
    return cookie.slice('JSESSIONID='.length);
  }

  async initializeModelConfiguration(cookie) {
    const base = `http://127.0.0.1:${this.backendPort}/api/v1`;
    const headers = { Cookie: `JSESSIONID=${cookie}` };
    const current = await fetch(`${base}/settings/model`, { headers, signal: AbortSignal.timeout(10000) });
    const state = await current.json();
    if (state.data?.revision != null && !this.config.applyModelConfig) return;
    const csrfResponse = await fetch(`${base}/auth/csrf`, { headers, signal: AbortSignal.timeout(10000) });
    const csrf = (await csrfResponse.json()).data.token;
    const saved = await fetch(`${base}/settings/model`, {
      method: 'PUT', headers: { ...headers, 'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf },
      body: JSON.stringify({ baseUrl: this.config.chatUrl, apiKey: this.config.chatKey || 'local-no-api-key',
        chatModel: this.config.chatModel, embeddingModel: this.config.embeddingModel, temperature: 0.7 }),
      signal: AbortSignal.timeout(15000),
    });
    if (!saved.ok || (await saved.json()).code !== 0) throw new Error('模型配置保存失败，请在首次设置中检查地址和模型名称');
  }

  async stop() {
    if (this.proxy) { await this.proxy.close(); this.proxy = null; }
    if (this.backend) {
      const backend = this.backend;
      this.backend = null;
      if (backend.exitCode === null && backend.pid) {
        backend.kill('SIGTERM');
        await waitForExit(backend, 20000);
        if (backend.exitCode === null && backend.signalCode === null) {
          backend.kill('SIGKILL');
          await waitForExit(backend, 5000);
        }
      }
    }
    if (this.startedDatabase) {
      await this.pg(['stop', '--name', 'desktop', '--timeout', '30']);
      this.startedDatabase = false;
    }
  }
}

module.exports = { LocalRuntime, backendEnvironment, systemEnvironment, freePort, redact };
