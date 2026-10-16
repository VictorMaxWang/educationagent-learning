// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { CodeJob } from '../src/practice/code-api';

const api = vi.hoisted(() => ({ create: vi.fn(), get: vi.fn(), cancel: vi.fn() }));
vi.mock('../src/practice/code-api', async importOriginal => ({ ...await importOriginal<typeof import('../src/practice/code-api')>(), createCodeJob: api.create, getCodeJob: api.get, cancelCodeJob: api.cancel }));
import CodeVisualization from '../src/practice/CodeVisualization';

const pendingJob = (): CodeJob => ({ id: 'real-job', status: 'running', stdout: '', stderr: '', diagnostics: [], trace: [] });
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done; }); return { promise, resolve }; }
let host: HTMLDivElement, root: Root;
const button = (label: string) => Array.from(host.querySelectorAll('button')).find(item => item.textContent?.includes(label))!;

beforeEach(() => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true });
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { value: vi.fn(), configurable: true });
  api.create.mockReset(); api.get.mockReset(); api.cancel.mockReset();
  api.cancel.mockResolvedValue({ ...pendingJob(), status: 'cancelled' });
  host = document.createElement('div'); document.body.append(host); root = createRoot(host);
});
afterEach(async () => { await act(async () => root.unmount()); host.remove(); vi.useRealTimers(); });

describe('代码任务的编辑与迟到响应边界', () => {
  it('语言改变时取消当前任务，旧轮询结果不能覆盖新例题', async () => {
    const result = deferred<CodeJob>(); api.create.mockResolvedValue(pendingJob()); api.get.mockReturnValue(result.promise);
    await act(async () => root.render(<CodeVisualization />));
    await act(async () => button('运行代码').click());
    expect(api.create).toHaveBeenCalledWith(expect.objectContaining({ language: 'javascript', stdin: '4 3' }));
    await act(async () => { const language = host.querySelector('select')!; language.value = 'python'; language.dispatchEvent(new Event('change', { bubbles: true })); });
    expect(api.cancel).toHaveBeenCalledWith('real-job');
    await act(async () => result.resolve({ ...pendingJob(), status: 'completed', stdout: '迟到的旧输出' }));
    expect(host.textContent).not.toContain('迟到的旧输出');
    expect((host.querySelector('#cvw-code-editor') as HTMLTextAreaElement).value).toContain('map(int, input().split())');
  });
  it('提交尚未返回时换语言，取得迟到任务 id 后立即取消', async () => {
    const response = deferred<CodeJob>(); api.create.mockReturnValue(response.promise);
    await act(async () => root.render(<CodeVisualization />));
    await act(async () => button('运行代码').click());
    await act(async () => { const language = host.querySelector('select')!; language.value = 'java'; language.dispatchEvent(new Event('change', { bubbles: true })); });
    await act(async () => response.resolve(pendingJob()));
    expect(api.cancel).toHaveBeenCalledWith('real-job'); expect(api.get).not.toHaveBeenCalled();
    expect(host.textContent).toContain('等待你的第一次运行');
  });
  it('超时仍展示已返回轨迹，两个视图共享同一步骤', async () => {
    api.create.mockResolvedValue({ ...pendingJob(), status: 'timeout', reason: '已达到执行时限', truncated: true, trace: [{ index: 0, line: 3, event: 'line', frames: [{ name: 'main', locals: { total: 12 } }], stdout: '12' }] });
    await act(async () => root.render(<CodeVisualization />));
    await act(async () => button('运行代码').click());
    expect(host.textContent).toContain('运行已到时限'); expect(host.querySelector('[aria-current="step"]')?.textContent).toContain('const length');
    await act(async () => button('执行过程追踪').click());
    expect(host.textContent).toContain('total'); expect(host.querySelector('.cvw-step-output')?.textContent).toBe('12');
    expect(api.get).not.toHaveBeenCalled();
  });
  it('取消先返回空轨迹时继续读取，2.5秒后补齐步骤，状态保持已停止', async () => {
    vi.useFakeTimers();
    let cancelledAt: number | undefined;
    const cancelled = { ...pendingJob(), status: 'cancelled' as const, tracePending: true };
    const settled = { ...cancelled, tracePending: false, trace: [{ index: 0, line: 3, event: 'line', frames: [{ name: 'main', locals: { total: 12 } }], stdout: '已停止前的输出' }] };
    api.create.mockResolvedValue(pendingJob());
    api.cancel.mockImplementation(async () => { cancelledAt = Date.now(); return cancelled; });
    api.get.mockImplementation(async () => cancelledAt === undefined ? pendingJob() : Date.now() - cancelledAt < 2500 ? cancelled : settled);
    await act(async () => root.render(<CodeVisualization />));
    await act(async () => button('运行代码').click());
    await act(async () => button('停止').click());
    expect(host.textContent).toContain('已停止，正在整理');
    expect(button('运行代码').disabled).toBe(false);
    await act(async () => vi.advanceTimersByTimeAsync(1950));
    expect(host.textContent).toContain('已停止，正在整理');
    await act(async () => vi.advanceTimersByTimeAsync(650));
    expect(host.textContent).not.toContain('已停止，正在整理');
    expect(host.querySelector('[role="status"]')?.textContent).toBe('已停止');
    expect(host.querySelector('[aria-current="step"]')?.textContent).toContain('const length');
    await act(async () => button('执行过程追踪').click());
    expect(host.querySelector('.cvw-step-output')?.textContent).toBe('已停止前的输出');
    const readCount = api.get.mock.calls.length;
    await act(async () => vi.advanceTimersByTimeAsync(1300));
    expect(api.get).toHaveBeenCalledTimes(readCount);
  });
});
