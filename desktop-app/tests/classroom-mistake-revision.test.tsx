import { afterEach, expect, it, vi } from 'vitest';
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { JSDOM } from 'jsdom';
import MistakeView from '../src/study/MistakeView';
vi.mock('../src/api/chatApi', () => ({ getMistakes: vi.fn(), redoMistake: vi.fn(), removeMistake: vi.fn() }));
const { getMistakes, redoMistake, removeMistake } = await import('../src/api/chatApi');
afterEach(() => { vi.unstubAllGlobals(); vi.clearAllMocks(); });

it('错误原题的历史作答保留展示，改由修订版入口继续学习', async () => {
  const dom = new JSDOM('<div id="root"></div>', { url: 'https://example.test' });
  vi.stubGlobal('window', dom.window); vi.stubGlobal('document', dom.window.document); vi.stubGlobal('navigator', dom.window.navigator);
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
  vi.mocked(getMistakes).mockResolvedValueOnce([{ questionId: 8, quizId: 5, no: 1, type: 'single', stem: '保留原题干20×8×3',
    options: ['A. 48', 'B. 240'], userAnswer: 'A', correctAnswer: 'B', explanation: '错误的旧解析',
    revision: { newCourseId: 'corrected-course', reason: '原题选项缺少480。' } }]);
  const root = createRoot(document.getElementById('root')!);
  await act(async () => { root.render(<MistakeView userId="classroom" embedded />); });
  expect(document.body.textContent).toContain('内容已更新');
  expect(document.body.textContent).toContain('当时的作答：A');
  expect(document.querySelector('a')?.getAttribute('href')).toBe('#/classroom/corrected-course');
  expect(Array.from(document.querySelectorAll('button')).some(button => button.textContent?.includes('重做'))).toBe(false);
  expect(document.body.textContent).not.toContain('错误的旧解析');
  expect(redoMistake).not.toHaveBeenCalled(); expect(removeMistake).not.toHaveBeenCalled();
  await act(async () => { root.unmount(); }); dom.window.close();
});
