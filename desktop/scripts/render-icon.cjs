'use strict';
// Render the repository's existing vector mark; no separate logo design.
const { app, BrowserWindow } = require('electron');
const fs = require('node:fs');
const path = require('node:path');
app.whenReady().then(async () => {
  const svg = fs.readFileSync(path.resolve(__dirname, '../../frontend/public/favicon.svg'), 'utf8');
  const window = new BrowserWindow({ width: 512, height: 512, show: false, transparent: true,
    webPreferences: { offscreen: true, sandbox: true, nodeIntegration: false, contextIsolation: true } });
  await window.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(
    '<style>html,body{margin:0;width:512px;height:512px;overflow:hidden}svg{width:512px;height:512px}</style>' + svg));
  await new Promise(resolve => setTimeout(resolve, 300));
  const image = await window.webContents.capturePage();
  const destination = path.resolve(__dirname, '../build');
  fs.mkdirSync(destination, { recursive: true });
  fs.writeFileSync(path.join(destination, 'icon.png'), image.toPNG());
  app.quit();
});
