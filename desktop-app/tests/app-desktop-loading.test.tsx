// @vitest-environment jsdom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { expect, it, vi } from 'vitest';

const music = vi.hoisted(() => ({ load: vi.fn(), resolve: undefined as undefined | (() => void) }));
vi.mock('../src/classroom/ClassroomView', () => ({ default: ({ onOpenNavigation }: { onOpenNavigation(): void }) => <button onClick={onOpenNavigation}>课堂更多工具</button> }));
vi.mock('../src/components/VoiceOrb', () => ({ default: () => null }));
vi.mock('../src/components/OnboardingGuide', () => ({ default: () => null }));
vi.mock('../src/components/KittyMusicPlayer', async () => {
  music.load(); await new Promise<void>(resolve => { music.resolve = resolve; });
  return { default: () => <div data-music-hidden={localStorage.getItem('music_player_hidden')}>已加载原音乐播放器</div> };
});

it('桌面旧工具入口保留，延迟加载音乐时第一次显示操作仍生效', async () => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true });
  localStorage.clear(); localStorage.setItem('music_player_hidden', '1'); vi.stubEnv('VITE_PUBLIC_CLASSROOM', 'false');
  const { default: App } = await import('../src/App');
  const host = document.createElement('div'); document.body.append(host); const root = createRoot(host);
  const button = (label: string) => [...host.querySelectorAll('button')].find(node => node.textContent?.includes(label))!;
  try {
    await act(async () => root.render(<App />)); expect(music.load).not.toHaveBeenCalled();
    await act(async () => button('课堂更多工具').click());
    const tools = host.querySelector('.more-tools-content')!.textContent!;
    expect(tools).toContain('JavaScript 浏览器练习'); expect(tools).toContain('单词学习'); expect(tools).toContain('教师工作台'); expect(tools).not.toContain('团队成果实验室');
    await act(async () => (host.querySelector('[aria-label="关闭更多工具"]') as HTMLButtonElement).click());
    await act(async () => (host.querySelector('[title="音乐播放器"]') as HTMLButtonElement).click());
    await vi.waitFor(() => expect(music.load).toHaveBeenCalledTimes(1));
    expect(localStorage.getItem('music_player_hidden')).toBe('0');
    await act(async () => { music.resolve!(); await Promise.resolve(); });
    expect(host.querySelector('[data-music-hidden="0"]')).not.toBeNull();
    expect(button('课堂更多工具')).toBeDefined();
  } finally { await act(async () => root.unmount()); host.remove(); vi.unstubAllEnvs(); }
});
