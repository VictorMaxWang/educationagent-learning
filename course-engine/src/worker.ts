import { createInterface } from 'node:readline';
import { randomUUID } from 'node:crypto';
import type { SceneOutline } from '@educationagent/generation';
import { auditQuiz, generateOutline, generateScene, planQuizRepair, type CourseInput, type RawCall } from './engine.js';

// 启动检查实际加载共享包；只验证依赖，不调用模型或等待 stdin。
if (process.argv.includes('--check')) {
  process.stdout.write(JSON.stringify({ type: 'ready', engine: 'educationagent' }) + '\n', () => process.exit(0));
  await new Promise(() => {});
}

const MAX_LINE_BYTES = 2_000_000;
const MODEL_TIMEOUT_MS = 180_000;
const pending = new Map<string, { resolve: (text: string) => void; reject: (error: Error) => void; timer: ReturnType<typeof setTimeout> }>();
let active = false;
let closed = false;

function emit(message: object, done?: () => void): void {
  const line = JSON.stringify(message) + '\n';
  if (Buffer.byteLength(line, 'utf8') > MAX_LINE_BYTES) {
    process.stdout.write(JSON.stringify({ type: 'error', id: 'worker', message: '生成结果超过进程通信上限，请减少内容后重试', code: 'output-too-large' }) + '\n', done);
    return;
  }
  process.stdout.write(line, done);
}

// purpose 让 Java 侧把复核请求路由到 temperature 0 的模型；不传时协议行与改动前逐字节一致。
const aiCall: RawCall = (system, user, purpose) => new Promise((resolve, reject) => {
  if (closed) { reject(new Error('父进程已关闭任务')); return; }
  const id = randomUUID();
  const timer = setTimeout(() => { pending.delete(id); reject(new Error('模型响应超时')); }, MODEL_TIMEOUT_MS);
  pending.set(id, { resolve, reject, timer });
  const request: Record<string, unknown> = { type: 'llm.request', id, messages: [{ role: 'system', content: system }, { role: 'user', content: user }] };
  if (purpose) request.purpose = purpose;
  emit(request);
});

const reader = createInterface({ input: process.stdin, crlfDelay: Infinity, terminal: false });
reader.on('line', (line: string) => {
  if (closed) return;
  if (Buffer.byteLength(line, 'utf8') > MAX_LINE_BYTES) { emit({ type: 'error', id: 'worker', message: '输入超过进程通信上限', code: 'input-too-large' }, () => process.exit(1)); return; }
  let message: Record<string, unknown>;
  try { message = JSON.parse(line); } catch { emit({ type: 'error', id: 'worker', message: '输入必须是一行完整JSON', code: 'invalid-json' }, () => process.exit(1)); return; }
  if (!message || typeof message !== 'object') { emit({ type: 'error', id: 'worker', message: '无效协议消息', code: 'invalid-message' }, () => process.exit(1)); return; }
  if (message.type === 'llm.result' || message.type === 'llm.error') {
    const call = pending.get(String(message.id));
    if (!call) return;
    pending.delete(String(message.id)); clearTimeout(call.timer);
    if (typeof message.error === 'string' || message.type === 'llm.error') call.reject(new Error(String(message.error || message.message || '模型调用失败')));
    else if (typeof message.text === 'string') call.resolve(message.text);
    else call.reject(new Error('模型响应缺少文本'));
    return;
  }
  if (message.type !== 'job' || active || typeof message.id !== 'string') { emit({ type: 'error', id: message.id || 'worker', message: '一个进程只接受一个有效生成任务', code: 'invalid-job' }, () => process.exit(1)); return; }
  active = true;
  const input = (message.input || {}) as CourseInput;
  const operation = message.operation;
  // review 只读补审；repair 只出修复方案（两者都不写任何东西，落库一律由 Java 决定）。
  const task = operation === 'outline' ? generateOutline(input, aiCall)
    : operation === 'review' ? auditQuiz(input, aiCall)
    : operation === 'repair' ? planQuizRepair(input, aiCall)
    : operation === 'scene' ? generateScene(input, message.outline as SceneOutline, aiCall)
    : Promise.reject(new Error('不支持的生成操作'));
  void task.then((value) => emit({ type: 'result', id: message.id, value }, () => process.exit(0))).catch((error: unknown) => emit({ type: 'error', id: message.id, message: error instanceof Error ? error.message : String(error), code: 'generation-failed' }, () => process.exit(1)));
});

function parentClosed(): void {
  closed = true;
  for (const call of pending.values()) { clearTimeout(call.timer); call.reject(new Error('父进程已关闭任务')); }
  pending.clear();
  process.exit(0);
}
reader.on('close', parentClosed);
process.stdin.on('error', parentClosed);
process.stdout.on('error', parentClosed);
