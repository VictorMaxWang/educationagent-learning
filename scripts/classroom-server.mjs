#!/usr/bin/env node
/**
 * 课堂生产入口：本地 HTTPS、构建后的网页与固定 Java 上游。
 * 不引入代理框架，不接收任意目标地址；流直接 pipe，避免把长对话读完才返回。
 */
import http from 'node:http';
import https from 'node:https';
import { createReadStream } from 'node:fs';
import { readFile, realpath, stat } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg',
  '.gif': 'image/gif', '.webp': 'image/webp', '.ico': 'image/x-icon',
  '.woff': 'font/woff', '.woff2': 'font/woff2', '.ttf': 'font/ttf',
  '.mp3': 'audio/mpeg', '.wav': 'audio/wav', '.mp4': 'video/mp4',
  '.txt': 'text/plain; charset=utf-8', '.pdf': 'application/pdf',
};
const HOP_HEADERS = new Set([
  'connection', 'keep-alive', 'proxy-authenticate', 'proxy-authorization',
  'te', 'trailer', 'transfer-encoding', 'upgrade',
]);

function send(res, status, text) {
  if (res.destroyed || res.writableEnded) return;
  if (res.headersSent) { res.destroy(); return; }
  res.writeHead(status, { 'content-type': 'text/plain; charset=utf-8', 'cache-control': 'no-store' });
  res.end(text);
}

/** 先检查原始路径再找文件，避免 URL 自动折叠 ../ 后掩盖越界请求。 */
function checkedPath(rawUrl) {
  if (!rawUrl?.startsWith('/') || rawUrl.startsWith('//') || /[\r\n\0]/.test(rawUrl)) return null;
  const rawPath = rawUrl.split('?')[0];
  let decoded;
  try { decoded = decodeURIComponent(rawPath); } catch { return null; }
  // Windows 的反斜线、盘符/备用数据流冒号，以及控制字符都不属于网页资源路径。
  if (/[\\:\x00-\x1f\x7f]/.test(decoded) || decoded.startsWith('//')) return null;
  if (decoded.split('/').some(segment => segment === '..' || segment === '.' || segment.startsWith('.'))) return null;
  return decoded;
}

function cleanHeaders(headers) {
  const blocked = new Set([...HOP_HEADERS, ...String(headers.connection ?? '').toLowerCase().split(',').map(x => x.trim())]);
  return Object.fromEntries(Object.entries(headers).filter(([name]) => !blocked.has(name.toLowerCase())));
}

function isInside(root, target) {
  const relative = path.relative(root, target);
  return relative === '' || (!relative.startsWith(`..${path.sep}`) && relative !== '..' && !path.isAbsolute(relative));
}

function proxy(req, res, backendPort) {
  const headers = cleanHeaders(req.headers);
  // 不信任客户端伪造的代理链。上游始终为本机 Java，绝不使用请求中的 URL/Host 选目标。
  for (const name of Object.keys(headers)) {
    if (name.startsWith('x-forwarded-') || name === 'forwarded') delete headers[name];
  }
  headers.host = `127.0.0.1:${backendPort}`;
  headers['x-forwarded-proto'] = 'https';
  headers['x-forwarded-for'] = req.socket.remoteAddress ?? '';
  const upstream = http.request({
    hostname: '127.0.0.1', port: backendPort, method: req.method, path: req.url, headers,
  }, upstreamRes => {
    if (res.destroyed) { upstreamRes.destroy(); return; }
    res.writeHead(upstreamRes.statusCode ?? 502, cleanHeaders(upstreamRes.headers));
    res.flushHeaders();
    upstreamRes.on('error', () => res.destroy());
    upstreamRes.pipe(res);
    res.once('close', () => upstreamRes.destroy());
  });
  // 按空闲而非总时长计时，持续产生 token 的长回复不会被固定总时长截断。
  upstream.setTimeout(180_000, () => upstream.destroy(new Error('upstream idle timeout')));
  upstream.on('error', () => send(res, 502, '后端暂不可用，请检查课堂后端日志。'));
  req.once('aborted', () => upstream.destroy());
  req.once('error', () => upstream.destroy());
  res.once('close', () => upstream.destroy());
  req.pipe(upstream);
}

/** 创建服务但不监听，方便在独立测试端口验证 TLS、流和路径边界。 */
export async function createClassroomServer({ distDir, tlsConfig, backendPort = 8888, runId = '' }) {
  if (!Number.isInteger(backendPort) || backendPort < 1 || backendPort > 65535) throw new Error('backendPort 无效');
  const root = await realpath(distDir);
  await stat(path.join(root, 'index.html'));
  const tls = JSON.parse(await readFile(tlsConfig, 'utf8'));
  if (tls.schemaVersion !== 1 || tls.pfx !== 'server.p12' || typeof tls.passphrase !== 'string') throw new Error('TLS 配置无效，请重新生成课堂证书');
  const server = https.createServer({
    pfx: await readFile(path.join(path.dirname(tlsConfig), tls.pfx)),
    passphrase: tls.passphrase, minVersion: 'TLSv1.2',
  }, async (req, res) => {
    res.setHeader('x-content-type-options', 'nosniff');
    res.setHeader('referrer-policy', 'same-origin');
    const pathname = checkedPath(req.url);
    if (pathname === null) { send(res, 400, '无效请求路径'); return; }
    if (/^\/(api|chat|actuator)(\/|$)/.test(pathname)) { proxy(req, res, backendPort); return; }
    if (req.method !== 'GET' && req.method !== 'HEAD') { res.setHeader('allow', 'GET, HEAD'); send(res, 405, '不支持的方法'); return; }
    if (pathname === '/_classroom/health') {
      res.writeHead(200, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' });
      res.end(req.method === 'HEAD' ? undefined : JSON.stringify({ service: 'classroom-web', runId, backendPort }));
      return;
    }
    try {
      let filename = path.resolve(root, `.${pathname === '/' ? '/index.html' : pathname}`);
      if (!isInside(root, filename)) { send(res, 403, '禁止访问'); return; }
      let info;
      try { info = await stat(filename); } catch (error) {
        if (error.code !== 'ENOENT' && error.code !== 'ENOTDIR') throw error;
        // SPA 只回退真正的页面导航，缺失脚本/字体不能返回 HTML 假装成功。
        if (!path.extname(pathname) && String(req.headers.accept).includes('text/html')) {
          filename = path.join(root, 'index.html');
          info = await stat(filename);
        } else { send(res, 404, '文件不存在'); return; }
      }
      if (!info.isFile() || !isInside(root, await realpath(filename))) { send(res, 403, '禁止访问'); return; }
      res.writeHead(200, {
        'content-type': MIME[path.extname(filename).toLowerCase()] ?? 'application/octet-stream',
        'content-length': info.size,
        'cache-control': filename.endsWith(`${path.sep}index.html`) ? 'no-store' : 'no-cache',
      });
      if (req.method === 'HEAD') { res.end(); return; }
      const stream = createReadStream(filename);
      stream.on('error', () => res.destroy());
      res.once('close', () => stream.destroy());
      stream.pipe(res);
    } catch { send(res, 500, '网页读取失败，请检查构建产物。'); }
  });
  server.requestTimeout = 180_000;
  server.headersTimeout = 30_000;
  server.on('upgrade', (_req, socket) => socket.end('HTTP/1.1 426 Upgrade Required\r\nConnection: close\r\n\r\n'));
  return server;
}

async function main() {
  const args = process.argv.slice(2);
  const options = {};
  const allowed = new Set(['dist', 'tls-config', 'port', 'backend-port', 'run-id']);
  for (let i = 0; i < args.length; i += 2) {
    const key = args[i]?.replace(/^--/, '');
    if (!args[i]?.startsWith('--') || !allowed.has(key) || !args[i + 1] || options[key]) throw new Error(`无效参数: ${args[i]}`);
    options[key] = args[i + 1];
  }
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
  const port = Number(options.port ?? 1443);
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('HTTPS 端口无效');
  const server = await createClassroomServer({
    distDir: options.dist ?? path.join(root, 'desktop-app', 'dist'),
    tlsConfig: options['tls-config'] ?? path.join(root, 'data', 'classroom', 'tls', 'tls.json'),
    backendPort: Number(options['backend-port'] ?? 8888), runId: options['run-id'] ?? '',
  });
  server.listen(port, '0.0.0.0', () => console.log(`课堂 HTTPS 已监听 ${port}；Java 上游 127.0.0.1:${options['backend-port'] ?? 8888}`));
  server.on('error', error => { console.error(`课堂 HTTPS 启动失败: ${error.code ?? error.message}`); process.exitCode = 1; });
  for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => {
    server.close(() => process.exit(0));
    setTimeout(() => { server.closeAllConnections(); process.exit(0); }, 5000).unref();
  });
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(error => { console.error(error.message); process.exitCode = 1; });
}
