/** 真实 TLS + 假 Java：验证网络边界，不连接项目数据库或模型。 */
import test, { before, after } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import https from 'node:https';
import { mkdtemp, mkdir, writeFile, readFile, symlink } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { once } from 'node:events';
import { createClassroomServer } from './classroom-server.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
let backend, web, ca, port, fixture, releaseStream, closeUpstream;
let backendRequests = 0;
before(async () => {
  assert.ok(process.env.CLASSROOM_TEST_TLS, '使用 Test-ClassroomServer.ps1 生成 target 中的隔离测试证书');
  await mkdir(path.join(root, 'target'), { recursive: true });
  fixture = await mkdtemp(path.join(root, 'target', 'classroom-web-test-'));
  await mkdir(path.join(fixture, 'dist'));
  await mkdir(path.join(fixture, 'private'));
  await writeFile(path.join(fixture, 'dist', 'index.html'), '<!doctype html><title>classroom fixture</title>');
  await writeFile(path.join(fixture, 'dist', 'app.js'), 'export const fixture = true;');
  await writeFile(path.join(fixture, 'private', 'secret.txt'), 'must not be exposed');
  await symlink(path.join(fixture, 'private'), path.join(fixture, 'dist', 'linked'), 'junction');
  backend = http.createServer((req, res) => {
    backendRequests++;
    if (req.url === '/api/stream') {
      res.writeHead(200, { 'content-type': 'application/x-ndjson', 'connection': 'keep-alive', 'x-fixture': 'upstream' });
      res.flushHeaders();
      res.write('{"part":1}\n');
      releaseStream = () => res.end('{"part":2}\n');
    } else if (req.url === '/chat/cancel') {
      res.writeHead(200, { 'content-type': 'text/event-stream' });
      res.write('data: open\n\n');
      res.once('close', () => closeUpstream?.());
    } else if (req.url === '/api/echo') {
      const chunks = [];
      req.on('data', chunk => chunks.push(chunk));
      req.on('end', () => {
        res.writeHead(201, { 'content-type': 'application/json', 'x-upstream': 'present' });
        res.end(JSON.stringify({ method: req.method, body: Buffer.concat(chunks).toString(), host: req.headers.host, forwarded: req.headers.forwarded, protocol: req.headers['x-forwarded-proto'], leaked: req.headers['x-remove-me'] }));
      });
    } else { res.writeHead(404); res.end(); }
  });
  backend.listen(0, '127.0.0.1');
  await once(backend, 'listening');
  const tlsDirectory = process.env.CLASSROOM_TEST_TLS;
  ca = await readFile(path.join(tlsDirectory, 'ca.crt'));
  web = await createClassroomServer({ distDir: path.join(fixture, 'dist'), tlsConfig: path.join(tlsDirectory, 'tls.json'), backendPort: backend.address().port, runId: 'isolated-fixture' });
  web.listen(0, '127.0.0.1');
  await once(web, 'listening');
  port = web.address().port;
});
after(async () => {
  for (const server of [web, backend]) {
    if (!server) continue;
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  }
});
function request(url, { method = 'GET', headers = {}, body = '' } = {}) {
  return new Promise((resolve, reject) => {
    const req = https.request({ hostname: '127.0.0.1', port, path: url, ca, method, headers }, res => {
      const chunks = [];
      res.on('data', chunk => chunks.push(chunk));
      res.on('error', reject);
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks).toString() }));
    });
    req.on('error', reject);
    req.end(body);
  });
}

test('证书验证启用时提供首页、JS 与 HEAD', async () => {
  const homepage = await request('/');
  assert.equal(homepage.status, 200);
  assert.match(homepage.body, /classroom fixture/);
  assert.equal(homepage.headers['cache-control'], 'no-store');
  const js = await request('/app.js');
  assert.match(js.headers['content-type'], /javascript/);
  const head = await request('/app.js', { method: 'HEAD' });
  assert.equal(head.status, 200);
  assert.equal(head.body, '');
  assert.ok(Number(head.headers['content-length']) > 0);
});

test('SPA 只回退页面导航，资源缺失及非 GET 不伪装成功', async () => {
  assert.equal((await request('/learn/1', { headers: { accept: 'text/html' } })).status, 200);
  assert.equal((await request('/missing.js', { headers: { accept: 'text/html' } })).status, 404);
  assert.equal((await request('/learn/1')).status, 404);
  assert.equal((await request('/', { method: 'POST' })).status, 405);
});

test('拒绝原始、编码和反斜线目录遍历及目录联接逃逸', async () => {
  for (const url of ['/../private/secret.txt', '/%2e%2e/private/secret.txt', '/%5c..%5cprivate/secret.txt', '/.env', '/%00', '/%0a', '/app.js:private', '/%invalid']) {
    assert.equal((await request(url)).status, 400, url);
  }
  assert.equal((await request('/linked/secret.txt')).status, 403);
});

test('API 代理保持方法正文状态且去除伪造代理链和逐跳头', async () => {
  const result = await request('/api/echo', { method: 'POST', body: '你好 classroom', headers: { 'content-type': 'text/plain', connection: 'x-remove-me', 'x-remove-me': 'secret', forwarded: 'host=attacker', 'x-forwarded-proto': 'http' } });
  assert.equal(result.status, 201);
  assert.equal(result.headers['x-upstream'], 'present');
  const echo = JSON.parse(result.body);
  assert.equal(echo.body, '你好 classroom');
  assert.equal(echo.method, 'POST');
  assert.equal(echo.host, `127.0.0.1:${backend.address().port}`);
  assert.equal(echo.protocol, 'https');
  assert.equal(echo.forwarded, undefined);
  assert.equal(echo.leaked, undefined);
});

test('不会成为任意 URL 的开放代理', async () => {
  const countBefore = backendRequests;
  assert.equal((await request('http://example.com/api/echo')).status, 400);
  assert.equal((await request('//example.com/api/echo')).status, 400);
  assert.equal((await request('/apix/echo')).status, 404);
  assert.equal(backendRequests, countBefore);
});

test('首段到达时上游尚未结束，NDJSON 不被缓冲', { timeout: 5000 }, async () => {
  let ended = false;
  let fullBody = '';
  let resolveFirst;
  const first = new Promise(resolve => { resolveFirst = resolve; });
  const done = new Promise((resolve, reject) => {
    const req = https.get({ hostname: '127.0.0.1', port, path: '/api/stream', ca }, res => {
      res.on('data', data => { fullBody += data.toString(); resolveFirst(); });
      res.on('end', () => { ended = true; resolve(); });
      res.on('error', reject);
    });
    req.on('error', reject);
  });
  await first;
  assert.equal(ended, false);
  assert.equal(fullBody, '{"part":1}\n');
  releaseStream();
  await done;
  assert.equal(fullBody, '{"part":1}\n{"part":2}\n');
});

test('用户中止连接后及时关闭对应上游', { timeout: 5000 }, async () => {
  const upstreamClosed = new Promise(resolve => { closeUpstream = resolve; });
  await new Promise((resolve, reject) => {
    const req = https.get({ hostname: '127.0.0.1', port, path: '/chat/cancel', ca }, res => {
      res.once('data', () => { res.destroy(); resolve(); });
    });
    req.on('error', reject);
  });
  await upstreamClosed;
});

test('健康端点标记的是网页服务，不冒充数据库或模型验收', async () => {
  const health = JSON.parse((await request('/_classroom/health')).body);
  assert.equal(health.service, 'classroom-web');
  assert.equal(health.runId, 'isolated-fixture');
});

test('上游断开时返回可读 502', async () => {
  backend.closeAllConnections();
  await new Promise(resolve => backend.close(resolve));
  const result = await request('/api/echo');
  assert.equal(result.status, 502);
  assert.match(result.body, /后端暂不可用/);
});
