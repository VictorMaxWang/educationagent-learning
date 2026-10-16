// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CreateCourse } from '../src/classroom/ClassroomView';

const mocks = vi.hoisted(() => ({ create: vi.fn() }));
vi.mock('../src/classroom/api', () => ({ createCourse: mocks.create }));
vi.mock('../src/classroom/GrowthClassroom', () => ({ default: () => null }));
vi.mock('../src/study/MistakeView', () => ({ default: () => null }));
vi.mock('../src/classroom/OutlineEditor', () => ({ default: () => null }));
vi.mock('../src/classroom/CourseExportMenu', () => ({ default: () => null }));
vi.mock('../src/classroom/CourseImportButton', () => ({ default: () => null }));
let root: Root; let host: HTMLDivElement;
beforeEach(() => {
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  Element.prototype.scrollIntoView = vi.fn(); mocks.create.mockReset(); mocks.create.mockResolvedValue({ id: 'prepared' });
  host = document.createElement('div'); document.body.append(host); root = createRoot(host);
});
afterEach(async () => { await act(async () => root.unmount()); host.remove(); });
const field = (label: string) => [...host.querySelectorAll<HTMLElement>('.premium-select')].find(item => item.querySelector('.ps-label')?.textContent === label);
const choose = async (label: string, option: string) => {
  const control = field(label)!;
  await act(async () => control.querySelector('button')!.click());
  const item = [...control.querySelectorAll<HTMLElement>('[role=option]')].find(value => value.querySelector('strong')?.textContent === option)!;
  await act(async () => item.click());
};
const pickTopic = async () => { await act(async () => [...host.querySelectorAll('button')].find(item => item.textContent === '用英语介绍我的家乡')!.click()); };
const submit = async () => { await act(async () => host.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))); };

describe('课程方向显隐与真实请求', () => {
  it('只有学科学习显示学科，切回保留之前选择', async () => {
    await act(async () => root.render(<CreateCourse onCreated={() => {}} />));
    await choose('学科', '英语'); await choose('学习方向', '身心成长');
    expect(field('学科')).toBeUndefined();
    await choose('学习方向', '实践探索'); expect(field('学科')).toBeUndefined();
    await choose('学习方向', '学科学习'); expect(field('学科')?.textContent).toContain('英语');
  });
  it('身心成长提交psychology且不携带隐藏学科', async () => {
    const created = vi.fn(); await act(async () => root.render(<CreateCourse onCreated={created} />));
    await pickTopic(); await choose('学习方向', '身心成长'); await choose('我在读', '初一'); await submit();
    expect(mocks.create).toHaveBeenCalledWith(expect.objectContaining({ topic: '用英语介绍我的家乡', grade: 7, direction: 'wellbeing', domain: 'psychology' }));
    expect(mocks.create.mock.calls[0][0]).not.toHaveProperty('subject'); expect(created).toHaveBeenCalledWith({ id: 'prepared' });
  });
  it('实践探索提交general且不带学科，学科学习提交原学科', async () => {
    await act(async () => root.render(<CreateCourse onCreated={() => {}} />));
    await pickTopic(); await choose('学习方向', '实践探索'); await submit();
    expect(mocks.create.mock.calls[0][0]).toMatchObject({ direction: 'exploration', domain: 'general' }); expect(mocks.create.mock.calls[0][0]).not.toHaveProperty('subject');
    await choose('学习方向', '学科学习'); await submit();
    expect(mocks.create.mock.calls[1][0]).toMatchObject({ direction: 'academic', domain: 'general', subject: '英语' });
  });
});
