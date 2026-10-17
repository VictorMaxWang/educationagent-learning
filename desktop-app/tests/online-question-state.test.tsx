// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
const api = vi.hoisted(() => ({ submit: vi.fn(), revisions: vi.fn(), mistakes: vi.fn(), redo: vi.fn(), remove: vi.fn() }));
vi.mock('../src/classroom/api', () => ({ submitAnswers: api.submit, getQuizRevisions: api.revisions }));
vi.mock('../src/api/chatApi', () => ({ getMistakes: api.mistakes, redoMistake: api.redo, removeMistake: api.remove }));
import QuizScene from '../src/classroom/QuizScene';
import MistakeView from '../src/study/MistakeView';
import type { Scene } from '../src/classroom/types';
let host: HTMLDivElement, root: Root;
const deferred = <T,>() => { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done; }); return { resolve, promise }; };
const quiz = (question = '原题', id = 'q'): Scene => ({ id: 'scene', title: '随堂题', type: 'quiz', order: 0, actions: [], content: { type: 'quiz', questions: [{ id, no: 1, type: 'single', question, options: [{ value: 'A', label: '甲' }, { value: 'B', label: '乙' }] }] } });
const result = { quizId: '7', score: 1, total: 1, results: [{ no: 1, correct: true, correctAnswer: 'A', userAnswer: 'A', explanation: '旧题专属解析' }] };
const mistakes = [{ questionId: 1, quizId: 7, no: 1, type: 'judge', stem: 'A题', options: [], userAnswer: '对' }, { questionId: 2, quizId: 7, no: 2, type: 'judge', stem: 'B题', options: [], userAnswer: '对' }];
const button = (text: string, parent: ParentNode = host) => [...parent.querySelectorAll('button')].find(item => item.textContent?.includes(text))!;
beforeEach(() => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true });
  for (const method of Object.values(api)) method.mockReset();
  api.revisions.mockImplementation(async (courseId: string) => ({ courseId, revisions: [] })); api.mistakes.mockResolvedValue(mistakes);
  vi.spyOn(window, 'alert').mockImplementation(() => {}); vi.spyOn(window, 'confirm').mockReturnValue(true);
  host = document.createElement('div'); document.body.append(host); root = createRoot(host);
});
afterEach(async () => { await act(async () => root.unmount()); host.remove(); vi.restoreAllMocks(); });

it('同scene题干变化后不保留旧选择、判分结果或提交身份', async () => {
  api.submit.mockResolvedValue(result);
  await act(async () => root.render(<QuizScene courseId="c" scene={quiz()} />));
  await act(async () => (host.querySelector('input') as HTMLInputElement).click());
  await act(async () => button('提交这组练习').click());
  const oldId = api.submit.mock.calls[0][2];
  await act(async () => root.render(<QuizScene courseId="c" scene={quiz('新的题干', 'q-new')} />));
  expect((host.querySelector('input') as HTMLInputElement).checked).toBe(false); expect(host.textContent).not.toContain('旧题专属解析');
  await act(async () => (host.querySelector('input') as HTMLInputElement).click());
  await act(async () => button('提交这组练习').click());
  expect(api.submit.mock.calls[1][2]).not.toBe(oldId);
});

it('旧题提交迟到结果不能贴到同scene的新题上', async () => {
  const pending = deferred<typeof result>(); api.submit.mockReturnValue(pending.promise);
  await act(async () => root.render(<QuizScene courseId="c" scene={quiz()} />));
  await act(async () => (host.querySelector('input') as HTMLInputElement).click());
  await act(async () => button('提交这组练习').click());
  await act(async () => root.render(<QuizScene courseId="c" scene={quiz('新题面')} />));
  await act(async () => pending.resolve(result));
  expect(host.textContent).not.toContain('旧题专属解析'); expect((host.querySelector('fieldset') as HTMLFieldSetElement).disabled).toBe(false);
});

it('兼容待确认回执不算完成，只有主动重新评阅才换用新的提交ID', async () => {
  api.submit.mockResolvedValue({ ...result, score: 0, confirmedCount: 0, pendingCount: 1, status: 'pending', results: [{ ...result.results[0], correct: null, explanation: '尚未确认' }] });
  await act(async () => root.render(<QuizScene courseId="c" scene={quiz()} />));
  await act(async () => (host.querySelector('input') as HTMLInputElement).click());
  await act(async () => button('提交这组练习').click());
  expect(host.textContent).toContain('1 道待确认'); expect(host.textContent).toContain('不计为答错'); expect(host.textContent).not.toContain('完成 1');
  expect(api.submit).toHaveBeenCalledTimes(1);
  const firstId = api.submit.mock.calls[0][2]; await act(async () => button('重新评阅').click());
  expect(api.submit.mock.calls[1][2]).not.toBe(firstId);
});

it('已封存的EVALUATION_UNKNOWN只在用户重新评阅时换ID，新请求断线恢复仍沿用新ID', async () => {
  api.submit.mockRejectedValueOnce(Object.assign(new Error('评阅未确定'), { status: 409, code: 'EVALUATION_UNKNOWN' }))
    .mockRejectedValueOnce(new Error('network outcome unknown'))
    .mockResolvedValueOnce(result);
  await act(async () => root.render(<QuizScene courseId="c" scene={quiz()} />));
  await act(async () => (host.querySelector('input') as HTMLInputElement).click());
  await act(async () => button('提交这组练习').click());
  expect(host.textContent).toContain('本次评阅结果未确认，请重新评阅');
  expect(host.textContent).not.toContain('内容已更新，请刷新');
  expect((host.querySelector('input') as HTMLInputElement).checked).toBe(true);
  expect(api.submit).toHaveBeenCalledTimes(1);
  const sealedId = api.submit.mock.calls[0][2];
  await act(async () => button('重新评阅').click());
  const freshId = api.submit.mock.calls[1][2];
  expect(freshId).not.toBe(sealedId);
  expect(api.submit).toHaveBeenCalledTimes(2);
  await act(async () => button('提交这组练习').click());
  expect(api.submit.mock.calls[2][2]).toBe(freshId);
  expect(api.submit.mock.calls[2][3]).toEqual(api.submit.mock.calls[1][3]);
  expect(host.textContent).toContain('答对 1 道');
});

it('明确pending后的主动评阅遇到普通网络错误时不能再自动更换ID', async () => {
  api.submit.mockResolvedValueOnce({ ...result, score: 0, confirmedCount: 0, pendingCount: 1, status: 'pending', results: [{ ...result.results[0], correct: null }] })
    .mockRejectedValueOnce(new Error('network unknown'))
    .mockResolvedValueOnce(result);
  await act(async () => root.render(<QuizScene courseId="c" scene={quiz()} />));
  await act(async () => (host.querySelector('input') as HTMLInputElement).click());
  await act(async () => button('提交这组练习').click());
  await act(async () => button('重新评阅').click());
  await act(async () => button('提交这组练习').click());
  const ids = api.submit.mock.calls.map(call => call[2]);
  expect(ids[1]).not.toBe(ids[0]); expect(ids[2]).toBe(ids[1]);
});

it('没有封存错误码的网络失败用原ID恢复，成功后重复渲染不重复提交', async () => {
  api.submit.mockRejectedValueOnce(Object.assign(new Error('response unknown'), { status: 502 })).mockResolvedValueOnce(result);
  await act(async () => root.render(<QuizScene courseId="c" scene={quiz()} />));
  await act(async () => (host.querySelector('input') as HTMLInputElement).click());
  await act(async () => button('提交这组练习').click());
  expect(button('重新评阅')).toBeUndefined();
  await act(async () => button('提交这组练习').click());
  expect(api.submit.mock.calls[1][2]).toBe(api.submit.mock.calls[0][2]);
  await act(async () => root.render(<QuizScene courseId="c" scene={quiz()} />));
  expect(api.submit).toHaveBeenCalledTimes(2);
  expect((host.querySelector('fieldset') as HTMLFieldSetElement).disabled).toBe(true);
});

it('同课程不同scene的当前练习入口可用且带scene深链', async () => {
  api.revisions.mockResolvedValue({ courseId: 'c', revisions: [{ sceneId: 'scene', newCourseId: 'c', newSceneId: 'target-scene', reason: '内容已更新', createdAt: '2026-10-17T00:00:00Z' }] });
  await act(async () => root.render(<QuizScene courseId="c" scene={quiz()} />));
  expect(host.querySelector('a')?.getAttribute('href')).toBe('#/classroom/c?scene=target-scene');
  expect(host.querySelector('a')?.textContent).toContain('打开当前练习'); expect(host.textContent).not.toContain('修订版');
});

it('切到B题后A题迟到的重做反馈不会串题或清空B答案', async () => {
  const pending = deferred<{ correct: boolean; removed: boolean; explanation: string }>(); api.redo.mockReturnValue(pending.promise);
  await act(async () => root.render(<MistakeView userId="classroom" embedded />));
  const [a, b] = host.querySelectorAll('.quiz-r-item');
  await act(async () => button('重做', a).click()); await act(async () => (a.querySelector('input') as HTMLInputElement).click());
  await act(async () => button('提交重做', a).click()); await act(async () => button('取消', a).click()); await act(async () => button('重做', b).click());
  await act(async () => (b.querySelectorAll('input')[1] as HTMLInputElement).click());
  await act(async () => pending.resolve({ correct: false, removed: false, explanation: 'A题旧答案解析' }));
  expect(b.textContent).not.toContain('A题旧答案解析'); expect((b.querySelectorAll('input')[1] as HTMLInputElement).checked).toBe(true);
});

it('错题网络失败后重试同题同答案沿用requestId', async () => {
  api.redo.mockRejectedValueOnce(new Error('network unknown')).mockResolvedValueOnce({ correct: null, removed: false, explanation: '待确认' });
  await act(async () => root.render(<MistakeView userId="classroom" embedded />)); const a = host.querySelector('.quiz-r-item')!;
  await act(async () => button('重做', a).click()); await act(async () => (a.querySelector('input') as HTMLInputElement).click());
  await act(async () => button('提交重做', a).click()); await act(async () => button('提交重做', a).click());
  expect(api.redo.mock.calls[0][0].requestId).toEqual(expect.any(String)); expect(api.redo.mock.calls[1][0].requestId).toBe(api.redo.mock.calls[0][0].requestId);
});

it('错题收到明确待确认回执后主动重新核对使用新的requestId', async () => {
  api.redo.mockResolvedValueOnce({ correct: null, removed: false, explanation: '待确认' })
    .mockResolvedValueOnce({ correct: false, removed: false, explanation: '这次核对已完成' });
  await act(async () => root.render(<MistakeView userId="classroom" embedded />)); const a = host.querySelector('.quiz-r-item')!;
  await act(async () => button('重做', a).click()); await act(async () => (a.querySelector('input') as HTMLInputElement).click());
  await act(async () => button('提交重做', a).click());
  const firstId = api.redo.mock.calls[0][0].requestId;
  expect(a.textContent).toContain('待确认'); expect(a.textContent).not.toContain('仍未答对');
  await act(async () => button('重新核对', a).click());
  expect(api.redo.mock.calls[1][0].requestId).not.toBe(firstId);
  expect(api.redo.mock.calls[1][0].answer).toBe(api.redo.mock.calls[0][0].answer);
  expect(a.textContent).toContain('这次核对已完成');
});

it('待确认后的新核对若网络失败，恢复该次请求仍沿用新的requestId', async () => {
  api.redo.mockResolvedValueOnce({ correct: null, removed: false, explanation: '待确认' })
    .mockRejectedValueOnce(new Error('network outcome unknown'))
    .mockResolvedValueOnce({ correct: null, removed: false, explanation: '仍待确认' });
  await act(async () => root.render(<MistakeView userId="classroom" embedded />)); const a = host.querySelector('.quiz-r-item')!;
  await act(async () => button('重做', a).click()); await act(async () => (a.querySelector('input') as HTMLInputElement).click());
  await act(async () => button('提交重做', a).click());
  await act(async () => button('重新核对', a).click());
  await act(async () => button('重新核对', a).click());
  const ids = api.redo.mock.calls.map(call => call[0].requestId);
  expect(ids[1]).not.toBe(ids[0]); expect(ids[2]).toBe(ids[1]);
  expect(a.textContent).toContain('仍待确认');
});

it('切题期间收到的明确待确认回执也会结束旧请求，且不污染当前题反馈', async () => {
  const pending = deferred<{ correct: null; removed: boolean; explanation: string }>();
  api.redo.mockReturnValueOnce(pending.promise).mockResolvedValueOnce({ correct: null, removed: false, explanation: '新的核对' });
  await act(async () => root.render(<MistakeView userId="classroom" embedded />)); const [a, b] = host.querySelectorAll('.quiz-r-item');
  await act(async () => button('重做', a).click()); await act(async () => (a.querySelector('input') as HTMLInputElement).click());
  await act(async () => button('提交重做', a).click());
  await act(async () => button('取消', a).click()); await act(async () => button('重做', b).click());
  await act(async () => pending.resolve({ correct: null, removed: false, explanation: 'A题明确待确认' }));
  expect(b.textContent).not.toContain('A题明确待确认');
  await act(async () => button('取消', b).click()); await act(async () => button('重做', a).click());
  await act(async () => (a.querySelector('input') as HTMLInputElement).click());
  await act(async () => button('提交重做', a).click());
  expect(api.redo.mock.calls[1][0].requestId).not.toBe(api.redo.mock.calls[0][0].requestId);
});

it('旧答案的待确认回执不能清除同题新答案尚待网络恢复的请求编号', async () => {
  const pending = deferred<{ correct: null; removed: boolean }>();
  api.redo.mockReturnValueOnce(pending.promise).mockRejectedValueOnce(new Error('new answer network unknown'))
    .mockResolvedValueOnce({ correct: null, removed: false });
  await act(async () => root.render(<MistakeView userId="classroom" embedded />)); const a = host.querySelector('.quiz-r-item')!;
  await act(async () => button('重做', a).click()); await act(async () => (a.querySelector('input') as HTMLInputElement).click());
  await act(async () => button('提交重做', a).click()); await act(async () => button('取消', a).click());
  await act(async () => button('重做', a).click()); await act(async () => (a.querySelectorAll('input')[1] as HTMLInputElement).click());
  await act(async () => button('提交重做', a).click());
  await act(async () => pending.resolve({ correct: null, removed: false }));
  await act(async () => button('提交重做', a).click());
  const ids = api.redo.mock.calls.map(call => call[0].requestId);
  expect(ids[1]).not.toBe(ids[0]); expect(ids[2]).toBe(ids[1]);
});

it('移除完成后较早的列表响应不能把旧题重新插回', async () => {
  const pending = deferred<typeof mistakes>(); api.remove.mockResolvedValue({ ok: true });
  await act(async () => root.render(<MistakeView userId="classroom" embedded />));
  api.mistakes.mockReturnValueOnce(pending.promise); await act(async () => button('刷新').click());
  await act(async () => button('移除', host.querySelector('.quiz-r-item')!).click());
  await act(async () => pending.resolve(mistakes));
  expect(host.textContent).not.toContain('A题'); expect(host.textContent).toContain('B题');
});
