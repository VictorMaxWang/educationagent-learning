// @vitest-environment jsdom
import { act, useState } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { RecommendationResponse } from '../src/classroom/study-types';

const api = vi.hoisted(() => ({ get: vi.fn(), refresh: vi.fn(), feedback: vi.fn() }));
vi.mock('../src/classroom/study-api', () => ({ getRecommendations: api.get, refreshRecommendations: api.refresh, sendVideoFeedback: api.feedback }));
import SupplementaryVideos, { displayableVideos, safeBilibiliVideoUrl, VideoBudgetControl } from '../src/classroom/SupplementaryVideos';

const response = (changes: Partial<RecommendationResponse> = {}): RecommendationResponse => ({
  status: 'ready', sceneId: 'scene', contentFingerprint: 'fingerprint', decisionId: 'decision', budgetMinutes: 10,
  updatedAt: new Date().toISOString(), items: [{ id: 'BV1xx411c7mD:1', url: 'https://www.bilibili.com/video/BV1xx411c7mD?p=1', title: '沁园春长沙朗诵', author: '课程老师', durationSeconds: 180, reason: '听一听诵读时的节奏', goals: ['诵读节奏'], purpose: '朗诵示范', verificationScope: 'metadata', checkedAt: new Date().toISOString() }], ...changes,
});
let host: HTMLDivElement, root: Root;
const render = (changes: Partial<Parameters<typeof SupplementaryVideos>[0]> = {}) => root.render(<SupplementaryVideos courseId="course" sceneId="scene" contentRevision={1} budgetMinutes={10} {...changes} />);
beforeEach(() => { Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true }); api.get.mockReset(); api.refresh.mockReset(); api.feedback.mockReset().mockResolvedValue({}); host = document.createElement('div'); document.body.append(host); root = createRoot(host); });
afterEach(async () => { await act(async () => root.unmount()); host.remove(); vi.useRealTimers(); });

describe('补充视频的展示和请求边界', () => {
  it.each(['hidden', 'error'] as const)('%s 时整个区域为空，不留搜索入口或占位', async status => {
    api.get.mockResolvedValue(response({ status, items: [] })); await act(async () => render());
    expect(host.innerHTML).toBe(''); expect(api.refresh).not.toHaveBeenCalled();
  });
  it('空闲时准备，排队后轮询；得到真实视频后停止', async () => {
    vi.useFakeTimers(); api.get.mockResolvedValueOnce(response({ status: 'idle', items: [] })).mockResolvedValue(response());
    api.refresh.mockResolvedValue(response({ status: 'queued', items: [], nextPollMs: 1500 }));
    await act(async () => render()); expect(host.innerHTML).toBe('');
    expect(api.refresh).toHaveBeenCalledWith('course', 'scene', 10, expect.any(AbortSignal));
    await act(async () => vi.advanceTimersByTimeAsync(1500)); expect(host.textContent).toContain('沁园春长沙朗诵');
    const count = api.get.mock.calls.length; await act(async () => vi.advanceTimersByTimeAsync(10000)); expect(api.get).toHaveBeenCalledTimes(count);
  });
  it('同一内容的重渲染不重新检索，内容改变才废弃旧结果', async () => {
    api.get.mockResolvedValue(response()); await act(async () => render()); await act(async () => render());
    expect(api.get).toHaveBeenCalledTimes(1);
    let done!: (value: RecommendationResponse) => void; api.get.mockReturnValue(new Promise<RecommendationResponse>(resolve => { done = resolve; }));
    await act(async () => render({ contentRevision: 2 })); expect(host.innerHTML).toBe('');
    expect(api.get.mock.calls[0][2].aborted).toBe(true);
    await act(async () => done(response({ status: 'hidden', items: [] }))); expect(host.innerHTML).toBe('');
  });
  it('切换页面会中止旧请求，迟到结果不能冒充新页', async () => {
    let done!: (value: RecommendationResponse) => void; api.get.mockReturnValueOnce(new Promise<RecommendationResponse>(resolve => { done = resolve; })).mockResolvedValue(response({ sceneId: 'next', status: 'hidden', items: [] }));
    await act(async () => render()); await act(async () => render({ sceneId: 'next' })); await act(async () => done(response()));
    expect(api.get.mock.calls[0][2].aborted).toBe(true); expect(host.innerHTML).toBe('');
  });
  it('过期数据先重新核验且不闪现旧视频', async () => {
    api.get.mockResolvedValue(response({ updatedAt: new Date(Date.now() - 25 * 3600000).toISOString() })); api.refresh.mockResolvedValue(response({ status: 'hidden', items: [] }));
    await act(async () => render()); expect(api.refresh).toHaveBeenCalledTimes(1); expect(host.innerHTML).toBe('');
  });
  it('预算变更提交真实分钟数，超预算组合不展示', async () => {
    api.get.mockResolvedValue(response()); api.refresh.mockResolvedValue(response({ budgetMinutes: 5, status: 'hidden', items: [] }));
    await act(async () => render({ budgetMinutes: 5 })); expect(api.refresh).toHaveBeenCalledWith('course', 'scene', 5, expect.any(AbortSignal));
    expect(displayableVideos(response({ budgetMinutes: 5, items: [{ ...response().items[0], durationSeconds: 301 }] }), 5)).toEqual([]);
  });
  it('外链只在新页打开，opened 不标记完成学习', async () => {
    api.get.mockResolvedValue(response()); await act(async () => render());
    const link = host.querySelector('a')!; expect(link.target).toBe('_blank'); expect(link.rel).toBe('noopener noreferrer');
    await act(async () => { const event = new MouseEvent('click', { bubbles: true, cancelable: true }); event.preventDefault(); link.dispatchEvent(event); });
    expect(api.feedback).toHaveBeenCalledWith('course', 'decision', 'BV1xx411c7mD:1', 'opened', expect.any(String), expect.any(AbortSignal));
    expect(host.textContent).not.toContain('已看完'); expect(host.textContent).not.toContain('已学会');
  });
  it('预算控件可独立键盘操作，不依赖推荐区可见', async () => {
    const change = vi.fn(); await act(async () => root.render(<VideoBudgetControl value={10} onChange={change} />));
    expect(host.querySelectorAll('button')).toHaveLength(3); expect(host.querySelector('[aria-pressed=true]')?.textContent).toBe('10 分钟');
    await act(async () => (host.querySelectorAll('button')[2] as HTMLButtonElement).click()); expect(change).toHaveBeenCalledWith(20);
  });
  it('首次读取采用共享网站的预算，旧浏览器默认值不能静默覆盖它', async () => {
    api.get.mockResolvedValue(response({ budgetMinutes: 5 })); const resolved = vi.fn();
    await act(async () => render({ budgetMinutes: 10, budgetOverride: false, onBudgetResolved: resolved }));
    expect(resolved).toHaveBeenCalledWith(5); expect(api.refresh).not.toHaveBeenCalled();
  });
  it('失败时视频区仍隐藏，点击独立工具栏才以同预算重新准备', async () => {
    api.get.mockResolvedValue(response({ status: 'error', items: [] })); api.refresh.mockResolvedValue(response());
    function Harness() {
      const [manual, setManual] = useState(''); const [preparing, setPreparing] = useState(false);
      return <><VideoBudgetControl value={10} onChange={() => {}} preparing={preparing} onRefresh={() => { setPreparing(true); setManual('manual-retry'); }} /><SupplementaryVideos courseId="course" sceneId="scene" contentRevision={1} budgetMinutes={10} manualRefreshId={manual} onPreparingChange={setPreparing} /></>;
    }
    await act(async () => root.render(<Harness />));
    expect(host.querySelector('[aria-label="这节课的补充视频"]')).toBeNull(); expect(api.refresh).not.toHaveBeenCalled();
    await act(async () => (host.querySelector('.cr-video-retry') as HTMLButtonElement).click());
    expect(api.refresh).toHaveBeenCalledTimes(1); expect(api.refresh).toHaveBeenCalledWith('course', 'scene', 10, expect.any(AbortSignal), true);
    expect(host.textContent).toContain('沁园春长沙朗诵');
  });
  it('手动准备只消费一次，切页及同页重渲染不重发旧意图', async () => {
    api.get.mockImplementation(async (_course, sceneId) => response({ sceneId, status: 'error', items: [] }));
    api.refresh.mockResolvedValue(response({ status: 'hidden', items: [] }));
    await act(async () => render({ manualRefreshId: 'manual-one' })); expect(api.refresh).toHaveBeenCalledTimes(1);
    await act(async () => render({ manualRefreshId: 'manual-one' }));
    await act(async () => render({ sceneId: 'next', manualRefreshId: '' }));
    await act(async () => render({ manualRefreshId: 'manual-one' }));
    expect(api.refresh).toHaveBeenCalledTimes(1);
  });
  it('独立重试按钮在准备中禁用，同一事件批次双击也只发一个意图', async () => {
    const retry = vi.fn(); await act(async () => root.render(<VideoBudgetControl value={10} onChange={() => {}} onRefresh={retry} />));
    const retryButton = host.querySelector('.cr-video-retry') as HTMLButtonElement;
    await act(async () => { retryButton.click(); retryButton.click(); }); expect(retry).toHaveBeenCalledTimes(1);
    await act(async () => root.render(<VideoBudgetControl value={10} onChange={() => {}} onRefresh={retry} preparing />));
    expect((host.querySelector('.cr-video-retry') as HTMLButtonElement).disabled).toBe(true);
    await act(async () => (host.querySelector('.cr-video-retry') as HTMLButtonElement).click()); expect(retry).toHaveBeenCalledTimes(1);
    await act(async () => root.render(<VideoBudgetControl value={10} onChange={() => {}} onRefresh={retry} preparing={false} />));
    await act(async () => (host.querySelector('.cr-video-retry') as HTMLButtonElement).click()); expect(retry).toHaveBeenCalledTimes(2);
  });
  it('核验缓存与直连资料明确区分，显示真实上海时间且不泄露网络诊断', async () => {
    vi.useFakeTimers(); vi.setSystemTime(new Date('2026-10-16T12:00:00Z'));
    const first = { ...response().items[0], checkedAt: '2026-10-16T09:05:00Z', sourceKind: 'bilibili-public-api' as const, sourceLabel: '已核验的视频资料' };
    const cached = { ...first, id: 'BV1xx411c7mD:2', url: 'https://www.bilibili.com/video/BV1xx411c7mD?p=2', sourceKind: 'verified-metadata-cache' as const, sourceLabel: 'HTTP 412 blocked' };
    api.get.mockResolvedValue(response({ items: [first, cached] })); await act(async () => render());
    const sources = Array.from(host.querySelectorAll('.cr-video-verification')).map(node => node.textContent);
    expect(sources).toEqual(['已核验的视频资料 · 2026-10-16 17:05', '已核验资料缓存 · 2026-10-16 17:05']);
    expect(host.querySelector('time')?.dateTime).toBe(first.checkedAt); expect(host.textContent).not.toContain('HTTP 412');
    await act(async () => { vi.setSystemTime(new Date('2026-10-17T09:06:00Z')); render({ contentRevision: 2 }); });
    expect(host.querySelector('.cr-video-verification')).toBeNull();
  });
});
describe('视频身份检查', () => {
  it.each(['https://evil.example/video/BV1xx411c7mD', 'https://www.bilibili.com.evil.example/video/BV1xx411c7mD', 'https://www.bilibili.com/search', 'javascript:alert(1)', 'https://user:pass@www.bilibili.com/video/BV1xx411c7mD', 'https://www.bilibili.com/video/BV1xx411c7mD?p=0'])('拒绝 %s', value => expect(safeBilibiliVideoUrl(value)).toBeNull());
  it('去掉跟踪参数但保留有效分P', () => expect(safeBilibiliVideoUrl('https://www.bilibili.com/video/BV1xx411c7mD/?p=2&track=foo')).toBe('https://www.bilibili.com/video/BV1xx411c7mD?p=2'));
  it('未知时长、重复URL和空标题都隐藏整个组合', () => {
    const first = response().items[0];
    for (const items of [[{ ...first, durationSeconds: 0 }], [first, { ...first, id: 'duplicate' }], [{ ...first, title: ' ' }]]) expect(displayableVideos(response({ items }), 10)).toEqual([]);
  });
});
