// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import QuizScene from '../src/classroom/QuizScene';
import type { Scene } from '../src/classroom/types';

// 保留真实组件和 API 序列化，只在 HTTP 边界替换服务端响应。
const http = vi.fn();
const response = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
const feedback = { quizId: '7', score: 1, total: 1, results: [{ no: 1, correct: true, correctAnswer: 'A', userAnswer: 'A', explanation: '已确认的解析' }] };
const quiz = (questionVersion?: string): Scene => ({ id: 'scene', title: '随堂题', type: 'quiz', order: 0, actions: [], questionVersion,
  content: { type: 'quiz', questions: [{ id: 'q', no: 1, type: 'single', question: '当前题面', options: [{ value: 'A', label: '甲' }, { value: 'B', label: '乙' }] }] } });
let host: HTMLDivElement, root: Root;
const button = (text: string) => [...host.querySelectorAll('button')].find(item => item.textContent?.includes(text));
const posted = () => http.mock.calls.filter(([, options]) => options?.method === 'POST').map(([, options]) => JSON.parse(options.body));
beforeEach(() => {
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true); vi.stubGlobal('fetch', http);
  http.mockReset().mockImplementation(async (_url, options) => response(options?.method === 'POST' ? feedback : { courseId: 'c', revisions: [] }));
  host = document.createElement('div'); document.body.append(host); root = createRoot(host);
});
afterEach(async () => { await act(async () => root.unmount()); host.remove(); vi.unstubAllGlobals(); });
async function show(scene: Scene) { await act(async () => root.render(<QuizScene courseId="c" scene={scene} />)); }
async function answerAndSubmit() {
  await act(async () => (host.querySelector('input') as HTMLInputElement).click());
  await act(async () => button('提交这组练习')!.click());
}

it('真实提交请求携带当前渲染题面版本', async () => {
  const version = 'a'.repeat(64); await show(quiz(version)); await answerAndSubmit();
  expect(posted()).toEqual([{ sceneId: 'scene', submissionId: expect.any(String), expectedQuestionVersion: version, answers: [{ no: 1, answer: 'A' }] }]);
  expect(host.textContent).toContain('答对 1 道');
});

it('版本冲突保留答案并要求重新载入，不能换号提交或缓存成成功', async () => {
  let attempts = 0;
  http.mockImplementation(async (_url, options) => {
    if (options?.method !== 'POST') return response({ courseId: 'c', revisions: [] });
    return ++attempts === 1 ? response({ error: '练习题目已更新，请刷新题面后重新作答', code: 'QUESTION_VERSION_CHANGED' }, 409) : response(feedback);
  });
  const scene = quiz('a'.repeat(64)); await show(scene); await answerAndSubmit();
  expect(host.textContent).toContain('练习内容已更新，请重新载入后作答');
  expect((host.querySelector('input') as HTMLInputElement).checked).toBe(true);
  expect(button('重新评阅')).toBeUndefined();
  expect(button('重新载入练习')).toBeDefined();
  await act(async () => button('提交这组练习')?.click());
  await show(scene);
  expect(posted()).toHaveLength(1);
  expect(host.textContent).not.toContain('已确认 1 道题');
  expect(host.textContent).not.toContain('已确认的解析');
});

it('只更新版本也隔离在途旧答卷，迟到成功不能覆盖新练习', async () => {
  let finishOld!: (value: Response) => void;
  const oldRequest = new Promise<Response>(resolve => { finishOld = resolve; });
  let attempts = 0;
  http.mockImplementation(async (_url, options) => options?.method !== 'POST'
    ? response({ courseId: 'c', revisions: [] }) : ++attempts === 1 ? oldRequest : response(feedback));
  await show(quiz('a'.repeat(64))); await answerAndSubmit();
  await show(quiz('b'.repeat(64)));
  expect((host.querySelector('input') as HTMLInputElement).checked).toBe(false);
  await act(async () => finishOld(response(feedback)));
  expect(host.textContent).not.toContain('已确认的解析');
  expect((host.querySelector('fieldset') as HTMLFieldSetElement).disabled).toBe(false);
  await answerAndSubmit();
  const requests = posted();
  expect(requests[1].expectedQuestionVersion).toBe('b'.repeat(64));
  expect(requests[1].submissionId).not.toBe(requests[0].submissionId);
  expect(host.textContent).toContain('答对 1 道');
});

it('旧课程无版本时真实请求仍只有原三字段', async () => {
  await show(quiz()); await answerAndSubmit();
  expect(posted()).toEqual([{ sceneId: 'scene', submissionId: expect.any(String), answers: [{ no: 1, answer: 'A' }] }]);
});

it('同版本网络结果未知只恢复原请求，成功后不自动重发', async () => {
  let attempts = 0;
  http.mockImplementation(async (_url, options) => {
    if (options?.method !== 'POST') return response({ courseId: 'c', revisions: [] });
    if (++attempts === 1) throw new TypeError('Failed to fetch');
    return response(feedback);
  });
  const scene = quiz('a'.repeat(64)); await show(scene); await answerAndSubmit();
  expect(posted()).toHaveLength(1);
  expect((host.querySelector('input') as HTMLInputElement).checked).toBe(true);
  await act(async () => button('恢复提交这组练习')!.click());
  expect(posted()[1]).toEqual(posted()[0]);
  await show(scene);
  expect(posted()).toHaveLength(2); expect(host.textContent).toContain('答对 1 道');
});

it.each(['已成功', '版本冲突'])('%s的旧状态在收到新题面版本后清除', async previous => {
  let attempts = 0;
  http.mockImplementation(async (_url, options) => {
    if (options?.method !== 'POST') return response({ courseId: 'c', revisions: [] });
    return ++attempts === 1 && previous === '版本冲突'
      ? response({ code: 'QUESTION_VERSION_CHANGED', error: '题面已更新' }, 409) : response(feedback);
  });
  await show(quiz('a'.repeat(64))); await answerAndSubmit();
  await show(quiz('b'.repeat(64)));
  expect((host.querySelector('input') as HTMLInputElement).checked).toBe(false);
  expect(host.textContent).not.toContain('已确认的解析'); expect(host.textContent).not.toContain('请重新载入后作答');
  expect(posted()).toHaveLength(1);
  await answerAndSubmit();
  expect(posted()[1].submissionId).not.toBe(posted()[0].submissionId);
  expect(posted()[1].expectedQuestionVersion).toBe('b'.repeat(64));
  expect(host.textContent).toContain('答对 1 道');
});
