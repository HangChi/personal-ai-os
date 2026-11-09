'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

function endpoint(value, field) {
  if (typeof value !== 'string' || value.length > 500) throw new Error(`${field}地址无效`);
  let url;
  try { url = new URL(value.trim()); } catch { throw new Error(`${field}地址无效`); }
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) {
    throw new Error(`${field}请填写不带用户名、密码或查询参数的 HTTP / HTTPS 地址`);
  }
  return url.href.replace(/\/$/, '');
}

function validateConfig(value) {
  if (!value || typeof value !== 'object') throw new Error('请填写模型配置');
  const result = { configured: true };
  for (const prefix of ['chat', 'embedding']) {
    const model = value[`${prefix}Model`];
    if (typeof model !== 'string' || !model.trim() || model.length > 120 || /[\r\n\0]/.test(model)) {
      throw new Error(prefix === 'chat' ? '请填写对话模型名称' : '请填写输出 1024 维向量的嵌入模型名称');
    }
    const key = value[`${prefix}Key`] || '';
    if (typeof key !== 'string' || key.length > 500 || /[\r\n\0]/.test(key)) throw new Error('API Key 格式无效');
    result[`${prefix}Url`] = endpoint(value[`${prefix}Url`], prefix === 'chat' ? '对话服务' : '嵌入服务');
    result[`${prefix}Model`] = model.trim();
    result[`${prefix}Key`] = key.trim();
  }
  return result;
}

function privateDirectory(directory) {
  fs.mkdirSync(directory, { recursive: true, mode: 0o700 });
  if (process.platform !== 'win32') fs.chmodSync(directory, 0o700);
}

function atomicJson(file, value) {
  const temporary = `${file}.${process.pid}.tmp`;
  fs.writeFileSync(temporary, JSON.stringify(value, null, 2) + '\n', { mode: 0o600 });
  fs.renameSync(temporary, file);
  if (process.platform !== 'win32') fs.chmodSync(file, 0o600);
}

class ConfigStore {
  constructor(directory, safeStorage) {
    privateDirectory(directory);
    this.file = path.join(directory, 'desktop-config.json');
    this.storage = safeStorage;
  }

  read() {
    if (!fs.existsSync(this.file)) return null;
    const envelope = JSON.parse(fs.readFileSync(this.file, 'utf8'));
    if (envelope.format !== 1) throw new Error('配置版本不受支持，请保留数据目录并查看帮助');
    if (envelope.encrypted) {
      if (!this.storage?.isEncryptionAvailable()) throw new Error('无法读取系统加密的配置，请解锁系统钥匙串后重试');
      return JSON.parse(this.storage.decryptString(Buffer.from(envelope.data, 'base64')));
    }
    return envelope.data;
  }

  write(config) {
    const canEncrypt = this.storage?.isEncryptionAvailable() &&
      this.storage.getSelectedStorageBackend?.() !== 'basic_text';
    atomicJson(this.file, canEncrypt
      ? { format: 1, encrypted: true, data: this.storage.encryptString(JSON.stringify(config)).toString('base64') }
      : { format: 1, encrypted: false, data: config });
    return Boolean(canEncrypt);
  }

  initialize() {
    const existing = this.read();
    if (existing) return existing;
    const config = {
      configured: false,
      databasePassword: crypto.randomBytes(32).toString('hex'),
      authPassword: crypto.randomBytes(32).toString('hex'),
      encryptionKey: crypto.randomBytes(32).toString('base64'),
    };
    this.write(config);
    return config;
  }

  configure(value) {
    const config = { ...this.initialize(), ...validateConfig(value) };
    this.write(config);
    return config;
  }
}

module.exports = { ConfigStore, validateConfig, privateDirectory, atomicJson };
