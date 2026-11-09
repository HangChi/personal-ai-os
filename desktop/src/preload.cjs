'use strict';
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('desktop', Object.freeze({
  state: () => ipcRenderer.invoke('desktop:state'),
  configure: value => ipcRenderer.invoke('desktop:configure', value),
  retry: () => ipcRenderer.invoke('desktop:retry'),
  openLogs: () => ipcRenderer.invoke('desktop:logs'),
  onStatus: listener => {
    const handler = (_event, value) => listener(value);
    ipcRenderer.on('desktop:status', handler);
    return () => ipcRenderer.removeListener('desktop:status', handler);
  },
}));
