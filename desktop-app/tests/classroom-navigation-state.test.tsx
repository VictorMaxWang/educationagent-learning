// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
const api = vi.hoisted(() => ({ get: vi.fn(), list: vi.fn(), saveOutline: vi.fn(), generate: vi.fn(), cancel: vi.fn(), progress: vi.fn() }));
vi.mock('../src/classroom/api', () => ({ getCourse: api.get, listCourses: api.list, saveOutline: api.saveOutline, generateCourse: api.generate, cancelCourse: api.cancel, saveProgress: api.progress, wechatStatus: async () => ({ bound: false }), resumeCourse: async (id: string) => api.get(id) }));
vi.mock('../src/study/MistakeView', () => ({ default: () => null }));
vi.mock('../src/classroom/GrowthClassroom', () => ({ default: () => null }));
vi.mock('../src/classroom/CourseExportMenu', () => ({ default: () => null }));
vi.mock('../src/classroom/CourseImportButton', () => ({ default: () => null }));
vi.mock('../src/classroom/OutlineEditor', () => ({ default: ({ onConfirm }: { onConfirm(outlines: unknown[]): Promise<void> }) => <button onClick={() => void onConfirm([])}>开始A生成</button> }));
vi.mock('../src/classroom/CoursePlayer', () => ({ default: ({ course, initialSceneId }: { course: { id: string }; initialSceneId?: string }) => <div data-player={course.id} data-entry={initialSceneId}>课程播放器</div> }));
import ClassroomView from '../src/classroom/ClassroomView';
import type { Course } from '../src/classroom/types';
import * as navigation from '../src/classroom/navigation';
let root: Root, host: HTMLDivElement;
const deferred = <T,>() => { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done; }); return { resolve, promise }; };
const course = (id: string, status: Course['status'] = 'ready'): Course => ({ id, title: `课程${id}`, topic: id, status, grade: 5, subject: '数学', domain: 'general', direction: 'academic', outlines: [], scenes: status === 'outline_ready' ? [] : [{ id: `${id}-first`, title: '第一页', order: 0, type: 'slide', actions: [], content: { type: 'slide' } }, { id: `${id}-target`, title: '第二页', order: 1, type: 'quiz', actions: [], content: { type: 'quiz', questions: [] } }], messages: [], progress: { sceneIndex: 0, actionIndex: 0 } });
const button = (text: string) => [...host.querySelectorAll('button')].find(item => item.textContent?.includes(text))!;
const click = async (text: string) => { await act(async () => { button(text).click(); await new Promise(resolve => setTimeout(resolve, 0)); }); };
beforeEach(() => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true }); for (const method of Object.values(api)) method.mockReset();
  api.saveOutline.mockResolvedValue({}); api.list.mockResolvedValue([course('A', 'outline_ready'), course('B')]);
  api.get.mockImplementation(async (id: string) => course(id, id === 'A' ? 'outline_ready' : 'ready'));
  window.history.replaceState(null, '', '#/classroom/A'); host = document.createElement('div'); document.body.append(host); root = createRoot(host);
});
afterEach(async () => { await act(async () => root.unmount()); host.remove(); vi.restoreAllMocks(); });

it('生成A的迟到响应不会替换已打开的B或重启B读取', async () => {
  const generated = deferred<Course>(); api.generate.mockReturnValue(generated.promise);
  await act(async () => root.render(<ClassroomView />)); await click('开始A生成'); await click('回到我的课堂'); await click('课程B');
  expect(host.querySelector('[data-player]')?.getAttribute('data-player')).toBe('B');
  const reads = api.get.mock.calls.filter(([id]) => id === 'B').length;
  await act(async () => generated.resolve(course('A', 'generating')));
  expect(host.querySelector('[data-player]')?.getAttribute('data-player')).toBe('B');
  expect(api.get.mock.calls.filter(([id]) => id === 'B')).toHaveLength(reads);
});

it.each(['停止生成', '继续准备未完成的内容'])('%s的迟到响应同样不能打断其他课堂', async label => {
  const operation = deferred<Course>();
  api.get.mockImplementation(async (id: string) => course(id, id === 'A' ? label === '停止生成' ? 'generating' : 'error' : 'ready'));
  api.cancel.mockReturnValue(operation.promise); api.generate.mockReturnValue(operation.promise);
  await act(async () => root.render(<ClassroomView />)); await click(label); await click('回到我的课堂'); await click('课程B');
  const reads = api.get.mock.calls.filter(([id]) => id === 'B').length;
  await act(async () => operation.resolve(course('A', 'cancelled')));
  expect(host.querySelector('[data-player]')?.getAttribute('data-player')).toBe('B');
  expect(api.get.mock.calls.filter(([id]) => id === 'B')).toHaveLength(reads);
});

it('旧课程别名与旧scene映射到当前课后纠正hash并打开实际对应页', async () => {
  const current = { ...course('current'), entrySceneMap: { 'old-scene': 'current-target' } };
  window.history.replaceState(null, '', '#/classroom/old?scene=old-scene'); api.get.mockResolvedValue(current);
  await act(async () => root.render(<ClassroomView />));
  expect(window.location.hash).toBe('#/classroom/current?scene=current-target');
  expect(host.querySelector('[data-player]')?.getAttribute('data-player')).toBe('current');
  expect(host.querySelector('[data-player]')?.getAttribute('data-entry')).toBe('current-target');
});

it('hash保存并读取scene，未知scene回到服务器当前保存页', () => {
  expect(navigation.courseIdFromHash('#/classroom/c?scene=s')).toBe('c');
  expect(navigation.sceneIdFromHash(navigation.courseHash('一节课 / 1', '页 / 2'))).toBe('页 / 2');
  expect(navigation.resolveEntryScene({ ...course('B'), progress: { sceneIndex: 1, actionIndex: 0 } }, 'missing')).toBe('B-target');
});

it('同课程scene深链变更会打开目标页而不是仍停留在旧页', async () => {
  window.history.replaceState(null, '', '#/classroom/B?scene=B-first');
  await act(async () => root.render(<ClassroomView />));
  await act(async () => { window.location.hash = '#/classroom/B?scene=B-target'; await new Promise(resolve => setTimeout(resolve, 0)); });
  expect(host.querySelector('[data-player]')?.getAttribute('data-entry')).toBe('B-target');
  expect(window.location.hash).toBe('#/classroom/B?scene=B-target');
});
