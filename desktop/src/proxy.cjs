'use strict';

const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');

const TYPES = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.woff2': 'font/woff2', '.webmanifest': 'application/manifest+json',
};

async function startProxy(frontend, backendPort, preferredPort = 0) {
  const directory = path.resolve(frontend);
  let origin;
  const server = http.createServer((request, response) => {
    if (request.headers.host !== new URL(origin).host ||
        (request.headers.origin && request.headers.origin !== origin) ||
        request.headers['sec-fetch-site'] === 'cross-site') {
      response.writeHead(403).end('Forbidden');
      return;
    }
    const target = new URL(request.url, origin);
    if (target.pathname.startsWith('/api/v1/')) {
      const upstream = http.request({
        hostname: '127.0.0.1', port: backendPort, method: request.method,
        path: target.pathname + target.search,
        headers: { ...request.headers, host: `127.0.0.1:${backendPort}`, connection: 'close' },
      }, incoming => {
        response.writeHead(incoming.statusCode, incoming.headers);
        incoming.pipe(response);
      });
      upstream.on('error', () => {
        if (!response.headersSent) response.writeHead(502, { 'Content-Type': 'text/plain; charset=utf-8' });
        response.end('本地服务连接中断，请从应用菜单重新连接。');
      });
      response.on('close', () => upstream.destroy());
      request.pipe(upstream);
      return;
    }
    if (!['GET', 'HEAD'].includes(request.method)) { response.writeHead(405).end(); return; }
    let relative;
    try { relative = decodeURIComponent(target.pathname); }
    catch { response.writeHead(400).end(); return; }
    let file = path.resolve(directory, '.' + relative);
    if (!file.startsWith(directory + path.sep) && file !== directory) { response.writeHead(403).end(); return; }
    if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) {
      if (path.extname(relative)) { response.writeHead(404).end(); return; }
      file = path.join(directory, 'index.html');
    }
    response.writeHead(200, {
      'Content-Type': TYPES[path.extname(file)] || 'application/octet-stream',
      'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff',
      'Referrer-Policy': 'same-origin',
    });
    if (request.method === 'HEAD') response.end();
    else fs.createReadStream(file).on('error', () => response.destroy()).pipe(response);
  });
  server.requestTimeout = 0;
  await new Promise((resolve, reject) => {
    const onError = error => {
      if (preferredPort && error.code === 'EADDRINUSE') {
        preferredPort = 0;
        server.listen(0, '127.0.0.1');
      } else reject(error);
    };
    server.on('error', onError);
    server.once('listening', () => { server.removeListener('error', onError); resolve(); });
    server.listen(preferredPort, '127.0.0.1');
  });
  origin = `http://127.0.0.1:${server.address().port}`;
  return { origin, port: server.address().port, close: () => new Promise(resolve => {
    server.close(resolve);
    server.closeAllConnections();
  }) };
}

module.exports = { startProxy };
