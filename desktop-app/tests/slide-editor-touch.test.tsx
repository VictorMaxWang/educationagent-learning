// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
vi.mock('@educationagent/renderer', async importOriginal => ({ ...await importOriginal<typeof import('@educationagent/renderer')>(), SlideCanvas: ({ slide }: { slide: { elements: unknown[] } }) => <div data-readonly-slide data-elements={slide.elements.length} /> }));
vi.mock('@educationagent/editor/ui', () => ({ EditableSlideCanvasWithUI: () => <div data-desktop-editor data-marquee-surface /> }));
vi.mock('../src/classroom/ClassroomImagePicker', () => ({ default: () => null }));
import SlideEditor from '../src/classroom/SlideEditor';
import { createWhiteboard, createShapeElement } from '../src/classroom/whiteboardModel';
let host: HTMLDivElement, root: Root;
const saved = vi.fn(), dirty = vi.fn();
const button = (text: string) => Array.from(host.querySelectorAll('button')).find(item => item.textContent === text)!;
const pointer = (node: Element, type: string, x: number, y: number, id = 1) => {
  const event = new Event(type, { bubbles: true, cancelable: true });
  Object.assign(event, { pointerId: id, pointerType: 'touch', button: 0, isPrimary: true, clientX: x, clientY: y }); node.dispatchEvent(event);
};
beforeEach(() => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true }); saved.mockReset().mockResolvedValue(undefined); dirty.mockReset();
  Object.defineProperty(window, 'matchMedia', { configurable: true, value: vi.fn(() => ({ matches: true })) });
  Object.defineProperty(SVGElement.prototype, 'getBoundingClientRect', { configurable: true, value: () => new DOMRect(0, 0, 320, 180) });
  host = document.createElement('div'); document.body.append(host); root = createRoot(host);
});
afterEach(async () => { await act(async () => root.unmount()); host.remove(); vi.restoreAllMocks(); });
const mount = () => root.render(<SlideEditor canvas={{ ...createWhiteboard().canvas, elements: [createShapeElement('rectangle', 'original')] }} onSave={saved} onCancel={() => {}} onDirtyChange={dirty} />);

describe('手机白板明确区分浏览与绘画', () => {
  it('默认浏览不挂载框选捕获层，手指竖滑不会生成文档修改', async () => {
    await act(async () => mount()); expect(button('浏览').getAttribute('aria-pressed')).toBe('true'); expect(host.querySelector('[data-marquee-surface]')).toBeNull();
    await act(async () => { const canvas = host.querySelector('.ce-canvas-browse')!; pointer(canvas, 'pointerdown', 30, 80); pointer(canvas, 'pointermove', 30, 20); pointer(canvas, 'pointerup', 30, 20); });
    expect(dirty.mock.calls.every(([value]) => value === false)).toBe(true); expect(button('保存修改').disabled).toBe(true); expect(saved).not.toHaveBeenCalled();
    await act(async () => button('选择').click()); expect(host.querySelector('[data-desktop-editor]')).not.toBeNull();
  });
  it('画笔完成一笔后可撤销重做、清除并保存，原图形保持不变', async () => {
    await act(async () => mount()); await act(async () => button('画笔').click());
    await act(async () => { const canvas = host.querySelector('svg')!; pointer(canvas, 'pointerdown', 30, 30); pointer(canvas, 'pointermove', 60, 55); pointer(canvas, 'pointerup', 80, 65); });
    expect(button('保存修改').disabled).toBe(false); expect(host.querySelector('[data-readonly-slide]')?.getAttribute('data-elements')).toBe('2');
    await act(async () => button('撤销').click()); expect(host.querySelector('[data-readonly-slide]')?.getAttribute('data-elements')).toBe('1');
    await act(async () => button('重做').click()); expect(host.querySelector('[data-readonly-slide]')?.getAttribute('data-elements')).toBe('2');
    await act(async () => button('保存修改').click()); expect(saved.mock.calls[0][0].elements).toHaveLength(2); expect(saved.mock.calls[0][0].elements[1]).toMatchObject({ type: 'shape', fill: 'transparent', outline: { width: 6 } });
    await act(async () => button('清除涂画').click()); await act(async () => button('保存修改').click()); expect(saved.mock.calls[1][0].elements.map((item: { id: string }) => item.id)).toEqual(['original']);
  });
  it('触摸取消或中途改回浏览不落库半笔，退出后不残留预览框', async () => {
    await act(async () => mount()); await act(async () => button('画笔').click());
    await act(async () => { const canvas = host.querySelector('svg')!; pointer(canvas, 'pointerdown', 30, 30); pointer(canvas, 'pointermove', 60, 55); pointer(canvas, 'pointercancel', 60, 55); });
    expect(host.querySelector('polyline')).toBeNull(); expect(button('保存修改').disabled).toBe(true);
    await act(async () => { const canvas = host.querySelector('svg')!; pointer(canvas, 'pointerdown', 30, 30); pointer(canvas, 'pointermove', 60, 55); });
    await act(async () => button('浏览').click()); expect(host.querySelector('svg')).toBeNull(); expect(button('保存修改').disabled).toBe(true);
  });
  it('桌面仍默认使用原选择编辑器', async () => {
    Object.defineProperty(window, 'matchMedia', { configurable: true, value: vi.fn(() => ({ matches: false })) });
    await act(async () => mount()); expect(button('选择').getAttribute('aria-pressed')).toBe('true'); expect(host.querySelector('[data-desktop-editor]')).not.toBeNull();
  });
});
