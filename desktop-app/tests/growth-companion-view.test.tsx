// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import GrowthCompanion from '../src/classroom/GrowthCompanion';
import { GROWTH_STORAGE_PREFIX, growthStorageKey } from '../src/classroom/growth-topics';

const mocks = vi.hoisted(() => ({ request: vi.fn(), upload: vi.fn() }));
vi.mock('../src/classroom/api', () => ({ classroomRequest: mocks.request, uploadMaterial: mocks.upload, audioUrl: (url: string) => url, synthesizeSpeech: vi.fn(), transcribeAudio: vi.fn(), voiceHealth: async () => ({ ttsAvailable: false, asrAvailable: false }) }));
vi.mock('../src/classroom/voiceContext', () => ({ registerVoiceContext: () => () => {} }));
vi.mock('../src/classroom/useAudioFocus', () => ({ useAudioFocus: () => ({ microphoneBlocked: false }) }));
vi.mock('../src/classroom/audioQueue', () => ({ NarrationQueue: class { stop() {} destroy() {} setMicrophoneBusy() {} } }));

let host: HTMLDivElement;
let root: Root;
const button = (label: string) => [...host.querySelectorAll('button')].find(value => value.textContent === label)!;
const localMessage = { id: 'personal', role: 'user', content: '我保存在本机的心情', category: 'study-stress' };

beforeEach(() => {
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  localStorage.clear(); vi.clearAllMocks();
  Element.prototype.scrollIntoView = vi.fn();
  host = document.createElement('div'); document.body.append(host); root = createRoot(host);
  mocks.request.mockImplementation(async (path: string) => path.startsWith('/guide/conversations') ? { messages: [
    { id: 'shared-u', role: 'user', content: '保存的课堂问题', category: 'study-stress' },
    { id: 'shared-a', role: 'assistant', content: '保存的课堂回答', category: 'study-stress' },
  ] } : path.startsWith('/guide/topics') ? { categories: [], persona: {} } : { id: 'reply', text: '真实接口返回的回答', category: 'study-stress', sources: [], suggestions: [] });
});
afterEach(async () => { await act(async () => root.unmount()); host.remove(); });

describe('成长课堂共享历史与个人续聊', () => {
  it('清空仅删除个人续聊，保留共享对话和旧版迁移源', async () => {
    localStorage.setItem(GROWTH_STORAGE_PREFIX + '5', JSON.stringify([localMessage]));
    await act(async () => root.render(<GrowthCompanion grade={5} group="mind-communication" category="study-stress" embedded />));
    expect(host.textContent).toContain('保存的课堂回答'); expect(host.textContent).toContain('我保存在本机的心情');
    await act(async () => button('清除我的续聊').click());
    expect(host.textContent).toContain('保存的课堂回答'); expect(host.textContent).not.toContain('我保存在本机的心情');
    expect(localStorage.getItem(growthStorageKey(5, 'mind-communication'))).toBe('[]');
    expect(localStorage.getItem(GROWTH_STORAGE_PREFIX + '5')).toContain('我保存在本机的心情');
  });
  it('切换年级或大类不会把上一个个人会话写进新键', async () => {
    localStorage.setItem(growthStorageKey(5, 'mind-communication'), JSON.stringify([localMessage]));
    mocks.request.mockImplementation(async (path: string) => path.startsWith('/guide/conversations') ? { messages: [] } : { categories: [], persona: {} });
    await act(async () => root.render(<GrowthCompanion grade={5} group="mind-communication" category="study-stress" embedded />));
    await act(async () => root.render(<GrowthCompanion grade={6} group="safety-health" category="life-safety" embedded />));
    expect(host.textContent).not.toContain('我保存在本机的心情');
    expect(localStorage.getItem(growthStorageKey(6, 'safety-health'))).toBe('[]');
    expect(localStorage.getItem(growthStorageKey(5, 'mind-communication'))).toContain('我保存在本机的心情');
  });
  it('同组切换话题保留草稿，带资料和操作上下文提交时保留共享历史', async () => {
    mocks.request.mockImplementation(async (path: string) => path.startsWith('/guide/conversations') ? { messages: [
      { id: 'shared-u', role: 'user', content: '保存的课堂问题', category: 'practice' }, { id: 'shared-a', role: 'assistant', content: '保存的课堂回答', category: 'practice' },
    ] } : path.startsWith('/guide/topics') ? { categories: [], persona: {} } : { id: 'reply', text: '真实接口返回的回答', category: 'practice', sources: [], suggestions: [] });
    await act(async () => root.render(<GrowthCompanion grade={5} group="practice-creation" category="practice" interactiveContext="当前执行第3行，total=6" embedded />));
    const question = '怎样用循环记录一周的浇水次数？';
    await act(async () => button(question).click());
    await act(async () => root.render(<GrowthCompanion grade={5} group="practice-creation" category="practice" interactiveContext="已选择数组步骤4" embedded />));
    expect(host.querySelector('textarea')?.value).toBe(question);
    mocks.upload.mockResolvedValue({ fileName: '菜地.txt', text: '一周浇水记录', truncated: false });
    const input = host.querySelector<HTMLInputElement>('input[accept^=".pdf"]')!;
    Object.defineProperty(input, 'files', { value: [new File(['一周浇水记录'], '菜地.txt', { type: 'text/plain' })], configurable: true });
    await act(async () => input.dispatchEvent(new Event('change', { bubbles: true })));
    expect(host.textContent).toContain('菜地.txt');
    await act(async () => button('发送 ↑').click());
    const call = mocks.request.mock.calls.find(([path]) => path === '/guide/chat')!;
    const payload = JSON.parse(call[1].body);
    expect(payload).toMatchObject({ question, grade: 5, group: 'practice-creation', category: 'practice', sourceText: '一周浇水记录', sourceName: '菜地.txt', interactiveContext: '已选择数组步骤4' });
    expect(payload.history).toEqual([{ role: 'user', content: '保存的课堂问题' }, { role: 'assistant', content: '保存的课堂回答' }]);
    expect(host.textContent).toContain('真实接口返回的回答');
  });
});
