/** Node Inspector 提供真实执行位置；不改写源码，因此行号与编辑器完全一致。 */
import { spawn } from 'node:child_process';
import { readFile, writeFile, rename } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const job = path.resolve(process.argv[2]);
const request = JSON.parse(await readFile(path.join(job, 'request.json'), 'utf8'));
const limits = request.limits;
const userFile = path.join(job, 'main.cjs');
const userUrl = pathToFileURL(userFile).href;
let stdout = '', stderr = '', reason, traceBytes = 0, finished = false;
let ws, child, sequence = 0;
let checkpointTime = 0;
let childExitCode, childSignal;
const trace = [], diagnostics = [], pending = new Map(), events = [], waiters = [];
const started = Date.now();
const normalize = (name = '') => name.replaceAll('\\', '/');
const isUser = (frame) => normalize(frame.url) === normalize(userUrl) || normalize(frame.url) === normalize(userFile) || scriptIds.has(frame.location.scriptId);
const scriptIds = new Set();
const sleep = (ms, background = false) => new Promise(resolve => {
  const timer = setTimeout(resolve, ms);
  if (background) timer.unref();
});
const enqueue = event => waiters.length ? waiters.shift()(event) : events.push(event);
const command = (method, params = {}) => new Promise((resolve, reject) => {
  const id = ++sequence;
  pending.set(id, { resolve, reject });
  ws.send(JSON.stringify({ id, method, params }));
});
const nextEvent = () => events.length ? Promise.resolve(events.shift()) : new Promise(resolve => waiters.push(resolve));
const stop = () => { if (child && !finished) child.kill('SIGKILL'); };
const appendOutput = (stream, text) => {
  const remaining = Math.max(0, limits.outputBytes - Buffer.byteLength(stdout) - Buffer.byteLength(stderr));
  const buffer = Buffer.from(text);
  const part = buffer.subarray(0, remaining).toString('utf8');
  if (stream === 'stdout') stdout += part; else stderr += part;
  if (buffer.length > remaining) { reason = 'output_limit'; stop(); }
};

async function valueOf(remote, heap, seen, depth = 0) {
  if (!remote) return { $unavailable: '无法读取' };
  if (remote.type === 'undefined') return { $unavailable: 'undefined' };
  if (remote.type === 'function' || remote.type === 'symbol') return { $unavailable: remote.description || remote.type };
  if (remote.unserializableValue) return { $unavailable: remote.unserializableValue };
  if (!remote.objectId) return remote.value ?? null;
  if (remote.subtype === 'null') return null;
  const id = remote.objectId;
  if (seen.has(id)) return { $ref: id };
  seen.add(id);
  if (depth >= 3 || Object.keys(heap).length >= 128) {
    heap[id] = { type: remote.className || remote.subtype || 'Object', $unavailable: depth >= 3 ? '最大展开深度' : '最大对象数量' };
    return { $ref: id };
  }
  try {
    const result = await command('Runtime.getProperties', { objectId: id, ownProperties: true, generatePreview: false });
    const fields = result.result || [];
    if (remote.subtype === 'array') {
      const length = fields.find(field => field.name === 'length')?.value?.value || 0;
      const items = [];
      for (let i = 0; i < Math.min(length, 50); i++) {
        const field = fields.find(field => field.name === String(i));
        items.push(field ? await valueOf(field.value, heap, seen, depth + 1) : { $unavailable: '空位' });
      }
      heap[id] = { type: 'Array', items, length, truncated: length > 50 };
    } else {
      const properties = {};
      for (const field of fields.filter(field => !field.name.startsWith('__')).slice(0, 40)) {
        // 仅读数据属性，查看变量绝不能触发学生定义的 getter。
        properties[field.name] = field.value ? await valueOf(field.value, heap, seen, depth + 1) : { $unavailable: '访问器未执行' };
      }
      heap[id] = { type: remote.className || 'Object', properties, truncated: fields.length > 40 };
    }
  } catch { heap[id] = { $unavailable: '对象已不可读取' }; }
  return { $ref: id };
}

async function snapshot(event) {
  const selected = event.params.callFrames.filter(isUser).slice(0, 20);
  if (!selected.length) return false;
  const heap = {}, seen = new Set(), frames = [];
  for (const frame of selected) {
    const locals = {};
    for (const scope of frame.scopeChain.filter(scope => ['local', 'closure', 'script', 'block', 'catch'].includes(scope.type)).slice(0, 8)) {
      const response = await command('Runtime.getProperties', { objectId: scope.object.objectId, ownProperties: true, generatePreview: false });
      for (const variable of (response.result || []).slice(0, 80)) {
        if (['exports', 'module', 'require', '__dirname', '__filename'].includes(variable.name) || variable.value?.type === 'function' || variable.name in locals) continue;
        locals[variable.name] = await valueOf(variable.value, heap, seen);
      }
    }
    frames.push({ name: frame.functionName || '<main>', locals });
  }
  // stdout 与 Inspector 使用不同管道，稍让出事件循环使同一步输出被读完。
  await sleep(1);
  const item = { index: trace.length, line: selected[0].location.lineNumber + 1, event: event.params.reason === 'exception' ? 'exception' : 'line', frames, heap, stdout };
  const size = Buffer.byteLength(JSON.stringify(item));
  if (traceBytes + size > limits.traceBytes) { reason = 'trace_limit'; return false; }
  traceBytes += size;
  trace.push(item);
  if (trace.length === 1 || trace.length % 25 === 0 || Date.now() - checkpointTime > 200) {
    await writeFile(path.join(job, 'checkpoint.tmp'), JSON.stringify({ status: 'completed', stdout, stderr, diagnostics, trace, truncated: false }));
    await rename(path.join(job, 'checkpoint.tmp'), path.join(job, 'checkpoint.json'));
    checkpointTime = Date.now();
  }
  return true;
}

try {
  child = spawn(process.execPath, ['--max-old-space-size=192', '--inspect-brk=127.0.0.1:0', userFile], { cwd: job, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true });
  child.stdin.end(request.stdin || '');
  const endpointPromise = new Promise((resolve, reject) => {
    let debugText = '';
    child.stderr.on('data', chunk => {
      const text = chunk.toString('utf8');
      debugText += text;
      const endpoint = debugText.match(/ws:\/\/127\.0\.0\.1:\d+\/[\w-]+/);
      if (endpoint) resolve(endpoint[0]);
      if (text.includes('Waiting for the debugger to disconnect')) enqueue({ method: 'program.finished' });
      const visible = text.split('\n').filter(line => !line.startsWith('Debugger ') && !line.startsWith('For help, see:') && !line.startsWith('Waiting for the debugger')).join('\n');
      appendOutput('stderr', visible);
    });
    child.on('error', reject);
  });
  child.stdout.on('data', chunk => {
    appendOutput('stdout', chunk.toString('utf8'));
  });
  child.on('exit', (code, signal) => {
    childExitCode = code; childSignal = signal; finished = true;
    enqueue({ method: 'child.exit', code, signal });
  });
  const endpoint = await Promise.race([endpointPromise, sleep(4000, true).then(() => { throw new Error('Inspector 启动超时'); })]);
  ws = new WebSocket(endpoint);
  await new Promise((resolve, reject) => { ws.addEventListener('open', resolve, { once: true }); ws.addEventListener('error', reject, { once: true }); });
  ws.addEventListener('message', event => {
    const message = JSON.parse(event.data);
    if (message.id) {
      const deferred = pending.get(message.id); pending.delete(message.id);
      if (message.error) deferred?.reject(new Error(message.error.message)); else deferred?.resolve(message.result || {});
    } else {
      if (message.method === 'Debugger.scriptParsed' && [normalize(userUrl), normalize(userFile)].includes(normalize(message.params.url))) scriptIds.add(message.params.scriptId);
      enqueue(message);
    }
  });
  ws.addEventListener('close', () => enqueue({ method: 'socket.closed' }));
  await command('Runtime.enable');
  await command('Debugger.enable');
  await command('Debugger.setBlackboxPatterns', { patterns: ['^node:', '^internal/', 'node:internal'] });
  await command('Debugger.setPauseOnExceptions', { state: 'uncaught' });
  await command('Runtime.runIfWaitingForDebugger');
  while (!finished && !reason) {
    if (Date.now() - started > limits.seconds * 1000) { reason = 'execution_timeout'; break; }
    const event = await Promise.race([nextEvent(), sleep(Math.max(1, limits.seconds * 1000 - (Date.now() - started)), true).then(() => ({ method: 'deadline' }))]);
    if (event.method === 'deadline') { reason = 'execution_timeout'; break; }
    if (event.method === 'child.exit' || event.method === 'socket.closed' || event.method === 'program.finished') break;
    if (event.method !== 'Debugger.paused') continue;
    if (trace.length >= limits.steps) { reason = 'step_limit'; break; }
    // 标准库栈下方仍有学生调用帧，只有当前顶帧在学生文件才记录这一步。
    const user = isUser(event.params.callFrames[0]) ? await snapshot(event) : false;
    if (reason) break;
    if (event.params.reason === 'exception') {
      const location = event.params.callFrames.find(isUser)?.location;
      const syntaxLine = stderr.match(/main\.cjs:(\d+)/)?.[1];
      diagnostics.push({ line: location ? location.lineNumber + 1 : Number(syntaxLine || 0), message: event.params.data?.description || '运行异常' });
      reason = 'runtime_error';
      break;
    }
    await command(user ? 'Debugger.stepInto' : 'Debugger.stepOut');
  }
  if (!reason && !finished) {
    // Node 完成后会等待 Inspector 断开，主动关闭才能让退出事件到达。
    ws.close();
    await Promise.race([new Promise(resolve => child.once('exit', resolve)), sleep(500)]);
  }
} catch (error) {
  reason ||= 'driver_error';
  stderr = (stderr + '\n' + String(error.message || error)).slice(0, limits.outputBytes);
} finally {
  if (reason) stop();
  try { ws?.close(); } catch { /* 调试连接可能已随程序关闭。 */ }
  await sleep(40);
  if (!finished) stop();
  if (!reason && finished && (childExitCode !== 0 || childSignal)) {
    reason = 'runtime_error';
    diagnostics.push({ line: trace.at(-1)?.line || 0, message: childSignal ? `程序收到信号 ${childSignal}` : `程序退出码: ${childExitCode}` });
  }
  const response = { status: reason ? (reason === 'execution_timeout' ? 'timeout' : 'error') : 'completed', stdout, stderr, diagnostics, trace, truncated: ['execution_timeout', 'step_limit', 'trace_limit', 'output_limit'].includes(reason) };
  if (reason) response.reason = reason;
  await writeFile(path.join(job, 'result.json'), JSON.stringify(response));
}
