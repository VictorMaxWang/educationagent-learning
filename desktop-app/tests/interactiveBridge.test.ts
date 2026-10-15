import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { runInNewContext } from 'node:vm';
import { JSDOM } from 'jsdom';
import { acceptsBridgeMessage, bridgeScript, commandForTeachingAction, INTERACTIVE_BRIDGE_CHANNEL, INTERACTIVE_CSP, isStableSelector, validateEditablePatch } from '../src/classroom/interactiveBridge';
import type { TeachingAction } from '../src/classroom/types';

const identity = { sceneId: 'scene-1', nonce: 'nonce-test-123' };

/** 让生产注入脚本在可读写 DOM 控件替身上运行，检查 ACK 来自实施结果，而非动作名。 */
function runtimeFixture() {
  class Element {
    id: string;
    tagName: string;
    isConnected = true;
    parentElement = null;
    children: Element[] = [];
    textContent = '';
    constructor(id: string, tagName = 'DIV') { this.id = id; this.tagName = tagName; }
    closest() { return null; }
    hasAttribute() { return false; }
    getAttribute() { return null; }
    querySelectorAll() { return []; }
    cloneNode() { return { outerHTML: this.textContent, querySelectorAll: () => [] }; }
  }
  class HTMLElement extends Element {}
  class HTMLInputElement extends HTMLElement {
    type = 'range'; value = '10'; checked = false;
    onInput?: () => void;
    constructor(id: string) { super(id, 'INPUT'); }
    dispatchEvent(event: Event) { if (event.type === 'input') this.onInput?.(); return true; }
    click() { if (this.type === 'checkbox') this.checked = !this.checked; }
  }
  class HTMLSelectElement extends HTMLElement { options: unknown[] = []; value = ''; }
  class HTMLTextAreaElement extends HTMLElement { value = ''; }
  class HTMLButtonElement extends HTMLElement { click = vi.fn(); }
  const slider = new HTMLInputElement('speed');
  const check = new HTMLInputElement('enabled'); check.type = 'checkbox';
  const button = new HTMLButtonElement('run', 'BUTTON');
  const body = new HTMLElement('body', 'BODY');
  const nodes = [slider, check, button];
  const messages: Record<string, unknown>[] = [];
  const listeners = new Map<string, ((event: Record<string, unknown>) => void)[]>();
  const parent = { postMessage: (message: Record<string, unknown>) => messages.push(message) };
  const window = {
    parent,
    addEventListener(type: string, listener: (event: Record<string, unknown>) => void) { listeners.set(type, [...(listeners.get(type) || []), listener]); },
  };
  const document = {
    body, documentElement: body, readyState: 'complete',
    querySelectorAll(selector: string) {
      if (selector === 'input[id],select[id],textarea[id]') return [slider, check];
      return nodes.filter(node => `#${node.id}` === selector);
    },
  };
  runInNewContext(bridgeScript(identity), {
    window, document, Element, HTMLElement, HTMLInputElement, HTMLSelectElement, HTMLTextAreaElement, HTMLButtonElement,
    Event, setTimeout, clearTimeout, getComputedStyle: () => ({ color: '#000000', backgroundColor: '#ffffff', fontSize: '16px' }),
  });
  let request = 0;
  async function command(payload: Record<string, unknown>, overrides: Record<string, unknown> = {}, source: unknown = parent) {
    const requestId = `request-${++request}`;
    const event = { source, data: { channel: INTERACTIVE_BRIDGE_CHANNEL, ...identity, requestId, ...payload, ...overrides } };
    for (const listener of listeners.get('message') || []) listener(event);
    await vi.advanceTimersByTimeAsync(100);
    return messages.find(message => message.requestId === requestId)?.result as { success: boolean; error?: string; state?: unknown } | undefined;
  }
  return { slider, check, button, nodes, body, messages, command };
}

describe('互动桥接协议', () => {
  it('opaque iframe 消息必须匹配窗口、场景和 nonce', () => {
    const frame = {} as Window;
    const data = { channel: INTERACTIVE_BRIDGE_CHANNEL, ...identity, kind: 'ack' };
    expect(acceptsBridgeMessage({ source: frame, data }, frame, identity)).toBe(true);
    expect(acceptsBridgeMessage({ source: {} as Window, data }, frame, identity)).toBe(false);
    expect(acceptsBridgeMessage({ source: frame, data: { ...data, nonce: 'stale' } }, frame, identity)).toBe(false);
    expect(acceptsBridgeMessage({ source: frame, data: { ...data, sceneId: 'another' } }, frame, identity)).toBe(false);
    expect(acceptsBridgeMessage({ source: frame, data: { ...data, kind: 'unknown' } }, frame, identity)).toBe(false);
  });
  it('引用只允许稳定的唯一 ID 形状，禁止任意 CSS 选择器', () => {
    expect(isStableSelector('#speed_2')).toBe(true);
    for (const value of ['#a > script', 'input', '#1wrong', '#a,#b', '#a:hover', '#a[b]', null]) expect(isStableSelector(value)).toBe(false);
  });
  it('兼容教师动作名；属性编辑拒绝脚本、HTML 和 CSS URL', () => {
    expect(commandForTeachingAction({ type: 'widget_setState', state: { speed: 20 } } as TeachingAction)).toMatchObject({ type: 'SET_WIDGET_STATE', state: { speed: 20 } });
    expect(commandForTeachingAction({ type: 'widget_annotation', target: '#speed', content: '观察变化' } as TeachingAction)).toMatchObject({ type: 'ANNOTATE_ELEMENT', target: '#speed', content: '观察变化' });
    expect(validateEditablePatch({ textContent: '<script>只是文字</script>', color: '#216d51', fontSize: '20px' })).toBe(true);
    for (const patch of [{ innerHTML: '<img onerror=alert(1)>' }, { onclick: 'alert(1)' }, { color: 'url(https://example.com)' }, { backgroundColor: 'var(--unsafe)' }, { fontSize: '999px' }, {}]) expect(validateEditablePatch(patch)).toBe(false);
  });
  it('隔离策略允许 Blob worker，但拒绝网络、eval 和同源授权', () => {
    expect(INTERACTIVE_CSP).toContain("worker-src blob:");
    expect(INTERACTIVE_CSP).toContain("connect-src 'none'");
    expect(INTERACTIVE_CSP).not.toContain('unsafe-eval');
    expect(INTERACTIVE_CSP).not.toContain('https:');
    const script = bridgeScript({ sceneId: '</script><script>bad()</script>', nonce: 'nonce' });
    expect(script).not.toContain('</script>');
  });
});

describe('互动操作真实执行与 ACK', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.clearAllTimers(); vi.useRealTimers(); });

  it('设置控件后读回真实值，重置恢复加载时默认值', async () => {
    const runtime = runtimeFixture();
    const result = await runtime.command({ command: 'execute', action: { type: 'SET_WIDGET_STATE', state: { target: '#speed', value: 42 } } });
    expect(runtime.slider.value).toBe('42');
    expect(result).toMatchObject({ success: true, state: { controls: { '#speed': { value: '42' } } } });
    const reset = await runtime.command({ command: 'execute', action: { type: 'RESET_WIDGET' } });
    expect(runtime.slider.value).toBe('10'); expect(reset?.success).toBe(true);
  });
  it('恢复快照真正写入控件；错误场景、nonce 或来源不执行', async () => {
    const runtime = runtimeFixture();
    const state = { controls: { '#speed': { value: '27' }, '#enabled': { checked: true } } };
    expect(await runtime.command({ command: 'restore', state }, { nonce: 'old' })).toBeUndefined();
    expect(await runtime.command({ command: 'restore', state }, { sceneId: 'other' })).toBeUndefined();
    expect(await runtime.command({ command: 'restore', state }, {}, {})).toBeUndefined();
    expect(runtime.slider.value).toBe('10');
    expect(await runtime.command({ command: 'restore', state })).toMatchObject({ success: true, state });
    expect(runtime.slider.value).toBe('27'); expect(runtime.check.checked).toBe(true);
  });
  it('主动snapshot直接读取控件，不必等待input/change节流通知', async () => {
    const runtime = runtimeFixture();
    runtime.slider.value = '73';
    expect(runtime.messages.some(message => message.kind === 'state')).toBe(false);
    expect(await runtime.command({ command: 'snapshot' })).toMatchObject({ success: true, state: { controls: { '#speed': { value: '73' } } } });
  });
  it('缺失目标、重复 ID 和无法实现的状态返回失败，不提前修改其他控件', async () => {
    const runtime = runtimeFixture();
    expect(await runtime.command({ command: 'execute', action: { type: 'SET_WIDGET_STATE', state: { speed: 20, missing: 3 } } })).toMatchObject({ success: false });
    expect(runtime.slider.value).toBe('10');
    runtime.nodes.push(runtime.slider);
    expect(await runtime.command({ command: 'execute', action: { type: 'SET_WIDGET_STATE', state: { speed: 20 } } })).toMatchObject({ success: false });
  });
  it('页面拒绝目标值时不会假报已恢复', async () => {
    const runtime = runtimeFixture();
    runtime.slider.onInput = () => { runtime.slider.value = '10'; };
    expect(await runtime.command({ command: 'restore', state: { speed: 99 } })).toMatchObject({ success: false });
    expect(runtime.slider.value).toBe('10');
  });
  it('trigger 必须观察到 DOM 或状态变化，没有处理器的按钮不能假成功', async () => {
    const runtime = runtimeFixture();
    expect(await runtime.command({ command: 'execute', action: { type: 'TRIGGER_WIDGET', target: '#run' } })).toMatchObject({ success: false });
    expect(runtime.button.click).toHaveBeenCalledOnce();
    expect(await runtime.command({ command: 'execute', action: { type: 'TRIGGER_WIDGET', target: '#enabled' } })).toMatchObject({ success: true, state: { controls: { '#enabled': { checked: true } } } });
  });
});

/**
 * jsdom 执行真实 HTML、样式层叠和 DOM 祖先关系；它没有布局引擎，矩形由独立布局夹具提供。
 * visibility:hidden 保留布局框、display:none 连同后代没有布局框，不能只伪造一个 visible 布尔值。
 */
function revealDomFixture(markup: string, bounds: Record<string, { left: number; top: number; width: number; height: number }> = {}) {
  const dom = new JSDOM(markup, { runScripts: 'outside-only', pretendToBeVisual: true, url: 'https://classroom.test' });
  const win = dom.window;
  const messages: Record<string, unknown>[] = [];
  win.postMessage = ((message: Record<string, unknown>) => { messages.push(message); }) as typeof win.postMessage;
  const rectangle = (node: Element) => {
    const box = bounds[node.id] || (node.id === 'target' ? { left: 80, top: 60, width: 180, height: 50 } : { left: 0, top: 0, width: win.innerWidth, height: win.innerHeight });
    return { ...box, x: box.left, y: box.top, right: box.left + box.width, bottom: box.top + box.height, toJSON: () => box };
  };
  Object.defineProperty(win.Element.prototype, 'getBoundingClientRect', { configurable: true, value(this: Element) { return rectangle(this); } });
  Object.defineProperty(win.Element.prototype, 'getClientRects', { configurable: true, value(this: Element) {
    if (!this.isConnected) return [];
    for (let ancestor: Element | null = this; ancestor; ancestor = ancestor.parentElement) {
      if (win.getComputedStyle(ancestor).display === 'none') return [];
    }
    return [rectangle(this)];
  } });
  Object.defineProperty(win.Element.prototype, 'checkVisibility', { configurable: true, value: undefined });
  win.eval(bridgeScript(identity));
  const target = win.document.getElementById('target')!;
  const reveal = async () => {
    const requestId = 'reveal-check';
    win.dispatchEvent(new win.MessageEvent('message', { source: win, data: { channel: INTERACTIVE_BRIDGE_CHANNEL, ...identity, requestId, command: 'execute', action: { type: 'REVEAL_ELEMENT', target: '#target' } } }));
    await vi.advanceTimersByTimeAsync(100);
    return messages.find(message => message.requestId === requestId)?.result as { success: boolean; error?: string } | undefined;
  };
  return { dom, win, target, reveal };
}

describe('REVEAL 必须确认真实 DOM 样式与显示区域', () => {
  const opened: JSDOM[] = [];
  const fixture = (...args: Parameters<typeof revealDomFixture>) => { const result = revealDomFixture(...args); opened.push(result.dom); return result; };
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { opened.splice(0).forEach(dom => dom.window.close()); vi.clearAllTimers(); vi.useRealTimers(); });

  it('解除目标 visibility:hidden，而不是仅删除 hidden 后就声称成功', async () => {
    const runtime = fixture('<div id="target" style="visibility:hidden">需要展示的解释</div>');
    expect(runtime.win.getComputedStyle(runtime.target).visibility).toBe('hidden');
    expect(await runtime.reveal()).toMatchObject({ success: true });
    expect(runtime.win.getComputedStyle(runtime.target).visibility).toBe('visible');
  });
  it('目标样式表中的 !important 可以被明确解除，并再次核对最终计算样式', async () => {
    const runtime = fixture('<style>#target { display:none !important; visibility:hidden !important; opacity:0 !important; }</style><div id="target" hidden>解释</div>');
    expect(await runtime.reveal()).toMatchObject({ success: true });
    const style = runtime.win.getComputedStyle(runtime.target);
    expect(style.display).not.toBe('none'); expect(style.visibility).toBe('visible'); expect(style.opacity).toBe('1');
    expect(runtime.target.style.getPropertyPriority('display')).toBe('important');
    expect(runtime.target.style.getPropertyPriority('visibility')).toBe('important');
  });
  it.each(['display:none !important', 'opacity:0'])('无法单独揭示被祖先 %s 隐藏的目标时返回失败，不展开祖先', async style => {
    const runtime = fixture(`<section id="container" style="${style}"><div id="target" hidden>解释</div></section>`);
    expect(await runtime.reveal()).toMatchObject({ success: false, error: expect.stringContaining('上层区域') });
    expect(runtime.target.hasAttribute('hidden')).toBe(true);
    expect(runtime.win.document.getElementById('container')!.getAttribute('style')).toBe(style);
  });
  it('零尺寸或被上层裁剪的对象不能取得成功 ACK', async () => {
    const zero = fixture('<div id="target">解释</div>', { target: { left: 10, top: 10, width: 0, height: 0 } });
    expect(await zero.reveal()).toMatchObject({ success: false, error: expect.stringContaining('显示区域') });
    const clipped = fixture('<section id="container" style="overflow-x:hidden;overflow-y:hidden"><div id="target">解释</div></section>', { container: { left: 0, top: 0, width: 40, height: 40 } });
    expect(await clipped.reveal()).toMatchObject({ success: false, error: expect.stringContaining('裁剪') });
  });
  it('浏览器仍判为不可见或页面随即隐藏/移除目标时返回失败', async () => {
    const invisible = fixture('<div id="target">解释</div>');
    Object.defineProperty(invisible.target, 'checkVisibility', { value: () => false });
    expect(await invisible.reveal()).toMatchObject({ success: false });
    const removed = fixture('<div id="target" hidden>解释</div>');
    removed.win.setTimeout(() => removed.target.remove(), 20);
    expect(await removed.reveal()).toMatchObject({ success: false, error: expect.stringContaining('移除') });
  });
});
