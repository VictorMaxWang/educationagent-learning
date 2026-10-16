import { API_BASE_URL } from '../api/baseUrl';

export type CodeLanguage = 'javascript' | 'python' | 'cpp' | 'java';
export type TraceValue = null | boolean | number | string | TraceValue[] | { [key: string]: TraceValue };
export interface TraceFrame { name: string; locals: Record<string, TraceValue> }
export interface TraceStep {
  index: number; line: number; event: string; frames: TraceFrame[];
  heap?: Record<string, TraceValue>; stdout: string;
}
export interface CodeJob {
  id: string;
  status: 'queued' | 'running' | 'completed' | 'error' | 'timeout' | 'cancelled';
  stdout: string; stderr: string; diagnostics: { line?: number; message: string }[];
  trace: TraceStep[]; truncated?: boolean; reason?: string; tracePending?: boolean;
}
export interface CodeSubmission { language: CodeLanguage; code: string; stdin: string }
export interface TraceVariable { key: string; name: string; frame: string; value: TraceValue }

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const response = await fetch(`${API_BASE_URL}/api/classroom/code/jobs${path}`, {
    ...options, credentials: 'include', headers: { 'Content-Type': 'application/json', ...options.headers },
  });
  const value = await response.json().catch(() => ({}));
  if (!response.ok || value.success === false) throw new Error(value.error || value.message || `请求失败（${response.status}）`);
  return value as T;
}

export const createCodeJob = (body: CodeSubmission, signal?: AbortSignal) => request<CodeJob>('', { method: 'POST', body: JSON.stringify(body), signal });
export const getCodeJob = (id: string, signal?: AbortSignal) => request<CodeJob>(`/${encodeURIComponent(id)}`, { signal });
export const cancelCodeJob = (id: string, signal?: AbortSignal) => request<CodeJob>(`/${encodeURIComponent(id)}`, { method: 'DELETE', signal });
export function isCodeJobActive(job: CodeJob | null): boolean { return job?.status === 'queued' || job?.status === 'running'; }
/** 取消立即生效，但运行器仍可能在收尾阶段补回已执行轨迹；这不阻止开始下一次运行。 */
export function shouldPollCodeJob(job: CodeJob | null): boolean { return isCodeJobActive(job) || (job?.status === 'cancelled' && job.tracePending === true); }

/** 保留栈帧下标，递归中同名变量不会被静默覆盖。 */
export function traceVariables(step?: TraceStep): TraceVariable[] {
  return (step?.frames ?? []).flatMap((frame, frameIndex) => Object.entries(frame.locals ?? {}).map(([name, value]) => ({ key: `${frameIndex}:${name}`, name, frame: frame.name, value })));
}
export function traceReference(value: TraceValue | undefined): string | undefined {
  if (value && typeof value === 'object' && !Array.isArray(value) && (typeof value.$ref === 'string' || typeof value.$ref === 'number')) return String(value.$ref);
  return undefined;
}
export function resolveTraceValue(value: TraceValue | undefined, step?: TraceStep): TraceValue | undefined {
  const id = traceReference(value);
  return id === undefined ? value : step?.heap?.[id];
}
/** 运行器可直接返回数组，也可将对象内容放在 items / elements / value 中。 */
export function numericTraceArray(value: TraceValue | undefined, step?: TraceStep): number[] | undefined {
  const resolved = resolveTraceValue(value, step);
  const candidate = Array.isArray(resolved) ? resolved : resolved && typeof resolved === 'object' ? resolved.items ?? resolved.elements ?? resolved.value : undefined;
  return Array.isArray(candidate) && candidate.every(item => typeof item === 'number' && Number.isFinite(item)) ? candidate as number[] : undefined;
}
export function displayTraceValue(value: TraceValue | undefined, limit = 160): string {
  if (value === undefined) return '此步骤暂不可读取';
  if (value && typeof value === 'object' && !Array.isArray(value) && typeof value.$unavailable === 'string') return `暂不可读取：${value.$unavailable}`;
  const ref = traceReference(value);
  const text = ref === undefined ? typeof value === 'string' ? JSON.stringify(value) : JSON.stringify(value) : `引用 ${ref}`;
  return text.length > limit ? `${text.slice(0, limit)}…` : text;
}
export function numericTraceSeries(trace: TraceStep[], variableKey: string, end: number): { step: number; value: number }[] {
  return trace.slice(0, end + 1).flatMap((step, index) => {
    const variable = traceVariables(step).find(item => item.key === variableKey);
    const value = resolveTraceValue(variable?.value, step);
    return typeof value === 'number' && Number.isFinite(value) ? [{ step: index, value }] : [];
  });
}
export function compareCodeOutput(actual: string, expected: string): boolean {
  const normalise = (text: string) => text.replace(/\r\n/g, '\n').split('\n').map(line => line.trimEnd()).join('\n').trim();
  return normalise(actual) === normalise(expected);
}
function contextSlice(text: string, limit: number): string {
  let result = text.slice(0, Math.max(0, limit));
  // 问答还有一次总长度裁剪，因此在这里就收敛预算，并避免留下半个代理对字符。
  if (/[\uD800-\uDBFF]$/.test(result)) result = result.slice(0, -1);
  return result;
}

function contextSource(code: string, line: number | undefined, budget: number): string {
  const lines = code.replace(/\r\n/g, '\n').split('\n');
  const focus = Math.max(0, Math.min(lines.length - 1, (line ?? 1) - 1));
  const complete = lines.map((text, index) => `${index === focus ? '>' : ' '} ${index + 1}: ${text}`).join('\n');
  if (complete.length <= budget) return complete;
  // 长源码先放选中行，再按距离补充上下文，不能让文件头占满当前步骤的源码预算。
  let result = contextSlice(`> ${focus + 1}: ${lines[focus]}`, Math.min(budget, 900));
  const nearby: { index: number; text: string }[] = [];
  for (let distance = 1; distance <= 8; distance++) {
    for (const index of [focus - distance, focus + distance]) {
      if (index < 0 || index >= lines.length) continue;
      const text = contextSlice(`  ${index + 1}: ${lines[index]}`, 300);
      if (result.length + nearby.reduce((count, item) => count + item.text.length + 1, 0) + text.length + 40 <= budget) nearby.push({ index, text });
    }
  }
  if (nearby.length) result += '\n附近源码（行号与源文件一致）：\n' + nearby.sort((left, right) => left.index - right.index).map(item => item.text).join('\n');
  return contextSlice(result, budget);
}

export function codeContext(submission: CodeSubmission, step?: TraceStep): string {
  const variables = contextSlice(traceVariables(step).slice(0, 10).map(item => `${contextSlice(item.frame, 50)}.${contextSlice(item.name, 40)}=${displayTraceValue(item.value, 70)}`).join('；'), 1100);
  const execution = step ? `当前执行步骤：${step.index + 1}，第 ${step.line} 行，${contextSlice(step.event, 70)}\n变量：${variables}\n当前输出：${contextSlice(step.stdout.slice(-500), 500)}` : '尚未执行；请结合当前代码与输入解答。';
  const prefix = `实践与创造 / 代码可视化\n语言：${submission.language}\n${execution}\n输入：${contextSlice(submission.stdin, 350)}\n代码（当前行优先）：\n`;
  return prefix + contextSource(submission.code, step?.line, 4000 - prefix.length);
}
