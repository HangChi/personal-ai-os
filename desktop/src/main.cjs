'use strict';

const { app, BrowserWindow, Menu, ipcMain, shell, safeStorage, dialog } = require('electron');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { ConfigStore } = require('./config.cjs');
const { LocalRuntime } = require('./runtime.cjs');

app.setName('Personal AI OS');
if (process.platform !== 'win32') process.umask(0o077);
// Test isolation is only available for an unpackaged development build.
if (!app.isPackaged && process.env.DESKTOP_TEST_DATA) app.setPath('userData', path.resolve(process.env.DESKTOP_TEST_DATA));
app.setPath('sessionData', path.join(app.getPath('userData'), 'browser'));
let window, runtime, store;
let state = { stage: 'setup', message: '设置模型，开始使用你的本地工作台。' };
let starting = false, quitting = false;
const setupUrl = pathToFileURL(path.join(__dirname, 'setup.html')).href;
const resources = app.isPackaged ? path.join(process.resourcesPath, 'runtime') : path.join(__dirname, '..', 'resources');

if (!app.requestSingleInstanceLock()) app.quit();
else {
  app.on('second-instance', () => { if (window) { window.show(); window.focus(); } });
  app.whenReady().then(initialize).catch(error => {
    dialog.showErrorBox('Personal AI OS 无法启动', error.message);
    app.quit();
  });
}

function status(value) {
  state = value;
  if (window && !window.isDestroyed()) window.webContents.send('desktop:status', state);
}

function trusted(event) {
  if (!window || event.sender !== window.webContents || event.senderFrame !== window.webContents.mainFrame ||
      event.senderFrame.url !== setupUrl) throw new Error('此操作仅可在本地配置页执行');
}

async function initialize() {
  store = new ConfigStore(app.getPath('userData'), safeStorage);
  const config = store.initialize();
  window = new BrowserWindow({
    title: 'Personal AI OS', width: 1280, height: 840, minWidth: 780, minHeight: 640,
    backgroundColor: '#f5f7fb', show: false,
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'), nodeIntegration: false,
      contextIsolation: true, sandbox: true, webSecurity: true,
      partition: 'persist:personal-ai-os-desktop',
    },
  });
  window.once('ready-to-show', () => window.show());
  window.webContents.setWindowOpenHandler(({ url }) => {
    openExternal(url);
    return { action: 'deny' };
  });
  function guardNavigation(event, url) {
    if (url !== setupUrl && (!runtime?.proxy || new URL(url).origin !== runtime.proxy.origin)) {
      event.preventDefault(); openExternal(url);
    }
  }
  window.webContents.on('will-navigate', guardNavigation);
  window.webContents.on('will-redirect', guardNavigation);
  window.webContents.session.setPermissionRequestHandler((_contents, _permission, callback) => callback(false));
  window.webContents.session.setPermissionCheckHandler(() => false);
  ipcMain.handle('desktop:state', event => {
    trusted(event);
    return { ...state, configured: store.read().configured, encryptionAvailable: safeStorage.isEncryptionAvailable(),
      dataDirectory: app.getPath('userData'), version: app.getVersion() };
  });
  ipcMain.handle('desktop:configure', async (event, value) => {
    trusted(event);
    if (starting) return { ok: false, error: '正在启动，请稍候' };
    try {
      const config = store.configure(value);
      void start({ ...config, applyModelConfig: true });
      return { ok: true };
    } catch (error) { return { ok: false, error: error.message }; }
  });
  ipcMain.handle('desktop:retry', event => { trusted(event); void start(store.read()); });
  ipcMain.handle('desktop:logs', event => { trusted(event); return shell.openPath(path.join(app.getPath('userData'), 'logs')); });
  const items = [
    ...(process.platform === 'darwin' ? [{ label: 'Personal AI OS', submenu: [{ role: 'about' }, { type: 'separator' }, { role: 'quit', label: '退出 Personal AI OS' }] }] : []),
    { label: '工作台', submenu: [
      { label: '重新连接', click: () => reconnect() },
      { label: '模型启动配置…', click: async () => {
        if (starting) return;
        status({ stage: 'setup', message: '更新模型配置后，应用将重新启动。已有对话和资料保留。' });
        if (runtime) { await runtime.stop(); runtime = null; }
        await window.loadURL(setupUrl);
      } },
      { type: 'separator' },
      { label: '打开数据目录', click: () => shell.openPath(app.getPath('userData')) },
      { label: '打开日志目录', click: () => shell.openPath(path.join(app.getPath('userData'), 'logs')) },
      ...(process.platform === 'darwin' ? [] : [{ type: 'separator' }, { role: 'quit', label: '退出' }]),
    ] },
    { label: '编辑', submenu: [{ role: 'undo' }, { role: 'redo' }, { type: 'separator' },
      { role: 'cut' }, { role: 'copy' }, { role: 'paste' }, { role: 'selectAll' }] },
    { label: '视图', submenu: [{ role: 'reload' }, { role: 'resetZoom' }, { role: 'zoomIn' }, { role: 'zoomOut' }, { role: 'togglefullscreen' }] },
  ];
  Menu.setApplicationMenu(Menu.buildFromTemplate(items));
  await window.loadURL(setupUrl);
  if (config.configured) void start(config);
  if (!app.isPackaged && process.env.DESKTOP_UI_SMOKE && process.env.DESKTOP_TEST_DATA && !config.configured) {
    await window.webContents.executeJavaScript(`
      for (const prefix of ['chat', 'embedding']) {
        document.getElementById(prefix + 'Url').value = 'http://127.0.0.1:9';
        document.getElementById(prefix + 'Model').value = 'desktop-ui-fixture';
      }
      document.getElementById('config-form').requestSubmit();
    `);
  }
  if (!app.isPackaged && process.env.DESKTOP_CAPTURE_SETUP) {
    await new Promise(resolve => setTimeout(resolve, 1200));
    const image = await window.webContents.capturePage();
    require('node:fs').writeFileSync(process.env.DESKTOP_CAPTURE_SETUP, image.toPNG());
    app.quit();
  }
}

function openExternal(value) {
  try {
    const url = new URL(value);
    if (['https:', 'http:'].includes(url.protocol) && !['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)) {
      void shell.openExternal(url.href);
    }
  } catch { /* refuse custom protocols and malformed URLs */ }
}

async function connect(origin, cookie) {
  await window.webContents.session.cookies.set({
    url: origin, name: 'JSESSIONID', value: cookie, path: '/', httpOnly: true, sameSite: 'lax',
  });
  await window.loadURL(origin);
  if (!app.isPackaged && process.env.DESKTOP_UI_SMOKE && process.env.DESKTOP_TEST_DATA) {
    let ready = false;
    for (let attempt = 0; attempt < 50; attempt++) {
      ready = await window.webContents.executeJavaScript(`Boolean(document.querySelector('.home__hero-title')) && !document.querySelector('.home__stat-status') && !location.pathname.includes('/login')`);
      if (ready) break;
      await new Promise(resolve => setTimeout(resolve, 200));
    }
    if (!ready) throw new Error('桌面窗口未能进入已登录工作台');
    await new Promise(resolve => setTimeout(resolve, 350));
    const previousPreference = await window.webContents.executeJavaScript(`localStorage.getItem('desktop-ui-persistence-fixture')`);
    if (process.env.DESKTOP_UI_EXPECT_RESTART && previousPreference !== 'retained') throw new Error('桌面偏好未能跨重启保留');
    await window.webContents.executeJavaScript(`localStorage.setItem('desktop-ui-persistence-fixture', 'retained')`);
    const fs = require('node:fs');
    const destination = path.resolve(process.env.DESKTOP_UI_SMOKE);
    fs.mkdirSync(destination, { recursive: true });
    fs.writeFileSync(path.join(destination, 'workbench.png'), (await window.webContents.capturePage()).toPNG());
    fs.writeFileSync(path.join(destination, 'ui-smoke.json'), JSON.stringify({ passed: true,
      platform: `${process.platform}-${process.arch}`, checks: ['first-run form submission through isolated preload',
        'encrypted or user-protected config saved', 'desktop session auto-login', 'Vue workbench rendered',
        ...(process.env.DESKTOP_UI_EXPECT_RESTART ? ['browser preferences retained after restart'] : [])] }, null, 2));
    app.quit();
  }
}

async function start(config) {
  if (starting || quitting) return;
  if (!config?.configured) { status({ stage: 'setup', message: '请先填写模型配置。' }); return; }
  starting = true;
  try {
    if (runtime) await runtime.stop();
    runtime = new LocalRuntime(resources, app.getPath('userData'), config, status);
    const { origin, cookie } = await runtime.start();
    store.write({ ...store.read(), frontendPort: runtime.proxy.port });
    if (quitting) { await runtime.stop(); return; }
    await connect(origin, cookie);
    runtime.backend.once('exit', () => {
      if (state.stage === 'ready' && !quitting && !starting) {
        status({ stage: 'error', message: '本地服务已停止，请查看日志或重新连接。' });
        void window.loadURL(setupUrl);
      }
    });
  } catch (error) {
    status({ stage: 'error', message: error.message });
    if (window && !window.isDestroyed()) await window.loadURL(setupUrl);
    if (!app.isPackaged && process.env.DESKTOP_UI_SMOKE && process.env.DESKTOP_TEST_DATA) {
      console.error(error.message);
      app.quit();
    }
  } finally { starting = false; }
}

async function reconnect() {
  if (starting) return;
  if (runtime?.proxy && runtime.backend?.exitCode === null) {
    try { await connect(runtime.proxy.origin, await runtime.login()); return; } catch { /* restart */ }
  }
  await window.loadURL(setupUrl);
  void start(store.read());
}

app.on('window-all-closed', () => app.quit());
app.on('before-quit', event => {
  if (quitting) return;
  event.preventDefault();
  quitting = true;
  status({ stage: 'stopping', message: '正在停止本地服务，数据将保留…' });
  // Startup and shutdown cannot race: finish an in-flight start before cleanup.
  void (async () => {
    while (starting) await new Promise(resolve => setTimeout(resolve, 100));
    if (runtime) await runtime.stop();
  })().catch(error => dialog.showErrorBox('服务停止未完成', error.message))
    .finally(() => app.quit());
});
