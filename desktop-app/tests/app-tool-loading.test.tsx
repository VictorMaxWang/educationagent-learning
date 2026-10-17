// @vitest-environment jsdom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { expect, it, vi } from 'vitest';

const loads = vi.hoisted(() => ({ technology: vi.fn(), settings: vi.fn(), watermark: vi.fn(), music: vi.fn(), resolveTechnology: undefined as undefined | (() => void) }));
vi.mock('../src/classroom/ClassroomView', () => ({ default: ({ onOpenNavigation }: { onOpenNavigation(): void }) => <button onClick={onOpenNavigation}>课堂更多工具</button> }));
vi.mock('../src/components/VoiceOrb', () => ({ default: () => null }));
vi.mock('../src/components/OnboardingGuide', () => ({ default: () => null }));
vi.mock('../src/research/TechnologyOverview', async () => {
  loads.technology();
  await new Promise<void>(resolve => { loads.resolveTechnology = resolve; });
  return { default: () => <section aria-label="技术说明内容">课堂算法与来源</section> };
});
vi.mock('../src/components/SettingsView', () => { loads.settings(); return { default: ({ onClose }: { onClose(): void }) => <button onClick={onClose}>关闭已加载设置</button> }; });
vi.mock('../src/tools/WatermarkView', () => { loads.watermark(); throw new Error('fixture: unavailable tool chunk'); });
vi.mock('../src/components/KittyMusicPlayer', () => { loads.music(); return { default: () => <div>本机音乐</div> }; });

it('公开首页不加载工具，慢分块可取消，失败分块可退出且不恢复隐藏入口', async () => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true });
  localStorage.clear(); vi.stubEnv('VITE_PUBLIC_CLASSROOM', 'true');
  const { default: App } = await import('../src/App');
  const host = document.createElement('div'); document.body.append(host); const root = createRoot(host);
  const button = (label: string) => [...host.querySelectorAll('button')].find(node => node.textContent?.includes(label))!;
  try {
    await act(async () => root.render(<App />));
    expect(button('课堂更多工具')).toBeDefined();
    expect(loads.technology).not.toHaveBeenCalled(); expect(loads.settings).not.toHaveBeenCalled(); expect(loads.watermark).not.toHaveBeenCalled(); expect(loads.music).not.toHaveBeenCalled();
    await act(async () => button('课堂更多工具').click());
    const tools = host.querySelector('.more-tools-content')!.textContent!;
    expect(tools).toContain('技术与参考'); expect(tools).toContain('刷题练习'); expect(tools).toContain('图片水印');
    expect(tools).not.toContain('团队成果实验室'); expect(tools).not.toContain('JavaScript 浏览器练习'); expect(tools).not.toContain('英语口语'); expect(tools).not.toContain('教师工作台');
    await act(async () => button('技术与参考').click());
    await vi.waitFor(() => expect(loads.technology).toHaveBeenCalledTimes(1));
    expect(host.querySelector('[role=status]')?.textContent).toContain('正在打开技术与参考');
    await act(async () => button('取消打开').click());
    expect(button('课堂更多工具')).toBeDefined();
    await act(async () => { loads.resolveTechnology!(); await Promise.resolve(); });
    await act(async () => button('课堂更多工具').click());
    await act(async () => button('技术与参考').click());
    expect(host.querySelector('[aria-label="技术说明内容"]')).not.toBeNull();
    expect(loads.technology).toHaveBeenCalledTimes(1); expect(loads.music).not.toHaveBeenCalled();
    await act(async () => button('返回学习课堂').click());
    await act(async () => button('课堂更多工具').click());
    await act(async () => button('设置 · 昵称').click());
    await act(async () => { await vi.waitFor(() => expect(loads.settings).toHaveBeenCalledTimes(1)); });
    expect(button('关闭已加载设置')).toBeDefined();
    await act(async () => button('关闭已加载设置').click());
    await act(async () => button('课堂更多工具').click());
    const errorLog = vi.spyOn(console, 'error').mockImplementation(() => {});
    try {
      await act(async () => button('图片水印').click());
      await act(async () => { await vi.waitFor(() => expect(loads.watermark).toHaveBeenCalledTimes(1)); });
      expect(host.querySelector('[role=alert]')?.textContent).toContain('图片水印暂时没有加载成功');
      await act(async () => button('关闭').click());
      expect(button('课堂更多工具')).toBeDefined();
    } finally { errorLog.mockRestore(); }
  } finally {
    await act(async () => root.unmount()); host.remove(); vi.unstubAllEnvs();
  }
});
