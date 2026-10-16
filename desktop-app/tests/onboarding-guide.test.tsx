// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import OnboardingGuide from '../src/components/OnboardingGuide';
import { clippedTourTarget, positionTourBubble } from '../src/components/onboardingGeometry';
let host: HTMLDivElement, root: Root;
const button = (text: string) => Array.from(host.querySelectorAll('button')).find(item => item.textContent === text)!;
beforeEach(() => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true }); localStorage.clear();
  Object.defineProperty(window, 'matchMedia', { configurable: true, value: () => ({ matches: true }) });
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
    if (this.classList.contains('onb-bubble')) return new DOMRect(0, 0, 360, 280);
    if (this.classList.contains('cr-main-question')) return new DOMRect(20, 100, 350, 90);
    if (this.classList.contains('cr-header')) return new DOMRect(0, 0, 390, 103);
    return new DOMRect(0, 0, 0, 0);
  });
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: vi.fn() });
  host = document.createElement('div'); document.body.append(host); root = createRoot(host);
});
afterEach(async () => { await act(async () => root.unmount()); host.remove(); vi.restoreAllMocks(); });
const mount = () => root.render(<><div className="cr-header"><button id="background-action">背景按钮</button></div><div className="cr-main-question"><input aria-label="学习主题" /></div><OnboardingGuide /></>);

describe('首次引导可退出且限制键盘焦点', () => {
  it('首次自动出现，Tab/Shift-Tab留在引导，Esc关闭并可从问号重播', async () => {
    await act(async () => mount()); expect(host.querySelector('[role=dialog]')).not.toBeNull(); expect(document.activeElement).toBe(button('知道了'));
    const background = host.querySelector('.cr-header') as HTMLElement; expect(background.inert).toBe(true);
    await act(async () => document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true }))); expect(document.activeElement).toBe(button('跳过全部'));
    await act(async () => document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', shiftKey: true, bubbles: true, cancelable: true }))); expect(document.activeElement).toBe(button('知道了'));
    await act(async () => (host.querySelector('#background-action') as HTMLButtonElement).focus()); expect(document.activeElement).toBe(button('知道了'));
    await act(async () => document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })));
    expect(host.querySelector('[role=dialog]')).toBeNull(); expect(background.inert).not.toBe(true); expect(localStorage.getItem('educationagent.onboarding.finished')).toBe('1');
    expect(document.activeElement).toBe(button('?')); await act(async () => button('?').click()); expect(host.querySelector('[role=dialog]')).not.toBeNull();
  });
  it('已经跳过的浏览器不再自动弹出；消失的锚点不会留下空高亮框', async () => {
    localStorage.setItem('educationagent.onboarding.finished', '1'); await act(async () => mount()); expect(host.querySelector('[role=dialog]')).toBeNull();
    await act(async () => button('?').click()); (host.querySelector('.cr-main-question') as HTMLElement).style.display = 'none';
    await act(async () => button('知道了').click()); expect(host.querySelector('.onb-spot')).toBeNull();
  });
});
describe('手机引导位置依照真实尺寸', () => {
  it('长气泡不会按固定176px估算出屏，窄横屏限制高度并居中', () => {
    const viewport = { left: 0, top: 0, width: 390, height: 600 };
    const result = positionTourBubble({ left: 15, top: 500, width: 360, height: 50 }, viewport, 280);
    expect(result.top).toBe(204); expect(result.top + 280).toBeLessThanOrEqual(588); expect(result.width).toBe(360);
    const short = positionTourBubble({ left: 15, top: 60, width: 360, height: 140 }, { ...viewport, height: 240 }, 330);
    expect(short.maxHeight).toBe(216); expect(short.top).toBe(12); expect(short.arrow).toBeNull();
  });
  it('软键盘/缩放偏移和越界大锚点均限制在可视区，箭头没有额外20px宽度误差', () => {
    const viewport = { left: 25, top: 70, width: 320, height: 400 };
    const result = positionTourBubble({ left: 250, top: 100, width: 80, height: 40 }, viewport, 160);
    expect(result.left).toBeGreaterThanOrEqual(37); expect(result.left + result.width).toBeLessThanOrEqual(333); expect(result.arrow?.offset).toBeLessThanOrEqual(result.width - 20);
    expect(clippedTourTarget({ left: -10, top: -100, width: 1000, height: 1000 }, viewport)).toEqual(viewport);
  });
});
