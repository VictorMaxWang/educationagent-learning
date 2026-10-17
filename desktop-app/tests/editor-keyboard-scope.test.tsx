// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { handleCanvasShortcut } from '../../vendor/educationagent/editor/src/react/useCanvasShortcuts';
import type { CanvasCommands } from '../../vendor/educationagent/editor/src/react/canvasCommands';
const capture = vi.hoisted(() => new Map<string, { host: { shortcutsEnabled?: boolean }; onTransaction: (value: unknown) => void }>());
vi.mock('@educationagent/editor/ui', () => ({ EditableSlideCanvasWithUI: (props: { documentSlide: { id: string }; host: { shortcutsEnabled?: boolean }; onTransaction(value: unknown): void }) => { capture.set(props.documentSlide.id, props); return <div data-editor-canvas={props.documentSlide.id} />; } }));
vi.mock('../src/classroom/ClassroomImagePicker', () => ({ default: () => null }));
import SlideEditor from '../src/classroom/SlideEditor';
import { createWhiteboard, createShapeElement } from '../src/classroom/whiteboardModel';
let host: HTMLDivElement, root: Root;
beforeEach(() => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true });
  Object.defineProperty(window, 'matchMedia', { configurable: true, value: () => ({ matches: false }) });
  capture.clear(); host = document.createElement('div'); document.body.append(host); root = createRoot(host);
});
afterEach(async () => { await act(async () => root.unmount()); host.remove(); vi.restoreAllMocks(); });

it('焦点在弹层按钮或另一编辑器时，背景编辑器不接收全局快捷键', async () => {
  const base = createWhiteboard().canvas;
  await act(async () => root.render(<><SlideEditor title="编辑A" canvas={{ ...base, id: 'a' }} onSave={async () => {}} onCancel={() => {}} /><SlideEditor title="编辑B" canvas={{ ...base, id: 'b' }} onSave={async () => {}} onCancel={() => {}} /><button id="dialog-control">引导按钮</button></>));
  await act(async () => (host.querySelector('[aria-label="编辑A"]') as HTMLElement).focus());
  expect(capture.get('a')!.host.shortcutsEnabled).toBe(true); expect(capture.get('b')!.host.shortcutsEnabled).toBe(false);
  await act(async () => (host.querySelector('#dialog-control') as HTMLElement).focus());
  expect(capture.get('a')!.host.shortcutsEnabled).toBe(false); expect(capture.get('b')!.host.shortcutsEnabled).toBe(false);
});

it('inert祖先及已被弹层处理的按键不能再删除画布对象', () => {
  const wrapper = document.createElement('div'); wrapper.setAttribute('inert', ''); const button = document.createElement('button'); wrapper.append(button); host.append(wrapper);
  const remove = vi.fn(); const commands = { deleteSelection: remove } as unknown as CanvasCommands;
  const event = { key: 'Delete', ctrlKey: false, metaKey: false, altKey: false, repeat: false, target: button, preventDefault: vi.fn() };
  expect(handleCanvasShortcut(event, commands)).toBe(false);
  wrapper.removeAttribute('inert'); expect(handleCanvasShortcut({ ...event, defaultPrevented: true }, commands)).toBe(false);
  expect(remove).not.toHaveBeenCalled(); expect(handleCanvasShortcut(event, commands)).toBe(true); expect(remove).toHaveBeenCalledTimes(1);
});

it('保存快照期间迟到的新事务保留未保存状态', async () => {
  let complete!: () => void; const pending = new Promise<void>(resolve => { complete = resolve; });
  const dirty = vi.fn(); const base = { ...createWhiteboard().canvas, id: 'a' };
  await act(async () => root.render(<SlideEditor canvas={base} onSave={() => pending} onCancel={() => {}} onDirtyChange={dirty} />));
  await act(async () => capture.get('a')!.onTransaction({ origin: 'toolbar', history: 'record', operations: [{ type: 'element.add', element: createShapeElement('rectangle', 'first') }] }));
  await act(async () => [...host.querySelectorAll('button')].find(button => button.textContent === '保存修改')!.click());
  await act(async () => capture.get('a')!.onTransaction({ origin: 'toolbar', history: 'record', operations: [{ type: 'element.add', element: createShapeElement('circle', 'late-paste') }] }));
  await act(async () => complete());
  expect(host.textContent).toContain('有未保存的修改'); expect(dirty.mock.lastCall?.[0]).toBe(true);
});
